package ing.aether.data;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jetty.util.ajax.JSON;
import ing.aether.tools.google.Drive;

/**
 * FileProvider implementation for Google Drive.
 */
public class GoogleDriveProvider implements FileProvider 
{
  private static final String ROOT_MYDRIVE = "mydrive";
  private static final String ROOT_SHARED_DRIVES = "shared-drives";
  private static final String ROOT_SHARED_WITH_ME = "shared-with-me";
  private static final String ROOT_COMPUTERS = "computers";
  
  private final String providerId;
  private final String providerKey;
  private final Drive drive;
  private final String displayName;
  private final JSON json = new JSON();
  private final Map<String, String> idCache = new ConcurrentHashMap<>();
  private final Map<String, String> pathCache = new ConcurrentHashMap<>();
  private final Map<String, Map<String, Object>> metadataCache = new ConcurrentHashMap<>();
  private volatile String myDriveRootId;
  private final Map<String, String> sharedDriveIds = new ConcurrentHashMap<>();

  public GoogleDriveProvider(String providerId, String providerKey, Drive drive, String displayName)
  {
    this.providerId = providerId;
    this.providerKey = providerKey;
    this.drive = drive;
    this.displayName = displayName;
    initializeRootIds();
  }

  private String getMyDriveRootId()
  {
    if (myDriveRootId != null) return myDriveRootId;
    synchronized (this)
    {
      if (myDriveRootId == null)
      {
        try
        {
          String rootJson = drive.getMetadata("root");
          Map<String, Object> rootRes = parseJson(rootJson);
          if (rootRes != null && rootRes.containsKey("id"))
          {
            myDriveRootId = (String) rootRes.get("id");
            idCache.put(ROOT_MYDRIVE, myDriveRootId);
            pathCache.put(myDriveRootId, ROOT_MYDRIVE);
          }
        }
        catch (Exception e)
        {
          System.err.println("WARN: GoogleDriveProvider failed to lazy-initialize myDriveRootId: " + e.getMessage());
        }
      }
    }
    return myDriveRootId;
  }

  @SuppressWarnings("unchecked")
  private void initializeRootIds()
  {
    try
    {
      String rootJson = drive.getMetadata("root");
      Map<String, Object> rootRes = parseJson(rootJson);
      if (rootRes != null && rootRes.containsKey("id"))
      {
        myDriveRootId = (String) rootRes.get("id");
        idCache.put(ROOT_MYDRIVE, myDriveRootId);
        pathCache.put(myDriveRootId, ROOT_MYDRIVE);
      }

      String drivesJson = drive.listDrives(null);
      Map<String, Object> drivesRes = parseJson(drivesJson);
      Object[] drives = (Object[]) drivesRes.get("drives");
      if (drives != null) for (Object d : drives)
      {
        Map<String, Object> driveMap = (Map<String, Object>) d;
        String id = (String) driveMap.get("id");
        String name = (String) driveMap.get("name");
        sharedDriveIds.put(id, id);
        pathCache.put(id, ROOT_SHARED_DRIVES + "/" + id);
        idCache.put(ROOT_SHARED_DRIVES + "/" + id, id);
      }
    } 
    catch (Exception e)
    {
      System.err.println("WARN: GoogleDriveProvider failed to initialize root IDs: " + e.getMessage());
    }
  }

  @Override
  public String getProviderId() {return providerId;}

  // Exposed for UserSettings, which stores aether.ing.setting.json in this account's Drive
  public Drive getDrive() {return drive;}

  @Override
  public String getScheme() {return "gdrive";}

  @Override
  public String getRootDisplayName() {return displayName;}

