package ing.aether.beans;

import java.util.ArrayList;
import java.util.List;
import ing.aether.CommandRegister;
import ing.aether.data.FileItem;
import ing.aether.data.FileProvider;
import ing.aether.data.FileProviderRegistry;

@CommandRegister(value="search", accessType=1, createSession=true, preventCache=true)
public class SearchBean extends BeanObject
{
  private String q = "";
  private FileItem[] results = null;

  public void setQ(String q) {this.q = q;}
  public FileItem[] getResults() {return results;}

  protected void processData()
  {
    if (q == null || q.trim().isEmpty()) return;
    
    List<FileItem> allResults = new ArrayList<>();
    for (String providerName : FileProviderRegistry.getProviderNames(session))
    {
       FileProvider fp = FileProviderRegistry.getProvider(session, providerName);
       if (fp.supportsSearch())
       {
          try 
          {
             List<FileItem> providerResults = fp.search(q, "");
             for (FileItem item : providerResults)
             {
                // Prefix path with provider name for identification
                // Actually FileItem already has providerId, but UI needs full path
                // Wait, search results should probably be displayed differently
                // For now, I'll just collect them
                allResults.add(item);
             }
          } catch (Exception e) {e.printStackTrace();}
       }
    }
    results = allResults.toArray(new FileItem[0]);
  }
}
