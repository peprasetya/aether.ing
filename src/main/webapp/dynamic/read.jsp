<%@page import="ing.aether.beans.*" pageEncoding="UTF-8" %><jsp:useBean id="bean" scope="request" class="ing.aether.beans.DocumentBean" /><?xml version="1.0" encoding="UTF-8" ?>
<ajax>
  <replacehtml id="current-filename"><%=bean.getFileName()%></replacehtml>
  <replacehtml id="file-content-buffer"><%=bean.getFileContent()%></replacehtml>
<% if (bean.isImage()) { %>
  <script id="loadScript">
    openImageViewer();
  </script>
<% } else if ("video".equals(bean.getMedia())) { %>
  <script id="loadScript">
    openVideoViewer();
  </script>
<% } else if ("3d".equals(bean.getMedia())) { %>
  <script id="loadScript">
    openModelViewer();
  </script>
<% } else { %>
  <script id="loadScript">
    updateEditorFromBuffer();
  </script>
<% } %>
</ajax>
