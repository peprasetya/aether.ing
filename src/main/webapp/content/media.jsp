<%@ page session="false" trimDirectiveWhitespaces="true"%><jsp:useBean id="bean" scope="request" class="ing.aether.beans.MediaBean" /><%
try
{
  if (bean.isRandomAccess()) response.setHeader("Accept-Ranges", "bytes");
  bean.writeTo(response.getOutputStream());
} catch (Exception e)
{
  // A player that seeks or closes drops the connection mid-stream: that is not a failure worth a stack trace
  System.out.println("WARN: media stream ended early: " + e.getMessage());
}
%>
