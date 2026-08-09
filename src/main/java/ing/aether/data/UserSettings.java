package ing.aether.data;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.eclipse.jetty.util.ajax.JSON;

import com.jcraft.jsch.*;

import ing.aether.SessionTracker;
import ing.aether.tools.google.Drive;

/**
 * Owns the per-user aether.ing.setting.json stored in the user's own Google Drive.
 * All user-level Aether settings (currently the storage provider list and the SSH key pair)
 * live in that file; the server keeps only an in-memory cache and persists nothing itself.
 */
public class UserSettings
{
  public static final String SETTINGS_FILENAME = "aether.ing.setting.json";
  public static final String KEY_SETTINGS = "aetherSettings";
  public static final String KEY_SETTINGS_FILE_ID = "aetherSettingsFileId";
  private static final String KEY_RETRY_AT = "aetherSettingsRetryAt";
  private static final String PROVIDER_KEY_PREFIX = "SSH ";
  private static final JSON json = new JSON();

  // Idempotent: finds (or creates) the settings file once per cache lifetime and registers its providers.
  // Called on login and on first sight of a session; failures back off for a minute instead of hammering Drive.
  @SuppressWarnings("unchecked")
  public static synchronized void ensureLoaded(String issSubId, String email)
  {
    Map<String, Object> userData = SessionTracker.getUserData(issSubId);
    if (userData == null) return;
    if (userData.containsKey(KEY_SETTINGS))
    {
      registerProviders(issSubId, email);
      return;
    }
    Object retryAt = userData.get(KEY_RETRY_AT);
    if (retryAt instanceof Long && (Long) retryAt > System.currentTimeMillis()) return;

    Drive drive = findDrive(issSubId);
    if (drive == null) return;

    try
    {
      String fileId = findLatestSettingsFileId(drive);
      Map<String, Object> settings;
      if (fileId == null)
      {
        settings = new HashMap<>();
        settings.put("providers", new ArrayList<Map<String, Object>>());
        String created = drive.createFileWithContent(SETTINGS_FILENAME, "application/json", "root", json.toJSON(settings).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> createdMap = parse(created);
        fileId = (String) createdMap.get("id");
        if (fileId == null) throw new Exception("Drive did not return an id for the new settings file");
        System.out.println("UserSettings: created " + SETTINGS_FILENAME + " in My Drive for " + email);
      }
      else
      {
        try (InputStream is = drive.download(fileId)) { settings = parse(new String(is.readAllBytes(), StandardCharsets.UTF_8)); }
        normalize(settings);
      }
      userData.put(KEY_SETTINGS, settings);
      userData.put(KEY_SETTINGS_FILE_ID, fileId);
      userData.remove(KEY_RETRY_AT);
      registerProviders(issSubId, email);
    }
    catch (Exception e)
    {
      System.out.println("UserSettings: failed to load " + SETTINGS_FILENAME + " for " + email + ": " + e.getMessage());
      userData.put(KEY_RETRY_AT, System.currentTimeMillis() + 60000L);
    }
  }

  // Several copies may exist (manual copies, sync conflicts): always use the most recently modified
  @SuppressWarnings("unchecked")
  private static String findLatestSettingsFileId(Drive drive) throws Exception
  {
    String listJson = drive.list("name = '" + SETTINGS_FILENAME + "' and trashed = false", null);
    Map<String, Object> res = parse(listJson);
    Object[] files = res.get("files") instanceof Object[] ? (Object[]) res.get("files") : null;
    if (files == null || files.length == 0) return null;
    Map<String, Object> latest = null;
    for (Object f : files)
    {
      Map<String, Object> fm = (Map<String, Object>) f;
      // RFC3339 timestamps compare correctly as plain strings
      if (latest == null || String.valueOf(fm.get("modifiedTime")).compareTo(String.valueOf(latest.get("modifiedTime"))) > 0) latest = fm;
    }
    return (String) latest.get("id");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> getSettings(String issSubId)
  {
    Map<String, Object> userData = SessionTracker.getUserData(issSubId);
    return userData != null ? (Map<String, Object>) userData.get(KEY_SETTINGS) : null;
  }

  public static List<Map<String, Object>> getProviders(String issSubId)
  {
    Map<String, Object> settings = getSettings(issSubId);
    return settings == null ? new ArrayList<>() : new ArrayList<>(providerList(settings));
  }

  @SuppressWarnings("unchecked")
  public static String getPublicKey(String issSubId)
  {
    Map<String, Object> settings = getSettings(issSubId);
    if (settings == null || !(settings.get("sshKey") instanceof Map)) return null;
    return (String) ((Map<String, Object>) settings.get("sshKey")).get("publicKey");
  }

  // Generated once, on the first SSH server the user adds; reused for every server afterwards
  @SuppressWarnings("unchecked")
  public static synchronized Map<String, Object> ensureKeyPair(String issSubId) throws Exception
  {
    Map<String, Object> settings = getSettings(issSubId);
    if (settings == null) throw new Exception("User settings not loaded (is Google Drive reachable?)");
    Map<String, Object> sshKey = settings.get("sshKey") instanceof Map ? (Map<String, Object>) settings.get("sshKey") : null;
    if (sshKey != null && sshKey.get("privateKey") != null) return sshKey;

    KeyPair kpair = KeyPair.genKeyPair(new JSch(), KeyPair.RSA, 3072);
    ByteArrayOutputStream prv = new ByteArrayOutputStream();
    kpair.writePrivateKey(prv);
    ByteArrayOutputStream pub = new ByteArrayOutputStream();
    kpair.writePublicKey(pub, "aether-ide");
    kpair.dispose();

    sshKey = new HashMap<>();
    sshKey.put("type", "rsa");
    sshKey.put("privateKey", prv.toString(StandardCharsets.UTF_8));
    sshKey.put("publicKey", pub.toString(StandardCharsets.UTF_8).trim());
    settings.put("sshKey", sshKey);
    save(issSubId);
    return sshKey;
  }

  // oneTimePassword, when given, is used ONLY to install the public key on the server and is never stored
  public static synchronized void addProvider(String issSubId, String email, Map<String, Object> cfg, String oneTimePassword) throws Exception
  {
    Map<String, Object> settings = getSettings(issSubId);
    if (settings == null) throw new Exception("User settings not loaded (is Google Drive reachable?)");
    Map<String, Object> sshKey = ensureKeyPair(issSubId);

    if (oneTimePassword != null && !oneTimePassword.isEmpty())
    {
      installPublicKey(cfg, oneTimePassword, (String) sshKey.get("publicKey"));
      verifyKeyAuth(cfg, sshKey);
    }

    List<Map<String, Object>> providers = providerList(settings);
    String name = str(cfg.get("name"));
    providers.removeIf(p -> name.equals(str(p.get("name"))));
    providers.add(cfg);
    save(issSubId);

    SessionTracker.unregisterProvider(issSubId, providerKey(name));
    registerOne(issSubId, email, cfg, sshKey);
  }

  public static synchronized void removeProvider(String issSubId, String name) throws Exception
  {
    Map<String, Object> settings = getSettings(issSubId);
    if (settings == null) throw new Exception("User settings not loaded");
    List<Map<String, Object>> providers = providerList(settings);
    if (!providers.removeIf(p -> name.equals(str(p.get("name"))))) throw new Exception("Provider not found: " + name);
    save(issSubId);
    SessionTracker.unregisterProvider(issSubId, providerKey(name));
  }

  @SuppressWarnings("unchecked")
  private static void save(String issSubId) throws Exception
  {
    Map<String, Object> userData = SessionTracker.getUserData(issSubId);
    if (userData == null) throw new Exception("No user data cache");
    Map<String, Object> settings = (Map<String, Object>) userData.get(KEY_SETTINGS);
    String fileId = (String) userData.get(KEY_SETTINGS_FILE_ID);
    if (settings == null || fileId == null) throw new Exception("Settings not loaded");
    Drive drive = findDrive(issSubId);
    if (drive == null) throw new Exception("Google Drive provider not available");
    drive.upload(fileId, json.toJSON(settings).getBytes(StandardCharsets.UTF_8), "application/json");
  }

  @SuppressWarnings("unchecked")
  private static void registerProviders(String issSubId, String email)
  {
    Map<String, Object> settings = getSettings(issSubId);
    if (settings == null || email == null) return;
    Map<String, Object> sshKey = settings.get("sshKey") instanceof Map ? (Map<String, Object>) settings.get("sshKey") : null;
    for (Map<String, Object> cfg : providerList(settings))
    {
      try { registerOne(issSubId, email, cfg, sshKey); }
      catch (Exception e) { System.out.println("UserSettings: failed to register provider " + cfg.get("name") + ": " + e.getMessage()); }
    }
  }

  private static void registerOne(String issSubId, String email, Map<String, Object> cfg, Map<String, Object> sshKey) throws Exception
  {
    String type = str(cfg.get("type"));
    if (!"ssh".equals(type)) return; // future types: gdrive, dropbox, pcloud, s3, onedrive, jdbc, vnc, ...
    if (sshKey == null || sshKey.get("privateKey") == null) return;
    String key = providerKey(str(cfg.get("name")));
    if (SessionTracker.getProvider(issSubId, key) != null) return;
    SshFileProvider provider = new SshFileProvider(email, key, key, str(cfg.get("host")), port(cfg), str(cfg.get("user")), (String) sshKey.get("privateKey"), (String) sshKey.get("publicKey"), str(cfg.get("root")));
    SessionTracker.registerProvider(issSubId, key, provider);
  }

  // Appends the public key to ~/.ssh/authorized_keys over a one-time password login
  private static void installPublicKey(Map<String, Object> cfg, String password, String publicKey) throws Exception
  {
    JSch jsch = new JSch();
    String user = str(cfg.get("user"));
    String host = str(cfg.get("host"));
    Session s = jsch.getSession(user, host, port(cfg));
    s.setPassword(password);
    s.setConfig("StrictHostKeyChecking", "no");
    try
    {
      try { s.connect(15000); }
      catch (JSchException e)
      {
        throw new Exception("SSH password login as " + user + "@" + host + " failed (" + e.getMessage() + "). Many servers forbid password login — Ubuntu forbids it for root by default. Use a different user, or leave the password empty and install the public key manually.");
      }
      String key = publicKey.trim().replace("'", "");
      String cmd = "mkdir -p ~/.ssh; chmod 700 ~/.ssh; touch ~/.ssh/authorized_keys; grep -qF '" + key + "' ~/.ssh/authorized_keys || echo '" + key + "' >> ~/.ssh/authorized_keys; chmod 600 ~/.ssh/authorized_keys";
      ChannelExec ch = (ChannelExec) s.openChannel("exec");
      ch.setCommand(cmd);
      InputStream out = ch.getInputStream();
      ch.connect(10000);
      out.readAllBytes(); // drains until the remote command finishes
      while (!ch.isClosed()) Thread.sleep(50);
      int status = ch.getExitStatus();
      ch.disconnect();
      if (status != 0) throw new Exception("Key installation command exited with status " + status);
    }
    finally { s.disconnect(); }
  }

  private static void verifyKeyAuth(Map<String, Object> cfg, Map<String, Object> sshKey) throws Exception
  {
    JSch jsch = new JSch();
    jsch.addIdentity("aether", ((String) sshKey.get("privateKey")).getBytes(StandardCharsets.UTF_8), ((String) sshKey.get("publicKey")).getBytes(StandardCharsets.UTF_8), null);
    Session s = jsch.getSession(str(cfg.get("user")), str(cfg.get("host")), port(cfg));
    s.setConfig("StrictHostKeyChecking", "no");
    s.setConfig("PreferredAuthentications", "publickey");
    try { s.connect(15000); }
    catch (Exception e) { throw new Exception("Public key was installed but key login failed: " + e.getMessage()); }
    finally { s.disconnect(); }
  }

  private static Drive findDrive(String issSubId)
  {
    for (String name : SessionTracker.getProviderNames(issSubId))
    {
      Object p = SessionTracker.getProvider(issSubId, name);
      if (p instanceof GoogleDriveProvider) return ((GoogleDriveProvider) p).getDrive();
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> providerList(Map<String, Object> settings)
  {
    if (!(settings.get("providers") instanceof List)) normalize(settings);
    return (List<Map<String, Object>>) settings.get("providers");
  }

  // Jetty JSON parses arrays as Object[]; keep providers as a mutable List internally
  @SuppressWarnings("unchecked")
  private static void normalize(Map<String, Object> settings)
  {
    Object provs = settings.get("providers");
    List<Map<String, Object>> list = new ArrayList<>();
    if (provs instanceof Object[]) { for (Object o : (Object[]) provs) if (o instanceof Map) list.add((Map<String, Object>) o); }
    else if (provs instanceof List) { for (Object o : (List<?>) provs) if (o instanceof Map) list.add((Map<String, Object>) o); }
    settings.put("providers", list);
  }

  // Registry key doubles as the URL library segment, so it must not contain '/'
  private static String providerKey(String name)
  {
    return PROVIDER_KEY_PREFIX + (name == null ? "server" : name.replace("/", "-"));
  }

  private static String str(Object o)
  {
    return o == null ? null : String.valueOf(o);
  }

  private static int port(Map<String, Object> cfg)
  {
    Object p = cfg.get("port");
    if (p instanceof Number) return ((Number) p).intValue();
    try { if (p instanceof String) return Integer.parseInt((String) p); } catch (Exception e) {}
    return 22;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parse(String jsonStr) throws Exception
  {
    if (jsonStr == null || jsonStr.trim().isEmpty()) return new HashMap<>();
    return (Map<String, Object>) json.parse(new JSON.ReaderSource(new StringReader(jsonStr)));
  }
}
