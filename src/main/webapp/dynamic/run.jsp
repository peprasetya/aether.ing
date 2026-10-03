<%@page import="org.eclipse.jetty.util.ajax.JSON" pageEncoding="UTF-8" %><jsp:useBean id="bean" scope="request" class="ing.aether.beans.RunBean" /><?xml version="1.0" encoding="UTF-8" ?>
<ajax><%
  JSON json = new JSON();
%>
<script id="pageScript">
openRunDeepLink(<%=json.toJSON(bean.getRunId())%>, <%=json.toJSON(bean.getWorkingDir())%>, <%=json.toJSON(bean.getChatSessionId())%>);
</script>
</ajax>
