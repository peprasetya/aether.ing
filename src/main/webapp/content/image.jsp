<%@ page session="false" trimDirectiveWhitespaces="true"%><jsp:useBean id="bean" scope="request" class="ing.aether.beans.ImageBean" /><%
try
{
  bean.writeTo(response.getOutputStream());
} catch (Exception e)
{
  System.out.println("WARN: image stream failed: " + e.getMessage());
}
%>