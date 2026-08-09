package ing.aether.beans;

import java.util.*;

import ing.aether.Command;
import ing.aether.CommandRegister;
import ing.aether.Portal;

@CommandRegister(value=SetupBean.COMMAND,accessType=0,createSession=true,preventCache=true)
public class SetupBean extends BeanObject
{
  public static final String COMMAND="setup";

  public static final String PropAuthProvider="Provider";
  public static final String PropAuthClientID="ClientId";
  public static final String PropAuthSecret="Secret";
  public static final String PropAuthScope="Scope";

  private static final String DRIVE_SCOPE="openid email profile https://www.googleapis.com/auth/drive";
  private static final String IP_PATTERN="^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$";
  private static final String IP6_PATTERN="^([0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}$";

  private String clientid=null;
  private String secret=null;
  private String action=null;
  protected boolean showSetup=true;
  private boolean isSecure=false;
  private boolean isIpOnly=false;
  private String serverName=null;
  private String serverUrl=null;
  private int serverPort=-1;

  public boolean isSecure(){return isSecure;}
  public boolean isIpOnly(){return isIpOnly;}
  public String getServerName(){return serverName;}
  public String getServerUrl(){return serverUrl;}
  public int getServerPort(){return serverPort;}
  public String getClientid(){return clientid;}
  public String getSecret(){return secret;}
  public String getAction(){return action;}

  public void setClientid(String newValue)
  {
    clientid=newValue;
  }

  public void setSecret(String newValue)
  {
    secret=newValue;
  }

  public void setAction(String newValue)
  {
    action=newValue;
  }

  public boolean isShowSetup(){return showSetup;}

  @SuppressWarnings("unchecked")
  protected void processData()
  {
    serverName=request.getServerName();
    isSecure="https".equals(request.getScheme());
    isIpOnly=serverName.matches(IP_PATTERN)||serverName.matches(IP6_PATTERN)||serverName.equals("localhost");
    serverPort=request.getServerPort();
    int port=request.getServerPort();
    int defaultPort=isSecure?443:80;
    if (port==defaultPort)
    {
      serverUrl=(isSecure?"https://":"http://")+serverName;
    }
    else
    {
      serverUrl=(isSecure?"https://":"http://")+serverName+":"+port;
    }

    initialize();
  }

  @SuppressWarnings("unchecked")
  private void initialize()
  {
    Object[] admins=Portal.getProperties(Portal.PropAdmin);
    Object[] auths=Portal.getProperties(Portal.PropAuth);

    if ((admins!=null && admins.length>0) && (auths!=null && auths.length>0))
    {
      if (!isAdmin())
      {
        command=Command.getCommand(BeanObject.CMDMenu);
        showSetup=false;
        return;
      }
    }

    if ("test".equals(action) && clientid!=null && secret!=null)
    {
      Map<String, Object> authConfig=new HashMap<>();
      authConfig.put(PropAuthProvider,"G");
      authConfig.put(PropAuthClientID,clientid);
      authConfig.put(PropAuthSecret,secret);
      authConfig.put(PropAuthScope,DRIVE_SCOPE);

      Object[] authArray=new Object[]{authConfig};
      Portal.setProperties(Portal.PropAuth,authArray);
      AuthOpenIDBean.resetConfig();
      success=true;

      if (!isAjaxCall())
      {
        this.redirect="/authopenid?provider=G";
        html=false;
      }
      return;
    }
  }

}
