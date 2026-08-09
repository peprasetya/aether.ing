package ing.aether.tools;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.eclipse.jetty.util.ajax.JSON;

public class OpenID
{
  private char providerCode;
  private String domain=null;
  private String configURL=null;
  private String clientID=null;
  private String secret=null;
  private String scope=null;
  private String authOption=null;
  private String loginOption=null;
  private String redirectURI=null;
  private Map<String, Object> configuration=null;
  private Map<String, Object> jwks=null;
  private final JSON json = new JSON();
  
  public char getProviderCode() {return providerCode;}
  public String getDomain() {return domain;}
  public String getConfigURL() {return configURL;}
  public String getClientID() {return clientID;}
  public String getScope() {return scope;}
  public String getLoginOption() {return loginOption;}

  public OpenID(char provider,String domain,String configURL,String clientID,String secret,String scope,String authOption,String loginOption) throws IOException
  {
    this.providerCode=provider;
    this.domain=domain;
    this.configURL=configURL;
    this.clientID=clientID;
    this.secret=secret;
    this.scope=scope;
    this.authOption=authOption;
    this.loginOption=loginOption;
    refresh();
  }
  
  protected static String encode(String data)
  {
    try
    {
      return URLEncoder.encode(data,StandardCharsets.UTF_8.name());
    }catch (UnsupportedEncodingException e) {return data;}
  }
  
  @SuppressWarnings("unchecked")
  public void refresh() throws IOException
  {
    configuration=(Map<String, Object>)json.parse(new JSON.ReaderSource(new InputStreamReader(new URL(configURL).openConnection().getInputStream(), StandardCharsets.UTF_8)));
    if (configuration!=null)
    {
      jwks=(Map<String, Object>)json.parse(new JSON.ReaderSource(new InputStreamReader(new URL((String)configuration.get("jwks_uri")).openConnection().getInputStream(), StandardCharsets.UTF_8)));
    }
  }
  
  public String getAuthorizationEndpoint(String redirectURI,String state)
  {
    return getAuthorizationEndpoint(redirectURI,state,scope,authOption);
  }

  // Overload for incremental authorization: the identity step and the Drive step ask for
  // different scopes and different options against the same provider configuration
  public String getAuthorizationEndpoint(String redirectURI,String state,String useScope,String useOption)
  {
    if (configuration!=null)
    {
      String url=(String)configuration.get("authorization_endpoint");
      if (url!=null)
      {
        this.redirectURI=encode(redirectURI);
        if (useScope==null) useScope=scope;
        return url+"?scope="+encode(useScope)+"&response_type=code&state="+state+"&client_id="+clientID+"&redirect_uri="+this.redirectURI+(useOption==null||useOption.length()==0?"":"&"+useOption);
      }
    }
    return "#";
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> exchangeCode(String code) throws IOException
  {
    if (configuration!=null)
    {
      String data="client_id="+clientID+"&client_secret="+encode(secret)+"&grant_type=authorization_code&redirect_uri="+redirectURI+"&code="+code;
      HttpURLConnection urlConn=(HttpURLConnection)new URL((String)configuration.get("token_endpoint")).openConnection();
      urlConn.setRequestMethod("POST");
      urlConn.setDoInput(true);
      urlConn.setDoOutput(true);
      urlConn.setRequestProperty("Accept", "*/*");
      urlConn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
      
      OutputStream os=urlConn.getOutputStream();
      os.write(data.getBytes());
      os.flush();
      
      Map<String, Object> result=(Map<String, Object>)json.parse(new JSON.ReaderSource(new InputStreamReader(urlConn.getInputStream(), StandardCharsets.UTF_8)));
      return result;

    }
    return null;
  }
  
  public String getIssuer()
  {
    if (configuration!=null)
    {
      return (String)configuration.get("issuer");
    }
    return null;
  }
  
  @SuppressWarnings("unchecked")
  private Map<String, Object> checkKey(String kid)
  {
    if (jwks!=null)
    {
      Object[] keys=(Object[])jwks.get("keys");
      if (keys!=null)for (int i=0;i<keys.length;i++)
      {
        Map<String, Object> key = (Map<String, Object>)keys[i];
        if (kid.equals(key.get("kid")))return key;
      }
    }
    return null;
  }
  
  public Map<String, Object> getKey(String kid)
  {
    Map<String, Object> rst=checkKey(kid);
    if (rst==null)
    {
      try{refresh();}catch (IOException e){}
      rst=checkKey(kid);
    }
    return rst;
  }



}
