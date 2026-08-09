<%@page pageEncoding="UTF-8" %><jsp:useBean id="bean" scope="request" class="ing.aether.beans.BeanObject" /><html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no">
<meta name="application-name" content="Aether">
<title>aether.ing</title>
<%
  java.io.File cssFile = new java.io.File(application.getRealPath("/maintemplate.css"));
  java.io.File jsFile = new java.io.File(application.getRealPath("/scriptlib.js"));
%>
<link rel="stylesheet" href="/maintemplate.css?<%=cssFile.lastModified()%>">
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.5/codemirror.min.css">
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.5/addon/hint/show-hint.min.css">
<script src="/scriptlib.js?<%=jsFile.lastModified()%>"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.5/codemirror.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.5/addon/hint/show-hint.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.5/mode/javascript/javascript.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.5/mode/python/python.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.5/mode/markdown/markdown.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.5/mode/clike/clike.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.5/mode/xml/xml.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.5/mode/css/css.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.5/mode/htmlmixed/htmlmixed.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.5/addon/mode/multiplex.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.5/mode/htmlembedded/htmlembedded.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.5/mode/php/php.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.5/mode/shell/shell.min.js"></script>
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/xterm/5.3.0/xterm.min.css">
<script src="https://cdnjs.cloudflare.com/ajax/libs/xterm/5.3.0/xterm.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/xterm-addon-fit/0.8.0/xterm-addon-fit.min.js"></script>
</head>
<body ontouchstart="" class="<%=bean.getAccount()==null?"logged-out":"logged-in"%>">

<header id="header">
  <div class="brand">
    <svg viewBox="0 0 120 120" class="logo">
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
    </svg> aether.ing
  </div>
  <div class="menu-items">
    <div id="hw-monitor" class="hw-monitor"></div>
    <div class="menu-btn" onclick="toggleProfileMenu(event)">
      <div class="ws-status" id="ws-status">
        <svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 12c2.7 0 5-2.3 5-5s-2.3-5-5-5-5 2.3-5 5 2.3 5 5 5zm0 2c-3.3 0-10 1.7-10 5v3h20v-3c0-3.3-6.7-5-10-5z"/></svg>
      </div>
      <div id="profile-menu-container">
      <div id="profile-dropdown" class="profile-dropdown">
        <% if (bean.isAdmin()) { %>
        <div class="dropdown-item" onclick="showSettings()">Settings</div>
        <% 
          Object[] auths = ing.aether.Portal.getProperties("Authentication");
          if (auths == null || auths.length == 0) { 
        %>
        <div class="dropdown-item" onclick="if(typeof showView === 'function')showView('browser');makeRequest('setup','')">System Setup</div>
        <% } %>
        <% } %>
        <div class="dropdown-item logout" onclick="if(typeof clearSessionState==='function')clearSessionState();else clearPanel(true);makeRequest('logout','',false)">Logout</div>
      </div>
      </div>
    </div>
  </div>
</header>

