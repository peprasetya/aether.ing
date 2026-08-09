<jsp:useBean id="bean" scope="request" class="ing.aether.beans.WelcomeBean" /><?xml version="1.0" ?>
<ajax>
<replacehtml id="menu"></replacehtml>
<replacehtml id="panel">
<%
if (bean.getAccount()==null)
{
%>
<div class="logbuttons" id="logbuttons">
<h1><svg viewBox="0 0 120 120" class="logo">
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
<h3>Intelligent Node Gateway</h3>
<br>
<div class="button" login="G" method="openid"><div style="background-color:#4285f4;border:none;color:#fff;box-shadow:0 2px 4px 0 rgba(0,0,0,.25);box-sizing:border-box;"><div style="border:1px solid transparent;height:100%;width:100%;"><div style="padding:8px;float:left;color:#fff;background-color:#fff;"><svg height="18px" viewBox="0 0 480 480"><path fill="#EA4335" d="M240 95c35 0 67 12 92 36l69-69C359 24 304 0 240 0 146 0 65 54 26 132l80 62C124 137 177 95 240 95z"></path><path fill="#4285F4" d="M470 246c0-16-2-31-4-46H240v90h129c-6 30-23 55-48 72l77 60c45-42 71-104 71-177z"></path><path fill="#FBBC05" d="M106 286c-5-15-8-30-8-46s3-31 8-46l-80-62C9 165 0 201 0 240c0 39 9 75 26 108l80-62z"></path><path fill="#34A853" d="M240 480c65 0 119-21 159-58l-77-60c-22 15-49 23-82 23-63 0-116-42-135-99l-80 62C65 426 146 480 240 480z"></path></svg></div><span style="font-size:16px;color:#fff;display:inline-block;padding:8px;">Login with Google</span></div></div></div>
<div class="info-box">
<div class="info-title">Open Source Application</div>
<div class="info-text">aether.ing is an open source web application. Signing in only shares your name and email address. Access to your Google Drive is requested separately, in a second step, and only when it is actually needed. You will only login on the site if you truly trust the deployer of this application on their premises.</div>
</div>
</div>
<%
} else if (bean.isDriveAuthNeeded())
{
%>
<div class="logbuttons" id="logbuttons">
<h1><svg viewBox="0 0 120 120" class="logo">
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
<h3>One more step</h3>
<br>
<div class="button" login="GD" method="openid"><div style="background-color:#4285f4;border:none;color:#fff;box-shadow:0 2px 4px 0 rgba(0,0,0,.25);box-sizing:border-box;"><div style="border:1px solid transparent;height:100%;width:100%;"><div style="padding:8px;float:left;color:#fff;background-color:#fff;"><svg height="18px" viewBox="0 0 480 480"><path fill="#EA4335" d="M240 95c35 0 67 12 92 36l69-69C359 24 304 0 240 0 146 0 65 54 26 132l80 62C124 137 177 95 240 95z"></path><path fill="#4285F4" d="M470 246c0-16-2-31-4-46H240v90h129c-6 30-23 55-48 72l77 60c45-42 71-104 71-177z"></path><path fill="#FBBC05" d="M106 286c-5-15-8-30-8-46s3-31 8-46l-80-62C9 165 0 201 0 240c0 39 9 75 26 108l80-62z"></path><path fill="#34A853" d="M240 480c65 0 119-21 159-58l-77-60c-22 15-49 23-82 23-63 0-116-42-135-99l-80 62C65 426 146 480 240 480z"></path></svg></div><span style="font-size:16px;color:#fff;display:inline-block;padding:8px;">Authorize Google Drive</span></div></div></div>
<div class="info-box">
<div class="info-title">Why aether needs your Drive</div>
<div class="info-text">aether keeps your projects, files and settings in your own Google Drive - nowhere else. You are signed in as <%=bean.getAccount()%>, but aether does not have access to your Drive yet. You may see a "Google hasn't verified this app" warning; this is expected for self-hosted deployments, and you can continue via Advanced. No data is sent to any third party.</div>
</div>
</div>
<%
} else
{
%>
<div class="loadsign"><div></div>Loading...</div>
<%
}
%>
</replacehtml>
<script id="pageScript">
document.body.classList.remove('logged-in');
document.body.classList.add('logged-out');
document.body.classList.remove('show-footer');
<%
if (bean.getAccount()==null || bean.isDriveAuthNeeded())
{
%>
// Applies to the Drive step too: with no Drive grant there is no reachable file or working
// directory, so drop whatever the URL restored and switch back to the view holding the panel
if (typeof clearSessionState === 'function') clearSessionState(true);
var loginwin;
setMessageListener(function(e)
{
  if (e.data=="checkLogin")
  {
   if (loginwin)
   {
    loginwin.close();
    loginwin=false;
   }
   clearPanel(false);
   initAppState();
  }
});
var logins=document.getElementById('logbuttons').getElementsByTagName("div");
for (var i=0;i<logins.length;i++)
{
 var prov=logins[i].getAttribute('login');
 if (prov)logins[i].addEventListener('click',function(event)
 {
  var top=screen.height/2-355;
  var left=screen.width/2-300;
  loginwin=window.open('/auth'+event.currentTarget.getAttribute('method')+'?provider='+event.currentTarget.getAttribute('login'),'Aether Login','toolbar=no,location=no,directories=no,status=no,menubar=no,scrollbars=no,resizable=no,copyhistory=no,width=600,height=710,top='+top+',left='+left);
 },false);
}
<%
} else
{
  %>
  makeRequest('menu','',true);<%
}
%>

</script>
<command>initMenu</command>
</ajax>