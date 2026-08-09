package ing.aether.tools.ai;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import ing.aether.Portal;
import ing.aether.data.FileItem;
import ing.aether.data.FileProvider;
import ing.aether.data.FileProviderRegistry;
import ing.aether.data.GoogleDriveProvider;
import ing.aether.data.PathMap;
import jakarta.servlet.http.HttpSession;

public class AITools
{
  private static String getWorkingDirectoryName(HttpSession session, String workingDir)
  {
    if (workingDir == null || workingDir.isEmpty()) return "";
    // Cached per working directory: this is called once per listed item, and resolving it
    // costs a provider round-trip (an SFTP stat per file without the cache)
    if (workingDir.equals(session.getAttribute("wdNameFor"))) return (String) session.getAttribute("wdName");
    try
    {
      PathMap pm = new PathMap("/read/" + workingDir, session);
      FileProvider provider = pm.getProvider();
      if (provider != null)
      {
        FileItem item = provider.getFileItem(pm.getSuffix());
        String name;
        if (item != null) name = item.getName();
        else name = (provider.getProviderId() != null) ? provider.getProviderId() : "Aether";
        session.setAttribute("wdNameFor", workingDir);
        session.setAttribute("wdName", name);
        return name;
      }
    } catch (Exception e) {
      System.out.println("WARN: getWorkingDirectoryName failed for " + workingDir + ": " + e.getMessage());
    }

    int lastSlash = workingDir.lastIndexOf('/');
    String fallback = (lastSlash == -1) ? workingDir : workingDir.substring(lastSlash + 1);
    return fallback;
  }

  private static String ensureFullPath(HttpSession session, String pathStr)
  {
    if (pathStr == null || pathStr.isEmpty()) return pathStr;

    // Already provider-qualified (possibly for a DIFFERENT provider than the working directory,
    // e.g. a Google Drive file open while an SSH project is active): never prefix it with the WD
    String firstSegment = pathStr.contains("/") ? pathStr.substring(0, pathStr.indexOf('/')) : pathStr;
    if (FileProviderRegistry.getProvider(session, firstSegment) != null) return pathStr;

    String workingDir = (String) session.getAttribute("workingDirectory");
    if (workingDir != null)
    {
      if (pathStr.equals(workingDir) || pathStr.startsWith(workingDir + "/")) {
        if (!pathStr.contains("..")) return pathStr;
        // Traversal segments below the working directory are stripped, never resolved
        String tail = pathStr.substring(workingDir.length()).replace('\\', '/').replace("..", "").replaceAll("/+", "/").replaceAll("^/+", "");
        String safe = workingDir + (tail.isEmpty() ? "" : "/" + tail);
        return safe;
      }

      String cleanPath = pathStr.trim().replace('\\', '/').replaceAll("/+", "/").replace("..", "").replaceAll("^[\\\\/]+", "");
      String wdName = getWorkingDirectoryName(session, workingDir);

      if (cleanPath.startsWith(wdName + "/")) cleanPath = cleanPath.substring(wdName.length() + 1);
      else if (cleanPath.equals(wdName)) cleanPath = "";

      String result = workingDir + (cleanPath.isEmpty() ? "" : "/" + cleanPath);
      return result;
    }
    return pathStr;
  }

  // True when the current working directory lives on an SSH provider (enables runCommand/terminal)
  public static boolean isSshWorkingDir(HttpSession session)
  {
    String workingDir = (String) session.getAttribute("workingDirectory");
    if (workingDir == null || workingDir.isEmpty()) return false;
    try
    {
      PathMap pm = new PathMap("/read/" + workingDir, session);
      return pm.getProvider() instanceof ing.aether.data.SshFileProvider;
    } catch (Exception e) { return false; }
  }