<div class="workspace" id="workspace">
  <div class="pane" id="pane-files">
    <div class="pane-header">
      <span>Explorer</span>
      <div class="explorer-actions">
        <button class="explorer-action-btn" onclick="createNewFile()" title="New File" id="new-file-btn" disabled>
          <svg viewBox="0 0 24 24"><path d="M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zM6 20V4h7v5h5v11H6z"/></svg>
        </button>
        <button class="explorer-action-btn" onclick="createNewFolder()" title="New Folder" id="new-folder-btn" disabled>
          <svg viewBox="0 0 24 24"><path d="M20 6h-8l-2-2H4c-1.1 0-1.99.9-1.99 2L4 18c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm0 12H6V6h3.17l2 2H20v10z"/></svg>
        </button>
        <button class="explorer-action-btn" onclick="showDialog('provider','')" title="Add Storage Provider" id="add-provider-btn">
          <svg viewBox="0 0 24 24"><path d="M20 13H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1v-6c0-.55-.45-1-1-1zM7 19c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zM20 3H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1V4c0-.55-.45-1-1-1zM7 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z"/></svg>
        </button>
        <button class="explorer-action-btn" onclick="makeRequest('menu','')" title="Refresh Explorer">
          <svg viewBox="0 0 24 24"><path d="M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"/></svg>
        </button>
        <button class="explorer-action-btn hidden" onclick="pasteMovedItem()" title="Paste here" id="paste-btn">
          <svg viewBox="0 0 24 24"><path d="M19 3h-4.18C14.4 1.84 13.3 1 12 1c-1.3 0-2.4.84-2.82 2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-7 0c.55 0 1 .45 1 1s-.45 1-1 1-1-.45-1-1 .45-1 1-1zm7 16H5V5h2v3h10V5h2v14z"/></svg>
        </button>
      </div>
    </div>
    <div class="search-container">
      <input type="text" id="file-search" placeholder="Search files..." class="search-input" onkeydown="if(event.key==='Enter') searchFiles(this.value)">
    </div>
    <div id="move-chip" class="selection-preview hidden">
      <div class="selection-header">
        <span id="move-chip-text">Moving…</span>
        <span class="selection-clear" onclick="cancelMoveItem()" title="Cancel move">✕</span>
      </div>
    </div>
    <nav id="menu" class="file-list"></nav>
  </div>

  <div class="pane" id="pane-editor">
    <div class="pane-header">
      <div class="filename-container">
        <span id="current-filename">Untitled</span>
        <span id="save-status"></span>
      </div>
      <button class="explorer-action-btn" onclick="toggleTerminal()" title="Terminal (SSH projects)" id="terminal-toggle-btn">
        <svg viewBox="0 0 24 24"><path d="M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 14H4V8h16v10zm-13.5-1l4-4-4-4L5 10.5 7.5 13 5 15.5 6.5 17zM12 17h6v-2h-6v2z"/></svg>
      </button>
      <div class="edit-label">Edit</div>
    </div>
    <div id="panel"></div>
    <div id="editor-container" class="hidden"></div>
    <textarea id="file-content-buffer" style="display:none"></textarea>
  </div>

  <div class="pane" id="pane-chat">
    <div class="pane-header">
      <span>AI Assistant</span>
      <div class="header-selectors" id="header-selectors">
        <!-- Only this wrapper is regenerated by menu.jsp; the chat-history menu below must survive that refresh -->
        <div id="agent-select-container">
          <select id="agent-select" class="agent-select" onchange="onAgentChange()"></select>
        </div>
        <div id="session-menu-container">
          <button class="explorer-action-btn" onclick="toggleSessionMenu(event)" title="Chat History">
            <svg viewBox="0 0 24 24"><path d="M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6a7 7 0 1 1 7 7c-1.9 0-3.6-.79-4.83-2.06l-1.42 1.42A8.95 8.95 0 0 0 13 21a9 9 0 0 0 0-18zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z"/></svg>
          </button>
          <div id="session-dropdown" class="session-dropdown">
            <div id="session-list"></div>
            <div class="session-dropdown-footer">
              <button class="p-btn" onclick="clearAllSessions()">Clear All</button>
            </div>
          </div>
        </div>
        <button class="explorer-action-btn" onclick="clearChat()" title="Clear Chat & New Session">
          <svg viewBox="0 0 24 24"><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/></svg>
        </button>
      </div>
    </div>
    <div class="chat-container">
      <div id="todo-card" class="todo-card hidden">
        <div class="todo-header" onclick="toggleTodoCard()">
          <span id="todo-title">📋 Tasks</span>
          <span id="todo-progress"></span>
        </div>
        <div class="todo-items" id="todo-items"></div>
      </div>
      <div class="chat-history" id="chat-history">
        <div class="msg ai">Aether AI initialized.</div>
      </div>
      <div class="chat-controls">
        <div class="chat-selectors" id="ai-selectors">
          <select id="model-select" class="model-select">
            <!-- Models will be populated here -->
          </select>
          <select id="thinking-mode" class="thinking-select">
             <option value="off">Off</option>
             <option value="low">Low</option>
             <option value="medium">Medium</option>
             <option value="high">High</option>
          </select>
        </div>
        <div class="prompt-btns" id="agent-commands">
          <!-- Agent specific buttons will be populated here -->
        </div>
        <div id="selection-preview" class="selection-preview hidden">
          <div class="selection-header">
            <span>Selection</span>
            <span class="selection-clear" onclick="clearEditorSelection()" title="Clear Selection">✕</span>
          </div>
          <div id="selection-text" class="selection-text"></div>
        </div>
        <div id="terminal-attach-chip" class="selection-preview hidden">
          <div class="selection-header">
            <span>Terminal output attached</span>
            <span class="selection-clear" onclick="clearTerminalContext()" title="Remove attachment">✕</span>
          </div>
          <div id="terminal-attach-text" class="selection-text"></div>
        </div>
        <div class="chat-input-area">
          <textarea class="chat-input" id="chat-input" placeholder="Ask AI..."></textarea>
          <button class="send-btn" id="send-btn" onclick="sendChatMessage()">➤</button>
          <button class="send-btn hidden" id="stop-btn" onclick="stopAiActivity()" title="Stop AI">⏹</button>
        </div>
      </div>
    </div>
  </div>

  <div id="terminal">
    <div class="term-header" onclick="toggleTerminal()">
      <span class="prompt-path" id="term-title">Terminal</span>
      <span class="term-actions">
        <label onclick="event.stopPropagation()"><input type="checkbox" id="term-ai-monitor" checked> AI on error</label>
        <button class="explorer-action-btn term-attach-btn" onclick="event.stopPropagation(); attachTerminalContext()" title="Attach last command output to the next chat message">Attach to chat</button>
      </span>
    </div>
    <div class="term-body" id="term-body"></div>
  </div>
