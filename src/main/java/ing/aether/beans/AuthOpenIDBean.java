package ing.aether.beans;

import java.io.*;
import java.util.*;

import org.eclipse.jetty.util.ajax.JSON;

import ing.aether.*;
import ing.aether.tools.*;
import ing.aether.tools.Random;

@CommandRegister(value=AuthOpenIDBean.COMMAND,accessType=0,createSession=true,preventCache=true)
public class AuthOpenIDBean extends BeanObject
{
  public static final String COMMAND="authopenid";

  // Non-sensitive scopes only: signing in must not require Google verification. The Drive
  // grant is requested separately, and only for users who do not already hold one.
  public static final String LOGIN_SCOPE="openid email profile";

  public static final String PropAuthProvider="Provider";
  public static final String PropAuthDomain="Domain";
  public static final String PropAuthClientID="ClientId";
  public static final String PropAuthSecret="Secret";
  public static final String PropAuthScope="Scope";
  public static final String PropAuthPrompUser="Prompt";
  public static final String PropAuthPersistent="Persistent";





  private static final String HEADERREFERER="Referer";
  private static final String KeyIDToken="id_token";
  private static final String KeyEmail="email";
  //private static final String KeyName="name";
  private static final String KeyISS="iss";
  private static final String KeySUB="sub";
  private static final String OpenIDSession="oidState";
  private static final String OpenIDProvider="oidProvider";
  private static final String OpenIDReferrer="oidReferrer";
  
  private static ArrayList<OpenID> openIds=null;
  private static long configCheck=0;
  private static final JSON json = new JSON();

  public static void resetConfig()
  {
    openIds=null;
    configCheck=0;
  }

  @SuppressWarnings("unchecked")
  private static void initialize()
  {
    if (openIds==null || configCheck<System.currentTimeMillis()) try
    {
      if (openIds==null)openIds=new ArrayList<OpenID>(); else openIds.clear();
      Object[] auth=Portal.getProperties(Portal.PropAuth);
      if (auth==null || auth.length==0)
      {
        System.out.println("Authentication is not SET!!!!");
        return;
      }
      for (int i=0;i<auth.length;i++)
      {
        Map<String, Object> ath=(Map<String, Object>)auth[i];
        if (!ath.containsKey(PropAuthProvider))continue;
        if (!ath.containsKey(PropAuthClientID))continue;
        if (!ath.containsKey(PropAuthSecret))continue;
        String config=null;
        char provider=((String)ath.get(PropAuthProvider)).charAt(0);
        switch (provider)
        {
          case 'G':
            config="https://accounts.google.com/.well-known/openid-configuration";
            break;
          case 'M':
            config="https://login.microsoftonline.com/common/v2.0/.well-known/openid-configuration";
            break;
          case 'Y':
            config="https://api.login.yahoo.com/.well-known/openid-configuration";
            break;
        }
        if (config==null)continue;
        String scope="openid email profile";
        if (ath.containsKey(PropAuthScope))scope=(String)ath.get(PropAuthScope);
        ArrayList<String> options=new ArrayList<>();
        if (ath.containsKey(PropAuthPrompUser) && (Boolean)ath.get(PropAuthPrompUser))options.add("prompt=select_account");
        else if (provider == 'G') options.add("prompt=consent"); // Ensure consent for Google to get refresh token

        if (ath.containsKey(PropAuthPersistent) && (Boolean)ath.get(PropAuthPersistent))options.add("access_type=offline");
        else if (provider == 'G') options.add("access_type=offline"); // Default to offline for Google

        // The identity step needs no refresh token, so it must not force offline/consent -
        // account selection is the only option that still makes sense there
        ArrayList<String> loginOptions=new ArrayList<>();
        if (ath.containsKey(PropAuthPrompUser) && (Boolean)ath.get(PropAuthPrompUser))loginOptions.add("prompt=select_account");

        openIds.add(new OpenID(provider,ath.containsKey(PropAuthDomain)?(String)ath.get(PropAuthDomain):null,config,(String)ath.get(PropAuthClientID),(String)ath.get(PropAuthSecret),scope,String.join("&",options),String.join("&",loginOptions)));
      }
      if (openIds.size()==0)System.out.println("No Valid Authentication Config!!");
      //scope="openid email profile https://www.googleapis.com/auth/drive";
      configCheck=System.currentTimeMillis()+3600000L;
    } catch (IOException e)
    {
      System.out.println("WARN: auth config load failed: "+e.getMessage()+" (configs: "+openIds.size()+")");
    }

  }
  
  private static OpenID getOpenID(char provider,String domain)
  {
    for (OpenID ch:openIds)
      if (ch.getProviderCode()==provider && (ch.getDomain()==null || ch.getDomain().equals(domain)))return ch;
    return null;
  }
  
  String provcall=null;
  String oiCode=null;
  String oiState=null;
  String referrer;
  
  public void setProvider(String newValue)
  {
    provcall=newValue;
  }
  
  public void setCode(String newValue)
  {
    oiCode=newValue;
  }
  
  public void setState(String newValue)
  {
    oiState=newValue; 
  }
  


