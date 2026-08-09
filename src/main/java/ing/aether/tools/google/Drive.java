package ing.aether.tools.google;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import org.eclipse.jetty.util.ajax.JSON;

public class Drive
{
  static final String APIDrive="https://www.googleapis.com/drive/v3/";
  static final String APIUpload="https://www.googleapis.com/upload/drive/v3/";
  
  private Token token=null;
  private final JSON json = new JSON();

  private int pageSize=1000;
  private String fileMeta="iconLink,thumbnailLink,id,description,fileExtension,mimeType,name,webContentLink,size,modifiedTime,capabilities,parents";
  
  public Drive(Token token)
  {
    this.token=token;
  }
  
  public void setPageSize(int pageSize)
  {
    this.pageSize=pageSize;
  }
  
  public void setFileMeta(String fileMeta)
  {
    this.fileMeta=fileMeta;
  }
  
  public String about()
  {
    return token.httpGet(APIDrive+"about?fields=*");
  }
  
  public String list(String query,String pageToken)
  {
    return token.httpGet(APIDrive+"files?pageSize="+pageSize+"&fields=nextPageToken,files("+fileMeta+")&supportsAllDrives=true&includeItemsFromAllDrives=true"+(pageToken==null?"":"&pageToken="+pageToken)+(query==null?"":"&q="+Token.encode(query)));
  }

  public String listDrives(String pageToken)
  {
    return token.httpGet(APIDrive+"drives?pageSize=100&fields=nextPageToken,drives(id,name)"+(pageToken==null?"":"&pageToken="+pageToken));
  }
  
  public String getMetadata(String fileId)
  {
    return token.httpGet(APIDrive+"files/"+fileId+"?fields="+fileMeta+"&supportsAllDrives=true&includeItemsFromAllDrives=true");
  }

  public String createFile(String name, String mimeType, String parentId)
  {
    String meta = "{\"name\":\"" + name + "\",\"mimeType\":\"" + mimeType + "\"" + (parentId != null ? ",\"parents\":[\"" + parentId + "\"]" : "") + "}";
    return token.httpPost(APIDrive + "files?supportsAllDrives=true", meta, Token.ContentTypeJSON);
  }

  public String createFileWithContent(String name, String mimeType, String parentId, byte[] content)
  {
    String res = createFile(name, mimeType, parentId);
    if (res == null) return null;
    try {
        Map<String, Object> map = (Map<String, Object>) json.parse(new org.eclipse.jetty.util.ajax.JSON.ReaderSource(new java.io.StringReader(res)));
        String id = (String) map.get("id");
        if (id != null && content != null) {
            upload(id, content, mimeType);
        }
    } catch (Exception e) { e.printStackTrace(); }
    return res;
  }

  public String mkdir(String name, String parentId)
  {
    return createFile(name, "application/vnd.google-apps.folder", parentId);
  }

  public String delete(String fileId)
  {
    // Need a specialized delete in Token or use httpPost with method override if supported, 
    // but Token.httpGet/Post are limited. I'll add httpDelete to Token.
    return token.httpDelete(APIDrive + "files/" + fileId + "?supportsAllDrives=true");
  }

  public String rename(String fileId, String newName)
  {
    String meta = "{\"name\":\"" + newName + "\"}";
    return token.httpPatch(APIDrive + "files/" + fileId + "?supportsAllDrives=true", meta, Token.ContentTypeJSON);
  }

  public String move(String fileId, String oldParentId, String newParentId)
  {
    return token.httpPatch(APIDrive + "files/" + fileId + "?supportsAllDrives=true&addParents=" + newParentId + "&removeParents=" + oldParentId, "{}", Token.ContentTypeJSON);
  }

  public String copy(String fileId, String newName, String parentId)
  {
    String meta = "{\"name\":\"" + newName + "\"" + (parentId != null ? ",\"parents\":[\"" + parentId + "\"]" : "") + "}";
    return token.httpPost(APIDrive + "files/" + fileId + "/copy?supportsAllDrives=true", meta, Token.ContentTypeJSON);
  }

  public InputStream download(String fileId) throws Exception
  {
    String url = APIDrive + "files/" + fileId + "?alt=media&supportsAllDrives=true";
    HttpURLConnection urlConn = (HttpURLConnection) new URL(url).openConnection();
    urlConn.setRequestMethod("GET");
    String bearer = token.getAccessToken();
    if (bearer != null) urlConn.setRequestProperty("Authorization", "Bearer " + bearer);
    return urlConn.getInputStream();
  }

  public InputStream download(String fileId, long offset, long length) throws Exception
  {
    String url = APIDrive + "files/" + fileId + "?alt=media&supportsAllDrives=true";
    HttpURLConnection urlConn = (HttpURLConnection) new URL(url).openConnection();
    urlConn.setRequestMethod("GET");
    String bearer = token.getAccessToken();
    if (bearer != null) urlConn.setRequestProperty("Authorization", "Bearer " + bearer);
    urlConn.setRequestProperty("Range", "bytes=" + offset + "-" + (offset + length - 1));
    return urlConn.getInputStream();
  }
  
  // Basic upload (small files)
  public String upload(String fileId, byte[] content, String mimeType)
  {
    return token.httpPatch(APIUpload + "files/" + fileId + "?uploadType=media&supportsAllDrives=true", content, mimeType);
  }

}
