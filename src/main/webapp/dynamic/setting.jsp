<%@page import="ing.aether.beans.SettingBean" pageEncoding="UTF-8" %><jsp:useBean id="bean" scope="request" class="ing.aether.beans.SettingBean" /><?xml version="1.0" ?>
<ajax>
<replacehtml id="dialog-content">
<div class="modal-header">
  <h3>⚙️ Settings</h3>
  <button type="button" class="close-btn" onclick="this.closest('dialog').close()">×</button>
</div>
<div class="modal-body" id="settingPanel">
  <div class="setup-content">
    <p>Configure global application parameters and user access.</p>

<% if (bean.getMessage() != null) { %>
    <div class="provider-msg <%=bean.isSuccess() ? "ok" : "error"%>"><%=bean.getMessage()%></div>
<% } %>
<% if (bean.isAdmin()) { %>
    <div class="settings-section">
      <h3>LLM Providers (OpenAI compatible)</h3>
      <p class="admin-note">Ollama, lemonade, OpenRouter, OpenAI... Models from every provider appear in the model selector as <code>model &middot; provider</code>.</p>
      <ul class="settings-list">
<%
  for (java.util.Map<String, Object> llm : bean.getLlmProviders()) {
    String llmName = String.valueOf(llm.get("Name"));
    Object llmKeyObj = llm.get("ApiKey");
    boolean llmHasKey = llmKeyObj != null && !llmKeyObj.toString().isEmpty();
%>
        <li>
          <span><strong><%=llmName%></strong> &mdash; <%=llm.get("Url")%><%=llmHasKey ? " (key set)" : ""%></span>
          <button type="button" class="remove-btn" onclick="makeRequest('setting', 'action=removeLlmProvider&value=<%=java.net.URLEncoder.encode(llmName, "UTF-8")%>', false)">Remove</button>
        </li>
<%
  }
%>
      </ul>
      <form action="/setting" method="post" class="add-form stacked" onsubmit="return ajaxSubmit('setting', this, false)">
        <input type="hidden" name="action" value="addLlmProvider" />
        <input type="hidden" name="ajaxcall" value="1" />
        <input type="text" name="llmname" class="input-field small" placeholder="Name (e.g. Ollama)" required />
        <input type="text" name="llmurl" class="input-field small" placeholder="http://localhost:11434" required />
        <input type="text" name="llmkey" class="input-field small" placeholder="API key (optional)" />
        <button type="submit" class="button">Add Provider</button>
      </form>
    </div>

    <div class="settings-section">
      <h3>AI Configuration</h3>
      <form action="/setting" method="post" class="formtable" onsubmit="return ajaxSubmit('setting', this, false)">
        <input type="hidden" name="action" value="saveConfig" />
        <input type="hidden" name="ajaxcall" value="1" />
        <div class="form-field">
          <label for="internalmodel">Internal Model (titles, background analysis):</label>
          <select id="internalmodel" name="internalmodel" class="input-field">
<%
  for (String im : bean.getAllModels()) {
    int imSep = im.indexOf("::");
    String imLabel = imSep > 0 ? "[" + im.substring(0, imSep) + "] " + im.substring(imSep + 2) : im;
%>
            <option value="<%=im%>" <%=im.equals(bean.getInternalmodel())?"selected":""%>><%=imLabel%></option>
<%
  }
  if (!bean.getAllModels().contains(bean.getInternalmodel())) {
%>
            <option value="<%=bean.getInternalmodel()%>" selected><%=bean.getInternalmodel()%> (unavailable!)</option>
<%
  }
%>
          </select>
        </div>
        <div class="form-field">
          <label for="searchprovider">Web Search Provider:</label>
          <select id="searchprovider" name="searchprovider" class="input-field">
            <option value="brave" <%="brave".equals(bean.getSearchprovider())?"selected":""%>>Brave Search (free tier: 2,000 queries/month)</option>
            <option value="tavily" <%="tavily".equals(bean.getSearchprovider())?"selected":""%>>Tavily (free tier: ~1,000/month, LLM-optimized)</option>
          </select>
        </div>
        <div class="form-field">
          <label for="searchkey">Search API Key:</label>
          <input type="text" id="searchkey" name="searchkey" class="input-field" value="<%=bean.getSearchkey()%>" placeholder="API key of the selected search provider" />
        </div>
        <div class="form-field">
          <button type="submit" class="button btn-primary">Save AI Config</button>
        </div>
      </form>
    </div>

    <div class="settings-section">
      <h3>Administrators</h3>
      <ul class="settings-list">
<%
  String[] admins = bean.getAdmins();
  for (String admin : admins) {
%>
        <li>
          <span><%=admin%></span>
          <button type="button" class="remove-btn" onclick="makeRequest('setting', 'action=removeAdmin&value=<%=java.net.URLEncoder.encode(admin, "UTF-8")%>', false)">Remove</button>
        </li>
<%
  }
%>
      </ul>
      <form action="/setting" method="post" class="add-form" onsubmit="return ajaxSubmit('setting', this, false)">
        <input type="hidden" name="action" value="addAdmin" />
        <input type="hidden" name="ajaxcall" value="1" />
        <input type="text" name="value" class="input-field small" placeholder="Email address" required />
        <button type="submit" class="button">Add Admin</button>
      </form>
    </div>

    <div class="settings-section">
      <h3>Allowed Users</h3>
      <ul class="settings-list">
<%
  String[] allowed = bean.getAllowedUsers();
  for (String user : allowed) {
%>
        <li>
          <span><%=user%></span>
          <button type="button" class="remove-btn" onclick="makeRequest('setting', 'action=removeAllowed&value=<%=java.net.URLEncoder.encode(user, "UTF-8")%>', false)">Remove</button>
        </li>
<%
  }
%>
      </ul>
      <form action="/setting" method="post" class="add-form" onsubmit="return ajaxSubmit('setting', this, false)">
        <input type="hidden" name="action" value="addAllowed" />
        <input type="hidden" name="ajaxcall" value="1" />
        <input type="text" name="value" class="input-field small" placeholder="Email address" required />
        <button type="submit" class="button">Add User</button>
      </form>
    </div>

    <div class="settings-section">
      <h3>Model Filters (Regex)</h3>
      <p class="admin-note">Allowed users can only use models matching at least one filter. A filter matches the bare model name or the qualified <code>Provider::model</code> id, so <code>^Ollama::</code> allows everything from one provider. Use <code>^[^/]+$</code> for mainstream models.</p>
      <ul class="settings-list">
<%
  String[] filters = bean.getModelFilters();
  for (String filter : filters) {
%>
        <li>
          <code><%=filter%></code>
          <button type="button" class="remove-btn" onclick="makeRequest('setting', 'action=removeFilter&value=<%=java.net.URLEncoder.encode(filter, "UTF-8")%>', false)">Remove</button>
        </li>
<%
  }
%>
      </ul>
      <form action="/setting" method="post" class="add-form" onsubmit="return ajaxSubmit('setting', this, false)">
        <input type="hidden" name="action" value="addFilter" />
        <input type="hidden" name="ajaxcall" value="1" />
        <input type="text" name="value" class="input-field small" placeholder="Regex pattern" required />
        <button type="submit" class="button">Add Filter</button>
      </form>
    </div>
<% } %>
  </div>
</div>
</replacehtml>
<script id="pageScript">
  if (typeof updateActionButtons === 'function') updateActionButtons();
</script>
</ajax>
