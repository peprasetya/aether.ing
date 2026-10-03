package ing.aether.tools.ai.run;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.*;

import javax.crypto.SecretKey;

import org.eclipse.jetty.util.ajax.JSON;

import ing.aether.tools.VibedCrypto;

// Append-only, encrypted event log of one agent run: <userHash>.<runId>.vibedlog beside the .vibed files.
// Header "AETHLOG1", then records framed as int32 length | IV | AES-GCM(ciphertext+tag). The AAD binds each
// record to its user, run and sequence number, so records cannot be reordered, spliced between runs or
// read as another user's. A crash can only tear the LAST record: load() stops at the first bad length or
// tag and truncates there, keeping everything before it intact.
public class RunEventLog
{
  private static final byte[] MAGIC = "AETHLOG1".getBytes(StandardCharsets.US_ASCII);
  private static final int MAX_RECORD = 2 * 1024 * 1024;
  // Events kept in memory for replay; older ones are read back from disk
  private static final int RING_SIZE = 4000;
  private static final JSON json = new JSON();

  private final String issSubId;
  private final String runId;
  private final File file;
  private final SecretKey key;
  private FileChannel channel;
  private long headSeq = 0;
  private final ArrayDeque<Map<String, Object>> ring = new ArrayDeque<>();

  private RunEventLog(String issSubId, String runId, File file, SecretKey key)
  {
    this.issSubId = issSubId;
    this.runId = runId;
    this.file = file;
    this.key = key;
  }

  public static File fileFor(String issSubId, String runId) throws Exception
  {
    return new File(VibedCrypto.dataDir(), VibedCrypto.userFileBase(issSubId) + "." + runId + ".vibedlog");
  }

  private static SecretKey keyFor(String issSubId) throws Exception
  {
    return VibedCrypto.subKey(VibedCrypto.userKey(issSubId), "runlog-v1");
  }

  private String aad(long seq)
  {
    return issSubId + "|" + runId + "|" + seq;
  }

  // Opens (or creates) the log for appending; existing records are loaded and a torn tail is truncated
  public static RunEventLog open(String issSubId, String runId) throws Exception
  {
    RunEventLog log = new RunEventLog(issSubId, runId, fileFor(issSubId, runId), keyFor(issSubId));
    log.channel = FileChannel.open(log.file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    if (log.channel.size() == 0)
    {
      log.channel.write(ByteBuffer.wrap(MAGIC));
      log.channel.force(true);
    }
    else log.scan(true);
    log.channel.position(log.channel.size());
    return log;
  }

  // Read-only view of a finished run's log (for replay after a restart)
  public static List<Map<String, Object>> readAll(String issSubId, String runId) throws Exception
  {
    RunEventLog log = new RunEventLog(issSubId, runId, fileFor(issSubId, runId), keyFor(issSubId));
    if (!log.file.exists()) return new ArrayList<>();
    try (FileChannel ch = FileChannel.open(log.file.toPath(), StandardOpenOption.READ))
    {
      log.channel = ch;
      return log.scan(false);
    }
    finally { log.channel = null; }
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> scan(boolean repair) throws IOException
  {
    List<Map<String, Object>> all = new ArrayList<>();
    long pos = MAGIC.length;
    long size = channel.size();
    ByteBuffer lenBuf = ByteBuffer.allocate(4);
    long seq = 0;
    while (pos + 4 <= size)
    {
      lenBuf.clear();
      channel.read(lenBuf, pos);
      lenBuf.flip();
      int len = lenBuf.getInt();
      if (len <= 0 || len > MAX_RECORD || pos + 4 + len > size) break;
      ByteBuffer rec = ByteBuffer.allocate(len);
      channel.read(rec, pos + 4);
      try
      {
        byte[] plain = VibedCrypto.open(key, aad(seq + 1), rec.array());
        Map<String, Object> evt = (Map<String, Object>) json.parse(new JSON.ReaderSource(new java.io.StringReader(new String(plain, StandardCharsets.UTF_8))));
        all.add(evt);
        seq++;
      }
      catch (Exception e) { break; } // torn or foreign record: everything from here is discarded
      pos += 4 + len;
    }
    if (repair && pos < size)
    {
      System.out.println("RunEventLog: truncating torn tail of " + file.getName() + " at " + pos + " (" + (size - pos) + " bytes)");
      channel.truncate(pos);
      channel.force(true);
    }
    headSeq = seq;
    for (int i = Math.max(0, all.size() - RING_SIZE); i < all.size(); i++) ring.addLast(all.get(i));
    return all;
  }

  // Appends one event; durable forces it to disk (state changes), deltas ride on the OS cache
  public synchronized Map<String, Object> append(Map<String, Object> event, boolean durable)
  {
    long seq = headSeq + 1;
    Map<String, Object> evt = new LinkedHashMap<>(event);
    evt.put("seq", seq);
    evt.put("runId", runId);
    if (!evt.containsKey("ts")) evt.put("ts", System.currentTimeMillis());
    String text = json.toJSON(evt);
    try
    {
      if (channel != null)
      {
        byte[] sealed = VibedCrypto.seal(key, aad(seq), text.getBytes(StandardCharsets.UTF_8));
        ByteBuffer buf = ByteBuffer.allocate(4 + sealed.length);
        buf.putInt(sealed.length).put(sealed).flip();
        while (buf.hasRemaining()) channel.write(buf);
        if (durable) channel.force(false);
      }
    }
    catch (Exception e)
    {
      // The live stream must not die with the disk: keep the event in memory and say so once per failure
      System.out.println("WARN: RunEventLog write failed for run " + runId + ": " + e.getMessage());
    }
    headSeq = seq;
    ring.addLast(evt);
    while (ring.size() > RING_SIZE) ring.removeFirst();
    return evt;
  }

  public synchronized long headSeq()
  {
    return headSeq;
  }

  // Events with seq > afterSeq, oldest first (memory ring first, disk when the ring no longer reaches)
  public synchronized List<Map<String, Object>> read(long afterSeq)
  {
    List<Map<String, Object>> out = new ArrayList<>();
    Map<String, Object> first = ring.peekFirst();
    long firstSeq = first != null ? ((Number) first.get("seq")).longValue() : headSeq + 1;
    if (afterSeq + 1 < firstSeq)
    {
      try
      {
        for (Map<String, Object> e : readAll(issSubId, runId)) if (((Number) e.get("seq")).longValue() > afterSeq) out.add(e);
        return out;
      }
      catch (Exception e) { System.out.println("WARN: RunEventLog disk replay failed: " + e.getMessage()); }
    }
    for (Map<String, Object> e : ring) if (((Number) e.get("seq")).longValue() > afterSeq) out.add(e);
    return out;
  }

  public synchronized void close()
  {
    if (channel == null) return;
    try
    {
      channel.force(true);
      channel.close();
    }
    catch (IOException e) {}
    channel = null;
  }

  public static void delete(String issSubId, String runId)
  {
    try { fileFor(issSubId, runId).delete(); } catch (Exception e) {}
  }
}
