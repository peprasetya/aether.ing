package ing.aether.data;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import com.jcraft.jsch.*;

/**
 * FileProvider implementation for SSH/SFTP servers (JSch).
 * Authentication is always by the user's Aether key pair from aether.ing.setting.json; passwords are never stored.
 */
public class SshFileProvider implements FileProvider
{
  private final String providerId;   // owner email; must match the session user for PathMap's ownership check
  private final String providerKey;  // registry key and URL library segment, e.g. "SSH myserver"
  private final String displayName;
  private final String host;
  private final int port;
  private final String username;
  private final String privateKey;
  private final String publicKey;
  private final String rootPath;

  private Session sshSession;

  public SshFileProvider(String providerId, String providerKey, String displayName, String host, int port, String username, String privateKey, String publicKey, String rootPath)
  {
    this.providerId = providerId;
    this.providerKey = providerKey;
    this.displayName = displayName;
    this.host = host;
    this.port = port > 0 ? port : 22;
    this.username = username;
    this.privateKey = privateKey;
    this.publicKey = publicKey;
    this.rootPath = (rootPath == null || rootPath.trim().isEmpty()) ? "." : rootPath.trim();
  }

  // One SSH session is kept and reused; each operation opens its own short-lived SFTP channel
  private synchronized Session session() throws Exception
  {
    if (sshSession != null && sshSession.isConnected()) return sshSession;
    JSch jsch = new JSch();
    jsch.addIdentity(providerKey, privateKey.getBytes(StandardCharsets.UTF_8), publicKey != null ? publicKey.getBytes(StandardCharsets.UTF_8) : null, null);
    sshSession = jsch.getSession(username, host, port);
    java.util.Properties config = new java.util.Properties();
    config.put("StrictHostKeyChecking", "no");
    config.put("PreferredAuthentications", "publickey");
    sshSession.setConfig(config);
    sshSession.connect(15000);
    return sshSession;
  }

  private ChannelSftp channel() throws Exception
  {
    ChannelSftp ch = (ChannelSftp) session().openChannel("sftp");
    ch.connect(10000);
    return ch;
  }

  // Remote path under the configured root; traversal segments are rejected outright
  private String remote(String path)
  {
    if (path == null || path.isEmpty()) return rootPath;
    for (String seg : path.split("/")) if ("..".equals(seg)) throw new SecurityException("Path traversal not allowed: " + path);
    return rootPath.endsWith("/") ? rootPath + path : rootPath + "/" + path;
  }

  // Public resolution for callers that need the remote absolute path (terminal cd); keeps the traversal guard
  public String resolveRemote(String subPath)
  {
    return remote(subPath);
  }

  // Interactive shell channel with a PTY; the caller connects and manages the channel lifecycle
  public ChannelShell openShell(int cols, int rows) throws Exception
  {
    ChannelShell ch = (ChannelShell) session().openChannel("shell");
    ch.setPtyType("xterm-256color", cols > 0 ? cols : 80, rows > 0 ? rows : 24, 0, 0);
    return ch;
  }

  // Run one command in the given directory (relative to the provider root); returns exit code plus capped stdout/stderr
  public String exec(String cwdSuffix, String command, int timeoutSeconds, int maxBytes) throws Exception
  {
    String cwd = remote(cwdSuffix);
    ChannelExec ch = (ChannelExec) session().openChannel("exec");
    try
    {
      ch.setCommand("cd '" + cwd.replace("'", "'\\''") + "' && " + command);
      ByteArrayOutputStream stdout = new ByteArrayOutputStream();
      ByteArrayOutputStream stderr = new ByteArrayOutputStream();
      ch.setErrStream(stderr, true);
      InputStream in = ch.getInputStream();
      ch.connect(10000);

      long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
      byte[] buf = new byte[4096];
      boolean timedOut = false;
      while (true)
      {
        while (in.available() > 0)
        {
          int n = in.read(buf, 0, buf.length);
          if (n < 0) break;
          if (stdout.size() < maxBytes * 2) stdout.write(buf, 0, n);
        }
        if (ch.isClosed() && in.available() == 0) break;
        if (System.currentTimeMillis() > deadline) { timedOut = true; break; }
        Thread.sleep(50);
      }

      StringBuilder sb = new StringBuilder();
      sb.append(new String(stdout.toByteArray(), StandardCharsets.UTF_8));
      if (stderr.size() > 0) sb.append("\n--- stderr ---\n").append(new String(stderr.toByteArray(), StandardCharsets.UTF_8));
      String output = capOutput(sb.toString(), maxBytes);

      if (timedOut) return "Timed out after " + timeoutSeconds + "s (the command was killed with the channel). Partial output:\n" + output;
      return "Exit code: " + ch.getExitStatus() + "\n" + output;
    }
    finally { ch.disconnect(); }
  }

