package ing.aether.data;

import java.io.File;
import java.util.*;
import java.util.stream.IntStream; // For streaming array

import ing.aether.Portal;
import ing.aether.SessionTracker;
import jakarta.servlet.http.HttpSession;

public class PathMap
{
  private int pathLength=0;
  private String command=null;
  private String[] subPaths=null;
  private String prePath=null;
  private String oriPath=null;
  private String subPath=null;
  private String realPath=null;
  private HttpSession session=null;
  private FileProvider provider=null;

  public int getLength(){return pathLength;}
  public String getCommand(){return command;}
  public String getLibrary(){return prePath;}
  public String getPrefix(){return oriPath;}
  public String getSuffix(){return subPath;}
  public String[] getSubPaths(){return subPaths;}
  public String getRealPath(){return realPath;}
  public HttpSession getSession(){return session;}
  public FileProvider getProvider(){return provider;}
 
  @SuppressWarnings("unchecked")
  public PathMap(String path,HttpSession currentSession)
  {
    if (path.startsWith("/")) path = path.substring(1);
    String[] paths=path.split("/");
    this.pathLength=paths.length;
    this.session=currentSession;
    
    if (pathLength<1)return;

    int libIdx=1;
    this.command=paths[0];

    if (pathLength<libIdx)return;


    boolean checkAdmin=Optional.ofNullable(this.session).map(s -> (Boolean)s.getAttribute(Portal.SessionAdmin)).orElse(false);
    if (this.pathLength>libIdx)
    {
      this.prePath=paths[libIdx];
      
      // Handle combined URLs: truncate path if a nested command is found
      List<String> subPathList = new ArrayList<>();
      for (int i = libIdx + 1; i < paths.length; i++)
      {
        if (("tree".equals(this.command) || "files".equals(this.command)) && ("read".equals(paths[i]) || "files".equals(paths[i]))) break;
        subPathList.add(paths[i]);
      }
      
      this.subPaths = subPathList.toArray(new String[0]);
      this.subPath = String.join("/", this.subPaths);
      
      this.provider = FileProviderRegistry.getProvider(this.session, this.prePath);
      
      // Ownership check: Ensure session user owns the provider, or is admin
      if (this.provider != null && !checkAdmin)
      {
        String sessionEmail = (String) this.session.getAttribute(Portal.SessionEmail);
        if (sessionEmail == null || !sessionEmail.equals(this.provider.getProviderId()))
        {
          System.err.println("PathMap: Access denied for user " + sessionEmail + " to provider " + this.prePath);
          this.provider = null;
        }
      }

      if (this.provider instanceof LocalFileProvider)
      {
        LocalFileProvider lfp = (LocalFileProvider)this.provider;
        this.oriPath = lfp.getProviderId(); // Or some other way to get the root path
        // For LocalFileProvider, subPath uses File.separator
        try 
        {
          this.realPath = new File(lfp.getAttributes("").get("rootPath").toString(), this.subPath.replace("/", File.separator)).getAbsolutePath();
        } catch (Exception e) {}
      }
      // For other providers, realPath might not be a local path, so we use subPath
    }
  }
}
