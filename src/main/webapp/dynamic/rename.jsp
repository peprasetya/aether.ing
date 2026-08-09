<%@page pageEncoding="UTF-8" %><jsp:useBean id="bean" scope="request" class="ing.aether.beans.FileOpBean" /><?xml version="1.0" encoding="UTF-8" ?>
<ajax>
<% if (bean.isSuccess()) { %>
  <script>
    makeRequest('menu', '', false);
  </script>
<% } %>
</ajax>
