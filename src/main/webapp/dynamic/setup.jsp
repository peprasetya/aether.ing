<%@page pageEncoding="UTF-8" %><jsp:useBean id="bean" scope="request" class="ing.aether.beans.SetupBean" /><?xml version="1.0" ?>
<ajax>
<replacehtml id="profile-menu-container">
      <div id="profile-dropdown" class="profile-dropdown">
        <% if (bean.isAdmin()) { %>
        <div class="dropdown-item" onclick="showSettings()">Settings</div>
        <% 
          Object[] auths = ing.aether.Portal.getProperties(ing.aether.Portal.PropAuth);
          if (auths == null || auths.length == 0) { 
        %>
        <div class="dropdown-item" onclick="if(typeof showView === 'function')showView('browser');makeRequest('setup','')">System Setup</div>
        <% } %>
        <% } %>
        <div class="dropdown-item logout" onclick="if(video && video.src)video.removeAttribute('src');clearPanel(true);makeRequest('logout','',false)">Logout</div>
      </div>
</replacehtml>
<replacehtml id="panel">
<div class="content-box" id="setupPanel">
<%
if (bean.isShowSetup()) {
%>
  <div class="setup-content" id="setupForm">
    <h1 id="pageTitle">Setup Required</h1>
    <p id="setupDescription">Configure Google OpenID for authentication.</p>
    <div id="setupFormContent">
    <h3>Google OpenID Setup</h3>
    <p>Follow these steps to configure Google Authentication:</p>
<%
String httpsUrl = bean.getServerUrl().replaceFirst("http://", "https://");
String redirectUri = httpsUrl + "/authopenid";
%>
    <ol class="steps-list">
      <li>Go to the <a href="https://console.cloud.google.com/apis/credentials" target="_blank">Google Cloud Console</a></li>
      <li>Create a new OAuth 2.0 Client ID or use an existing one</li>
      <li>Set the Authorized JavaScript origins to:<br><code id="originUrl"><%=httpsUrl%></code> <button class="copy-btn" data-copy="<%=httpsUrl%>" title="Copy">📋</button></li>
      <li>Set the Authorized redirect URIs to:<br><code id="redirectUri"><%=redirectUri%></code> <button class="copy-btn" data-copy="<%=redirectUri%>" title="Copy">📋</button></li>
      <li>Copy the Client ID and Client Secret below</li>
      <li>Enable Google Drive API: Go to <strong>APIs & Services</strong> > <strong>Library</strong>, search for "Google Drive API" and enable it</li>
      <li>In <strong>Data Access</strong>, add the scopes: <code>openid</code>, <code>email</code>, <code>profile</code> and <code>https://www.googleapis.com/auth/drive</code></li>
      <li>Signing in only requests name and email. Drive access is requested in a separate second step, shown once per account, so users see the unverified-app warning only when Drive is actually needed.</li>
    </ol>
    <form action="/setup" method="post" class="formtable">
      <input type="hidden" name="action" value="test" />
      <input type="hidden" name="ajaxcall" value="1" />
      <div class="form-field">
        <label for="clientid">Google Client ID:</label>
        <input type="text" id="clientid" name="clientid" class="input-field" value="<%=bean.getClientid() != null ? bean.getClientid() : ""%>" />
      </div>
      <div class="form-field">
        <label for="secret">Google Client Secret:</label>
        <input type="text" id="secret" name="secret" class="input-field" value="<%=bean.getSecret() != null ? bean.getSecret() : ""%>" />
      </div>
      <div class="form-field">
        <div>
          <div class="button btn-primary" id="testLoginBtn">Test Login</div>
          <p class="admin-note">Successful test login will make this account the Administrator.</p>
        </div>
      </div>
      <div></div>
    </form>
    <div class="info-box">
      <h4 id="appInfoTitle">About this Application</h4>
      <p id="appInfoText">This application stores your user preferences in Google Drive. It is open source and connects to your private AI LLM server for intelligence features. No data is sent to any public third-party server.</p>
      <p id="appInfoWarning">You may see a "Google hasn't verified this app" warning during your first login - this is expected for self-hosted applications using custom domains, as Google's verification process requires public app registration. You can proceed by clicking <strong>Advanced</strong> and then <strong>Go to (app name) (unsafe)</strong>.</p>
    </div>
    </div>
    <div id="prerequisitesSection" class="prerequisites-box" style="display:none;">
      <h4 id="prerequisitesTitle">Prerequisites Required</h4>
      <p id="prerequisitesDescription">Before setup, configure HTTPS with a domain name.</p>
      <div class="info-box">
        <h4>Current Server</h4>
        <p>URL: <strong><%=bean.getServerUrl()%></strong></p>
      </div>
      <div class="info-box">
        <h4>Setup Reverse Proxy</h4>
        <p>Use Apache, Nginx, or Caddy to get HTTPS with domain.</p>
        <p>Caddyfile example:</p>
        <pre class="code-block">yourdomain.com {
  reverse_proxy localhost:<%=bean.getServerPort()%>
}</pre>
      </div>
    </div>
  </div>
<%
}
%>
  <div id="loggedPage" style="display:none; text-align:center; padding: 40px 0;">
    <h1 id="loggedTitle"><svg viewBox="0 0 120 120" class="logo">
      <g stroke="currentColor" stroke-width="6" stroke-linecap="round">
        <line x1="20" y1="20" x2="60" y2="40"></line>
        <line x1="60" y1="40" x2="40" y2="80"></line>
        <line x1="40" y1="80" x2="80" y2="80"></line>
        <line x1="80" y1="80" x2="100" y2="40"></line>
        <line x1="100" y1="40" x2="60" y2="20"></line>
      </g>
      <g fill="currentColor">
        <circle cx="20" cy="20" r="6"></circle>
        <circle cx="60" cy="20" r="6"></circle>
        <circle cx="100" cy="40" r="6"></circle>
        <circle cx="80" cy="80" r="6"></circle>
        <circle cx="40" cy="80" r="6"></circle>
        <circle cx="60" cy="50" r="9"></circle>
      </g>
    </svg> aether.ing</h1>
    <h3 id="loggedSubTitle">Intelligent Node Gateway</h3>
    <br><br>
    <div class="button btn-primary" onclick="window.location.href='/'" style="padding: 12px 30px; font-size: 1.1rem;">Enter aether</div>
    <span id="loggedEmail" style="display:none;"></span>
  </div>
