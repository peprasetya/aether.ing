package ing.aether.data;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * FileProvider implementation for the local filesystem.
 */
public class LocalFileProvider implements FileProvider 
{
  private final String providerId;
  private final String rootPath;
  private final String displayName;

  public LocalFileProvider(String providerId, String rootPath, String displayName) 
  {
    this.providerId = providerId;
    this.rootPath = rootPath;
    this.displayName = displayName;
  }

  @Override
  public String getProviderId() {return providerId;}

  @Override
  public String getScheme() {return "local";}

  @Override
  public String getRootDisplayName() {return displayName;}

  @Override
  public FileItem getFileItem(String path) throws Exception 
  {
    File file = resolve(path);
    if (!file.exists()) return null;
    return toFileItem(file, path);
  }

  @Override
  public List<FileItem> listFiles(String path) throws Exception 
  {
    File dir = resolve(path);
    if (!dir.exists() || !dir.isDirectory()) return Collections.emptyList();
    
    File[] files = dir.listFiles();
    if (files == null) return Collections.emptyList();
    
    List<FileItem> items = new ArrayList<>();
    for (File f : files) 
    {
      if (f.getName().startsWith(".")) continue;
      String subPath = (path.isEmpty() ? "" : path + "/") + f.getName();
      items.add(toFileItem(f, subPath));
    }
    return items;
  }

  @Override
  public boolean exists(String path) throws Exception {return resolve(path).exists();}

  @Override
  public boolean isDirectory(String path) throws Exception {return resolve(path).isDirectory();}

  @Override
  public void createFile(String path) throws Exception {if (!resolve(path).createNewFile()) throw new IOException("File already exists: " + path);}

  @Override
  public void mkdir(String path) throws Exception {resolve(path).mkdirs();}

  @Override
  public InputStream getInputStream(String path) throws Exception {return new FileInputStream(resolve(path));}

  @Override
  public InputStream getInputStream(String path, long offset, long length) throws Exception 
  {
    RandomAccessFile raf = new RandomAccessFile(resolve(path), "r");
    raf.seek(offset);
    return new InputStream() 
    {
      private long remaining = length;
      @Override
      public int read() throws IOException 
      {
        if (remaining <= 0) return -1;
        int b = raf.read();
        if (b != -1) remaining--;
        return b;
      }
      @Override
      public void close() throws IOException {raf.close();}
    };
  }

  @Override
  public OutputStream getOutputStream(String path) throws Exception {return new FileOutputStream(resolve(path));}

  @Override
  public void delete(String path, boolean recursive) throws Exception
  {
    File file = resolve(path);
    if (!file.exists()) throw new FileNotFoundException("Not found: " + path);
    if (recursive) deleteRecursive(file);
    else if (!file.delete()) throw new IOException("Failed to delete: " + path + (file.isDirectory() ? " (directory not empty?)" : ""));
  }

  private void deleteRecursive(File file) throws IOException
  {
    if (file.isDirectory())
    {
      File[] children = file.listFiles();
      if (children != null) for (File child : children) deleteRecursive(child);
    }
    if (!file.delete()) throw new IOException("Failed to delete: " + file.getPath());
  }

  @Override
  public void rename(String path, String newName) throws Exception
  {
    // A rename never changes directory (that's move) — reject anything that could escape the parent via a path separator or traversal
    if (newName == null || newName.contains("/") || newName.contains("\\") || newName.contains("..")) throw new SecurityException("Invalid new name: " + newName);
    File file = resolve(path);
    File newFile = new File(file.getParentFile(), newName);
    if (!file.renameTo(newFile)) throw new IOException("Failed to rename " + path + " to " + newName);
  }

  @Override
  public void move(String src, String dest) throws Exception 
  {
    Files.move(resolve(src).toPath(), resolve(dest).toPath(), StandardCopyOption.REPLACE_EXISTING);
  }

  @Override
  public void copy(String src, String dest) throws Exception 
  {
    Files.copy(resolve(src).toPath(), resolve(dest).toPath(), StandardCopyOption.REPLACE_EXISTING);
  }

