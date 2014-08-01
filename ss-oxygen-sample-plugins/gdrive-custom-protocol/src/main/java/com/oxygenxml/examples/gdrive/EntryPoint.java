package com.oxygenxml.examples.gdrive;


import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeRequestUrl;
import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.client.http.InputStreamContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.ParentReference;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;


/**
 * Entry point for our app from the Google Drive UI. 
 * 
 * This class handles the "Open" and "New" requests from Google Drive.
 */
public class EntryPoint extends HttpServlet {
  /**
   * The token marking a path as referring to a shared file.
   */
  public static final String SHARED_PATH_TYPE = "shared";

  /**
   * The token marking a path as referring to a file in the user's drive.
   */
  public static final String DRIVE_PATH_TYPE = "drive";

  /**
   * Encoding for URL paths.
   */
  private static final String UTF_8_ENCODING = "UTF-8";

  /**
   * The "Open With" action sent by the Google servers.
   */
  private static final String OPEN_ACTION = "open";

  /**
   * The "Create New" action sent by the Google servers.
   */
  private static final String CREATE_ACTION = "create";

  /**
   * Serial version id.
   */
	private static final long serialVersionUID = 1L;
	
  /**
   * Logger for logging.
   */
  private static final Logger logger = 
      Logger.getLogger(EntryPoint.class.getName());

	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doGet(HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws ServletException, IOException {
	  String stateJson = httpRequest.getParameter("state");
    logger.debug("Request with state: " + stateJson);
    
    String userId = AuthCode.getUserId(httpRequest);
    logger.debug("Checking user " + userId + " for authorization");
    UserData userData = GDriveManagerFilter.getCurrentUserData(userId);
    logger.debug("Found user data: " + userData);
   
    if (stateJson == null || userData == null ) {
      // Ask the user for authorization if he comes from the Open With or Create
      // actions (stateJson != null) and we do not have an authorization token, or
      // if the user starts from this page directly.
      logger.debug("Authorizing user.");
      GoogleAuthorizationCodeRequestUrl redirectUri = 
          Credentials.getInstance().createAuthorizationCodeRequestUrl();
      if (stateJson != null) {
        redirectUri.setState(httpRequest.getRequestURI() + "?state=" + stateJson);
      } else {
        // User landed on this page and our app is authorized, teach the user
        // how to use the Google drive app. 
        redirectUri.setState("gdrive.html");
      }
      String authorizationUrl = redirectUri.build();
      httpResponse.sendRedirect(authorizationUrl);
    } else {
      if (stateJson != null) {
        State state = new State(stateJson);
        logger.debug("Requesting user data for user: " + userId);
        
        String filePath = null;
        if (CREATE_ACTION.equals(state.action)) {
          String urlParam = httpRequest.getParameter("url");
          if (urlParam != null) {
            // The user has already chosen the template, create it in the
            // Google Drive.
            logger.debug("Creating new document with template: " + urlParam);
            URL url = new URL(URLDecoder.decode(urlParam, UTF_8_ENCODING));
            
            // If the filename is not specified, generate one.
            String fileName = httpRequest.getParameter("file_name");
            if (fileName == null) {
              fileName = generateFileName();
            }
            
            filePath = createNewTopic(state.folderId, fileName, url, userId);
            openInWebapp(httpResponse, userId, filePath, userData.getUserName());
          } else {
            logger.debug("Redirecting the user to choose the template.");
            // Redirect the user to choose the template that they want for
            // the new document.
            httpResponse.sendRedirect("NewFile.html?state=" + stateJson);
          }
        } else if (OPEN_ACTION.equals(state.action)) {
          // Open the specified file.
          Iterator<String> idsIterator = state.ids.iterator();
          filePath = computeFilePath(idsIterator.next(), userId);
          logger.debug("Opening the file in the webapp: " + filePath);
          openInWebapp(httpResponse, userId, filePath, userData.getUserName());
        }
      }
    }
	}

	/**
	 * Generates a file name for a new file.
	 * 
	 * @return The file name.
	 */
  private String generateFileName() {
    return "Untitled-" + randomId() + ".xml";
  }

	/**
	 * Redirect the user to open the specified file in the webapp.
	 * 
	 * @param httpResponse The response to send to the user.
	 * @param userId The id of the user.
	 * @param filePath The path of the file in the user's drive.
	 * @param userName The name of the author.
	 * 
	 * @throws UnsupportedEncodingException
	 * @throws IOException
	 */
  private void openInWebapp(HttpServletResponse httpResponse, String userId, String filePath, String userName) throws UnsupportedEncodingException, IOException {
    String fileUrl = "gdrive:///" + userId + filePath;
    logger.debug("Opening url: " + fileUrl);
    String encodedFileUrl = encodeUrlComponent(fileUrl);
    String encodedUserName = encodeUrlComponent(userName);
    httpResponse.sendRedirect("../app/demo-mobile.html?url=" + encodedFileUrl +
        "&author=" + encodedUserName +
        "&showSave=true");
  }

  /**
   * Encodes an url.
   * 
   * @param url The url.
   * @return The encoded url.
   * 
   * @throws UnsupportedEncodingException
   */
  private String encodeUrlComponent(String url) throws UnsupportedEncodingException {
    return URLEncoder.encode(url, UTF_8_ENCODING).replace("+", "%20");
  }
	
	/**
   * An object representing the state parameter passed into this application
   * from the Drive UI integration (i.e. Open With or Create New). Required
   * for Gson to deserialize the JSON into POJO form.
   *
   */
  private static class State {
    /**
     * Action intended by the state.
     */
    public String action;

    /**
     * IDs of files on which to take action.
     */
    public Collection<String> ids;

    /**
     * Parent ID related to the given action.
     */
    public String folderId;
    
    /**
     * Empty constructor required by Gson.
     */
    @SuppressWarnings("unused")
    public State() {}

    /**
     * Create a new State given its JSON representation.
     *
     * @param json Serialized representation of a State.
     */
    public State(String json) {
      GsonBuilder builder = new GsonBuilder();
      Gson gson = builder.create();
      State other = gson.fromJson(json, State.class);
      this.action = other.action;
      this.ids = other.ids;
      this.folderId = other.folderId;
    }
  }
	
  /**
   * Computes the path of the file with the given id. 
   * 
   * Note: Even in in Google drive it is allowed to have more than one parents for
   * a file, we do not support such a scenario since we have to resolve relative links
   * between the files. 
   * 
   * If the file is inside the user's drive, the url structure is:
   * gdrive:///{user_id}/drive/path/to/file.xml
   * 
   * If the file is just shared with the user, the url is:
   * gdrive:///{user_id}/shared/path/to/file.xml
   * 
   * @param fileId The id of the file.
   * @param userId The user id in whose drive the file is located.
   * 
   * @return The path relative to the drive.
   * 
   * @throws IOException
   */
	public String computeFilePath(String fileId, String userId) throws IOException {
	  logger.debug("determining the path for the file to be opened.");
	  String path = "";
	  while (true) {
	    final String crtFileId = fileId;
	    File file = GDriveManagerFilter.executeWithRetry(userId, new GDriveOperation<File>() {
        @Override
        public File executeOperation(Drive drive) throws IOException {
          return drive.files().get(crtFileId).execute();
        }
      });
	    
      String encodedFileTitle = URLEncoder.encode(file.getTitle(), UTF_8_ENCODING);
      
	    List<ParentReference> parents = file.getParents();
	    if (parents.isEmpty()) {
	      if (file.getSharedWithMeDate() == null) {
	        // This file is the root of the user's drive.
	        path = "/" + DRIVE_PATH_TYPE + path; 
	      } else {
	        // This file is shared to the user but not linked in the user's drive.
	        path = "/" + SHARED_PATH_TYPE +"/" + encodedFileTitle + path;
	      }
        break;
      }
	    if (parents.size() > 1) {
	      throw new FileNotFoundException("We cannot resolve relative links for files with more than one parent.");
	    }
      path = "/" + encodedFileTitle + path;
      logger.debug("file id " + fileId + " current path " + path);

	    fileId = parents.get(0).getId();
	  }
	  return path;
	}
	
	/**
	 * Creates a new file in the specified folder and returns the path to that file.
	 * 
	 * @param parentId The id of the parent folder.
	 * @param fileName The file name to use.
	 * @param url The url of the template.
	 * @param userId The id of the user in whose drive we create the file.
	 * 
	 * @return The path relative to the drive.
	 * 
	 * @throws IOException
	 */
	public String createNewTopic(String parentId, String fileName, URL url, String userId) throws IOException {
	  final File file = new File();
	  file.setMimeType(GDriveUrlConnection.MIME_TYPE);
	  file.setTitle(fileName);
	  logger.debug("parent id: " + parentId);
	  if (parentId != null && parentId.length() > 0) {
	    ParentReference parentRef = new ParentReference();
	    parentRef.setId(parentId);
	    file.setParents(Arrays.asList(parentRef));
	  }
	  
	  final AbstractInputStreamContent mediaContent = new InputStreamContent(
	      GDriveUrlConnection.MIME_TYPE, url.openConnection().getInputStream());
	  
	  File insertedFile = GDriveManagerFilter.executeWithRetry(userId, new GDriveOperation<File>() {
      @Override
      public File executeOperation(Drive drive) throws IOException {
        return drive.files().insert(file, mediaContent).execute();
      }
    });
	  return computeFilePath(insertedFile.getId(), userId);
	}

	/**
	 * Generates a random id.
	 * 
	 * @return A random id. 
	 */
  private String randomId() {
    return new BigInteger(30, new Random(System.currentTimeMillis())).toString(32);
  }
}