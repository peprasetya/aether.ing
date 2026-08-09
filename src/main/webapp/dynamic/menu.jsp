<%@page import="ing.aether.beans.*,ing.aether.data.FileItem,org.eclipse.jetty.util.ajax.JSON,ing.aether.data.*,java.util.Map,java.util.List" pageEncoding="UTF-8" %><jsp:useBean id="bean" scope="request" class="ing.aether.beans.BrowseBean" /><?xml version="1.0" encoding="UTF-8" ?>
<ajax><%String account=bean.getAccount();
  FileItem[] files=bean.listFiles();
  
  JSON json = new JSON();
  String agentsJson = json.toJSON(bean.getAgentList());
  agentsJson = agentsJson.replace("'", "&#39;");
%>
<%-- Replaces only the agent select: targeting the whole header-selectors bar wiped out the
     chat-history button and its dropdown, which live in maintemplate.jsp and are not re-rendered here --%>
<replacehtml id="agent-select-container">
  <select id="agent-select" class="agent-select" onchange="onAgentChange()" data-agents='<%=agentsJson%>'>
    <% for (Map<String, Object> agent : bean.getAgentList()) { %>
      <option value="<%= agent.get("id") %>"><%= agent.get("name") %></option>
    <% } %>
  </select>
</replacehtml>
<replacehtml id="ai-selectors">
  <select id="model-select" class="model-select">
    <% for (String model : bean.getModels()) {
      int sepIdx = model.indexOf("::");
      String label = sepIdx > 0 ? "[" + model.substring(0, sepIdx) + "] " + model.substring(sepIdx + 2) : model;
    %>
      <option value="<%= model %>"><%= label %></option>
    <% } %>
  </select>
  <select id="thinking-mode" class="thinking-select">
     <option value="off">Off</option>
     <option value="low">Low</option>
     <option value="medium">Medium</option>
     <option value="high">High</option>
  </select>
</replacehtml>
<replacehtml id="chat-history">
  <%
     java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm");
     if (bean.getChatHistory().isEmpty()) {
  %>
    <div class="msg ai">Aether AI initialized.</div>
  <% } else { %>
    <% for (Map<String, Object> msg : bean.getChatHistory()) {
         String role = (String)msg.get("role");
         Object timeObj = msg.get("time");
         String time = timeObj != null ? sdf.format(new java.util.Date((Long)timeObj)) : "";
    %>
      <div class="msg <%= role %>">
        <div class="msg-meta">
          <span class="msg-role"><%= "tool".equals(role) ? "🛠️ " + msg.get("tool") : role.toUpperCase() %></span>
          <span class="msg-time"><%= time %></span>
        </div>
        <% if (msg.get("thinking") != null && !((String)msg.get("thinking")).isEmpty()) { %>
          <div class="thinking-block collapsed" onclick="this.classList.toggle('collapsed'); this.querySelector('.thinking-content').style.display = this.classList.contains('collapsed') ? 'none' : '';">
            <div class="thinking-header" onclick="event.stopPropagation(); this.closest('.thinking-block').classList.toggle('collapsed'); this.nextElementSibling.style.display = this.closest('.thinking-block').classList.contains('collapsed') ? 'none' : '';">Thought Process ▾</div>
            <div class="thinking-content" style="display:none;"><%= msg.get("thinking") %></div>
          </div>
        <% } %>
        <div class="msg-content"><%= msg.get("content") %></div>
      </div>
    <% } %>
  <% } %>
</replacehtml>
<replacehtml id="profile-menu-container">
      <div id="profile-dropdown" class="profile-dropdown">
        <% if (bean.isAdmin()) { %>
        <div class="dropdown-item" onclick="showSettings()">Settings</div>
        <div class="dropdown-item" onclick="toggleMonitor()">H/w Monitor</div>
        <% 
          Object[] auths = ing.aether.Portal.getProperties(ing.aether.Portal.PropAuth);
          if (auths == null || auths.length == 0) { 
        %>
        <div class="dropdown-item" onclick="if(typeof showView === 'function')showView('browser');makeRequest('setup','')">System Setup</div>
        <% } %>
        <% } %>
        <div class="dropdown-item logout" onclick="if(typeof clearSessionState==='function')clearSessionState();else clearPanel(true);makeRequest('logout','',false)">Logout</div>
      </div>
