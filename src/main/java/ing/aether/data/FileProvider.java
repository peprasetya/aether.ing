package ing.aether.data;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * Interface for unified file operations across different storage backends.
 */
public interface FileProvider 
{
  /**
   * @return The unique identifier for this provider instance (e.g., account email).
   */
  String getProviderId();

  /**
   * @return The URI scheme this provider handles (e.g., "local", "gdrive", "ssh").
   */
  String getScheme();

  /**
   * @return The display name for the root of this provider.
   */
  String getRootDisplayName();

  /**
   * Retrieves a single FileItem for the given path.
   */
  FileItem getFileItem(String path) throws Exception;

  /**
   * Lists the contents of a directory.
   */
  List<FileItem> listFiles(String path) throws Exception;

  /**
   * Checks if a file or directory exists at the given path.
   */
  boolean exists(String path) throws Exception;

  /**
   * Checks if the path points to a directory.
   */
  boolean isDirectory(String path) throws Exception;

  /**
   * Creates a new empty file.
   */
  void createFile(String path) throws Exception;

  /**
   * Creates a new directory.
   */
  void mkdir(String path) throws Exception;

  /**
   * Returns an input stream for reading the file.
   */
  InputStream getInputStream(String path) throws Exception;

  /**
   * Returns an input stream for reading a portion of the file.
   */
  InputStream getInputStream(String path, long offset, long length) throws Exception;

  /**
   * Returns an output stream for writing to the file.
   */
  OutputStream getOutputStream(String path) throws Exception;

  /**
   * Deletes a file or directory.
   */
  void delete(String path, boolean recursive) throws Exception;

  /**
   * Renames a file or directory.
   */
  void rename(String path, String newName) throws Exception;

  /**
   * Moves a file or directory from source to destination.
   */
  void move(String src, String dest) throws Exception;

  /**
   * Copies a file or directory from source to destination.
   */
  void copy(String src, String dest) throws Exception;

  /**
   * Searches for files matching the query under the given root path.
   */
  List<FileItem> search(String query, String rootPath) throws Exception;

  /**
   * Returns provider-specific extended metadata.
   */
  Map<String, Object> getAttributes(String path) throws Exception;

  /**
   * Sets a provider-specific attribute.
   */
  void setAttribute(String path, String attr, Object value) throws Exception;

  /**
   * Returns a URL or path to an icon for the given file item.
   */
  String getIcon(FileItem item);

  /**
   * Returns a URL or path to a thumbnail for the given file item.
   */
  String getThumbnail(FileItem item);

  /**
   * Returns a list of parent IDs for the given path, from the provider root down to the direct parent.
   */
  List<String> getParentIds(String path) throws Exception;

  // Capabilities
  boolean supportsRandomAccess();
  boolean supportsSearch();
  boolean supportsPermissions();
  boolean isReadOnly();

  // Pinning
  default boolean isPinnable(String path) {return true;}

  /**
   * Returns a relative, human-readable path for the given path/ID suffix.
   */
  default String getRelativePath(String path) throws Exception {return path;}
}