  @SuppressWarnings("unchecked")
  protected void processData()
  {
    initialize();
    contentType="text/html";
    if (provcall!=null && provcall.length()>0)
    {
      OpenID openid=getOpenID(provcall.charAt(0),request.getServerName());
      if (openid!=null)
      {
        
        String state=Random.getAlphanumeric(14);
        session.setAttribute(OpenIDSession,state);
        session.setAttribute(OpenIDProvider,provcall);
        referrer=request.getHeader(HEADERREFERER);
        if (referrer==null || referrer.length()==0) referrer=request.getRequestURL().toString();
        session.setAttribute(OpenIDReferrer,referrer);
        int idx=referrer.indexOf('/',9);
        String base=idx==-1?referrer:referrer.substring(0,idx+1);
        if (!base.endsWith("/")) base+="/";
        // Second character of the provider call selects the step: 'D' asks for the Drive
        // grant with the full configured scope, anything else is the identity-only login
        if (provcall.length()>1 && provcall.charAt(1)=='D')
          redirect=openid.getAuthorizationEndpoint(base+COMMAND,state,openid.getScope(),"access_type=offline&prompt=consent");
        else
          redirect=openid.getAuthorizationEndpoint(base+COMMAND,state,LOGIN_SCOPE,openid.getLoginOption());
        return;
      } else
      {
        command=Command.getCommand("welcome");
      }
    } else
    {
      //String oiCode=request.getParameter("code");
      String type=null;
      String state=null;
      if (
          oiCode!=null && oiCode.length()>0 && (type=(String)session.getAttribute(OpenIDProvider))!=null &&
          (state=(String)session.getAttribute(OpenIDSession))!=null && state.equals(oiState)
         ) try
      {
        referrer=(String)session.getAttribute(OpenIDReferrer);
        session.removeAttribute(OpenIDSession);
        session.removeAttribute(OpenIDProvider);
        session.removeAttribute(OpenIDReferrer);
        char provider=type.charAt(0);
        char mode='L';
        if (type.length()>1)mode=type.charAt(1);
        OpenID oconf=getOpenID(provider,request.getServerName());
        if (oconf==null)return;
        int tCount=0;
        for (tCount=0;tCount<3;tCount++)try
        {
          Map<String, Object> tokens=oconf.exchangeCode(oiCode);
          if (!tokens.containsKey(KeyIDToken))
          {
            System.out.println("WARN: OpenID code exchange returned no ID token ("+provider+")");
            return;
          }
          String idToken=(String)tokens.get(KeyIDToken);
          String[] idparts=idToken.split("\\.");
          if (idparts.length!=3)
          {
            System.out.println("WARN: malformed ID token, expected 3 parts ("+provider+")");
            return;
          }
          //JSONObject idHeader=new JSONObject(new JSONTokener(new String(Base64.getDecoder().decode(idparts[0]))));
          Map<String, Object> idClaim=(Map<String, Object>)json.parse(new JSON.ReaderSource(new StringReader(new String(Base64.getDecoder().decode(idparts[1])))));
          String tEmai=idClaim.containsKey(KeyEmail)?(String)idClaim.get(KeyEmail):null;
          String issSubId=(idClaim.containsKey(KeyISS)?(String)idClaim.get(KeyISS):"")+"/"+(idClaim.containsKey(KeySUB)?(String)idClaim.get(KeySUB):"");
          if (issSubId==null || issSubId.length()<2) System.out.println("WARN: incomplete ISS/SUB claim from "+provider);
          
          session.setAttribute(Portal.SessionEmail,tEmai);
          session.setAttribute(Portal.SessionAccountID,issSubId);
          session.setAttribute(Portal.SessionLoginProvider,String.valueOf(provider));
          SessionTracker.sessionCheck(session);

          Map<String, Object> userData = SessionTracker.getSessionData(session);
          if (tokens.containsKey("access_token")) userData.put(SessionTracker.KEY_ACCESS_TOKEN, tokens.get("access_token"));
          if (tokens.containsKey("refresh_token"))
          {
            userData.put(SessionTracker.KEY_REFRESH_TOKEN, tokens.get("refresh_token"));
          } else if (mode=='D') {
            // Expected here and nowhere else: the identity step deliberately runs without
            // access_type=offline, so a missing refresh token is only a problem on step two
            System.out.println("WARN: Drive authorization returned no refresh token for " + tEmai);
          }

          // Register the Drive provider and load user settings (aether.ing.setting.json) right at login,
          // covering the first login where no persisted token existed during sessionCheck
          String refreshToken=(String)userData.get(SessionTracker.KEY_REFRESH_TOKEN);
          if (refreshToken!=null && tEmai!=null)
          {
            SessionTracker.registerGoogleProvider(issSubId,tEmai,refreshToken);
            ing.aether.data.UserSettings.ensureLoaded(issSubId,tEmai);
          }

          boolean isAdmin = false;
          Object[] adminArr=Portal.getProperties(Portal.PropAdmin);
          if (adminArr==null || adminArr.length==0)
          {
            isAdmin = true;
            adminArr=new Object[]{tEmai};
            Portal.setProperties(Portal.PropAdmin,adminArr);
          } else for (int i=0;i<adminArr.length;i++)
          {
            if (adminArr[i].equals(tEmai)) isAdmin = true;
          }
          session.setAttribute(Portal.SessionAdmin, isAdmin);
          return;
        }catch (IOException e)
        {
          e.printStackTrace(System.out);
          try {Thread.sleep(1000);}catch (InterruptedException e1){}
        }
        
      }catch (NullPointerException e) {} else
      {
        command=Command.getCommand("welcome");
      }
      //PrintWriter out = response.getWriter();
      //String target=request.getRequestURL().toString().replaceAll("auth","");
    }
  }
  
  public String getReferrer()
  {
    // Error paths never populate this, and a null target origin makes postMessage throw
    if (referrer==null || referrer.length()==0) return request.getRequestURL().toString();
    return referrer;
  }
}
