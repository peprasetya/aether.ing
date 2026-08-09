package ing.aether.tools.google;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.Base64;

import org.eclipse.jetty.util.ajax.JSON;

import ing.aether.beans.BeanObject;

public class Token
{
  static final String ContentTypeURLEncoded="application/x-www-form-urlencoded";
  static final String ContentTypeJSON="application/json; charset=UTF-8";
  
  static final String APIOAuth="https://accounts.google.com/o/oauth2/";
  
  private String clientId;
  private String clientSecret;
  private String refreshToken=null;
  private String accessToken=null;
  private String lastTokenMessage=null;
  private long tokenExpire=0;
  private final JSON json = new JSON();

  public Token(String clientId,String clientSecret, String refreshToken)
  {
    this.clientId=clientId;
    this.clientSecret=clientSecret;
    if (refreshToken!=null)refreshToken(refreshToken);
  }
  
  @SuppressWarnings("unchecked")
  public Token(File serviceAccountJson, String scope) {
    try {
        Map<String, Object> jsonMap = (Map<String, Object>)json.parse(new JSON.ReaderSource(new FileReader(serviceAccountJson)));

        String clientEmail = (String)jsonMap.get("client_email");
        String privateKeyPem = (String)jsonMap.get("private_key");
        String tokenUri = (String)jsonMap.get("token_uri");

        // Clean up the PEM
        String privateKeyClean = privateKeyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s+", "");

        byte[] pkcs8EncodedBytes = Base64.getDecoder().decode(privateKeyClean);

        // Build PrivateKey
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(pkcs8EncodedBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = kf.generatePrivate(keySpec);

        long now = System.currentTimeMillis() / 1000;
        Map<String, Object> jwtHeader = new HashMap<>();
        jwtHeader.put("alg", "RS256");
        jwtHeader.put("typ", "JWT");

        Map<String, Object> jwtClaimSet = new HashMap<>();
        jwtClaimSet.put("iss", clientEmail);
        jwtClaimSet.put("scope", scope);
        jwtClaimSet.put("aud", tokenUri);
        jwtClaimSet.put("exp", now + 3600);
        jwtClaimSet.put("iat", now);

        String headerBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toJSON(jwtHeader).getBytes(StandardCharsets.UTF_8));
        String claimBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toJSON(jwtClaimSet).getBytes(StandardCharsets.UTF_8));
        String unsignedJWT = headerBase64 + "." + claimBase64;

        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(unsignedJWT.getBytes(StandardCharsets.UTF_8));
        String signatureBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());

        String jwt = unsignedJWT + "." + signatureBase64;

        String requestBody = "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=" + encode(jwt);
        String tokenResponse = httpPost(tokenUri, requestBody, ContentTypeURLEncoded, false);

