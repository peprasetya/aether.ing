<jsp:useBean id="bean" scope="request" class="ing.aether.beans.AuthOpenIDBean" /><%
%><html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1" />
<link rel="stylesheet" href="/maintemplate.css" />
<script>
// Login already succeeded server-side by the time this page renders; all that remains
// is telling the app tab. Never let one failing channel stop the others.
try
{
  if (window.opener && !window.opener.closed) window.opener.postMessage('checkLogin', '<%=bean.getReferrer().replace("'","%27")%>');
}catch (e) {}
try
{
  localStorage.setItem('aetherLoginPing', Date.now() + '-' + Math.random());
}catch (e) {}

// Closing is refused for plain tabs on iOS, so show a way out if we survive the attempt.
setTimeout(function()
{
  try { window.close(); } catch (e) {}
  setTimeout(function()
  {
    var done = document.getElementById('signed-in');
    if (done) done.style.display = 'block';
  }, 400);
}, 100);
</script>
</head>
<body>
  <div id="signed-in" style="display:none; text-align:center; padding: 40px 20px; margin: auto;">
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
    <h3>Signed in</h3>
    <p>You can close this tab and return to aether.</p>
    <br>
    <div class="button btn-primary" onclick="window.location.href='/'" style="padding: 12px 30px; font-size: 1.1rem;">Continue here</div>
  </div>
</body>
</html>
