package ing.aether;

import jakarta.servlet.annotation.WebListener;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.*;
import javax.crypto.spec.*;

import org.eclipse.jetty.util.ajax.JSON;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

@WebListener
public class SessionTracker implements HttpSessionListener,ServletContextListener
{
  private static final String PropEncryption="KeySet";
  private static final String PROP_MASTER_KEY_HEX="KeyID";
  private static final String PROP_FILENAME_SALT="NameID";
  private static final String USER_DATA_FILE_EXTENSION=".vibed";
  private static final int GCM_IV_LENGTH=12; // 96 bits
  private static final int GCM_TAG_LENGTH=16; // 128 bits, in bytes
  private static final String ENCRYPTION_ALGORITHM="AES/GCM/NoPadding";
  private static final int KEY_LENGTH_BITS=256; // AES-256
  private static final int PBKDF2_ITERATIONS=65536; // Number of iterations for PBKDF2
  private static final int PBKDF2_SALT_LENGTH=16; // 16 bytes for PBKDF2 salt
  
  public static final String KEY_ACCESS_TOKEN = "accessToken";
  public static final String KEY_REFRESH_TOKEN = "refreshToken";
  public static final String KEY_PROVIDERS = "providers";
  

  private static final ConcurrentHashMap<String,HttpSession> sessionMap=new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, Map<String, Object>> jsonMap=new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, Integer> issSubIdSessions = new ConcurrentHashMap<>();
  // Session ids already counted into issSubIdSessions; sessions are created before login, so counting must happen on first sight of the account id
  private static final Set<String> countedSessions = ConcurrentHashMap.newKeySet();
  private static final JSON json = new JSON();
    
  private static SecretKey serverMasterKey;
  private static String serverSecretSaltForFilenames;


  @Override
  public void sessionCreated(HttpSessionEvent event)
  {
    HttpSession session=event.getSession();
    sessionMap.put(session.getId(),session);
    System.out.println("Session Created: "+session.getId());
  }

  @Override
  public void sessionDestroyed(HttpSessionEvent event)
  {
    HttpSession sessionBeingDestroyed=event.getSession();
    String sessionId=sessionBeingDestroyed.getId();
    sessionMap.remove(sessionId);

    if (!countedSessions.remove(sessionId))return;
    String issSubId=(String)sessionBeingDestroyed.getAttribute(Portal.SessionAccountID);
    if (issSubId==null)return;
    issSubIdSessions.compute(issSubId,(key,count) ->
    {
      if (count==null||count<=1)
      {
        Map<String, Object> userData=jsonMap.remove(issSubId);
        if (userData!=null)saveUserJsonData(issSubId,userData);
        return null;
      }else return count-1;
    });
  }
  
  public static void sessionCheck(HttpSession session)
  {
    if (session == null) return;
    try { session.setMaxInactiveInterval(session.getMaxInactiveInterval()); } catch (Exception e) { return; }

    String sessionId = session.getId();
    if (!sessionMap.containsKey(sessionId))
    {
      sessionMap.put(sessionId, session);
      System.out.println("Session Tracked: " + sessionId);
    }
    String issSubId = (String) session.getAttribute(Portal.SessionAccountID);
    if (issSubId == null) return;
    if (countedSessions.add(sessionId)) issSubIdSessions.merge(issSubId, 1, Integer::sum);

    if (jsonMap.containsKey(issSubId)) return;
    Map<String, Object> userData = loadUserJsonData(issSubId);
    jsonMap.put(issSubId, userData);
    String refreshToken = (String) userData.get(KEY_REFRESH_TOKEN);
    if (refreshToken != null)
    {
      String email = (String) session.getAttribute(Portal.SessionEmail);
      if (email != null) registerGoogleProvider(issSubId, email, refreshToken);
    }
    // User settings live in the user's own Drive (aether.ing.setting.json); load them and register their providers
    ing.aether.data.UserSettings.ensureLoaded(issSubId, (String) session.getAttribute(Portal.SessionEmail));
  }

