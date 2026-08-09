package ing.aether.beans;

import java.util.Map;
import ing.aether.CommandRegister;
import ing.aether.SessionTracker;
import ing.aether.data.FileProvider;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

@CommandRegister(value="save", accessType=1, createSession=true, preventCache=true)
public class SaveBean extends BeanObject
{
  private String content;
  private int patchStart = -1;
  private int patchDelete = -1;
  private long baseLen = -1;
  private long baseHash = -1;

  public void setContent(String content)
  {
    this.content = content;
  }

  public void setPatchstart(String patchstart)
  {
    try { patchStart = Integer.parseInt(patchstart); } catch (Exception e) {}
  }

  public void setPatchdelete(String patchdelete)
  {
    try { patchDelete = Integer.parseInt(patchdelete); } catch (Exception e) {}
  }

  public void setBaselen(String baselen)
  {
    try { baseLen = Long.parseLong(baselen); } catch (Exception e) {}
  }

  public void setBasehash(String basehash)
  {
    try { baseHash = Long.parseLong(basehash); } catch (Exception e) {}
  }

  // FNV-1a 32-bit over UTF-16 code units; must stay identical to fnv1aHash() in scriptlib.js
  private static long fnv1a(String s)
  {
    int hash = 0x811c9dc5;
    for (int i = 0; i < s.length(); i++)
    {
      hash ^= s.charAt(i);
      hash *= 0x01000193;
    }
    return hash & 0xffffffffL;
  }

  @Override
  protected void processData()
  {
    boolean isPatch = patchStart >= 0 && patchDelete >= 0 && baseLen >= 0 && baseHash >= 0;
    if (content == null && !isPatch) return;
    FileProvider provider = path.getProvider();
    if (provider == null) return;

    String subPath = path.getSuffix();
    if (subPath.isEmpty()) return;

    int receivedSize = content != null ? content.length() : 0;

    String finalContent = content;
    if (isPatch)
    {
      // Delta save: splice the change into the session buffer, which mirrors the client's baseline
      String base = (String) session.getAttribute("bufferedContent");
      String bufferedPath = (String) session.getAttribute("bufferedPath");
      String currentPath = path.getLibrary() + "/" + subPath;
      // Portal drops empty parameters, so a pure deletion arrives with no content
      String inserted = content != null ? content : "";
      if (base == null || !currentPath.equals(bufferedPath) || base.length() != baseLen
          || patchStart + patchDelete > base.length() || fnv1a(base) != baseHash)
      {
        success = false;
        message = "Save buffer out of sync, resending full content";
        return;
      }
      finalContent = base.substring(0, patchStart) + inserted + base.substring(patchStart + patchDelete);
    }
    try
    {
      try (OutputStream os = provider.getOutputStream(subPath))
      {
        os.write(finalContent.getBytes(StandardCharsets.UTF_8));
      }
      session.setAttribute("bufferedContent", finalContent);
      session.setAttribute("bufferedPath", path.getLibrary() + "/" + path.getSuffix());
      success = true;
      message = "Saved successfully";
    }
    catch (Exception e)
    {
      success = false;
      message = "Error saving file: " + e.getMessage();
      e.printStackTrace();
    }
  }
}