  // Head + tail capping so huge outputs cannot flood the model context
  private static String capOutput(String s, int maxBytes)
  {
    if (s.length() <= maxBytes) return s;
    int head = Math.min(2048, maxBytes / 2);
    int tail = maxBytes - head;
    return s.substring(0, head) + "\n[... " + (s.length() - head - tail) + " chars truncated ...]\n" + s.substring(s.length() - tail);
  }

  @Override
  public String getProviderId() {return providerId;}

  @Override
  public String getScheme() {return "ssh";}

  @Override
  public String getRootDisplayName() {return displayName;}

  @Override
  public FileItem getFileItem(String path) throws Exception
  {
    if (path == null || path.isEmpty()) return new FileItem(displayName, remote(""), "", FileItem.TYPEDIRECTORY);
    ChannelSftp ch = channel();
    try
    {
      SftpATTRS attrs = ch.stat(remote(path));
      String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
      return toFileItem(name, path, attrs);
    }
    catch (SftpException e)
    {
      if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return null;
      throw e;
    }
    finally { ch.disconnect(); }
  }

  @Override
  public List<FileItem> listFiles(String path) throws Exception
  {
    ChannelSftp ch = channel();
    try
    {
      List<FileItem> items = new ArrayList<>();
      for (Object o : ch.ls(remote(path)))
      {
        ChannelSftp.LsEntry entry = (ChannelSftp.LsEntry) o;
        String name = entry.getFilename();
        if (name.startsWith(".")) continue;
        String subPath = (path == null || path.isEmpty()) ? name : path + "/" + name;
        items.add(toFileItem(name, subPath, entry.getAttrs()));
      }
      return items;
    }
    finally { ch.disconnect(); }
  }

  @Override
  public boolean exists(String path) throws Exception
  {
    if (path == null || path.isEmpty()) return true;
    ChannelSftp ch = channel();
    try { ch.stat(remote(path)); return true; }
    catch (SftpException e)
    {
      if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return false;
      throw e;
    }
    finally { ch.disconnect(); }
  }

  @Override
  public boolean isDirectory(String path) throws Exception
  {
    if (path == null || path.isEmpty()) return true;
    ChannelSftp ch = channel();
    try { return ch.stat(remote(path)).isDir(); }
    catch (SftpException e)
    {
      if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return false;
      throw e;
    }
    finally { ch.disconnect(); }
  }

  @Override
  public void createFile(String path) throws Exception
  {
    ChannelSftp ch = channel();
    try { try (OutputStream os = ch.put(remote(path), ChannelSftp.OVERWRITE)) {} }
    finally { ch.disconnect(); }
  }

  @Override
  public void mkdir(String path) throws Exception
  {
    ChannelSftp ch = channel();
    try { ch.mkdir(remote(path)); }
    finally { ch.disconnect(); }
  }

  @Override
  public InputStream getInputStream(String path) throws Exception
  {
    ChannelSftp ch = channel();
    try { return new ChannelBoundInputStream(ch.get(remote(path)), ch); }
    catch (Exception e) { ch.disconnect(); throw e; }
  }

  @Override
  public InputStream getInputStream(String path, long offset, long length) throws Exception
  {
    ChannelSftp ch = channel();
    try { return new ChannelBoundInputStream(new LimitedInputStream(ch.get(remote(path), null, offset), length), ch); }
    catch (Exception e) { ch.disconnect(); throw e; }
  }