  // Run a shell command in the working directory on the remote SSH host; returns exit code plus capped output
  public static String runCommand(HttpSession session, String command, Integer timeoutSec) throws Exception
  {
    if (command == null || command.trim().isEmpty()) return "Error: no command given.";
    String workingDir = (String) session.getAttribute("workingDirectory");
    if (workingDir == null || workingDir.isEmpty()) return "Error: no working directory set.";
    PathMap pm = new PathMap("/read/" + workingDir, session);
    FileProvider provider = pm.getProvider();
    if (!(provider instanceof ing.aether.data.SshFileProvider)) return "Error: runCommand is only available when the working directory is on an SSH provider; this project's storage does not support command execution.";
    int timeout = timeoutSec != null ? Math.max(1, Math.min(300, timeoutSec)) : 60;
    return ((ing.aether.data.SshFileProvider) provider).exec(pm.getSuffix(), command, timeout, 10240);
  }

  public static String readFile(HttpSession session, String pathStr) throws Exception
  {
    String fullPath = ensureFullPath(session, pathStr);
    PathMap path = new PathMap("/read/" + fullPath, session);
    FileProvider provider = path.getProvider();
    if (provider == null) throw new Exception("Provider not found for path: " + pathStr + " (Resolved to: " + fullPath + ")");

    String subPath = path.getSuffix();
    if (!provider.exists(subPath)) throw new Exception(notFoundMessage(provider, subPath));
    try (InputStream is = provider.getInputStream(subPath))
    {
      return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  // Clean "file not found" with the containing directory's actual files, so the model can self-correct on a near-miss name
  private static String notFoundMessage(FileProvider provider, String subPath)
  {
    int lastSlash = subPath.lastIndexOf('/');
    String parentSubPath = lastSlash == -1 ? "" : subPath.substring(0, lastSlash);
    try
    {
      List<String> names = provider.listFiles(parentSubPath).stream().map(FileItem::getName).collect(Collectors.toList());
      return "File not found: " + subPath + ". Files in '" + (parentSubPath.isEmpty() ? "/" : parentSubPath) + "': " + names;
    }
    catch (Exception e)
    {
      return "File not found: " + subPath;
    }
  }

  public static void writeFile(HttpSession session, String pathStr, String content) throws Exception
  {
    String fullPath = ensureFullPath(session, pathStr);
    PathMap path = new PathMap("/save/" + fullPath, session);
    FileProvider provider = path.getProvider();
    if (provider == null) throw new Exception("Provider not found for path: " + pathStr + " (Resolved to: " + fullPath + ")");

    String subPath = path.getSuffix();
    try (OutputStream os = provider.getOutputStream(subPath))
    {
      os.write(content.getBytes(StandardCharsets.UTF_8));
    }
    // Only mirror the session buffer when the AI writes the file the user currently has OPEN in the
    // editor. That buffer is the delta-save baseline SaveBean uses for the open file; if a write to any
    // OTHER file (e.g. aether.ing.json for chat-session persistence, which happens every AI turn) were to
    // hijack it, the user's next editor autosave would see a mismatched baseline and be forced into a full
    // resend ("save buffer out of sync").
    String writtenPath = path.getLibrary() + "/" + subPath;
    if (writtenPath.equals(session.getAttribute("lastFilePath")))
    {
      session.setAttribute("bufferedContent", content);
      session.setAttribute("bufferedPath", writtenPath);
    }
  }

  public static String patchFile(HttpSession session, String pathStr, String oldText, String newText) throws Exception
  {
    Map<String, Object> edit = new HashMap<>();
    edit.put("old_text", oldText);
    edit.put("new_text", newText);
    return patchFile(session, pathStr, Collections.singletonList(edit));
  }

  public static String patchFile(HttpSession session, String pathStr, List<Map<String, Object>> edits) throws Exception
  {
    if (edits == null || edits.isEmpty()) throw new Exception("No edits provided.");
    String currentContent = readFile(session, pathStr);

    int applied = 0;
    for (Map<String, Object> edit : edits)
    {
      String oldText = edit.get("old_text") instanceof String ? (String) edit.get("old_text") : null;
      String newText = edit.get("new_text") instanceof String ? (String) edit.get("new_text") : "";
      if (oldText == null || oldText.isEmpty()) throw new Exception("Edit " + (applied + 1) + ": old_text is empty.");

      int count = 0;
      int index = currentContent.indexOf(oldText);
      while (index != -1)
      {
        count++;
        index = currentContent.indexOf(oldText, index + oldText.length());
      }

      if (count == 0) throw new Exception("Edit " + (applied + 1) + ": the string to replace was not found in the file.");
      if (count > 1) throw new Exception("Edit " + (applied + 1) + ": the string to replace is not unique. Found " + count + " occurrences.");

      currentContent = currentContent.replace(oldText, newText);
      applied++;
    }

    writeFile(session, pathStr, currentContent);
    return "Successfully applied " + applied + " edit(s) to " + pathStr;
  }

  // Public view of the session-aware path resolution so permission requests can carry the real target path
  public static String resolveFullPath(HttpSession session, String pathStr)
  {
    return ensureFullPath(session, pathStr);
  }

  public static void createFile(HttpSession session, String pathStr, String content) throws Exception
  {
    writeFile(session, pathStr, content);
  }

  public static void deleteFile(HttpSession session, String pathStr) throws Exception
  {
    String fullPath = ensureFullPath(session, pathStr);
    PathMap path = new PathMap("/files/" + fullPath, session);
    FileProvider provider = path.getProvider();
    if (provider == null) throw new Exception("Provider not found for path: " + pathStr + " (Resolved to: " + fullPath + ")");
    provider.delete(path.getSuffix(), false);
  }

  public static void mkdir(HttpSession session, String pathStr) throws Exception
  {
    String fullPath = ensureFullPath(session, pathStr);
    PathMap path = new PathMap("/files/" + fullPath, session);
    FileProvider provider = path.getProvider();
    if (provider == null) throw new Exception("Provider not found for path: " + pathStr + " (Resolved to: " + fullPath + ")");
    provider.mkdir(path.getSuffix());
  }

  public static void renameFile(HttpSession session, String pathStr, String newName) throws Exception
  {
    // A rename never changes directory (that's move) — reject anything that could escape the parent
    if (newName == null || newName.trim().isEmpty() || newName.contains("/") || newName.contains("\\") || newName.contains("..")) throw new Exception("Invalid new name: " + newName);
    String fullPath = ensureFullPath(session, pathStr);
    PathMap path = new PathMap("/files/" + fullPath, session);
    FileProvider provider = path.getProvider();
    if (provider == null) throw new Exception("Provider not found for path: " + pathStr + " (Resolved to: " + fullPath + ")");
    provider.rename(path.getSuffix(), newName.trim());
  }

  public static void moveFile(HttpSession session, String pathStr, String destStr) throws Exception
  {
    String fullPath = ensureFullPath(session, pathStr);
    PathMap path = new PathMap("/files/" + fullPath, session);
    FileProvider provider = path.getProvider();
    if (provider == null) throw new Exception("Provider not found for path: " + pathStr + " (Resolved to: " + fullPath + ")");

    // destStr is a bare provider-qualified path, not a request URL — PathMap discards the first slash-separated
    // segment as a throwaway "command", so a dummy leading segment is required here or the destination misparses
    String fullDest = ensureFullPath(session, destStr);
    PathMap destPath = new PathMap("x/" + fullDest, session);
    FileProvider destProvider = destPath.getProvider();
    if (destProvider == null) throw new Exception("Provider not found for destination: " + destStr + " (Resolved to: " + fullDest + ")");
    if (destProvider != provider) throw new Exception("Cannot move across different storage providers.");

    provider.move(path.getSuffix(), destPath.getSuffix());
  }

  public static void copyFile(HttpSession session, String pathStr, String destStr) throws Exception
  {
    String fullPath = ensureFullPath(session, pathStr);
    PathMap path = new PathMap("/files/" + fullPath, session);
    FileProvider provider = path.getProvider();
    if (provider == null) throw new Exception("Provider not found for path: " + pathStr + " (Resolved to: " + fullPath + ")");

    String fullDest = ensureFullPath(session, destStr);
    PathMap destPath = new PathMap("x/" + fullDest, session);
    FileProvider destProvider = destPath.getProvider();
    if (destProvider == null) throw new Exception("Provider not found for destination: " + destStr + " (Resolved to: " + fullDest + ")");
    if (destProvider != provider) throw new Exception("Cannot copy across different storage providers.");

    provider.copy(path.getSuffix(), destPath.getSuffix());
  }

  public static boolean exists(HttpSession session, String pathStr)
  {
    try
    {
      String fullPath = ensureFullPath(session, pathStr);
      PathMap path = new PathMap("/read/" + fullPath, session);
      FileProvider provider = path.getProvider();
      return provider != null && provider.exists(path.getSuffix());
    } catch (Exception e) { return false; }
  }

  public static String resolveRelativePath(HttpSession session, String pathStr)
  {
    if (pathStr == null || pathStr.isEmpty()) return pathStr;
    
    String workingDir = (String) session.getAttribute("workingDirectory");
    if (workingDir == null) return pathStr;

    try 
    {
      // Normalize both paths for comparison
      String normalizedPath = pathStr.replace('\\', '/').replaceAll("/+", "/").replaceAll("^/+", "");
      String normalizedWD = workingDir.replace('\\', '/').replaceAll("/+", "/").replaceAll("^/+", "");

      String wdName = getWorkingDirectoryName(session, workingDir);

      // If it's already a traditional path starting with the WD name, return as is
      if (normalizedPath.startsWith(wdName + "/")) return normalizedPath;
      if (normalizedPath.equals(wdName)) return normalizedPath;

      // Search for the full WD path within the provided path
      int wdIdx = normalizedPath.indexOf(normalizedWD);
      if (wdIdx != -1)
      {
         return wdName + normalizedPath.substring(wdIdx + normalizedWD.length());
      }

      // Fallback: Use PathMap to resolve and then try to strip WD
      PathMap pm = new PathMap("/read/" + pathStr, session);
      FileProvider provider = pm.getProvider();
      if (provider != null)
      {
        String rel = provider.getRelativePath(pm.getSuffix());
        String full = (pm.getLibrary() != null && !rel.startsWith(pm.getLibrary())) ? pm.getLibrary() + "/" + rel : rel;
        String normalizedFull = full.replace('\\', '/').replaceAll("/+", "/").replaceAll("^/+", "");
        
        int wdIdxFull = normalizedFull.indexOf(normalizedWD);
        if (wdIdxFull != -1) return wdName + normalizedFull.substring(wdIdxFull + normalizedWD.length());
        
        // Final fallback: just the file name prefixed with wdName if it's under WD
        if (normalizedFull.contains(normalizedWD)) return wdName + "/" + pm.getSuffix();

        return normalizedFull;
      }
      
      return normalizedPath;
    } catch (Exception e) { return pathStr; }
  }

  public static List<Map<String, Object>> listFiles(HttpSession session, String pathStr) throws Exception
  {
    String fullPath = ensureFullPath(session, pathStr);
    PathMap path = new PathMap("/tree/" + fullPath, session);
    FileProvider provider = path.getProvider(); // PathMap now handles session-scoped provider
    if (provider == null) throw new Exception("Provider not found for path: " + pathStr + " (Resolved to: " + fullPath + ")");

    return provider.listFiles(path.getSuffix()).stream().map(item -> {
      Map<String, Object> map = new HashMap<>();
      map.put("name", item.getName());
      map.put("path", resolveRelativePath(session, path.getLibrary() + "/" + item.getPath()));
      map.put("type", item.getType());
      return map;
    }).collect(Collectors.toList());
  }

  // Dependency/build trees are noise for the AI context and each directory costs a provider round-trip
  private static final Set<String> SKIP_DIRS = Set.of("node_modules", "venv", ".venv", "env", "__pycache__", "site-packages", ".git", "target", "build", "dist");
  private static final int MAX_WALK_ENTRIES = 400;

  public static List<String> listAllFiles(HttpSession session, String pathStr) throws Exception
  {
    List<String> results = new ArrayList<>();
    walk(session, pathStr, results, 0);
    return results;
  }

  private static void walk(HttpSession session, String pathStr, List<String> results, int depth) throws Exception
  {
    if (depth > 5 || results.size() >= MAX_WALK_ENTRIES) return;
    List<Map<String, Object>> items = listFiles(session, pathStr);
    for (Map<String, Object> item : items)
    {
      if (results.size() >= MAX_WALK_ENTRIES) return;
      String p = (String) item.get("path");
      results.add(p);
      if (((Number) item.get("type")).intValue() == FileItem.TYPEDIRECTORY)
      {
        String name = (String) item.get("name");
        if (name == null || name.startsWith(".") || SKIP_DIRS.contains(name) || name.endsWith(".dist-info") || name.endsWith(".egg-info")) continue;
        walk(session, p, results, depth + 1);
      }
    }
  }

  public static List<Map<String, Object>> grepSearch(HttpSession session, String rootPath, String pattern) throws Exception
  {
    List<String> allFiles = listAllFiles(session, rootPath);
    List<Map<String, Object>> matches = new ArrayList<>();

    java.util.regex.Pattern regex = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE);

    for (String filePath : allFiles)
    {
      if (filePath.endsWith("/") || filePath.contains("/.git/") || filePath.contains("/node_modules/")) continue;
      
      try {
        String content = readFile(session, filePath);
        String[] lines = content.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++)
        {
          if (regex.matcher(lines[i]).find())
          {
            Map<String, Object> match = new HashMap<>();
            match.put("file", filePath);
            match.put("line", i + 1);
            match.put("content", lines[i].trim());
            matches.add(match);
          }
        }
      } catch (Exception e) { }
      
      if (matches.size() > 50) break;
    }
    return matches;
  }

