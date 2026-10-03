package ing.aether.tools;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

// Shared crypto for everything stored beside the .vibed token files (run logs, run indexes, the server
// run registry). Same key hierarchy as SessionTracker: a per-user key is PBKDF2(serverMasterKey) salted
// by the user's id, and every file type gets its own HMAC-derived sub key so records of one kind can
// never be decrypted as another. JDK crypto only.
public class VibedCrypto
{
  private static final int GCM_IV_LENGTH = 12;
  private static final int GCM_TAG_BITS = 128;
  private static final int PBKDF2_ITERATIONS = 65536;
  private static final int PBKDF2_SALT_LENGTH = 16;
  private static final int KEY_LENGTH_BITS = 256;

  private static SecretKey masterKey;
  private static String filenameSalt;
  private static final SecureRandom random = new SecureRandom();
  // PBKDF2 costs ~50ms per derivation: done once per user, never per record
  private static final Map<String, SecretKey> userKeys = new ConcurrentHashMap<>();

  public static void init(SecretKey serverMasterKey, String serverFilenameSalt)
  {
    masterKey = serverMasterKey;
    filenameSalt = serverFilenameSalt;
    userKeys.clear();
  }

  public static boolean isReady()
  {
    return masterKey != null && filenameSalt != null;
  }

  // Directory holding the .vibed files: the JVM working directory, exactly as SessionTracker writes them
  public static File dataDir()
  {
    return new File(".").getAbsoluteFile();
  }

  // Same hash SessionTracker uses for <hash>.vibed, so a user's files sit together
  public static String userFileBase(String issSubId) throws Exception
  {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hashed = digest.digest((issSubId + filenameSalt).getBytes(StandardCharsets.UTF_8));
    StringBuilder sb = new StringBuilder();
    for (byte b : hashed) sb.append(String.format("%02x", b));
    return sb.toString();
  }

  public static SecretKey userKey(String issSubId) throws Exception
  {
    SecretKey cached = userKeys.get(issSubId);
    if (cached != null) return cached;
    byte[] salt = java.util.Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(issSubId.getBytes(StandardCharsets.UTF_8)), PBKDF2_SALT_LENGTH);
    SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
    PBEKeySpec spec = new PBEKeySpec(Base64.getEncoder().encodeToString(masterKey.getEncoded()).toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
    SecretKey key = new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    userKeys.put(issSubId, key);
    return key;
  }

  public static SecretKey subKey(SecretKey parent, String domain) throws Exception
  {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(parent.getEncoded(), "HmacSHA256"));
    return new SecretKeySpec(mac.doFinal(domain.getBytes(StandardCharsets.UTF_8)), "AES");
  }

  public static SecretKey serverKey(String domain) throws Exception
  {
    return subKey(masterKey, domain);
  }

  // Returns IV | ciphertext+tag
  public static byte[] seal(SecretKey key, String aad, byte[] plain) throws Exception
  {
    byte[] iv = new byte[GCM_IV_LENGTH];
    random.nextBytes(iv);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
    cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
    byte[] ct = cipher.doFinal(plain);
    ByteBuffer out = ByteBuffer.allocate(iv.length + ct.length);
    out.put(iv).put(ct);
    return out.array();
  }

  // Throws AEADBadTagException when the blob was tampered with, torn, or belongs to another AAD
  public static byte[] open(SecretKey key, String aad, byte[] blob) throws Exception
  {
    if (blob.length < GCM_IV_LENGTH + 16) throw new javax.crypto.AEADBadTagException("record too short");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_LENGTH));
    cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
    return cipher.doFinal(blob, GCM_IV_LENGTH, blob.length - GCM_IV_LENGTH);
  }

  // Crash-safe replace: a reader sees either the old file or the complete new one, never a torn write
  public static void atomicWrite(File target, byte[] data) throws IOException
  {
    File tmp = new File(target.getAbsolutePath() + ".tmp");
    try (FileChannel ch = FileChannel.open(tmp.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))
    {
      ByteBuffer buf = ByteBuffer.wrap(data);
      while (buf.hasRemaining()) ch.write(buf);
      ch.force(true);
    }
    try { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
    catch (AtomicMoveNotSupportedException e) { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
  }

  // One encrypted JSON-able blob per file (run index, server registry)
  public static void writeSealedFile(File target, SecretKey key, String aad, String text) throws Exception
  {
    atomicWrite(target, seal(key, aad, text.getBytes(StandardCharsets.UTF_8)));
  }

  public static String readSealedFile(File source, SecretKey key, String aad) throws Exception
  {
    if (!source.exists()) return null;
    return new String(open(key, aad, Files.readAllBytes(source.toPath())), StandardCharsets.UTF_8);
  }
}