  @Override
  public OutputStream getOutputStream(String path) throws Exception
  {
    ChannelSftp ch = channel();
    try
    {
      OutputStream os = ch.put(remote(path), ChannelSftp.OVERWRITE);
      return new FilterOutputStream(os)
      {
        @Override
        public void write(byte[] b, int off, int len) throws IOException {out.write(b, off, len);}
        @Override
        public void close() throws IOException
        {
          try { super.close(); } finally { ch.disconnect(); }
        }
      };
    }
    catch (Exception e) { ch.disconnect(); throw e; }
  }

  @Override
  public void delete(String path, boolean recursive) throws Exception
  {
    ChannelSftp ch = channel();
    try
    {
      String rp = remote(path);
      SftpATTRS attrs = ch.stat(rp);
      if (attrs.isDir())
      {
        if (recursive) deleteRecursive(ch, rp);
        else ch.rmdir(rp);
      }
      else ch.rm(rp);
    }
    finally { ch.disconnect(); }
  }

  private void deleteRecursive(ChannelSftp ch, String rp) throws Exception
  {
    for (Object o : ch.ls(rp))
    {
      ChannelSftp.LsEntry entry = (ChannelSftp.LsEntry) o;
      String name = entry.getFilename();
      if (".".equals(name) || "..".equals(name)) continue;
      if (entry.getAttrs().isDir()) deleteRecursive(ch, rp + "/" + name);
      else ch.rm(rp + "/" + name);
    }
    ch.rmdir(rp);
  }

  @Override
  public void rename(String path, String newName) throws Exception
  {
    String parent = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
    String destSub = parent.isEmpty() ? newName : parent + "/" + newName;
    ChannelSftp ch = channel();
    try { ch.rename(remote(path), remote(destSub)); }
    finally { ch.disconnect(); }
  }

  @Override
  public void move(String src, String dest) throws Exception
  {
    ChannelSftp ch = channel();
    try { ch.rename(remote(src), remote(dest)); }
    finally { ch.disconnect(); }
  }

  @Override
  public void copy(String src, String dest) throws Exception
  {
    // Streams over two independent channels; SFTP has no server-side copy
    try (InputStream is = getInputStream(src); OutputStream os = getOutputStream(dest)) { is.transferTo(os); }
  }

  @Override
  public List<FileItem> search(String query, String rootPathArg) throws Exception
  {
    List<FileItem> results = new ArrayList<>();
    ChannelSftp ch = channel();
    try { searchWalk(ch, rootPathArg == null ? "" : rootPathArg, query.toLowerCase(), results, 0); }
    finally { ch.disconnect(); }
    return results;
  }

  private void searchWalk(ChannelSftp ch, String path, String query, List<FileItem> results, int depth) throws Exception
  {
    if (depth > 4 || results.size() >= 200) return;
    for (Object o : ch.ls(remote(path)))
    {
      ChannelSftp.LsEntry entry = (ChannelSftp.LsEntry) o;
      String name = entry.getFilename();
      if (name.startsWith(".")) continue;
      String subPath = path.isEmpty() ? name : path + "/" + name;
      if (name.toLowerCase().contains(query)) results.add(toFileItem(name, subPath, entry.getAttrs()));
      if (results.size() >= 200) return;
      if (entry.getAttrs().isDir()) searchWalk(ch, subPath, query, results, depth + 1);
    }
  }

  @Override
  public Map<String, Object> getAttributes(String path) throws Exception
  {
    ChannelSftp ch = channel();
    try
    {
      SftpATTRS attrs = ch.stat(remote(path));
      Map<String, Object> map = new HashMap<>();
      map.put("size", attrs.getSize());
      map.put("lastModified", attrs.getMTime() * 1000L);
      map.put("permissions", attrs.getPermissionsString());
      map.put("rootPath", rootPath);
      return map;
    }
    finally { ch.disconnect(); }
  }