        Map<String, Object> tokenJson = (Map<String, Object>)json.parse(new JSON.ReaderSource(new StringReader(tokenResponse)));
        this.accessToken = (String)tokenJson.get("access_token");
        this.tokenExpire = System.currentTimeMillis() + ((Number)tokenJson.get("expires_in")).intValue() * 1000;

    } catch (Exception e) {
        e.printStackTrace(System.out);
        throw new RuntimeException("Service account auth failed", e);
    }
  }
  
  /* Usage:
File jsonKey = new File("my-service-account.json");
String scopes = "https://www.googleapis.com/auth/drive https://www.googleapis.com/auth/spreadsheets";
Token token = new Token(jsonKey, scopes);

Drive drive = new Drive(token);
String result = drive.about();
System.out.println(result);

   */

  
  protected static String encode(String data)
  {
    try
    {
      return URLEncoder.encode(data,StandardCharsets.UTF_8.name());
    }catch (UnsupportedEncodingException e) {return data;}
  }
  
  
  public String httpGet(String url)
  {
    return httpGet(url, true);
  }

  private String httpGet(String url, boolean retryOn403)
  {
    HttpURLConnection urlConn = null;
    try
    {
      urlConn=(HttpURLConnection) new URL(url).openConnection();
      urlConn.setRequestMethod("GET");
      String bearer=getAccessToken();
      if (bearer!=null)
        urlConn.setRequestProperty( "Authorization", "Bearer "+bearer);
      urlConn.setUseCaches( false );
      
      int responseCode = urlConn.getResponseCode();
      if (responseCode == 403 && retryOn403 && refreshToken != null)
      {
        urlConn.disconnect();
        accessToken = null;
        refreshToken(refreshToken);
        return httpGet(url, false);
      }

      StringBuilder resp=new StringBuilder();
      BufferedReader br=new BufferedReader(new InputStreamReader(urlConn.getInputStream()));
      String inputLine;
      while ((inputLine=br.readLine())!=null)
      {
        resp.append(inputLine+"\r\n");
      }
      return resp.toString();
    }catch(Exception e)
    {
      if (urlConn != null) try {
        InputStream es = urlConn.getErrorStream();
        if (es != null) {
          BufferedReader br = new BufferedReader(new InputStreamReader(es));
          String line;
          while ((line = br.readLine()) != null) System.err.println("ERROR_STREAM: " + line);
        }
      } catch (Exception e2) {}
      e.printStackTrace(System.out);
    } finally {
      if (urlConn != null) urlConn.disconnect();
    }
    return null;
  }
  
  public String httpDelete(String url)
  {
    HttpURLConnection urlConn = null;
    try
    {
      urlConn=(HttpURLConnection) new URL(url).openConnection();
      urlConn.setRequestMethod("DELETE");
      String bearer=getAccessToken();
      if (bearer!=null)
        urlConn.setRequestProperty( "Authorization", "Bearer "+bearer);
      urlConn.setUseCaches( false );
      StringBuilder resp=new StringBuilder();
      BufferedReader br=new BufferedReader(new InputStreamReader(urlConn.getInputStream()));
      String inputLine;
      while ((inputLine=br.readLine())!=null)
      {
        resp.append(inputLine+"\r\n");
      }
      return resp.toString();
    }catch(Exception e){e.printStackTrace(System.out);}
    finally { if (urlConn != null) urlConn.disconnect(); }
    return null;
  }

  public String httpPatch(String url, String message, String contentType)
  {
    return httpPatch(url, message.getBytes(StandardCharsets.UTF_8), contentType, true);
  }

  public String httpPatch(String url, byte[] body, String contentType)
  {
    return httpPatch(url, body, contentType, true);
  }

  private String httpPatch(String url, byte[] body, String contentType, boolean retryOn403)
  {
    HttpURLConnection urlConn = null;
    try
    {
      urlConn=(HttpURLConnection) new URL(url).openConnection();
      urlConn.setRequestMethod("POST");
      urlConn.setRequestProperty("X-HTTP-Method-Override", "PATCH");
      urlConn.setRequestProperty( "Content-Type", contentType);
      urlConn.setDoInput(true);
      urlConn.setDoOutput(true);
      urlConn.setRequestProperty( "Charset", StandardCharsets.UTF_8.name());
      urlConn.setRequestProperty( "Content-Length", Integer.toString(body.length));
      String bearer=getAccessToken();
      if (bearer!=null)
        urlConn.setRequestProperty( "Authorization", "Bearer "+bearer);
      urlConn.setUseCaches( false );
      OutputStream os=urlConn.getOutputStream();
      os.write(body);
      os.flush();
      os.close();

      int responseCode = urlConn.getResponseCode();
      if (responseCode == 403 && retryOn403 && refreshToken != null)
      {
        urlConn.disconnect();
        accessToken = null;
        refreshToken(refreshToken);
        return httpPatch(url, body, contentType, false);
      }

      StringBuilder resp=new StringBuilder();
      BufferedReader br=new BufferedReader(new InputStreamReader(urlConn.getInputStream()));
      String inputLine;
      while ((inputLine=br.readLine())!=null)
      {
        resp.append(inputLine+"\r\n");
      }
      return resp.toString();
    }catch(Exception e){e.printStackTrace(System.out);}
    finally { if (urlConn != null) urlConn.disconnect(); }
    return null;
  }

  public String httpPost(String url,String message,String contentType,boolean useBearer)
  {
    return httpPost(url, message, contentType, useBearer, true);
  }

  private String httpPost(String url,String message,String contentType,boolean useBearer, boolean retryOn403)
  {
    HttpURLConnection urlConn = null;
    try
    {
      byte[] body=message.getBytes(StandardCharsets.UTF_8);
      urlConn=(HttpURLConnection) new URL(url).openConnection();
      urlConn.setRequestMethod("POST");
      urlConn.setRequestProperty( "Content-Type", contentType);
      urlConn.setDoInput(true);
      urlConn.setDoOutput(true);
      urlConn.setRequestProperty( "Charset", StandardCharsets.UTF_8.name());
      urlConn.setRequestProperty( "Content-Length", Integer.toString(body.length));
      if (useBearer)
      {
        String bearer=getAccessToken();
        if (bearer!=null)
          urlConn.setRequestProperty( "Authorization", "Bearer "+bearer);
      }
      urlConn.setUseCaches( false );
      OutputStream os=urlConn.getOutputStream();
      os.write(body);
      os.flush();
      os.close();

      int responseCode = urlConn.getResponseCode();
      if (responseCode == 403 && useBearer && retryOn403 && refreshToken != null)
      {
        urlConn.disconnect();
        accessToken = null;
        refreshToken(refreshToken);
        return httpPost(url, message, contentType, useBearer, false);
      }

      StringBuilder resp=new StringBuilder();
      BufferedReader br=new BufferedReader(new InputStreamReader(urlConn.getInputStream()));
      String inputLine;
      while ((inputLine=br.readLine())!=null)
      {
        resp.append(inputLine+"\r\n");
      }
      return resp.toString();
    }catch(Exception e)
    {
      if (urlConn != null) try {
        InputStream es = urlConn.getErrorStream();
        if (es != null) {
          BufferedReader br = new BufferedReader(new InputStreamReader(es));
          String line;
          while ((line = br.readLine()) != null) System.err.println("ERROR_STREAM: " + line);
        }
      } catch (Exception e2) {}
      e.printStackTrace(System.out);
    } finally {
      if (urlConn != null) urlConn.disconnect();
    }
    return null;
  }
  
  public String httpPost(String url,String message,String contentType)
  {
    return httpPost(url,message,contentType,true);
  }
    
  public String getAccessToken()
  {
    if (refreshToken!=null && tokenExpire<System.currentTimeMillis())
    {
      accessToken=null;
      refreshToken(refreshToken);
    }
    return accessToken;
  }

  
  @SuppressWarnings("unchecked")
  private void grabAccessToken(String data)
  {
    lastTokenMessage=data;
    accessToken=null;
    // A revoked or de-scoped refresh token makes the POST fail and return null; that is a
    // recoverable "needs re-authorization" state, not a crash
    if (data==null) return;
    Map<String, Object> authObj=(Map<String, Object>)json.parse(new JSON.ReaderSource(new StringReader(data)));
    if (authObj==null) return;
    accessToken=(String)authObj.get("access_token");
    Object expires=authObj.get("expires_in");
    if (accessToken!=null && expires instanceof Number)
      tokenExpire=System.currentTimeMillis()+(((Number)expires).intValue()*1000);
    if (authObj.containsKey("refresh_token"))refreshToken=(String)authObj.get("refresh_token");
  }

  // Plain state read: unlike getAccessToken() this never triggers another refresh attempt
  public boolean hasAccessToken()
  {
    return accessToken!=null;
  }
  
  public String getLastAccessMessage() {return lastTokenMessage;}
    
  public String refreshToken(String refreshToken)
  {
    this.refreshToken=refreshToken;
    String rst=httpPost(APIOAuth+"token","refresh_token="+refreshToken+"&client_id="+clientId+"&client_secret="+clientSecret+"&grant_type=refresh_token",ContentTypeURLEncoded,false);
    grabAccessToken(rst);
    return rst;
  }
  
  public String getRefreshToken()
  {
    return refreshToken;
  }

}