  public static void ensureProjectFiles(HttpSession session, String workingDir)
  {
    if (workingDir == null || workingDir.isEmpty()) return;
    
    // Heuristic: Avoid virtual roots and shallow directories
    String[] parts = workingDir.split("/");
    if (parts.length < 3) return; // e.g., "Library/mydrive" is length 2
    
    // Further heuristic: Avoid common virtual roots explicitly
    if (workingDir.endsWith("/mydrive") || workingDir.endsWith("/shared-drives") || workingDir.endsWith("/shared-with-me") || workingDir.endsWith("/computers")) return;

    String[] files = {"aether.ing.json", "AGENTS.md"};
    String[] defaults = {"{}", "# AGENTS\n\nProject overview and instructions for AI agents. Describe what this project is, its conventions, and anything an agent should know before making changes.\n"};

    for (int i = 0; i < files.length; i++)
    {
      String path = workingDir + "/" + files[i];
      try
      {
        PathMap pm = new PathMap("/read/" + path, session);
        FileProvider provider = pm.getProvider();
        if (provider != null && !provider.exists(pm.getSuffix()))
        {
          System.out.println("AITools: Creating missing project file: " + path);
          writeFile(session, path, defaults[i]);
        }
      } catch (Exception e)
      {
        System.out.println("AITools: Cannot create project file " + path + ": " + e.getMessage());
        ing.aether.socket.AetherWebSocket.notify(session.getId(), "⚠️ Project setup failed: cannot create " + files[i] + " in this directory (" + e.getMessage() + "). It may not be writable by your user.");
      }
    }
  }

