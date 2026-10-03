package ing.aether.tools.push;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.ExecutorService;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.jetty.util.ajax.JSON;

import ing.aether.Portal;
import ing.aether.data.UserSettings;
import ing.aether.socket.AetherWebSocket;
import jakarta.servlet.http.HttpSession;

// Standard Web Push (RFC 8030) with VAPID (RFC 8292) and aes128gcm payload encryption (RFC 8291),
// JDK crypto only. The server's VAPID key pair lives in aether.json ("WebPush"); each user's browser
// subscriptions live in their own aether.ing.setting.json (UserSettings), like every user setting.
// Used to reach the phone when a run needs input or finishes while no Aether tab is visible.
public class WebPush
{
  private static final String PROP = "WebPush";
  private static final JSON json = new JSON();
  private static final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
  private static final ExecutorService sender = ing.aether.tools.ai.run.RunManager.newExecutor();
  private static final SecureRandom random = new SecureRandom();

  private static KeyPair vapid;
  private static String publicKeyB64;
  private static String subject;

  private static ECParameterSpec p256() throws Exception
  {
    AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
    params.init(new ECGenParameterSpec("secp256r1"));
    return params.getParameterSpec(ECParameterSpec.class);
  }

  // Loads the VAPID key pair from aether.json, generating it on first use
  @SuppressWarnings("unchecked")
  private static synchronized void ensureKeys() throws Exception
  {
    if (vapid != null) return;
    Map<String, Object> cfg = Portal.getProperty(PROP);
    KeyFactory kf = KeyFactory.getInstance("EC");
    if (cfg != null && cfg.get("PrivateKey") instanceof String && cfg.get("PublicKey") instanceof String)
    {
      PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode((String) cfg.get("PrivateKey"))));
      PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode((String) cfg.get("PublicKey"))));
      vapid = new KeyPair(pub, priv);
      subject = cfg.get("Subject") instanceof String ? (String) cfg.get("Subject") : "mailto:noreply@aether.ing";
    }
    else
    {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
      g.initialize(new ECGenParameterSpec("secp256r1"), random);
      vapid = g.generateKeyPair();
      subject = "mailto:noreply@aether.ing";
      Map<String, Object> out = cfg != null ? new HashMap<>(cfg) : new HashMap<>();
      out.put("PrivateKey", Base64.getEncoder().encodeToString(vapid.getPrivate().getEncoded()));
      out.put("PublicKey", Base64.getEncoder().encodeToString(vapid.getPublic().getEncoded()));
      out.put("Subject", subject);
      Portal.setProperty(PROP, out);
    }
    publicKeyB64 = b64url(uncompressed((ECPublicKey) vapid.getPublic()));
  }

  // applicationServerKey for PushManager.subscribe in the browser
  public static String publicKey()
  {
    try
    {
      ensureKeys();
      return publicKeyB64;
    }
    catch (Exception e)
    {
      System.out.println("WebPush: VAPID keys unavailable: " + e.getMessage());
      return "";
    }
  }

  @SuppressWarnings("unchecked")
  public static void subscribe(HttpSession http, Object subscription)
  {
    String issSubId = (String) http.getAttribute(Portal.SessionAccountID);
    if (issSubId == null || !(subscription instanceof Map)) return;
    Map<String, Object> sub = (Map<String, Object>) subscription;
    Map<String, Object> keys = sub.get("keys") instanceof Map ? (Map<String, Object>) sub.get("keys") : null;
    if (!(sub.get("endpoint") instanceof String) || keys == null || keys.get("p256dh") == null || keys.get("auth") == null)
    {
      AetherWebSocket.notify(http.getId(), "⚠️ Push subscription rejected: incomplete data from the browser.");
      return;
    }
    Map<String, Object> clean = new HashMap<>();
    clean.put("endpoint", sub.get("endpoint"));
    clean.put("p256dh", keys.get("p256dh"));
    clean.put("auth", keys.get("auth"));
    clean.put("created", System.currentTimeMillis());
    try
    {
      UserSettings.addPushSubscription(issSubId, clean);
      AetherWebSocket.notify(http.getId(), "🔔 Notifications enabled on this device.");
    }
    catch (Exception e) { AetherWebSocket.notify(http.getId(), "⚠️ Could not save the push subscription: " + e.getMessage()); }
  }

  public static void notifyUser(String issSubId, String email, String title, String body, String url, String tag)
  {
    if (issSubId == null) return;
    List<Map<String, Object>> subs = UserSettings.getPushSubscriptions(issSubId);
    if (subs.isEmpty()) return;
    Map<String, Object> payload = new HashMap<>();
    payload.put("title", title);
    payload.put("body", body != null ? body : "");
    payload.put("url", url);
    payload.put("tag", tag);
    byte[] data = json.toJSON(payload).getBytes(StandardCharsets.UTF_8);
    for (Map<String, Object> sub : subs)
    {
      sender.submit(() ->
      {
        try
        {
          int status = send(sub, data);
          // 404/410: the browser dropped this subscription for good
          if (status == 404 || status == 410) UserSettings.removePushSubscription(issSubId, String.valueOf(sub.get("endpoint")));
          else if (status >= 300) System.out.println("WebPush: push service answered " + status + " for " + email);
        }
        catch (Exception e) { System.out.println("WebPush: send failed: " + e.getMessage()); }
      });
    }
  }

  private static int send(Map<String, Object> sub, byte[] payload) throws Exception
  {
    ensureKeys();
    String endpoint = String.valueOf(sub.get("endpoint"));
    byte[] body = encrypt(payload, b64urlDecode(String.valueOf(sub.get("p256dh"))), b64urlDecode(String.valueOf(sub.get("auth"))));
    URI uri = URI.create(endpoint);
    String audience = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
    HttpRequest req = HttpRequest.newBuilder(uri)
      .timeout(java.time.Duration.ofSeconds(30))
      .header("Content-Type", "application/octet-stream")
      .header("Content-Encoding", "aes128gcm")
      .header("TTL", "86400")
      .header("Urgency", "high")
      .header("Authorization", "vapid t=" + jwt(audience) + ", k=" + publicKeyB64)
      .POST(HttpRequest.BodyPublishers.ofByteArray(body))
      .build();
    return httpClient.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  private static String jwt(String audience) throws Exception
  {
    String header = b64url("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
    long exp = System.currentTimeMillis() / 1000 + 12 * 3600;
    String claims = b64url(("{\"aud\":\"" + audience + "\",\"exp\":" + exp + ",\"sub\":\"" + subject + "\"}").getBytes(StandardCharsets.UTF_8));
    Signature sig = Signature.getInstance("SHA256withECDSAinP1363Format");
    sig.initSign(vapid.getPrivate());
    sig.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
    return header + "." + claims + "." + b64url(sig.sign());
  }

  // RFC 8291: ECDH with the browser key, HKDF with its auth secret, one aes128gcm record
  static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret) throws Exception
  {
    ECParameterSpec spec = p256();
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(spec, random);
    KeyPair as = g.generateKeyPair();
    byte[] asPublic = uncompressed((ECPublicKey) as.getPublic());

    ECPublicKey ua = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(new BigInteger(1, Arrays.copyOfRange(uaPublic, 1, 33)), new BigInteger(1, Arrays.copyOfRange(uaPublic, 33, 65))), spec));
    KeyAgreement ka = KeyAgreement.getInstance("ECDH");
    ka.init(as.getPrivate());
    ka.doPhase(ua, true);
    byte[] ecdhSecret = ka.generateSecret();

    byte[] prkKey = hmac(authSecret, ecdhSecret);
    byte[] keyInfo = concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[] { 0 }, uaPublic, asPublic, new byte[] { 1 });
    byte[] ikm = hmac(prkKey, keyInfo);

    byte[] salt = new byte[16];
    random.nextBytes(salt);
    byte[] prk = hmac(salt, ikm);
    byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII), new byte[] { 0, 1 })), 16);
    byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII), new byte[] { 0, 1 })), 12);

    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
    byte[] ct = cipher.doFinal(concat(plaintext, new byte[] { 2 }));

    ByteBuffer out = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length + ct.length);
    out.put(salt).putInt(4096).put((byte) asPublic.length).put(asPublic).put(ct);
    return out.array();
  }

  private static byte[] hmac(byte[] key, byte[] data) throws Exception
  {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(data);
  }

  private static byte[] concat(byte[]... parts)
  {
    int len = 0;
    for (byte[] p : parts) len += p.length;
    ByteBuffer b = ByteBuffer.allocate(len);
    for (byte[] p : parts) b.put(p);
    return b.array();
  }

  private static byte[] uncompressed(ECPublicKey key)
  {
    byte[] out = new byte[65];
    out[0] = 4;
    byte[] x = unsigned(key.getW().getAffineX(), 32);
    byte[] y = unsigned(key.getW().getAffineY(), 32);
    System.arraycopy(x, 0, out, 1, 32);
    System.arraycopy(y, 0, out, 33, 32);
    return out;
  }

  private static byte[] unsigned(BigInteger v, int len)
  {
    byte[] raw = v.toByteArray();
    byte[] out = new byte[len];
    int copy = Math.min(raw.length, len);
    System.arraycopy(raw, raw.length - copy, out, len - copy, copy);
    return out;
  }

  private static String b64url(byte[] b)
  {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private static byte[] b64urlDecode(String s)
  {
    return Base64.getUrlDecoder().decode(s.replace('+', '-').replace('/', '_').replace("=", ""));
  }
}