  @Override
  @SuppressWarnings("unchecked")
  public FileItem getFileItem(String path) throws Exception 
  {
    if (path.isEmpty() || isVirtualRoot(path)) return new FileItem(path.isEmpty() ? displayName : path, "", path, FileItem.TYPEDIRECTORY);
    
    String fileId = resolveFileId(path);
    if (fileId == null) return null;
    Map<String, Object> meta;
    Map<String, Object> cached = metadataCache.get(fileId);
    if (cached != null)
    {
      meta = (Map<String, Object>) new java.util.HashMap<>(metadataCache.get(fileId));
    }
    else
    {
      String metaJson = drive.getMetadata(fileId);
      meta = parseJson(metaJson);
      metadataCache.put(fileId, meta);
    }
    return toFileItem(meta, path);
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<FileItem> listFiles(String path) throws Exception 
  {
    if (path.isEmpty()) 
    {
      List<FileItem> roots = new ArrayList<>();
      roots.add(new FileItem("My Drive", ROOT_MYDRIVE, ROOT_MYDRIVE, FileItem.TYPEDIRECTORY));
      if (!sharedDriveIds.isEmpty()) roots.add(new FileItem("Shared Drives", ROOT_SHARED_DRIVES, ROOT_SHARED_DRIVES, FileItem.TYPEDIRECTORY));
      roots.add(new FileItem("Shared with Me", ROOT_SHARED_WITH_ME, ROOT_SHARED_WITH_ME, FileItem.TYPEDIRECTORY));
      
      String computersJson = drive.list("mimeType = 'application/vnd.google-apps.folder' and 'root' in parents and trashed = false", null);
      Map<String, Object> compRes = parseJson(computersJson);
      Object[] compFiles = (Object[]) compRes.get("files");
      if (compFiles != null && compFiles.length > 0) roots.add(new FileItem("Computers", ROOT_COMPUTERS, ROOT_COMPUTERS, FileItem.TYPEDIRECTORY));
      return roots;
    }

    if (path.equals(ROOT_SHARED_DRIVES)) 
    {
      String drivesJson = drive.listDrives(null);
      Map<String, Object> res = parseJson(drivesJson);
      Object[] drives = (Object[]) res.get("drives");
      List<FileItem> items = new ArrayList<>();
      if (drives != null) for (Object d : drives) 
      {
        Map<String, Object> driveMap = (Map<String, Object>) d;
        String id = (String) driveMap.get("id");
        String name = (String) driveMap.get("name");
        items.add(new FileItem(name, id, ROOT_SHARED_DRIVES + "/" + id, FileItem.TYPEDIRECTORY));
      }
      return items;
    }

    String query;
    String folderId = resolveFileId(path);
    if (folderId == null && !path.equals(ROOT_MYDRIVE) && !path.equals(ROOT_SHARED_WITH_ME) && !path.equals(ROOT_COMPUTERS)) throw new java.io.FileNotFoundException("Folder not found: " + path);
    if (path.equals(ROOT_MYDRIVE)) query = "'root' in parents and trashed = false";
    else if (path.equals(ROOT_SHARED_WITH_ME)) query = "sharedWithMe = true and trashed = false";
    else if (path.equals(ROOT_COMPUTERS)) query = "mimeType = 'application/vnd.google-apps.folder' and 'root' in parents and trashed = false";
    else query = "'" + folderId + "' in parents and trashed = false";

    String listJson = drive.list(query, null);
    Map<String, Object> res = parseJson(listJson);
    Object[] files = (Object[]) res.get("files");
    List<FileItem> items = new ArrayList<>();
    if (files != null) for (Object f : files)
    {
      Map<String, Object> fileMap = (Map<String, Object>) f;
      String name = (String) fileMap.get("name");
      String id = (String) fileMap.get("id");
      String fullPath = path + "/" + name;
      idCache.put(fullPath, id);
      pathCache.put(id, fullPath);
      metadataCache.put(id, fileMap);
      items.add(toFileItem(fileMap, fullPath));
    }
    return items;
  }

  @Override
  public boolean exists(String path) throws Exception 
  {
    if (path.isEmpty() || isVirtualRoot(path)) return true;
    try
    {
      String id = resolveFileId(path);
      if (id == null) return false;
      String meta = drive.getMetadata(id);
      return meta != null;
    } 
    catch (Exception e) 
    {
      return false;
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public boolean isDirectory(String path) throws Exception 
  {
    if (path.isEmpty() || isVirtualRoot(path)) return true;
    String fileId = resolveFileId(path);
    if (fileId == null) return false;
    String metaJson = drive.getMetadata(fileId);
    if (metaJson == null) return false;
    Map<String, Object> meta = parseJson(metaJson);
    String mimeType = (String)meta.get("mimeType");
    boolean isDir = "application/vnd.google-apps.folder".equals(mimeType);
    return isDir;
  }

  @Override
  public void createFile(String path) throws Exception
  {
    int lastSlash = path.lastIndexOf('/');
    String name = path.substring(lastSlash + 1);
    String parentPath = lastSlash <= 0 ? "" : path.substring(0, lastSlash);
    String parentId = assertIsFolder(parentPath);
    drive.createFile(name, "text/plain", parentId);
    idCache.clear();
    pathCache.clear();
  }

  @Override
  public void mkdir(String path) throws Exception
  {
    int lastSlash = path.lastIndexOf('/');
    String name = path.substring(lastSlash + 1);
    String parentPath = lastSlash <= 0 ? "" : path.substring(0, lastSlash);
    String parentId = assertIsFolder(parentPath);
    drive.mkdir(name, parentId);
    idCache.clear();
    pathCache.clear();
  }

  @Override
  public InputStream getInputStream(String path) throws Exception
  {
    String fileId = resolveFileId(path);
    if (fileId == null) throw new java.io.FileNotFoundException("File not found: " + path);
    return drive.download(fileId);
  }

  @Override
  public InputStream getInputStream(String path, long offset, long length) throws Exception
  {
    String fileId = resolveFileId(path);
    if (fileId == null) throw new java.io.FileNotFoundException("File not found: " + path);
    return drive.download(fileId, offset, length);
  }

  @Override
  @SuppressWarnings("unchecked")
  public OutputStream getOutputStream(String path) throws Exception 
  {
    return new java.io.ByteArrayOutputStream() 
    {
      @Override
      public void close() throws java.io.IOException 
      {
        super.close();
        try
        {
          String resolved = resolveFileId(path);
          String mimeType = "text/plain";

          if (resolved == null) 
          {
            int lastSlash = path.lastIndexOf('/');
            String name = path.substring(lastSlash + 1);
            String parentPath = lastSlash <= 0 ? "" : path.substring(0, lastSlash);
            String parentId = assertIsFolder(parentPath);
            drive.createFileWithContent(name, "text/plain", parentId, this.toByteArray());
            idCache.clear();
            pathCache.clear();
          } 
          else 
          {
            try 
            {
              String metaJson = drive.getMetadata(resolved);
              Map<String, Object> meta = parseJson(metaJson);
              mimeType = (String)meta.get("mimeType");
            } 
            catch (Exception e) {}
            drive.upload(resolved, this.toByteArray(), mimeType);
          }
        } 
        catch (Exception e) 
        {
          throw new java.io.IOException(e);
        }
      }
    };
  }

  @Override
  public void delete(String path, boolean recursive) throws Exception
  {
    String fileId = resolveFileId(path);
    if (fileId == null) throw new java.io.FileNotFoundException("File not found: " + path);
    drive.delete(fileId);
    idCache.clear();
    pathCache.clear();
    metadataCache.clear();
  }

  @Override
  public void rename(String path, String newName) throws Exception
  {
    String fileId = resolveFileId(path);
    if (fileId == null) throw new java.io.FileNotFoundException("File not found: " + path);
    drive.rename(fileId, newName);
    idCache.clear();
    pathCache.clear();
    metadataCache.clear();
  }

  @Override
  public void move(String src, String dest) throws Exception
  {
    String fileId = resolveFileId(src);
    if (fileId == null) throw new java.io.FileNotFoundException("File not found: " + src);
    // A destination with no '/' means the Drive root — resolveFileId("") is the root, not a not-found case
    String oldParentPath = src.lastIndexOf('/') == -1 ? "" : src.substring(0, src.lastIndexOf('/'));
    String newParentPath = dest.lastIndexOf('/') == -1 ? "" : dest.substring(0, dest.lastIndexOf('/'));
    String oldParentId = resolveFileId(oldParentPath);
    String newParentId = resolveFileId(newParentPath);
    if (oldParentId == null) throw new java.io.FileNotFoundException("Source folder not found: " + oldParentPath);
    if (newParentId == null) throw new java.io.FileNotFoundException("Destination folder not found: " + newParentPath);
    drive.move(fileId, oldParentId, newParentId);
    idCache.clear();
    pathCache.clear();
    metadataCache.clear();
  }

  @Override
  public void copy(String src, String dest) throws Exception
  {
    String fileId = resolveFileId(src);
    if (fileId == null) throw new java.io.FileNotFoundException("File not found: " + src);
    String newName = dest.substring(dest.lastIndexOf('/') + 1);
    // A destination with no '/' means the Drive root — resolveFileId("") is the root, not a not-found case
    String parentPath = dest.lastIndexOf('/') == -1 ? "" : dest.substring(0, dest.lastIndexOf('/'));
    String parentId = resolveFileId(parentPath);
    if (parentId == null) throw new java.io.FileNotFoundException("Destination folder not found: " + parentPath);
    drive.copy(fileId, newName, parentId);
    idCache.clear();
    pathCache.clear();
    metadataCache.clear();
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<FileItem> search(String query, String rootPath) throws Exception
  {
    String q = "name contains '" + query + "' and trashed = false";
    if (!rootPath.isEmpty() && !isVirtualRoot(rootPath))
    {
      String rootId = resolveFileId(rootPath);
      if (rootId == null) throw new java.io.FileNotFoundException("Folder not found: " + rootPath);
      q += " and '" + rootId + "' in parents";
    }

    String listJson = drive.list(q, null);
    Map<String, Object> res = parseJson(listJson);
    Object[] files = (Object[]) res.get("files");
    List<FileItem> items = new ArrayList<>();
    if (files != null) for (Object f : files)
    {
      Map<String, Object> fileMap = (Map<String, Object>) f;
      String name = (String) fileMap.get("name");
      String id = (String) fileMap.get("id");
      String fullPath = rootPath + "/" + name;
      metadataCache.put(id, fileMap);
      items.add(toFileItem(fileMap, fullPath));
    }
    return items;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Object> getAttributes(String path) throws Exception 
  {
    if (path.isEmpty() || isVirtualRoot(path)) return Collections.emptyMap();
    String fileId = resolveFileId(path);
    if (fileId == null) throw new java.io.FileNotFoundException("File not found: " + path);
    String metaJson = drive.getMetadata(fileId);
    return parseJson(metaJson);
  }

  @Override
  public void setAttribute(String path, String attr, Object value) throws Exception {}

  @Override
  public String getIcon(FileItem item) 
  {
    String icon = (String) item.getAttributes().get("iconLink");
    if (icon != null) return icon;
    if (item.getType() == FileItem.TYPEDIRECTORY || item.getType() == FileItem.TYPEVOLUMES) 
    {
      String path = item.getPath();
      if (item.getType() == FileItem.TYPEVOLUMES) return "/images/folder-remote.svg";
      if (path == null || path.isEmpty() || path.equals("/")) return "/images/folder-remote.svg";
      if (path.equals(ROOT_MYDRIVE) || path.contains("My Drive")) return "/images/user-home.svg";
      if (path.equals(ROOT_SHARED_DRIVES) || path.contains("Shared Drives")) return "/images/folder-remote.svg";
      if (path.equals(ROOT_SHARED_WITH_ME) || path.contains("Shared with Me")) return "/images/folder-remote.svg";
      if (path.equals(ROOT_COMPUTERS) || path.contains("Computers")) return "/images/applications-system.svg";
      return "/images/folder.svg";
    }
    return "/images/text-x-generic.svg";
  }

  @Override
  public String getThumbnail(FileItem item) {return (String) item.getAttributes().get("thumbnailLink");}

  @Override
  @SuppressWarnings("unchecked")
  public List<String> getParentIds(String path) throws Exception 
  {
    if (path == null || path.isEmpty()) return Collections.emptyList();
    
    List<String> parents = new ArrayList<>();
    String currentId = resolveFileId(path);
    if (currentId == null) return Collections.emptyList();
    
    String rootId = getMyDriveRootId();
    int safety = 0;
    while (currentId != null &&
           !currentId.equals("root") &&
           !isVirtualRoot(currentId) &&
           (rootId == null || !currentId.equals(rootId)) &&
           !sharedDriveIds.containsKey(currentId) &&
           safety < 10)
    {
      safety++;
      try 
      {
        String metaJson = drive.getMetadata(currentId);
        if (metaJson == null) break;
        Map<String, Object> meta = parseJson(metaJson);
        Object[] parentArr = (Object[]) meta.get("parents");

        if (parentArr != null && parentArr.length > 0)
        {
          currentId = (String) parentArr[0];
          if (currentId.equals("root") || (rootId != null && currentId.equals(rootId))) parents.add(0, ROOT_MYDRIVE);
          else parents.add(0, currentId);
        } 
        else 
        {
          currentId = null;
        }
      } 
      catch (Exception e) 
      {
        System.out.println("WARN: GoogleDriveProvider.getParentIds failed: " + e.getMessage());
        currentId = null;
      }
    }

    if (rootId != null && currentId != null && currentId.equals(rootId))
    {
      if (!parents.contains(ROOT_MYDRIVE)) parents.add(0, ROOT_MYDRIVE);
    }

    if (path.startsWith(ROOT_MYDRIVE) && !parents.contains(ROOT_MYDRIVE)) parents.add(0, ROOT_MYDRIVE);
    else if (path.startsWith(ROOT_SHARED_DRIVES) && !parents.contains(ROOT_SHARED_DRIVES)) parents.add(0, ROOT_SHARED_DRIVES);
    else if (path.startsWith(ROOT_SHARED_WITH_ME) && !parents.contains(ROOT_SHARED_WITH_ME)) parents.add(0, ROOT_SHARED_WITH_ME);
    
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

  @Override
  public boolean isPinnable(String path)
  {
    if (isVirtualRoot(path)) return false;
    return true;
  }

  @SuppressWarnings("unchecked")
  @Override
  public String getRelativePath(String pathOrId) throws Exception
  {
    if (pathOrId == null || pathOrId.isEmpty() || pathOrId.equals("root")) return "";
    if (isVirtualRoot(pathOrId)) return pathOrId;
    if (pathCache.containsKey(pathOrId)) return pathCache.get(pathOrId);

    if (pathOrId.contains("/")) 
    {
      int lastSlash = pathOrId.lastIndexOf('/');
      String parent = pathOrId.substring(0, lastSlash);
      String name = pathOrId.substring(lastSlash + 1);
      String parentPath = getRelativePath(parent);
      String fullPath = parentPath.isEmpty() ? name : parentPath + "/" + name;
      pathCache.put(pathOrId, fullPath);
      return fullPath;
    }

    String fileId = pathOrId;
    if (fileId.length() <= 20 || fileId.contains(" ")) return fileId;
    
    String metaJson = drive.getMetadata(fileId);
    if (metaJson == null) return null;
    Map<String, Object> meta = parseJson(metaJson);
    String name = (String) meta.get("name");
    Object[] parents = (Object[]) meta.get("parents");
    
    String fullPath;
    if (parents == null || parents.length == 0 || parents[0].equals("root")) 
    {
      fullPath = ROOT_MYDRIVE + "/" + name;
    } 
    else 
    {
      String parentId = (String) parents[0];
      String parentPath = getRelativePath(parentId);
      fullPath = parentPath + "/" + name;
    }
    
    pathCache.put(fileId, fullPath);
    idCache.put(fullPath, fileId);
    return fullPath;
  }

  private boolean isVirtualRoot(String id)
  {
    return ROOT_MYDRIVE.equals(id) || ROOT_SHARED_DRIVES.equals(id) || ROOT_SHARED_WITH_ME.equals(id) || ROOT_COMPUTERS.equals(id) || "root".equals(id);
  }

  @SuppressWarnings("unchecked")
  private String assertIsFolder(String path) throws Exception
  {
    if (path.isEmpty() || isVirtualRoot(path)) return "root";

    String id = resolveFileId(path);
    if (id == null) return null;
    if (id.equals("root")) return id;

    try
    {
      String metaJson = drive.getMetadata(id);
      Map<String, Object> meta = parseJson(metaJson);
      String mimeType = (String) meta.get("mimeType");
      if (!"application/vnd.google-apps.folder".equals(mimeType))
      {
        idCache.clear();
        pathCache.clear();
        return null;
      }
    }
    catch (Exception e)
    {
      System.out.println("WARN: GoogleDriveProvider could not verify [" + path + "] as folder: " + e.getMessage());
      idCache.clear();
      pathCache.clear();
      return null;
    }

    return id;
  }

  public String resolveFileId(String path) throws Exception
  {
    if (path == null || path.isEmpty() || path.equals("/")) return "root";
    if (path.equals(ROOT_MYDRIVE)) return "root";

    String cleanPath = path;
    String stripped;
    do
    {
      stripped = cleanPath;
      if (cleanPath.startsWith(displayName + "/")) cleanPath = cleanPath.substring(displayName.length() + 1);
      else if (cleanPath.startsWith(displayName.replace(" ", "") + "/")) cleanPath = cleanPath.substring(displayName.replace(" ", "").length() + 1);
      else if (cleanPath.equals(displayName) || cleanPath.equals(displayName.replace(" ", ""))) return "root";
      
      if (cleanPath.contains("/" + displayName + "/")) cleanPath = cleanPath.replace("/" + displayName + "/", "/");
      if (cleanPath.endsWith("/" + displayName)) cleanPath = cleanPath.substring(0, cleanPath.length() - displayName.length() - 1);
    } while (!stripped.equals(cleanPath));

    if (idCache.containsKey(cleanPath))
    {
      String cachedId = idCache.get(cleanPath);
      return cachedId;
    }

    int lastSlash = cleanPath.lastIndexOf('/');
    String parentPath = lastSlash == -1 ? "" : cleanPath.substring(0, lastSlash);
    String segment = lastSlash == -1 ? cleanPath : cleanPath.substring(lastSlash + 1);

    if (segment.length() > 20 && segment.matches("^[a-zA-Z0-9_-]+$")) return segment;
    if (isVirtualRoot(segment)) return "root";
    if ("read".equals(segment) || "files".equals(segment)) return null;

    String parentId = resolveFileId(parentPath);
    if (parentId == null) return null;

    String listQuery = parentId.equals("root")
        ? "'root' in parents and trashed = false"
        : "'" + parentId + "' in parents and trashed = false";

    String listJson = drive.list(listQuery, null);
    Map<String, Object> res = parseJson(listJson);
    Object[] files = (Object[]) res.get("files");
    if (files == null) files = new Object[0];

    for (int i = 0; i < files.length; i++)
    {
      Map<String, Object> f = (Map<String, Object>) files[i];
      String fileId = (String) f.get("id");
      String fileName = (String) f.get("name");
      String mimeType = (String) f.get("mimeType");
      
      String currentPrefix = parentPath;
      if (currentPrefix.isEmpty())
      {
        if (parentId.equals("root") || parentId.equals(myDriveRootId)) currentPrefix = ROOT_MYDRIVE;
        else if (sharedDriveIds.containsKey(parentId)) currentPrefix = ROOT_SHARED_DRIVES + "/" + parentId;
      }
      String fullPath = currentPrefix.isEmpty() ? fileName : currentPrefix + "/" + fileName;
      
      if ("application/vnd.google-apps.folder".equals(mimeType) || !idCache.containsKey(fullPath))
      {
        idCache.put(fullPath, fileId);
        pathCache.put(fileId, fullPath);
        metadataCache.put(fileId, f);
      }
    }
 
    if (idCache.containsKey(cleanPath))
    {
      String selectedId = idCache.get(cleanPath);
      return selectedId;
    }

    if (!cleanPath.contains("/") && cleanPath.length() > 20 && cleanPath.matches("^[a-zA-Z0-9_-]+$")) return cleanPath;

    return null;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseJson(String jsonStr) throws Exception 
  {
    if (jsonStr == null || jsonStr.isEmpty()) throw new Exception("Google Drive API response was empty");
    return (Map<String, Object>) json.parse(new JSON.ReaderSource(new StringReader(jsonStr)));
  }

  private FileItem toFileItem(Map<String, Object> meta, String subPath) 
  {
    String name = (String) meta.get("name");
    String id = (String) meta.get("id");
    String mimeType = (String) meta.get("mimeType");
    short type = "application/vnd.google-apps.folder".equals(mimeType) ? FileItem.TYPEDIRECTORY : FileItem.TYPEFILE;
    long size = 0;
    if (meta.containsKey("size")) 
    {
      Object sizeObj = meta.get("size");
      if (sizeObj instanceof Number) size = ((Number) sizeObj).longValue();
      else if (sizeObj instanceof String) size = Long.parseLong((String) sizeObj);
    }
    FileItem item = new FileItem(name, id, subPath, type, size, mimeType, providerKey, (String) meta.get("iconLink"), (String) meta.get("thumbnailLink"));
    item.setId(id);
    item.getAttributes().putAll(meta);
    return item;
  }
}