</div>

<div id="permission-modal" class="modal hidden">
  <div class="modal-content">
    <div class="modal-header">
      <h3 id="permission-title">🤖 AI Tool Permission</h3>
    </div>
    <div class="modal-body">
      <p id="permission-msg">The AI agent wants to perform an action.</p>
      <div id="permission-details" class="permission-details"></div>
      <input type="text" id="permission-reason" class="review-reason-input" placeholder="Reason if denying (optional, guides the AI)...">
    </div>
    <div class="buttonContainer">
      <button class="cancel-btn" onclick="respondPermission(false)">Deny</button>
      <button class="save-btn" onclick="respondPermission(true)">Allow</button>
    </div>
  </div>
</div>

<div class="mobile-nav-indicator" id="mobile-nav">
  <div class="dot active"></div>
  <div class="dot"></div>
  <div class="dot"></div>
</div>

<footer><div><div>Aether, Intelligent Node Gateway.<br>Licensed under the <a href="LICENSE">MIT License</a>.</div></div></footer>

<!-- Hidden inputs to satisfy scriptlib.js legacy logic if it scans for them -->
<input id="menucb" type="checkbox" style="display:none">
<label id="menubtn" for="menucb" style="display:none"><div></div></label>

<dialog id="new-item-dialog">
  <form method="dialog">
    <h3 id="dialog-title">New File</h3>
    <div class="dialog-body">
      <input type="text" id="new-item-name" placeholder="Name..." class="dialog-input">
    </div>
    <div class="buttonContainer">
      <button type="submit" value="cancel" class="cancel-btn">Cancel</button>
      <button type="submit" value="create" id="new-item-submit">Create</button>
    </div>
  </form>
</dialog>

<dialog id="confirm-dialog">
  <form method="dialog">
    <h3 id="confirm-title">Confirm</h3>
    <div class="dialog-body"><span id="confirm-message"></span></div>
    <div class="buttonContainer">
      <button type="submit" value="cancel" class="cancel-btn" id="confirm-cancel">Cancel</button>
      <button type="submit" value="ok" id="confirm-ok">OK</button>
    </div>
  </form>
</dialog>

<dialog id="dialog">
  <div id="dialog-content"></div>
</dialog>

<div id="notification-container" class="notification-container" popover="manual"></div>

<script id="pageScript">
const workspace = document.getElementById('workspace');
if (workspace) {
  const dots = document.querySelectorAll('.dot');
  workspace.addEventListener('scroll', () => {
    const index = Math.round(workspace.scrollLeft / workspace.clientWidth);
    dots.forEach((d, i) => d.classList.toggle('active', i === index));
  });
}

function toggleProfileMenu(event) {
  event.stopPropagation();
  const dropdown = document.getElementById('profile-dropdown');
  if (dropdown) dropdown.classList.toggle('show');
}

window.addEventListener('click', () => {
  const dropdown = document.getElementById('profile-dropdown');
  if (dropdown) dropdown.classList.remove('show');
});

<% if (bean.getAccount()==null) { %>
makeRequest('welcome','');
<% } else { %>
initAppState();
<% } %>
</script>
</body>
</html>
