<%@page import="ing.aether.beans.*,ing.aether.data.FileItem" pageEncoding="UTF-8" %><jsp:useBean id="bean" scope="request" class="ing.aether.beans.BrowseBean" /><?xml version="1.0" encoding="UTF-8" ?>
<ajax>
<%
String targetId = request.getParameter("targetId");
if (targetId != null) {
  FileItem[] files=bean.listFiles();
%>
  <replacehtml id="<%=targetId%>">
<%
  if (files!=null)for (FileItem file:files)
  {
    short fileType = file.getType();
    String iconPath = file.getIcon();
    String iconHtml = "";
    if (iconPath != null) {
      iconHtml = "<img src=\"" + iconPath + "\" class=\"tree-icon\">";
    } else {
      // Fallback
      if (file.getType() == FileItem.TYPEFILE) iconHtml = "📄 ";
      else iconHtml = "📁 ";
    }
    
    if (fileType == FileItem.TYPEFILE) {
    %>
    <li class="file-item" data-url="<%=file.getPath()%>" data-id="<%=file.getId()%>" data-type="<%=fileType%>" onclick="treeItemClick(event)" title="<%=file.getName()%>">
      <input type="checkbox" class="ctx-toggle" onclick="event.stopPropagation()">
      <span class="tree-file-name"><%=iconHtml%><%=file.getName()%></span>
      <span class="tree-kebab" onclick="event.stopPropagation();showItemMenu(event, this)">⋮</span>
    </li>
    <%
    } else {
      String typeClass = (fileType == FileItem.TYPEVOLUMES) ? "tree-provider" : "tree-folder";
      boolean isDirectory = (fileType == FileItem.TYPEDIRECTORY);
      boolean isPinnable = "true".equals(file.getAttributes().get("isPinnable"));
    %>
    <li class="<%=typeClass%> collapsed" data-url="<%=file.getPath()%>" data-id="<%=file.getId()%>" data-type="<%=fileType%>" data-pinnable="<%=isDirectory && isPinnable%>" onclick="treeItemClick(event)" title="<%=file.getName()%>">
      <span class="tree-folder-name"><%=iconHtml%><%=file.getName()%></span>
      <%if (fileType != FileItem.TYPEVOLUMES) {%><span class="tree-kebab" onclick="event.stopPropagation();showItemMenu(event, this)">⋮</span><%}%>
    </li>
    <ul class="folder-content collapsed"></ul>
<%
    }
  }
%>
  </replacehtml>
<%
}
%>
</ajax>
