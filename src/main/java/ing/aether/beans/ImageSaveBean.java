package ing.aether.beans;

import java.io.OutputStream;
import java.util.concurrent.locks.ReentrantLock;

import ing.aether.CommandRegister;
import ing.aether.data.FileProvider;
import ing.aether.tools.FileUploadParser;
import ing.aether.tools.ai.ImageWorker;

// Binary writes from the image viewer: layer PNGs, merged exports and the "<image>.layers.json"
// sidecar, sent as multipart. Anything that is not an image or a sidecar is refused
@CommandRegister(value=ImageSaveBean.COMMAND, accessType=1, createSession=true, preventCache=true)
public class ImageSaveBean extends BeanObject
{
  public static final String COMMAND = "imagesave";

  @Override
  protected void processData()
  {
    FileProvider provider = path.getProvider();
    String subPath = path.getSuffix();
    if (provider == null || subPath == null || subPath.isEmpty())
    {
      message = "Not saved: no storage found for this path";
      return;
    }
    boolean sidecar = subPath.endsWith(ImageWorker.SIDECAR_SUFFIX);
    if (!sidecar && !ImageWorker.isImagePath(subPath))
    {
      message = "Not saved: only images and layer files can be written here";
      return;
    }
    byte[] data = null;
    if (uploads != null) for (FileUploadParser.MultipartItem u : uploads)
    {
      if (!u.isFormField())
      {
        data = u.getFileContent();
        break;
      }
    }
    if (data == null)
    {
      message = "Not saved: the upload was empty";
      return;
    }

    // Same lock as ImageWorker.appendLayer, keyed by the same "library/path" form DocumentBean records
    ReentrantLock lock = sidecar ? ImageWorker.sidecarLock(path.getLibrary() + "/" + subPath) : null;
    if (lock != null) lock.lock();
    try
    {
      // Layers uploaded from the device go into "<image>.layers/", created on first use
      String parent = ImageWorker.dirOf(subPath);
      if (!parent.isEmpty() && !provider.exists(parent)) provider.mkdir(parent);
      try (OutputStream os = provider.getOutputStream(subPath))
      {
        os.write(data);
      }
      success = true;
    }
    catch (Exception e)
    {
      message = "Not saved " + ImageWorker.nameOf(subPath) + ": " + e.getMessage();
      e.printStackTrace();
    }
    finally
    {
      if (lock != null) lock.unlock();
    }
  }
}
