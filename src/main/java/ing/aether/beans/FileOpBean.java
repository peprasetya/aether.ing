package ing.aether.beans;

import ing.aether.CommandRegister;
import ing.aether.data.FileProvider;
import ing.aether.data.PathMap;

/**
 * Manual (non-AI) file operations from the explorer's kebab menu: rename, move, delete.
 * The target item's path comes from the request URL itself, exactly like NewBean — the
 * routed command segment plus the item's path.
 */
@CommandRegister(value=FileOpBean.CMDRename, accessType=1, createSession=true, preventCache=true)
@CommandRegister(value=FileOpBean.CMDMove, accessType=1, createSession=true, preventCache=true)
@CommandRegister(value=FileOpBean.CMDDelete, accessType=1, createSession=true, preventCache=true)
public class FileOpBean extends BeanObject
{
  public static final String CMDRename="rename";
  public static final String CMDMove="movefile";
  public static final String CMDDelete="deleteitem";

  private String newname;
  private String destination;

  public void setNewname(String newname)
  {
    this.newname = newname;
  }

  public void setDestination(String destination)
  {
    this.destination = destination;
  }

  @Override
  protected void processData()
  {
    FileProvider provider = path.getProvider();
    if (provider == null) return;

    try
    {
      String cmd = path.getCommand();
      if (CMDRename.equals(cmd))
      {
        if (newname == null || newname.trim().isEmpty() || newname.contains("/") || newname.contains("\\") || newname.contains("..")) throw new Exception("Invalid new name");
        provider.rename(path.getSuffix(), newname.trim());
        success = true;
        message = "Renamed to " + newname.trim();
      }
      else if (CMDMove.equals(cmd))
      {
        if (destination == null || destination.trim().isEmpty()) throw new Exception("Destination is required");
        // destination is a bare provider-qualified path, not a request URL — PathMap discards its
        // first slash-separated segment as a throwaway "command", so a dummy leading segment is
        // required here, mirroring AITools.moveFile's fix for the same PathMap parsing quirk
        PathMap destPath = new PathMap("x/" + destination, session);
        FileProvider destProvider = destPath.getProvider();
        if (destProvider == null || destProvider != provider) throw new Exception("Invalid destination");
        provider.move(path.getSuffix(), destPath.getSuffix());
        success = true;
        message = "Moved";
      }
      else if (CMDDelete.equals(cmd))
      {
        provider.delete(path.getSuffix(), true);
        success = true;
        message = "Deleted";
      }
    }
    catch (Exception e)
    {
      success = false;
      message = "Error: " + e.getMessage();
      e.printStackTrace();
    }
  }
}
