<%@page import="ing.aether.beans.*" pageEncoding="UTF-8" %><jsp:useBean id="bean" scope="request" class="ing.aether.beans.DocumentBean" /><?xml version="1.0" encoding="UTF-8" ?>
<ajax>
  <replacehtml id="current-filename"><%=bean.getFileName()%></replacehtml>
  <replacehtml id="file-content-buffer"><%=bean.getFileContent()%></replacehtml>
  <script id="loadScript">
    updateEditorFromBuffer();
  </script>
</ajax>
