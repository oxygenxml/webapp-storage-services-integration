package com.oxygenxml.examples.dbx;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.dropbox.core.DbxAuthFinish;
import com.dropbox.core.DbxException;
import com.dropbox.core.DbxWebAuth;

import ro.sync.ecss.extensions.api.webapp.plugin.WebappServletPluginExtension;
/**
 * Servlet that is called back by the google servers after the user authorized
 * our app to access its Drive.
 */
public class AuthCode extends WebappServletPluginExtension {

  /**
   * Logger for logging.
   */
  private static final Logger logger = 
      LogManager.getLogger(AuthCode.class.getName());
  
  /**
   * The session attribute key for holding the user id.
   */
  public static final String USERID = "userid";
  
  /**
   * @see HttpServlet#HttpServlet()
   */
  public AuthCode() {
    super();
    logger.debug("Auth callback initialized");
  }
  
  /**
   * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse
   *      response)
   */
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
    DbxWebAuth auth = Credentials.getFlow();
    DbxAuthFinish authFinish;
    try {
        @SuppressWarnings("unchecked")
        Map<String, String[]> parameterMap = request.getParameterMap();
        if (logger.isDebugEnabled()) {
        	logger.debug("Callback from Dropbox.. :");
        	for (Map.Entry<String, String[]> param: parameterMap.entrySet()) {
        		logger.debug(param.getKey() + " = " + Arrays.toString(param.getValue()));
        	}
        }
        authFinish = auth.finishFromRedirect(
            Credentials.REDIRECT_URI,
            Credentials.getSessionStore(request), 
            parameterMap);
    }
    catch (DbxWebAuth.BadRequestException ex) {
        logger.debug("On /dropbox-auth-finish: Bad request: " + ex.getMessage());
        response.sendError(400);
        return;
    }
    catch (DbxWebAuth.BadStateException ex) {
      logger.debug("Bad state exception", ex);
        // Send them back to the start of the auth flow.
        response.sendRedirect("../app/oxygen.html");
        return;
    }
    catch (DbxWebAuth.CsrfException ex) {
        logger.error("On /dropbox-auth-finish: CSRF mismatch: " + ex.getMessage());
        response.sendError(HttpServletResponse.SC_BAD_REQUEST, "CSRF mismatch");
        return;
    }
    catch (DbxWebAuth.NotApprovedException ex) {
        // When Dropbox asked "Do you want to allow this app to access your
        // Dropbox account?", the user clicked "No".
        response.getOutputStream().println("You should authorize the "
            + "oXygen Author Webapp in order to edit your Dropbox files.");
        return;
    }
    catch (DbxWebAuth.ProviderException ex) {
      logger.debug("On /dropbox-auth-finish: Auth failed: " + ex.getMessage());
        response.sendError(503, "Error communicating with Dropbox.");
        return;
    }
    catch (DbxException ex) {
      logger.debug("On /dropbox-auth-finish: Error getting token: " + ex.getMessage());
        response.sendError(503, "Error communicating with Dropbox.");
        return;
    }
    String accessToken = authFinish.getAccessToken();
    logger.debug("Authorization fisnished with access token: " + accessToken);

    // Save the access token somewhere (probably in your database) so you
    // don't need to send the user through the authorization process again.
    try {
      DbxManagerFilter.setCredential(accessToken, authFinish.getUserId());
      setUserId(request, authFinish.getUserId());
      logger.debug("Set user id : " + authFinish.getUserId());
    } catch (DbxException ex) {
      logger.warn(ex, ex);
      DbxManagerFilter.authorizationFailedForUser(authFinish.getUserId());
    }
    
    logger.debug("Redirecting to " + authFinish.getUrlState());
    response.sendRedirect(authFinish.getUrlState());
  }
  
  /**
   * Returns the cached used id from the session.
   * 
   * @param request The http request.
   * 
   * @return The user id.
   */
  public static String getUserId(ServletRequest request) {
    HttpServletRequest httpRequest = (HttpServletRequest) request;
    String userId = (String) httpRequest.getSession().getAttribute(AuthCode.USERID);
    return userId;
  }
  
  /**
   * Sets the user id for the current session.
   * 
   * @param request The http request.
   * @param userId The id of the user which is the owner of the current session.
   */
  public static void setUserId(ServletRequest request, String userId) {
    HttpServletRequest httpRequest = (HttpServletRequest) request;
    httpRequest.getSession().setAttribute(AuthCode.USERID, userId);
  }

  @Override
  public String getPath() {
    return "dbx-oauth-callback";
  }
}
