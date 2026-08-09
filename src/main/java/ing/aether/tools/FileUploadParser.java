package ing.aether.tools;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;

// A container for a single part of a multipart request.
// This is a simplified version for your specific needs.
public class FileUploadParser
{

  public static class MultipartItem
  {
    private final String fieldName;
    private final String value;
    private final String fileName;
    private final String contentType;
    private final byte[] fileContent;

    public MultipartItem(String fieldName,String value)
    {
      this.fieldName=fieldName;
      this.value=value;
      this.fileName=null;
      this.contentType=null;
      this.fileContent=null;
    }

    public MultipartItem(String fieldName,String fileName,String contentType,byte[] fileContent)
    {
      this.fieldName=fieldName;
      this.fileName=fileName;
      this.contentType=contentType;
      this.fileContent=fileContent;
      this.value=null;
    }

    public boolean isFormField() { return this.fileName == null; }
    public String getFieldName() { return fieldName; }
    public String getValue() { return value; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public byte[] getFileContent() { return fileContent; }
  }

  public static Map<String,List<MultipartItem>> parse(HttpServletRequest request) throws IOException
  {
    Map<String,List<MultipartItem>> items=new HashMap<>();
    String contentType=request.getContentType();
    if (contentType==null||!contentType.toLowerCase().startsWith("multipart/form-data"))
    { return items; }

    String boundary="--"+contentType.substring(contentType.indexOf("boundary=")+9);

    // Get the total request length to check for truncation
    long contentLength = request.getContentLengthLong();

    try (InputStream in=request.getInputStream())
    {
      byte[] boundaryBytes=(boundary).getBytes();
      byte[] requestBody=in.readAllBytes();

      // Check if the received data is smaller than expected.
      // This indicates truncation, likely due to a server-side request size limit.
      if (contentLength != -1 && requestBody.length < contentLength)
      {
        String errorMessage = "CRITICAL FILE UPLOAD TRUNCATION DETECTED: Expected total request size " + contentLength + " bytes, but received only " + requestBody.length + " bytes. This indicates a server-side maximum request size limit has been hit.";
        throw new IOException(errorMessage); // Still throw, so DocumentBean handles the failure.
      }

      int lastIndex=boundaryBytes.length+2; // Start after first boundary
      int currentIndex;

      while ((currentIndex=indexOf(requestBody,boundaryBytes,lastIndex))!=-1)
      {
        byte[] partData=new byte[currentIndex-lastIndex-2]; // -2 for trailing
                                                            // \r\n
        System.arraycopy(requestBody,lastIndex,partData,0,partData.length);
        lastIndex=currentIndex+boundaryBytes.length;

        int headerEndIndex=indexOf(partData,new byte[]{13,10,13,10},0);
        if (headerEndIndex!=-1)
        {
          String headersStr=new String(partData,0,headerEndIndex);
          byte[] content=new byte[partData.length-headerEndIndex-4];
          System.arraycopy(partData,headerEndIndex+4,content,0,content.length);

          String fieldName=getHeaderValue(headersStr,"Content-Disposition","name");
          if (fieldName!=null)
          {
            String fileName=getHeaderValue(headersStr,"Content-Disposition","filename");
            MultipartItem item;
            if (fileName!=null&&!fileName.isEmpty())
            {
              String partContentType=getHeaderValue(headersStr,"Content-Type",null);
              item=new MultipartItem(fieldName,fileName,partContentType,content);
            }else item=new MultipartItem(fieldName,new String(content));
            items.computeIfAbsent(fieldName,k -> new ArrayList<>()).add(item);
          }
        }
      }
    }
    return items;
  }

  private static String getHeaderValue(String headers,String headerName,String paramName)
  {
    for (String line:headers.split("\r\n"))
    {
      if (line.toLowerCase().startsWith(headerName.toLowerCase()+":"))
      {
        if (paramName==null) return line.substring(line.indexOf(":")+1).trim();
        for (String part:line.split(";"))
        {
          part=part.trim();
          if (part.toLowerCase().startsWith(paramName.toLowerCase()+"="))
          { return part.substring(part.indexOf("\"")+1,part.length()-1); }
        }
      }
    }
    return null;
  }

  private static int indexOf(byte[] source,byte[] match,int startIndex)
  {
    for (int i=startIndex;i<=source.length-match.length;i++)
    {
      boolean found=true;
      for (int j=0;j<match.length;j++)
        if (source[i+j]!=match[j])
        {
          found=false;
          break;
        }
      if (found) return i;
    }
    return -1;
  }
}