  public static boolean registerGoogleProvider(String issSubId, String email, String refreshToken)
  {
    Object[] auths = Portal.getProperties("Authentication");
    String clientId = null;
    String clientSecret = null;
    if (auths != null) for (Object obj : auths)
    {
      Map<String, Object> auth = (Map<String, Object>) obj;
      if ("G".equals(auth.get("Provider")))
      {
        clientId = (String) auth.get("ClientId");
        clientSecret = (String) auth.get("Secret");
        break;
      }
    }

    if (clientId == null || clientSecret == null) return false;

    String providerKey = "Google Drive " + email;
    if (getProvider(issSubId, providerKey) != null) return true;

    ing.aether.tools.google.Token token = new ing.aether.tools.google.Token(clientId, clientSecret, refreshToken);
    // The constructor already performed the refresh round trip, so this costs no extra
    // call: no access token means the stored refresh token was revoked or lost its grant
    if (!token.hasAccessToken())
    {
      System.out.println("WARN: Google refresh token unusable for " + email + "; Drive authorization needed");
      Map<String, Object> userData = jsonMap.get(issSubId);
      if (userData != null) userData.remove(KEY_REFRESH_TOKEN);
      return false;
    }

    ing.aether.tools.google.Drive drive = new ing.aether.tools.google.Drive(token);
    registerProvider(issSubId, providerKey, new ing.aether.data.GoogleDriveProvider(email, providerKey, drive, "Google Drive " + email));
    return true;
  }

  // Whether this account currently holds a working Google Drive grant
  public static boolean hasGoogleDrive(String issSubId, String email)
  {
    if (issSubId == null || email == null) return false;
    return getProvider(issSubId, "Google Drive " + email) != null;
  }

  @SuppressWarnings("unchecked")
  public static void registerProvider(String issSubId, String name, Object provider) 
  {
    if (issSubId == null) return;
    Map<String, Object> userData = jsonMap.get(issSubId);
    if (userData == null) return;
    
    Map<String, Object> providers = (Map<String, Object>) userData.get(KEY_PROVIDERS);
    if (providers == null) 
    {
      providers = new ConcurrentHashMap<>();
      userData.put(KEY_PROVIDERS, providers);
    }
    providers.put(name, provider);
  }

  @SuppressWarnings("unchecked")
  public static Object getProvider(String issSubId, String name) 
  {
    if (issSubId == null) return null;
    Map<String, Object> userData = jsonMap.get(issSubId);
    if (userData == null) return null;
    
    Map<String, Object> providers = (Map<String, Object>) userData.get(KEY_PROVIDERS);
    return (providers != null) ? providers.get(name) : null;
  }

  @SuppressWarnings("unchecked")
  public static Set<String> getProviderNames(String issSubId) 
  {
    if (issSubId == null) return Collections.emptySet();
    Map<String, Object> userData = jsonMap.get(issSubId);
    if (userData == null) return Collections.emptySet();
    
    Map<String, Object> providers = (Map<String, Object>) userData.get(KEY_PROVIDERS);
    return (providers != null) ? providers.keySet() : Collections.emptySet();
  }

  @SuppressWarnings("unchecked")
  public static void unregisterProvider(String issSubId, String name)
  {
    if (issSubId == null) return;
    Map<String, Object> userData = jsonMap.get(issSubId);
    if (userData == null) return;
    Map<String, Object> providers = (Map<String, Object>) userData.get(KEY_PROVIDERS);
    if (providers != null) providers.remove(name);
  }

  public static void clearProviders(String issSubId)
  {
    if (issSubId == null) return;
    Map<String, Object> userData = jsonMap.get(issSubId);
    if (userData != null) userData.remove(KEY_PROVIDERS);
  }

  // In-memory user cache accessor for UserSettings; nothing here is persisted except the auth tokens
  public static Map<String, Object> getUserData(String issSubId)
  {
    return issSubId != null ? jsonMap.get(issSubId) : null;
  }

  public static HttpSession getSessionById(String sessionId)
  {
    return sessionMap.get(sessionId);
  }
  
  public static Map<String, Object> getSessionData(HttpSession session)
  {
    String issSubId=(String) session.getAttribute(Portal.SessionAccountID);
    if (issSubId==null)return new HashMap<>();
    
    Map<String, Object> data=jsonMap.get(issSubId);
    if (data!=null)return data;
    data=loadUserJsonData(issSubId);
    jsonMap.put(issSubId,data);
    return data;
  }
  
  public static void setSessionData(HttpSession session,Map<String, Object> data)
  {
    String issSubId=(String) session.getAttribute(Portal.SessionAccountID);
    if (issSubId==null)return;
    jsonMap.put(issSubId,data);
  }


  public static void listSessions()
  {
    System.out.println("Active Sessions:");
    sessionMap.forEach((id,session)->System.out.println("Session ID: "+id+", Created: "+session.getCreationTime()));
    System.out.println("User Cache:");
    jsonMap.forEach((issSubId,data)->System.out.println("User: "+issSubId+" (Sessions: "+issSubIdSessions.getOrDefault(issSubId,0)+"), Data Size: "+data.size()+" keys."));
  }

  public static int getActiveSessionCount()
  {
    return sessionMap.size();
  }
  
