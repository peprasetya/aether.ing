package ing.aether.data;

import java.util.HashMap;
import java.util.Map;

public class FileItem
{
  public static final short TYPEVOLUMES=1;
  public static final short TYPEDIRECTORY=2;
  public static final short TYPEFILE=3;
  public static final short TYPELINK=4;
  
  private String id=null;
  private String name=null;
  private String target=null;
  private String path=null;
  private short type=0;

  // Extended metadata
  private long size=0;
  private String mimeType=null;
  private String providerId=null;
  private String icon=null;
  private String thumbnail=null;
  private Map<String, Object> attributes=new HashMap<>();
  
  public String getId() {return id != null ? id : path;}
  public String getName() {return name;}
  public String getTarget() {return target;}
  public String getPath() {return path;}
  public short getType() {return type;}
  public long getSize() {return size;}
  public String getMimeType() {return mimeType;}
  public String getProviderId() {return providerId;}
  public String getIcon() {return icon;}
  public String getThumbnail() {return thumbnail;}
  public Map<String, Object> getAttributes() {return attributes;}

  public void setId(String id) {this.id = id;}
  public void setPath(String path) {this.path = path;}
  public void setSize(long size) {this.size = size;}
  public void setMimeType(String mimeType) {this.mimeType = mimeType;}
  public void setProviderId(String providerId) {this.providerId = providerId;}
  public void setIcon(String icon) {this.icon = icon;}
  public void setThumbnail(String thumbnail) {this.thumbnail = thumbnail;}
  public void setAttribute(String key, Object value) {this.attributes.put(key, value);}

  public FileItem(String name,String target,String path,short type)
  {
    this.name=name;
    this.target=target;
    this.path=path;
    this.type=type;
  }
  
  public FileItem(String name, String target, String path, short type, long size, String mimeType, String providerId)
  {
    this.name = name;
    this.target = target;
    this.path = path;
    this.type = type;
    this.size = size;
    this.mimeType = mimeType;
    this.providerId = providerId;
  }

  public FileItem(String name, String target, String path, short type, long size, String mimeType, String providerId, String icon, String thumbnail)
  {
    this.name = name;
    this.target = target;
    this.path = path;
    this.type = type;
    this.size = size;
    this.mimeType = mimeType;
    this.providerId = providerId;
    this.icon = icon;
    this.thumbnail = thumbnail;
  }
}
