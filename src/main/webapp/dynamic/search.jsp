<%@page import="ing.aether.beans.*,ing.aether.data.FileItem" pageEncoding="UTF-8" %><jsp:useBean id="bean" scope="request" class="ing.aether.beans.SearchBean" /><?xml version="1.0" encoding="UTF-8" ?>
<ajax>
  <replacehtml id="menu">
    <div style="padding: 10px; font-weight: bold; border-bottom: 1px solid var(--border);">Search Results</div>
<%
  FileItem[] results = bean.getResults();
  if (results != null && results.length > 0) {
    for (FileItem file : results) {
      String providerName = ""; // We should ideally get the display name of the provider
      String icon = (file.getType() == FileItem.TYPEDIRECTORY) ? "📁 " : "📄 ";
%>
<%
    if (file.getType() == FileItem.TYPEFILE) {
%>
    <li class="file-item" data-url="<%=file.getProviderId() + "/" + file.getPath()%>" data-type="<%=file.getType()%>" onclick="treeItemClick(event)">
      <input type="checkbox" class="ctx-toggle" onclick="event.stopPropagation()">
      <span><%=icon%><%=file.getName()%> <small style="opacity:0.6">(<%=file.getProviderId()%>)</small></span>
    </li>
<%
    } else {
%>
    <li class="tree-folder collapsed" data-url="<%=file.getProviderId() + "/" + file.getPath()%>" data-type="<%=file.getType()%>" onclick="treeItemClick(event)">
      <%=icon%><%=file.getName()%> <small style="opacity:0.6">(<%=file.getProviderId()%>)</small>
    </li>
    <ul class="folder-content collapsed"></ul>
<%
    }
    }
  } else {
%>
    <div style="padding: 10px; opacity: 0.6;">No results found.</div>
<%
  }
%>
  </replacehtml>
</ajax>