</div>
</replacehtml>
<script id="pageScript">
var isSecure = window.location.protocol === 'https:';
var isIpOnly = <%=bean.isIpOnly()%>;
var showPrereq = true;
var isSetupComplete = <%=!bean.isShowSetup() || bean.getAccount() != null%>;
var accountEmail = '<%=bean.getAccount() != null ? bean.getAccount().replace("'", "\\'") : ""%>';
var title = document.getElementById('pageTitle');
var desc = document.getElementById('setupDescription');
var prerequisitesSection = document.getElementById('prerequisitesSection');
var setupFormContent = document.getElementById('setupFormContent');
var infoBoxTitle = document.getElementById('appInfoTitle');
var infoBoxText = document.getElementById('appInfoText');
var infoBoxWarning = document.getElementById('appInfoWarning');

// When accessing via IP+HTTP, hide form content and show prerequisites message
if (!isSecure && isIpOnly) {
  if (setupFormContent) setupFormContent.style.display = 'none';
  if (prerequisitesSection) {
    prerequisitesSection.style.display = 'block';
    if (title) title.textContent = 'Prerequisites Required';
    if (desc) desc.textContent = 'Before setup, configure HTTPS with a domain name.';
  }
  if (infoBoxTitle) infoBoxTitle.textContent = 'Current Server';
  if (infoBoxText) infoBoxText.innerHTML = 'URL: <strong><%=bean.getServerUrl()%></strong>';
} else if (isSecure) {
  showPrereq = false;
  if (title) title.textContent = 'Setup Required';
  if (desc) desc.textContent = 'Configure Google OpenID for authentication.';
  if (prerequisitesSection) prerequisitesSection.style.display = 'none';
} else {
  if (title) title.textContent = 'Prerequisites Required';
  if (desc) desc.textContent = 'Before setup, configure HTTPS with a domain name.';
  if (infoBoxTitle) infoBoxTitle.textContent = 'Current Server';
  if (infoBoxText) infoBoxText.innerHTML = 'URL: <strong><%=bean.getServerUrl()%></strong>';
}

if (isSetupComplete) {
  document.body.classList.remove('show-footer');
  if (title) title.textContent = 'Setup Complete';
  if (desc) desc.textContent = 'Google Authentication is already configured. Only administrators can access this page.';
  if (setupFormContent) setupFormContent.style.display = 'none';
  var loggedPage = document.getElementById('loggedPage');
  if (loggedPage) loggedPage.style.display = 'block';
  var loggedEmail = document.getElementById('loggedEmail');
  if (loggedEmail) loggedEmail.textContent = accountEmail;
} else if (!showPrereq) {
  document.body.classList.add('show-footer');
  if (prerequisitesSection) prerequisitesSection.style.display = 'none';
  infoBoxTitle.textContent = 'About this Application';
  infoBoxText.textContent = 'This application stores your user preferences in Google Drive. It is open source and connects to your private AI LLM server for intelligence features. No data is sent to any public third-party server.';
  infoBoxWarning.textContent = "You may see a \"Google hasn't verified this app\" warning during your first login - this is expected for self-hosted applications using custom domains, as Google's verification process requires public app registration. You can proceed by clicking Advanced and then Go to (app name) (unsafe).";
  infoBoxWarning.style.display = 'block';
} else {
  document.body.classList.remove('show-footer');
  document.getElementById('appInfoWarning').style.display = 'none';
}

document.querySelectorAll('.copy-btn').forEach(function(btn) {
  btn.addEventListener('click', function() {
    navigator.clipboard.writeText(this.getAttribute('data-copy'));
    var orig = this.textContent;
    this.textContent = '✓';
    setTimeout(function() { this.textContent = orig; }.bind(this), 1200);
  });
});

var loginwin;
var testBtn = document.getElementById('testLoginBtn');
if (testBtn) testBtn.addEventListener('click', function() {
  // Save configuration via AJAX in the background
  ajaxSubmit('setup', this.closest('form'), false);

  // Open the login window directly (direct user gesture for Safari)
  var top = screen.height / 2 - 355;
  var left = screen.width / 2 - 300;
  loginwin = window.open('/authopenid?provider=G', 'Aether Login', 'toolbar=no,location=no,directories=no,status=no,menubar=no,scrollbars=no,resizable=no,copyhistory=no,width=600,height=710,top=' + top + ',left=' + left);
});

setMessageListener(function(e) {
  if (e.data == "checkLogin") {
    if (loginwin) {
      loginwin.close();
      loginwin = false;
    }
    makeRequest('setup', '');
  }
});
</script>
<command>initMenu</command>
</ajax>