  @Override
  public List<FileItem> search(String query, String rootPath) throws Exception 
  {
    // Simple implementation: walk the tree
    Path start = resolve(rootPath).toPath();
    List<FileItem> results = new ArrayList<>();
    Files.walk(start).forEach(path -> 
    {
      if (path.getFileName().toString().contains(query)) 
      {
        String relative = start.relativize(path).toString().replace(File.separator, "/");
        String fullSubPath = (rootPath.isEmpty() ? "" : rootPath + "/") + relative;
        try {results.add(toFileItem(path.toFile(), fullSubPath));} catch (Exception e) {}
      }
    });
    return results;
  }

  @Override
  public Map<String, Object> getAttributes(String path) throws Exception 
  {
    File file = resolve(path);
    Map<String, Object> attrs = new HashMap<>();
    attrs.put("lastModified", file.lastModified());
    attrs.put("canRead", file.canRead());
    attrs.put("canWrite", file.canWrite());
    attrs.put("isHidden", file.isHidden());
    attrs.put("rootPath", rootPath);
    return attrs;
  }

  @Override
  public void setAttribute(String path, String attr, Object value) throws Exception 
  {
    // Basic implementation for some attributes
    if ("lastModified".equals(attr)) resolve(path).setLastModified((Long)value);
  }

  @Override
  public String getIcon(FileItem item) 
  {
    if (item.getType() == FileItem.TYPEDIRECTORY || item.getType() == FileItem.TYPEVOLUMES) return "/images/folder.svg";
    String mime = item.getMimeType();
    if (mime == null) 
    {
      String name = item.getName().toLowerCase();
      if (name.endsWith(".mp4") || name.endsWith(".m4v") || name.endsWith(".mkv") || name.endsWith(".avi")) return "/images/video-x-generic.svg";
      if (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".ogg") || name.endsWith(".flac")) return "/images/audio-x-generic.svg";
      if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".gif") || name.endsWith(".svg")) return "/images/image-x-generic.svg";
      if (name.endsWith(".zip") || name.endsWith(".tar") || name.endsWith(".gz") || name.endsWith(".7z")) return "/images/package-x-generic.svg";
      return "/images/text-x-generic.svg";
    }
    if (mime.startsWith("video/")) return "/images/video-x-generic.svg";
    if (mime.startsWith("audio/")) return "/images/audio-x-generic.svg";
    if (mime.startsWith("image/")) return "/images/image-x-generic.svg";
    if (mime.startsWith("text/")) return "/images/text-x-generic.svg";
    if (mime.contains("zip") || mime.contains("archive") || mime.contains("compressed")) return "/images/package-x-generic.svg";
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
  public boolean supportsPermissions() {return true;}

  @Override
  public boolean isReadOnly() {return false;}

  private File resolve(String path)
  {
    File root = new File(rootPath);
    if (path == null || path.isEmpty()) return root;
    File file = new File(root, path.replace("/", File.separator));
    // Containment check: a resolved path (including ".." and symlinked segments) must stay inside the provider root
    try
    {
      String canonical = file.getCanonicalPath();
      String canonicalRoot = root.getCanonicalPath();
      if (!canonical.equals(canonicalRoot) && !canonical.startsWith(canonicalRoot + File.separator)) throw new SecurityException("Path escapes provider root: " + path);
    }
    catch (IOException e)
    {
      throw new SecurityException("Cannot resolve path: " + path);
    }
    return file;
  }

  private FileItem toFileItem(File file, String subPath) 
  {
    short type = file.isDirectory() ? FileItem.TYPEDIRECTORY : FileItem.TYPEFILE;
    FileItem item = new FileItem(file.getName(), file.getAbsolutePath(), subPath, type);
    item.setSize(file.length());
    item.setProviderId(providerId);
    // Simple MIME type detection by extension
    String name = file.getName().toLowerCase();
    if (name.endsWith(".java")) item.setMimeType("text/x-java");
    else if (name.endsWith(".jsp")) item.setMimeType("text/html");
    else if (name.endsWith(".css")) item.setMimeType("text/css");
    else if (name.endsWith(".js")) item.setMimeType("application/javascript");
    else if (name.endsWith(".py")) item.setMimeType("text/x-python");
    else if (name.endsWith(".sh")) item.setMimeType("application/x-sh");
    else if (name.endsWith(".txt")) item.setMimeType("text/plain");
    else if (name.endsWith(".md")) item.setMimeType("text/markdown");
    else if (name.endsWith(".json")) item.setMimeType("application/json");
    return item;
  }
}
