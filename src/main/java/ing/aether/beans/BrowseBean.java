package ing.aether.beans;

import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.eclipse.jetty.util.ajax.JSON;

import ing.aether.CommandRegister;
import ing.aether.Portal;
import ing.aether.data.FileItem;
import ing.aether.data.FileProvider;
import ing.aether.data.FileProviderRegistry;
import ing.aether.data.GoogleDriveProvider;

@CommandRegister(value="menu",accessType=1,createSession=true,preventCache=true)
@CommandRegister(value="tree",accessType=1,createSession=true,preventCache=true)
public class BrowseBean extends BeanObject implements Comparator<Object>
{
  public static final String CMDBrowse="files";

  FileItem[] fileItem=null;
  
  @SuppressWarnings("unchecked")
  protected void processData()
  {
    loadAIData();
    if (path.getLength()<2 && !("menu".equalsIgnoreCase(path.getCommand()) || CMDBrowse.equalsIgnoreCase(path.getCommand()) || "tree".equalsIgnoreCase(path.getCommand()))) return;
    
    FileProvider provider = path.getProvider();
    if (provider != null)
    {
      boolean isTreeRequest = "tree".equalsIgnoreCase(path.getCommand());
      boolean isBrowseRequest = CMDBrowse.equalsIgnoreCase(path.getCommand());
      boolean setWD = "1".equals(request.getParameter("setWD")) || isBrowseRequest || (isTreeRequest && request.getParameter("targetId") == null);

      if (setWD)
      {
        String suffix = path.getSuffix();
        String library = path.getLibrary();
        String wd = (suffix.startsWith(library + "/") || suffix.equals(library)) ? suffix : library + "/" + suffix;
        String oldWd = (String) session.getAttribute("workingDirectory");
        
        if (!wd.equals(oldWd))
        {
          session.setAttribute("workingDirectory", wd);
          
          // Clear chat history to force reload from new WD
          session.removeAttribute("chatHistory");
          session.removeAttribute("currentChatSessionId");
          ing.aether.socket.AetherWebSocket.clearBuffer(session.getId());

          ing.aether.tools.ai.AITools.ensureProjectFiles(session, wd);
          ing.aether.tools.ai.AgentWorker.loadAiConfig(session);
          ing.aether.tools.ai.AgentWorker.broadcastAiConfig(session);
          
          // Load and broadcast new history
          List<Map<String, Object>> history = ing.aether.tools.ai.AgentWorker.loadLatestSession(session);
          if (history != null) session.setAttribute("chatHistory", history);
          ing.aether.tools.ai.AgentWorker.broadcastChatHistory(session, history);
          
          // Refresh bean's local state after session update
          loadAIData();
        }

        resolveExpansionParents(provider, path.getLibrary(), path.getSuffix());
      }
      if ("read".equalsIgnoreCase(path.getCommand()))
      {
        session.setAttribute("lastFilePath", path.getLibrary() + "/" + path.getSuffix());
        resolveExpansionParents(provider, path.getLibrary(), path.getSuffix());
      }
      try
      {
        String subPath = path.getSuffix();
        if (provider.isDirectory(subPath))
        {
          List<FileItem> items = provider.listFiles(subPath);
          if (items != null) for (FileItem item : items)
          {
            String itemPath = item.getPath();
            String lib = path.getLibrary();
            String mapPath = (itemPath.startsWith(lib + "/") || itemPath.equals(lib)) ? itemPath : lib + "/" + itemPath;
            item.setPath(mapPath);

            // Qualify the ID with the provider name for PathMap routing
            String rawId = item.getId();
            if (rawId != null) {
              String mapId = (rawId.startsWith(lib + "/") || rawId.equals(lib)) ? rawId : lib + "/" + rawId;
              item.setId(mapId);
            }

            item.setIcon(provider.getIcon(item));
            item.setThumbnail(provider.getThumbnail(item));
            if (item.getType() != FileItem.TYPEFILE)
            {
              String providerPath = item.getPath();
              String gdpPath = providerPath.startsWith(lib + "/") ? providerPath.substring(lib.length() + 1) : providerPath;
              item.setAttribute("isPinnable", String.valueOf(provider.isPinnable(gdpPath)));
            }
          }
          fileItem = items != null ? items.toArray(new FileItem[0]) : null;
        }
      } catch (Exception e)
      {
        message = "Error: " + e.getMessage();
        e.printStackTrace();
      }
      return;
    }

    if (fileItem != null) return;
    if (CMDBrowse.equalsIgnoreCase(path.getCommand()) || "menu".equalsIgnoreCase(path.getCommand()) || "tree".equalsIgnoreCase(path.getCommand()))
    {
      loadAIData();
      ArrayList<FileItem> items = new ArrayList<>();
      String sessionEmail = (String) session.getAttribute(Portal.SessionEmail);
      boolean isAdmin = Optional.ofNullable((Boolean) session.getAttribute(Portal.SessionAdmin)).orElse(false);

      for (String providerName : FileProviderRegistry.getProviderNames(session))
      {
        FileProvider fp = FileProviderRegistry.getProvider(session, providerName);
        // Only show providers that belong to the current user, or all if admin
        if (isAdmin || (sessionEmail != null && sessionEmail.equals(fp.getProviderId())))
        {
          FileItem item = new FileItem(fp.getRootDisplayName(), providerName, providerName, FileItem.TYPEVOLUMES);
          item.setIcon(fp.getIcon(item));
          items.add(item);
        }
      }
      
      fileItem = items.toArray(new FileItem[0]);
    }
  }

  public String getPrePath()
  {
    return path.getLibrary();
  }
  
  public String[] getSubPaths()
  {
    return path.getSubPaths();
  }
  
  public FileItem[] listFiles()
  {
    if (fileItem == null) return null;
    Arrays.sort(fileItem,this);
    return fileItem;
  }
  
  public String getLastWorkingDirectory()
  {
    return (String) session.getAttribute("workingDirectory");
  }

  public String getLastFilePath()
  {
    return (String) session.getAttribute("lastFilePath");
  }

  @SuppressWarnings("unchecked")
  public List<String> getLastExpansionParents()
  {
    return (List<String>) session.getAttribute("expansionParents");
  }

  private void resolveExpansionParents(FileProvider provider, String library, String subPath)
  {
    try
    {
      List<String> rawParents = provider.getParentIds(subPath);
      List<String> parents = rawParents.stream()
        .map(id -> (id.startsWith(library + "/") || id.equals(library)) ? id : library + "/" + id)
        .collect(Collectors.toList());
      if (!parents.contains(library)) parents.add(0, library);
      session.setAttribute("expansionParents", parents);
    } catch (Exception e)
    {
      e.printStackTrace();
    }
  }

  @Override
  public int compare(Object o1,Object o2)
  {
    if (o1 instanceof FileItem)
    {
      FileItem f1=(FileItem)o1;
      FileItem f2=(FileItem)o2;
      if (f1.getType()!=f2.getType())return f1.getType()-f2.getType();
      else return f1.getName().compareToIgnoreCase(f2.getName());
    }
    return 0;
  }

}
