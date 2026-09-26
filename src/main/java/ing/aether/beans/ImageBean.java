package ing.aether.beans;

import java.io.InputStream;
import java.io.OutputStream;

import ing.aether.CommandRegister;
import ing.aether.data.FileProvider;
import ing.aether.tools.ai.ImageWorker;

// Raw image bytes for the viewer and chat thumbnails. PathMap enforces provider ownership exactly as
// for "read"; a non-image path gets an empty text/plain body instead of the application page
@CommandRegister(value=ImageBean.COMMAND, accessType=1, createSession=true, preventCache=true)
public class ImageBean extends BeanObject
{
  public static final String COMMAND = "image";

  private FileProvider provider = null;
  private String subPath = null;

  @Override
  protected void processData()
  {
    contentType = "text/plain";
    FileProvider p = path.getProvider();
    if (p == null) return;
    String sp = path.getSuffix();
    if (!ImageWorker.isImagePath(sp)) return;
    contentType = ImageWorker.mimeFor(sp);
    provider = p;
    subPath = sp;
  }

  public void writeTo(OutputStream out) throws Exception
  {
    if (provider == null) return;
    try (InputStream is = provider.getInputStream(subPath))
    {
      is.transferTo(out);
    }
  }
}
