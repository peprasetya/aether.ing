package ing.aether.beans;

import java.io.InputStream;
import java.io.OutputStream;

import ing.aether.CommandRegister;
import ing.aether.data.FileItem;
import ing.aether.data.FileProvider;
import ing.aether.tools.ai.MediaWorker;

// Raw bytes of a video or 3D file for the viewers. PathMap enforces provider ownership exactly as for
// "read". Byte ranges are honoured because a <video> element seeks with them (Safari refuses to play
// without); any other path gets an empty text/plain body instead of the application page
@CommandRegister(value=MediaBean.COMMAND, accessType=1, createSession=true, preventCache=true)
public class MediaBean extends BeanObject
{
  public static final String COMMAND = "media";
  // An open-ended range ("bytes=0-") is answered in pieces: the player asks for the next one itself
  private static final long MAX_RANGE = 8L * 1024 * 1024;

  private FileProvider provider = null;
  private String subPath = null;
  private long offset = 0;
  private long length = -1;

  @Override
  protected void processData()
  {
    contentType = "text/plain";
    FileProvider p = path.getProvider();
    if (p == null) return;
    String sp = path.getSuffix();
    if (!MediaWorker.isMediaPath(sp)) return;
    contentType = MediaWorker.mimeFor(sp);
    provider = p;
    subPath = sp;

    long size = -1;
    try
    {
      FileItem item = p.getFileItem(sp);
      if (item != null) size = item.getSize();
    } catch (Exception e) {}
    if (size <= 0) return;
    contentLength = size;

    String range = request != null ? request.getHeader("Range") : null;
    if (range == null || !range.startsWith("bytes=") || !p.supportsRandomAccess()) return;
    try
    {
      String[] parts = range.substring(6).split(",")[0].trim().split("-", 2);
      long from, to;
      if (parts[0].isEmpty())
      {
        // Suffix range: the last N bytes
        from = Math.max(0, size - Long.parseLong(parts[1]));
        to = size - 1;
      }
      else
      {
        from = Long.parseLong(parts[0]);
        to = parts.length > 1 && !parts[1].isEmpty() ? Math.min(Long.parseLong(parts[1]), size - 1) : Math.min(from + MAX_RANGE - 1, size - 1);
      }
      if (from < 0 || from >= size || to < from) return;
      offset = from;
      length = to - from + 1;
      partial = true;
      contentRange = "bytes " + from + "-" + to + "/" + size;
      contentLength = length;
    } catch (NumberFormatException e) {}
  }

  public boolean isRandomAccess()
  {
    return provider != null && provider.supportsRandomAccess();
  }

  public void writeTo(OutputStream out) throws Exception
  {
    if (provider == null) return;
    try (InputStream is = length >= 0 ? provider.getInputStream(subPath, offset, length) : provider.getInputStream(subPath))
    {
      is.transferTo(out);
    }
  }
}