  @Override
  public void contextInitialized(ServletContextEvent sce)
  {
    System.setProperty("org.eclipse.jetty.server.Request.maxFormContentSize", "50000000");
    sce.getServletContext().setAttribute("org.eclipse.jetty.server.Request.maxFormContentSize", 50000000);
    Portal.reloadProperty();
    Map<String, Object> encryption=Portal.getProperty(PropEncryption);
    if (encryption == null)encryption = new HashMap<>();

    String masterKeyHex = (String)encryption.get(PROP_MASTER_KEY_HEX);
    String filenameSalt = (String)encryption.get(PROP_FILENAME_SALT);
    
    boolean saveProp=false;
    SecureRandom secureRandom = new SecureRandom();
    if (masterKeyHex == null || masterKeyHex.isEmpty())
    {
      byte[] masterKeyBytes = new byte[KEY_LENGTH_BITS / 8]; // 32 bytes for AES-256
      secureRandom.nextBytes(masterKeyBytes);
      masterKeyHex = byteArrayToHexString(masterKeyBytes);
      encryption.put(PROP_MASTER_KEY_HEX, masterKeyHex);
      saveProp=true;
    }
    if (filenameSalt == null || filenameSalt.isEmpty())
    {
      byte[] filenameSaltBytes = new byte[16]; // 16 bytes for a good salt (32 hex characters)
      secureRandom.nextBytes(filenameSaltBytes);
      filenameSalt = byteArrayToHexString(filenameSaltBytes);
      encryption.put(PROP_FILENAME_SALT, filenameSalt);
      saveProp=true;
    }
    if (saveProp)Portal.setProperty(PropEncryption,encryption);


    try
    {
      byte[] masterKeyBytes=hexStringToByteArray(masterKeyHex);
      if (masterKeyBytes.length*8!=KEY_LENGTH_BITS)  throw new IllegalArgumentException("Master key (from hex) must be "+KEY_LENGTH_BITS+"-bit long.");
      serverMasterKey=new SecretKeySpec(masterKeyBytes,"AES");
      serverSecretSaltForFilenames=filenameSalt;
    }catch (IllegalArgumentException e)
    {
      System.err.println("FATAL ERROR: Failed to initialize secure data management components. User data cannot be secured.");
      e.printStackTrace(System.out);
    }

    System.out.println("Context Initialized.");
  }
    
    
   @Override
  public void contextDestroyed(ServletContextEvent sce)
  {
    System.out.println("Application stopped. Cleaning up.");
    for (Map.Entry<String,Map<String, Object>> entry:jsonMap.entrySet())
    {
      String issSubId=entry.getKey();
      Map<String, Object> userData=entry.getValue();
      if (userData==null)continue;
      saveUserJsonData(issSubId,userData);
    }
    sessionMap.clear();
    jsonMap.clear();
    issSubIdSessions.clear();
  }
   
   private static String byteArrayToHexString(byte[] bytes)
   {
     StringBuilder sb=new StringBuilder();
     for (byte b:bytes)
     {
       sb.append(String.format("%02x",b));
     }
     return sb.toString();
   }

   private static byte[] hexStringToByteArray(String hexString)
   {
     int len=hexString.length();
     byte[] data=new byte[len/2];
     for (int i=0;i<len;i+=2)
     {
       data[i/2]=(byte)((Character.digit(hexString.charAt(i),16)<<4)+Character.digit(hexString.charAt(i+1),16));
     }
     return data;
   }
   
   private static String getFilenameForUser(String issSubId) throws NoSuchAlgorithmException
   {
     MessageDigest digest=MessageDigest.getInstance("SHA-256");
     // Combine iss+sub ID with a server-side secret salt before hashing
     byte[] hashedBytes=digest.digest((issSubId+serverSecretSaltForFilenames).getBytes(StandardCharsets.UTF_8));
     return byteArrayToHexString(hashedBytes);
   }

   private static SecretKey deriveUserKey(String issSubId,byte[] salt) throws NoSuchAlgorithmException,InvalidKeySpecException
   {
     SecretKeyFactory factory=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
     // Use the serverMasterKey's encoded form as the password for PBKDF2
     PBEKeySpec spec=new PBEKeySpec(Base64.getEncoder().encodeToString(serverMasterKey.getEncoded()).toCharArray(),salt,PBKDF2_ITERATIONS,KEY_LENGTH_BITS);
     SecretKey tmp=factory.generateSecret(spec);
     return new SecretKeySpec(tmp.getEncoded(),"AES");
   }

