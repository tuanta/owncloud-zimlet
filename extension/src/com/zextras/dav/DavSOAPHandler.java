package com.zextras.dav;

import com.zextras.util.UserPropertyExtractor;
import org.json.JSONArray;
import org.json.JSONObject;
import org.openzal.zal.Account;
import org.openzal.zal.Provisioning;
import org.openzal.zal.soap.*;
import org.openzal.zal.soap.QName;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * SOAP Handler to interface a class which act as a client, with the SOAP infrastructure.
 */
public class DavSOAPHandler implements SoapHandler
{
  private static String NAMESPACE = "urn:zimbraAccount";
  public static final QName REQUEST_QNAME = new QName("davSoapConnector", NAMESPACE);

  private static String ZIMLET_NAME = "tk_barrydegraaff_owncloud_zimlet";

  private final Provisioning mProvisioning;

  public DavSOAPHandler()
  {
    mProvisioning = new Provisioning();
  }

  /**
   * Handle a SOAP request.
   * @param zimbraContext The zimbra contest
   * @param soapResponse The response container for the SOAP request
   * @param zimbraExceptionContainer
   */
  @Override
  public void handleRequest(
    ZimbraContext zimbraContext,
    SoapResponse soapResponse,
    ZimbraExceptionContainer zimbraExceptionContainer
  )
  {
    final String accountId = zimbraContext.getAuthenticatedAccontId();
    final Account account = mProvisioning.assertAccountById(accountId);

    final Map<String, String> userProperties = UserPropertyExtractor.getZimletUserProperties(account, ZIMLET_NAME);

    if (
      userProperties.get(ZimletProperty.DAV_SERVER_NAME) == null ||
      userProperties.get(ZimletProperty.DAV_SERVER_PORT) == null ||
      userProperties.get(ZimletProperty.DAV_SERVER_PATH) == null ||
      userProperties.get(ZimletProperty.DAV_USER_USERNAME) == null ||
      userProperties.get(ZimletProperty.DAV_USER_PASSWORD) == null
      )
    {
      handleError(
        new RuntimeException("DAV Data connection not set for user '" + account.getName() + "'"),
        soapResponse,
        zimbraExceptionContainer
      );
      return;
    }

    {
      final URL serverUrl;
      try
      {
        serverUrl = new URL(userProperties.get(ZimletProperty.DAV_SERVER_NAME));
      } catch (MalformedURLException e)
      {
        handleError(e, soapResponse, zimbraExceptionContainer);
        return;
      }
      if (!checkPermissionOnTarget(serverUrl, account))
      {
        handleError(
          new RuntimeException("Proxy domain not allowed '" + serverUrl + "' for user '" + account.getName() + "'"),
          soapResponse,
          zimbraExceptionContainer
        );
        return;
      }
    }

    final DavSoapConnector connector = new DavSoapConnector(
      userProperties.get(ZimletProperty.DAV_SERVER_NAME),
      Integer.parseInt(userProperties.get(ZimletProperty.DAV_SERVER_PORT)),
      userProperties.get(ZimletProperty.DAV_SERVER_PATH),
      userProperties.get(ZimletProperty.DAV_USER_USERNAME),
      userProperties.get(ZimletProperty.DAV_USER_PASSWORD)
    );

    final String actionStr = zimbraContext.getParameter("action", "");
    final String path = zimbraContext.getParameter("path", null);
    final DavCommand command;
    try
    {
      command = DavCommand.fromString(actionStr);
    } catch (RuntimeException ex)
    {
      handleError(ex, soapResponse, zimbraExceptionContainer);
      return;
    }

    try
    {
      switch (command)
      {
        case GET:
          soapResponse.setValue(
            "GET",
            connector.get(
              zimbraContext.getParameter("path", "/")
            ).toString()
          );
          break;
        case PUT:
          if (path == null)
          {
            throw new RuntimeException("Path not provided for PUT DAV action.");
          }
          String data = zimbraContext.getParameter("data", null);
          String contentType = zimbraContext.getParameter("contentType", "text/xml,charset=UTF-8");
          connector.put(
            path,
            new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)),
            contentType
          );
          soapResponse.setValue(command.name(), true);
          break;
        case PROPFIND:
          soapResponse.setValue(
            command.name(),
            connector.propfind(
              zimbraContext.getParameter("path", "/"),
              Integer.parseInt(zimbraContext.getParameter("depth", "1"))
            ).toString()
          );
          break;
        case DELETE:
          if (path == null)
          {
            throw new RuntimeException("Path not provided for DELETE DAV action.");
          }
          connector.delete(path);
          soapResponse.setValue(command.name(), true);
          break;
        case MKCOL:
          if (path == null)
          {
            throw new RuntimeException("Path not provided for MKCOL DAV action.");
          }
          connector.mkcol(path);
          soapResponse.setValue(command.name(), true);
          break;
        case COPY:
          if (path == null)
          {
            throw new RuntimeException("Source path not provided for COPY DAV action.");
          }
          String cpDestPath = zimbraContext.getParameter("destPath", null);
          if (cpDestPath == null)
          {
            throw new RuntimeException("Destination path not provided for COPY DAV action.");
          }
          boolean cpOverwrite = Boolean.parseBoolean(zimbraContext.getParameter("overwrite", "false"));
          connector.copy(path, cpDestPath, cpOverwrite);
          soapResponse.setValue(command.name(), true);
          break;
        case MOVE:
          if (path == null)
          {
            throw new RuntimeException("Source path not provided for MOVE DAV action.");
          }
          String mvDestPath = zimbraContext.getParameter("destPath", null);
          if (mvDestPath == null)
          {
            throw new RuntimeException("Destination path not provided for MOVE DAV action.");
          }
          boolean mvOverwrite = Boolean.parseBoolean(zimbraContext.getParameter("overwrite", "false"));
          connector.move(path, mvDestPath, mvOverwrite);
          soapResponse.setValue(command.name(), true);
          break;
        default:
          throw new RuntimeException("DAV command '" + command.name() + "' not handled.");
      }
    } catch (IOException ex)
    {
      handleError(ex, soapResponse, zimbraExceptionContainer);
    }
  }

  /**
   * Encode an error into a JSON Object.
   * @param error The error which will be encoded.
   * @param resp The response container
   */
  private static void handleError(
    Exception error,
    SoapResponse resp,
    ZimbraExceptionContainer errorContainer
  )
  {
    resp.setValue("error", encodeError(error).toString());
    errorContainer.setException(error);
  }

  private static JSONObject encodeError(Throwable error)
  {
    JSONObject errorObj = new JSONObject();
    JSONArray stackArr = new JSONArray();
    for (StackTraceElement el : error.getStackTrace()) {
      stackArr.put(el.toString());
    }
    errorObj.put("message", error.getMessage());
    errorObj.put("trace", stackArr);
    if (error.getCause() != null)
    {
      errorObj.put("cause", encodeError(error.getCause()));
    }
    return errorObj;
  }

  /**
   * If the user needs to be authenticated as admin to use this handler.
   * @param zimbraContext The zimbra context.
   * @return If the user needs to be an administrator.
   */
  @Override
  public boolean needsAdminAuthentication(
    ZimbraContext zimbraContext
  )
  {
    return false;
  }

  /**
   * If the user needs to be authenticated to use this handler.
   * @param zimbraContext The zimbra context.
   * @return If the user needs to be authenticated.
   */
  @Override
  public boolean needsAuthentication(
    ZimbraContext zimbraContext
  )
  {
    return true;
  }

  private boolean checkPermissionOnTarget(URL target, Account account) {
    Set<String> domains = UserPropertyExtractor.getProxyAllowedDomain(account);
    String host = target.getHost().toLowerCase();
    for (String domain : domains) {
      if (domain.equals("*")) {
        return true;
      }
      if (domain.charAt(0) == '*') {
        domain = domain.substring(1);
      }
      if (host.endsWith(domain)) {
        return true;
      }
    }
    return false;
  }
}