  @Override
  public void setAttribute(String path, String attr, Object value) throws Exception
  {
    if ("lastModified".equals(attr))
    {
      ChannelSftp ch = channel();
      try { ch.setMtime(remote(path), (int) (((Long) value) / 1000L)); }
      finally { ch.disconnect(); }
    }
  }

  @Override
  public String getIcon(FileItem item)
  {
    if (item.getType() == FileItem.TYPEVOLUMES) return "/images/folder-remote.svg";
    if (item.getType() == FileItem.TYPEDIRECTORY) return "/images/folder.svg";
    String name = item.getName().toLowerCase();
    if (name.endsWith(".mp4") || name.endsWith(".m4v") || name.endsWith(".mkv") || name.endsWith(".avi")) return "/images/video-x-generic.svg";
    if (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".ogg") || name.endsWith(".flac")) return "/images/audio-x-generic.svg";
    if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".gif") || name.endsWith(".svg")) return "/images/image-x-generic.svg";
    if (name.endsWith(".zip") || name.endsWith(".tar") || name.endsWith(".gz") || name.endsWith(".7z")) return "/images/package-x-generic.svg";
    return "/images/text-x-generic.svg";
  }

  @Override
  public String getThumbnail(FileItem item) {return null;}

  @Override
  public List<String> getParentIds(String path) throws Exception
  {
    if (path == null || path.isEmpty()) return Collections.emptyList();
    List<String> parents = new ArrayList<>();
    String[] segments = path.split("/");
    StringBuilder current = new StringBuilder();
    for (int i = 0; i < segments.length - 1; i++)
    {
      if (current.length() > 0) current.append("/");
      current.append(segments[i]);
      parents.add(current.toString());
    }
    return parents;
  }

  @Override
  public boolean supportsRandomAccess() {return true;}

  @Override
  public boolean supportsSearch() {return true;}

  @Override
  public boolean supportsPermissions() {return false;}

  @Override
  public boolean isReadOnly() {return false;}

  private FileItem toFileItem(String name, String subPath, SftpATTRS attrs)
  {
    short type = attrs.isDir() ? FileItem.TYPEDIRECTORY : FileItem.TYPEFILE;
    FileItem item = new FileItem(name, remote(subPath), subPath, type);
    item.setSize(attrs.getSize());
    item.setProviderId(providerId);
    String lower = name.toLowerCase();
    if (lower.endsWith(".java")) item.setMimeType("text/x-java");
    else if (lower.endsWith(".jsp")) item.setMimeType("text/html");
    else if (lower.endsWith(".css")) item.setMimeType("text/css");
    else if (lower.endsWith(".js")) item.setMimeType("application/javascript");
    else if (lower.endsWith(".py")) item.setMimeType("text/x-python");
    else if (lower.endsWith(".sh")) item.setMimeType("application/x-sh");
    else if (lower.endsWith(".txt")) item.setMimeType("text/plain");
    else if (lower.endsWith(".md")) item.setMimeType("text/markdown");
    else if (lower.endsWith(".json")) item.setMimeType("application/json");
    else if (lower.endsWith(".mp4")) item.setMimeType("video/mp4");
    return item;
  }

  // Closes the SFTP channel together with the stream it serves
  private static class ChannelBoundInputStream extends FilterInputStream
  {
    private final ChannelSftp ch;

    ChannelBoundInputStream(InputStream in, ChannelSftp ch)
    {
      super(in);
      this.ch = ch;
    }

    @Override
    public void close() throws IOException
    {
      try { super.close(); } finally { ch.disconnect(); }
    }
  }

  // Caps a ranged read at the requested length
  private static class LimitedInputStream extends FilterInputStream
  {
    private long remaining;

    LimitedInputStream(InputStream in, long limit)
    {
      super(in);
      this.remaining = limit;
    }

    @Override
    public int read() throws IOException
    {
      if (remaining <= 0) return -1;
      int b = super.read();
      if (b != -1) remaining--;
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
      if (remaining <= 0) return -1;
      int n = super.read(b, off, (int) Math.min(len, remaining));
      if (n > 0) remaining -= n;
      return n;
    }
  }
}
