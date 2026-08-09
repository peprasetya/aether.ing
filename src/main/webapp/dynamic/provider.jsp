<%@page import="ing.aether.beans.SettingBean" pageEncoding="UTF-8" %><jsp:useBean id="bean" scope="request" class="ing.aether.beans.SettingBean" /><?xml version="1.0" ?>
<ajax>
<replacehtml id="dialog-content">
<div class="modal-header">
  <h3>🗄️ Storage Providers</h3>
  <button type="button" class="close-btn" onclick="this.closest('dialog').close()">×</button>
</div>
<div class="modal-body" id="provider-panel">
  <div class="setup-content">
    <p>Connect external storage to the explorer. Providers are stored in <code>aether.ing.setting.json</code> in your Google Drive &mdash; the server keeps no copy of your settings.</p>

<% if (bean.getMessage() != null) { %>
    <div class="provider-msg <%=bean.isSuccess() ? "ok" : "error"%>"><%=bean.getMessage()%></div>
<% } %>

<% if (bean.getPublicKey() != null) { %>
    <div class="settings-section">
      <h3>Aether Public Key</h3>
      <p class="admin-note">Install this key on your servers (append to <code>~/.ssh/authorized_keys</code>). It is generated once and reused for every server you add.</p>
      <textarea id="aether-pubkey" class="pubkey-box" readonly><%=bean.getPublicKey()%></textarea>
      <div class="pubkey-actions">
        <button type="button" class="button" onclick="copyPubkey()">Copy</button>
        <button type="button" class="button" onclick="downloadPubkey()">Download</button>
      </div>
    </div>
<% } %>

    <div class="settings-section">
      <h3>Connected Providers</h3>
      <ul class="settings-list">
<%
  java.util.List<java.util.Map<String,Object>> provs = bean.getProviders();
  if (provs.isEmpty()) {
%>
        <li><span class="admin-note">No providers yet.</span></li>
<%
  }
  for (java.util.Map<String,Object> p : provs) {
    String pname = String.valueOf(p.get("name"));
%>
        <li>
          <span><code><%=p.get("type")%></code> <%=pname%> &mdash; <%=p.get("user")%>@<%=p.get("host")%>:<%=p.get("port")%></span>
          <button type="button" class="remove-btn" onclick="makeRequest('provider', 'action=removeProvider&value=<%=java.net.URLEncoder.encode(pname, "UTF-8")%>', false)">Remove</button>
        </li>
<%
  }
%>
      </ul>
    </div>

    <div class="settings-section">
      <h3>Add Provider</h3>
      <form action="/provider" method="post" class="formtable" onsubmit="return ajaxSubmit('provider', this, false)">
        <input type="hidden" name="action" value="addProvider" />
        <input type="hidden" name="ajaxcall" value="1" />
        <div class="form-field">
          <label for="providertype">Type:</label>
          <select id="providertype" name="providertype" class="input-field">
            <option value="ssh">SSH / SFTP</option>
          </select>
        </div>
        <div class="form-field">
          <label for="providername">Name:</label>
          <input type="text" id="providername" name="providername" class="input-field" placeholder="My Server" />
        </div>
        <div class="form-field">
          <label for="sshhost">Host:</label>
          <input type="text" id="sshhost" name="sshhost" class="input-field" placeholder="server.example.com" required />
        </div>
        <div class="form-field">
          <label for="sshport">Port:</label>
          <input type="text" id="sshport" name="sshport" class="input-field" value="22" />
        </div>
        <div class="form-field">
          <label for="sshuser">User:</label>
          <input type="text" id="sshuser" name="sshuser" class="input-field" required />
        </div>
        <div class="form-field">
          <label for="sshroot">Root path:</label>
          <input type="text" id="sshroot" name="sshroot" class="input-field" placeholder="/home/user (empty = login directory)" />
        </div>
        <div class="form-field">
          <label for="sshpassword">Password (one-time, optional):</label>
          <input type="password" id="sshpassword" name="sshpassword" class="input-field" autocomplete="new-password" />
          <p class="admin-note">The password is <b>never saved</b>. It is used once to install Aether's public key on the server; all future logins use the key. Leave empty to install the public key yourself.</p>
        </div>
        <div class="form-field">
          <button type="submit" class="button btn-primary">Add Provider</button>
        </div>
      </form>
    </div>
  </div>
</div>
</replacehtml>
<script id="pageScript">
<% if (bean.isProvidersChanged()) { %>
  makeRequest('menu', '', false);
<% } %>
</script>
</ajax>
