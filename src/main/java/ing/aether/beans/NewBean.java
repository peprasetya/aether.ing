package ing.aether.beans;

import ing.aether.CommandRegister;
import ing.aether.data.FileProvider;

@CommandRegister(value="new", accessType=1, createSession=true, preventCache=true)
public class NewBean extends BeanObject
{
  private String name;
  private String type;

  public void setName(String name)
  {
    this.name = name;
  }

  public void setType(String type)
  {
    this.type = type;
  }

  @Override
  protected void processData()
  {
    if (name == null || type == null) return;

    FileProvider provider = path.getProvider();
    if (provider == null) return;

    try
    {
      String subPath = path.getSuffix();
      String newPath = subPath.isEmpty() ? name : subPath + "/" + name;

      if ("file".equals(type)) provider.createFile(newPath);
      else if ("folder".equals(type)) provider.mkdir(newPath);
      
      success = true;
      message = (type.equals("file") ? "File" : "Folder") + " created: " + name;
    } catch (Exception e)
    {
      success = false;
      message = "Error creating " + type + ": " + e.getMessage();
      e.printStackTrace();
    }
  }
}
