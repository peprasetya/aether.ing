<%@page pageEncoding="UTF-8" %><jsp:useBean id="bean" scope="request" class="ing.aether.beans.ImageSaveBean" /><?xml version="1.0" encoding="UTF-8" ?>
<ajax>
<% if (bean.getMessage() != null) { %>
  <message><%= bean.getMessage().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") %></message>
<% } %>
  <result><%= bean.isSuccess() ? "ok" : "error" %></result>
</ajax>