  private static final org.eclipse.jetty.util.ajax.JSON searchJson = new org.eclipse.jetty.util.ajax.JSON();

  public static String webSearch(String query) throws Exception
  {
    Object[] searchArr = Portal.getProperties("SearchKey");
    String apiKey = (searchArr != null && searchArr.length > 0) ? (String) searchArr[0] : null;
    if (apiKey == null || apiKey.isEmpty()) return "Web search is not configured: an admin must set the search provider and API key in Settings.";

    Object[] provArr = Portal.getProperties("SearchProvider");
    String provider = (provArr != null && provArr.length > 0) ? (String) provArr[0] : "brave";
    return "tavily".equals(provider) ? tavilySearch(apiKey, query) : braveSearch(apiKey, query);
  }

  @SuppressWarnings("unchecked")
  private static String braveSearch(String apiKey, String query) throws Exception
  {
    String urlStr = "https://api.search.brave.com/res/v1/web/search?count=5&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
    URL url = new URI(urlStr).toURL();
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setConnectTimeout(10000);
    conn.setReadTimeout(20000);
    conn.setRequestProperty("Accept", "application/json");
    conn.setRequestProperty("X-Subscription-Token", apiKey);

    Map<String, Object> root = (Map<String, Object>) searchJson.parse(new org.eclipse.jetty.util.ajax.JSON.ReaderSource(new java.io.StringReader(readSearchResponse(conn))));
    Map<String, Object> web = root.get("web") instanceof Map ? (Map<String, Object>) root.get("web") : null;
    Object[] results = (web != null && web.get("results") instanceof Object[]) ? (Object[]) web.get("results") : null;
    return formatSearchResults(query, null, results, "description");
  }