   public static void saveUserJsonData(String issSubId,Map<String, Object> jsonObject) 
   {
     if (serverMasterKey==null||serverSecretSaltForFilenames==null)
     {
       System.out.println("WARN: secure data management components are not initialized, user data not saved");
       return;
     }
     
     // Only keep tokens for persistence as per user request
     Map<String, Object> persistentData = new HashMap<>();
     if (jsonObject.containsKey(KEY_ACCESS_TOKEN)) persistentData.put(KEY_ACCESS_TOKEN, jsonObject.get(KEY_ACCESS_TOKEN));
     if (jsonObject.containsKey(KEY_REFRESH_TOKEN)) persistentData.put(KEY_REFRESH_TOKEN, jsonObject.get(KEY_REFRESH_TOKEN));
     
     String plainTextJson=json.toJSON(persistentData); 
     
     SecureRandom secureRandom=new SecureRandom();
     byte[] iv=new byte[GCM_IV_LENGTH];
     secureRandom.nextBytes(iv);

     byte[] pbkdf2Salt;
     try
     {
       pbkdf2Salt=MessageDigest.getInstance("SHA-256").digest(issSubId.getBytes(StandardCharsets.UTF_8));
       pbkdf2Salt=java.util.Arrays.copyOf(pbkdf2Salt,PBKDF2_SALT_LENGTH);
       SecretKey userKey=deriveUserKey(issSubId,pbkdf2Salt);
       Cipher cipher=Cipher.getInstance(ENCRYPTION_ALGORITHM);
       GCMParameterSpec gcmParameterSpec=new GCMParameterSpec(GCM_TAG_LENGTH*8,iv);
       cipher.init(Cipher.ENCRYPT_MODE,userKey,gcmParameterSpec);

       cipher.updateAAD(issSubId.getBytes(StandardCharsets.UTF_8));

       byte[] cipherText=cipher.doFinal(plainTextJson.getBytes(StandardCharsets.UTF_8));

       ByteBuffer byteBuffer=ByteBuffer.allocate(GCM_IV_LENGTH+PBKDF2_SALT_LENGTH+cipherText.length);
       byteBuffer.put(iv);
       byteBuffer.put(pbkdf2Salt);
       byteBuffer.put(cipherText);
       byte[] encryptedData=byteBuffer.array();

       String filename=getFilenameForUser(issSubId)+USER_DATA_FILE_EXTENSION;
       try (FileOutputStream fos=new FileOutputStream(filename))
       {
         fos.write(encryptedData);
       }catch (IOException e)
       {
         e.printStackTrace(System.out);
       }
     }catch (NoSuchAlgorithmException|InvalidKeySpecException|NoSuchPaddingException|InvalidKeyException|InvalidAlgorithmParameterException|IllegalBlockSizeException|BadPaddingException e)
     {
       e.printStackTrace(System.out);
     }


   }

   @SuppressWarnings("unchecked")
   public static Map<String, Object> loadUserJsonData(String issSubId)
   {
     if (serverMasterKey==null||serverSecretSaltForFilenames==null)
     {
       System.out.println("WARN: secure data management components are not initialized, user data not loaded");
       return new HashMap<>();
     }
     try
     {

       String filename=getFilenameForUser(issSubId)+USER_DATA_FILE_EXTENSION;
       File userFile=new File(filename);
       if (userFile.exists())
       {
         byte[] encryptedData=new byte[(int)userFile.length()];
         try (FileInputStream fis=new FileInputStream(userFile))
         {
           fis.read(encryptedData);
         }

         ByteBuffer byteBuffer=ByteBuffer.wrap(encryptedData);

         byte[] iv=new byte[GCM_IV_LENGTH];
         byteBuffer.get(iv);

         byte[] pbkdf2Salt=new byte[PBKDF2_SALT_LENGTH];
         byteBuffer.get(pbkdf2Salt);

         byte[] cipherText=new byte[byteBuffer.remaining()];
         byteBuffer.get(cipherText);

         SecretKey userKey=deriveUserKey(issSubId,pbkdf2Salt);

         Cipher cipher=Cipher.getInstance(ENCRYPTION_ALGORITHM);
         GCMParameterSpec gcmParameterSpec=new GCMParameterSpec(GCM_TAG_LENGTH*8,iv);
         cipher.init(Cipher.DECRYPT_MODE,userKey,gcmParameterSpec);

         cipher.updateAAD(issSubId.getBytes(StandardCharsets.UTF_8));

         byte[] plainText=cipher.doFinal(cipherText);
         String plainTextJson=new String(plainText,StandardCharsets.UTF_8);

         return (Map<String, Object>)json.parse(new JSON.ReaderSource(new StringReader(plainTextJson)));       }
     }catch (NoSuchAlgorithmException|InvalidKeySpecException|NoSuchPaddingException|InvalidKeyException|InvalidAlgorithmParameterException|IllegalBlockSizeException|BadPaddingException|IOException e)
     {
       e.printStackTrace(System.out);
     }
     System.out.println("No data found for user "+issSubId);
     return new HashMap<>();
   }

 }