</replacehtml>
<% if (!"1".equals(request.getParameter("noTree"))) { %>
<replacehtml id="menu">
  <ul class="tree-root">
<%
  if (files!=null)for (FileItem file:files)
  {
    String icon = "📄 ";
    if (file.getType() == FileItem.TYPEVOLUMES || file.getPath().equals("mydrive") || file.getPath().equals("shared-drives") || file.getPath().equals("shared-with-me") || file.getPath().equals("computers")) 
    {
       String name = file.getName().toLowerCase();
       if (name.contains("my drive")) icon = "👤 ";
       else if (name.contains("shared drive")) icon = "👥 ";
       else if (name.contains("shared with me")) icon = "📩 ";
       else if (name.contains("computer")) icon = "💻 ";
       else if (name.contains("drive")) icon = "☁️ ";
       else icon = "💾 ";
    } else if (file.getType() == FileItem.TYPEDIRECTORY) icon = "📁 ";
    
    if (file.getType() == FileItem.TYPEFILE) {
%>
    <li class="file-item" data-url="<%=file.getPath()%>" data-id="<%=file.getId()%>" data-type="<%=file.getType()%>" onclick="treeItemClick(event)" title="<%=file.getName()%>">
      <input type="checkbox" class="ctx-toggle" onclick="event.stopPropagation()">
      <span class="tree-file-name"><%=icon%><%=file.getName()%></span>
      <span class="tree-kebab" onclick="event.stopPropagation();showItemMenu(event, this)">⋮</span>
    </li>
<%
    } else {
      String typeClass = (file.getType() == FileItem.TYPEVOLUMES) ? "tree-provider" : "tree-folder";
      boolean isDirectory = (file.getType() == FileItem.TYPEDIRECTORY);
      boolean isPinnable = "true".equals(file.getAttributes().get("isPinnable"));
%>
    <li class="<%=typeClass%> collapsed" data-url="<%=file.getPath()%>" data-id="<%=file.getId()%>" data-type="<%=file.getType()%>" data-pinnable="<%=isDirectory && isPinnable%>" onclick="treeItemClick(event)" title="<%=file.getName()%>">
      <span class="tree-folder-name"><%=icon%><%=file.getName()%></span>
      <%if (file.getType() != FileItem.TYPEVOLUMES) {%><span class="tree-kebab" onclick="event.stopPropagation();showItemMenu(event, this)">⋮</span><%}%>
    </li>
    <ul class="folder-content collapsed"></ul>
<%
    }
  }
%>
  </ul>
</replacehtml>
<% } %>
<script id="pageScript">
document.body.classList.remove('logged-out');
document.body.classList.add('logged-in');
document.body.classList.remove('show-footer');

<%
  String wd = bean.getLastWorkingDirectory();
  String lf = bean.getLastFilePath();
  List<String> ep = bean.getLastExpansionParents();
  String epJson = "[]";
  if (ep != null) {
    epJson = new JSON().toJSON(ep);
  }
  Map<String, String> aiCfg = bean.getAiConfig();
  String aiCfgJson = "{}";
  if (aiCfg != null) {
    aiCfgJson = new JSON().toJSON(aiCfg);
  }
%>
(function() {
  if ("<%=wd != null ? wd : ""%>") window.workingDir = "<%=wd != null ? wd : ""%>";
  if ("<%=lf != null ? lf : ""%>") window.openedFile = "<%=lf != null ? lf : ""%>";
  window.expandParents = <%=epJson%>;
  window.aiConfig = <%=aiCfgJson%>;
  if (typeof updateActionButtons === 'function') updateActionButtons();
  if (typeof restoreAIConfig === 'function') restoreAIConfig();
  if (typeof renderChatHistory === 'function') renderChatHistory();
})();

if (typeof initEditor === 'function') initEditor();
</script>
<command>initMenu</command>
</ajax>