  @SuppressWarnings("unchecked")
  private static String tavilySearch(String apiKey, String query) throws Exception
  {
    URL url = new URI("https://api.tavily.com/search").toURL();
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setConnectTimeout(10000);
    conn.setReadTimeout(30000);
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/json");
    conn.setRequestProperty("Authorization", "Bearer " + apiKey);

    Map<String, Object> req = new HashMap<>();
    req.put("query", query);
    req.put("max_results", 5);
    req.put("include_answer", true);
    try (OutputStream os = conn.getOutputStream()) { os.write(searchJson.toJSON(req).getBytes(StandardCharsets.UTF_8)); }

    Map<String, Object> root = (Map<String, Object>) searchJson.parse(new org.eclipse.jetty.util.ajax.JSON.ReaderSource(new java.io.StringReader(readSearchResponse(conn))));
    String answer = root.get("answer") instanceof String ? (String) root.get("answer") : null;
    Object[] results = root.get("results") instanceof Object[] ? (Object[]) root.get("results") : null;
    return formatSearchResults(query, answer, results, "content");
  }

  private static String readSearchResponse(HttpURLConnection conn) throws Exception
  {
    int status = conn.getResponseCode();
    InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
    String body = is != null ? new String(is.readAllBytes(), StandardCharsets.UTF_8) : "";
    if (status >= 400) throw new Exception("Search API HTTP " + status + (body.isEmpty() ? "" : ": " + (body.length() > 300 ? body.substring(0, 300) : body)));
    return body;
  }

  // Compact title/url/snippet list: much cheaper for the model than the provider's raw JSON payload
  @SuppressWarnings("unchecked")
  private static String formatSearchResults(String query, String answer, Object[] results, String snippetKey)
  {
    StringBuilder sb = new StringBuilder("Search results for: " + query + "\n");
    if (answer != null && !answer.trim().isEmpty()) sb.append("\nAnswer summary: ").append(answer.trim()).append("\n");
    int count = 0;
    if (results != null) for (Object o : results)
    {
      if (!(o instanceof Map)) continue;
      if (count >= 5) break;
      Map<String, Object> r = (Map<String, Object>) o;
      String snippet = r.get(snippetKey) != null ? r.get(snippetKey).toString().replaceAll("<[^>]+>", "") : "";
      if (snippet.length() > 500) snippet = snippet.substring(0, 500) + "…";
      count++;
      sb.append("\n").append(count).append(". ").append(r.get("title")).append("\n   ").append(r.get("url")).append("\n   ").append(snippet).append("\n");
    }
    if (count == 0) sb.append("\n(no results)");
    return sb.toString();
  }
}
