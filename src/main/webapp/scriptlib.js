

let menu,menucb,menubtn,panel;

function makeRequest(url,data,savingState,callback) 
{
  var httpRequest;

  if (window.XMLHttpRequest) 
  {
    httpRequest = new XMLHttpRequest();
    if (httpRequest.overrideMimeType) 
    {
      httpRequest.overrideMimeType('text/xml');
    }
  }

  if (!httpRequest) 
  {
    // Native on purpose: without XMLHttpRequest the app cannot run at all, so the styled dialog is not dependable here
    alert('Please use a modern browser!');
    return false;
  }
  httpRequest.onreadystatechange=function()
  {
    if (httpRequest.readyState == 4)
    {
      alertContents(httpRequest);
      if (typeof callback === 'function') callback(httpRequest);
    }
  };
  var saveState=savingState;
  if (history)
  {
    if (saveState && history.state && history.state.url && history.state.data) if (history.state.url==url && history.state.data==data) saveState=false;
    if (saveState) history.replaceState({url:url,data:data}, "", "/" + url);
  }
  if(!menu)menu=document.getElementById('menu');
  if (menu)
  {
   var mItems=menu.getElementsByTagName('li');
   for (var i=0,ni=mItems.length;i<ni;i++)
   {
    var page=mItems[i].getAttribute('page');
    if (page)mItems[i].classList.toggle("select",page==url);
   }
  }
  if(!menucb)menucb=document.getElementById('menucb');
  if (menucb)menucb.checked=false;
  httpRequest.open('POST','/'+url+'?ajaxcall=1', true);
  httpRequest.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
  httpRequest.send(data);
}

function ajaxSubmit(url,form,saveState)
{
  var eleme=form.elements;
  var data='';
  for (var i=0;i<eleme.length;i++)
  {
    var item=eleme[i];
    if (item.tagName=='INPUT')
    {
      if (item.type=='checkbox')
      {
        if (item.checked && item.name!='')data+=item.name+'='+encodeURIComponent(item.value)+'&';
      } else if (item.name!='')
        data+=item.name+'='+encodeURIComponent(item.value)+'&';
      if (item.type=='password')item.value='';
    } else if (item.tagName=='SELECT')
    {
      if (item.name!='')
        data+=item.name+'='+encodeURIComponent(item.value)+'&';
    } else if (item.tagName=='TEXTAREA')
    {
    	data+=item.name+'='+encodeURIComponent(item.value)+'&';
    }
  }
  makeRequest(url,data,saveState);
  return false;
}

function checkExpansion(root) {
  const expandTarget = window.expandTarget;
  const workingDir = window.workingDir;
  const openedFile = currentFileUrl || window.openedFile;
  const expandParents = window.expandParents || [];
  
  if (!expandTarget && !workingDir && !openedFile && expandParents.length === 0) return;

  const items = root.querySelectorAll('.tree-folder, .tree-provider, .file-item');
  items.forEach(el => {
    const url = el.dataset.url;
    const itemId = el.dataset.id || url; 
    if (!url) return;

    // Apply highlight if this is the working directory
    if ((url === workingDir || itemId === workingDir) && !el.classList.contains('file-item')) {
      el.classList.add('selected');
    }

    // Apply highlight if this is the opened file
    if ((url === openedFile || itemId === openedFile) && el.classList.contains('file-item')) {
      el.classList.add('opened');
    }

    // Check if this item is in the expandParents list (explicit hierarchy from server)
    // Normalize by stripping trailing slashes for robust matching
    const itemIdNorm = itemId.endsWith('/') ? itemId.slice(0, -1) : itemId;
    const urlNorm = url.endsWith('/') ? url : url; // url is already handled by startsWith logic usually

    const isExplicitParent = expandParents.some(p => {
      const pNorm = p.endsWith('/') ? p.slice(0, -1) : p;
      return pNorm === itemIdNorm || pNorm === url;
    });

    // Fallback expansion for parent of the expandTarget or openedFile (prefix matching)
    // Normalize for prefix check (ensure trailing slash)
    const normalizedUrl = url.endsWith('/') ? url : url + '/';
    const normalizedId = itemId.endsWith('/') ? itemId : itemId + '/';
    
    const isTargetParent = expandTarget && 
                           (expandTarget.startsWith(normalizedUrl) || expandTarget.startsWith(normalizedId)) &&
                           (expandTarget.length > url.length || expandTarget.length > itemId.length);

    const isOpenedParent = openedFile && 
                           (openedFile.startsWith(normalizedUrl) || openedFile.startsWith(normalizedId)) &&
                           (openedFile.length > url.length || openedFile.length > itemId.length);
    
    if ((isExplicitParent || isTargetParent || isOpenedParent) && el.classList.contains('collapsed')) {
      console.log("DEBUG: Expanding item:", itemId, "isExplicitParent:", isExplicitParent, "isTargetParent:", isTargetParent);
      const content = el.nextElementSibling;
      if (content && (content.classList.contains('folder-content') || content.tagName == 'UL')) {
        const tempId = content.id || 'tree-' + Math.random().toString(36).substr(2, 9);
        content.id = tempId;
        el.classList.add('loading');
        makeRequest('tree/' + itemId, 'targetId=' + tempId, false);
        el.classList.remove('collapsed');
        content.classList.remove('collapsed');
      }
    }
  });
}

function showNotification(message, duration = 3000) {
  const container = document.getElementById('notification-container');
  if (!container) return;

  const notification = document.createElement('div');
  notification.className = 'notification';
  notification.textContent = message;

  container.appendChild(notification);

  // Re-raise into the top layer so the toast paints above any modal dialog opened
  // since the last notification. hide+show re-adds it as the topmost top-layer element.
  if (typeof container.showPopover === 'function')
  {
    try { container.hidePopover(); } catch (e) {}
    try { container.showPopover(); } catch (e) {}
  }

  setTimeout(() => {
    notification.classList.add('fade-out');
    notification.addEventListener('animationend', () => {
      notification.remove();
      // Leave the top layer once the last toast is gone
      if (!container.firstChild && typeof container.hidePopover === 'function')
      {
        try { container.hidePopover(); } catch (e) {}
      }
    });
  }, duration);
}

function alertContents(httpRequest) 
{
  var statdiv=document.getElementById("logstat");
  if (httpRequest.readyState == 4) 
  {
    if (httpRequest.status == 200) 
    {
      if (statdiv) statdiv.innerHTML='';
      var xd=httpRequest.responseXML;
      console.log("DEBUG: AJAX response received", xd);
      
      var newMsgs=xd.getElementsByTagName('message');
      if (newMsgs.length>0)
      {
        var newMsg=newMsgs.item(0).firstChild.data;
        if (newMsg === "Saved successfully") {
          setSaveStatus('saved');
        } else {
          showNotification(newMsg);
        }
      }

      var newHTMLs=xd.getElementsByTagName('replacehtml');
      console.log("DEBUG: Found " + newHTMLs.length + " <replacehtml> tags");
      
      const replacedElements = [];
      if (newHTMLs.length>0)
      for (var i=0;i<newHTMLs.length;i++)//>
      {
        var item=newHTMLs.item(i);
        var replacedId=item.getAttribute('id');
        var tagChange=document.getElementById(replacedId);
        console.log("DEBUG: Replacing HTML for ID: " + replacedId + (tagChange ? " (Found)" : " (NOT FOUND)"));
        if (tagChange)
        {
          var content = "";
          if (item.firstChild && (item.firstChild.nodeType === 3 || item.firstChild.nodeType === 4)) {
            content = item.firstChild.nodeValue;
          } else {
            content = item.textContent || item.text || "";
          }
          
          console.log("DEBUG: Processing ID: " + replacedId + ", Content length: " + content.length);
          if (replacedId === 'file-content-buffer') {
            console.log("DEBUG: Setting textarea value for file-content-buffer");
            tagChange.value = content;
            const filenameEl = document.getElementById('current-filename');
            if (filenameEl) filenameEl.classList.remove('loading');
          } else {
            tagChange.innerHTML = content;
            replacedElements.push(tagChange);
          }
          
          // Remove loading state from parent folder/provider
          const parentItem = tagChange.previousElementSibling;
          if (parentItem && parentItem.classList.contains('loading')) {
            parentItem.classList.remove('loading');
          }
        }
      }
      if (window.sr)window.sr.sync();
      var newCmds=xd.getElementsByTagName('command');
      if (newCmds.length>0)
      for (var i=0;i<newCmds.length;i++)
      {
       var item=newCmds.item(i).firstChild.data;
       if ('refresh'==item) window.location.href=window.location.href;
       if ('home'==item) window.location.href='/';
       if ('historyback'==item) window.history.back();
       else if ('initMenu'==item)
       {
    	 initMenu();
       } else if ('initAIUI'==item)
       {
         if (typeof onAgentChange === 'function') onAgentChange();
       } else if ('initList'==item && initList)initList();
      }
      var newScripts=xd.getElementsByTagName('script');
      if (newScripts.length>0)
      for (var i=0;i<newScripts.length;i++)
      {
       var item=newScripts.item(i);
       var scriptId=item.getAttribute('id');
       if (scriptId)
       {
        var scp=document.getElementById(scriptId);
        if (scp)scp.parentNode.removeChild(scp);
       }
       var scrp=document.createElement("script");
       if (scriptId)scrp.id=scriptId;
       var src=item.getAttribute('src');
       if (src) scrp.src=src;
       else scrp.innerHTML=newScripts.item(i).textContent;
       document.body.appendChild(scrp); 
      }
      
      // Perform expansion checks AFTER scripts have potentially set window.expandTarget
      replacedElements.forEach(el => {
        if (el.id === 'menu' || el.id.startsWith('tree-')) {
          checkExpansion(el);
        }
      });
      
    } else 
    {
//    alert('There was a problem with the request.');
    }
  } else if (statdiv)
  {
    if (httpRequest.readyState == 0)
    {
      statdiv.innerHTML="Ready.";
    } else if (httpRequest.readyState == 1) 
    {
      statdiv.innerHTML="Connecting...";
    } else if (httpRequest.readyState == 2) 
    {
      statdiv.innerHTML="Connected.";
    } else if (httpRequest.readyState == 3) 
    {
      statdiv.innerHTML="Transfering...";
    }
  }
}

var timeoutHanler=[];
var intervalHandler=[];

function clearTimer()
{
 while (timeoutHanler.length>0)clearTimeout(timeoutHanler.pop());
 while (intervalHandler.length>0)clearInterval(intervalHandler.pop());
}

function toggleMenu()
{
 if (menu && menu.classList.contains("floatmenu"))
  menu.classList.toggle("close");
}

function showMenu(show)
{
 if (menu && menu.classList.contains("floatmenu"))
  menu.classList.toggle("close",!show);
}

function stateChange(event)
{
 makeRequest(event.state.url,event.state.data,false);
}

function clearPanel(loading)
{
  if(!panel)return;
  while (panel.firstChild)panel.removeChild(panel.lastChild);
  if (loading)panel.innerHTML='<div class="loadsign"><div></div>Loading...</div>';
}

function clearSessionState(keepPanel)
{
  if (editor) editor.setValue('');
  const filenameEl = document.getElementById('current-filename');
  if (filenameEl)
  {
    filenameEl.textContent = 'Untitled';
    filenameEl.classList.remove('loading');
  }
  setSaveStatus('');
  currentFileUrl = null;
  window.openedFile = null;
  window.workingDir = null;
  window.expandTarget = null;
  showView('browser');
  if (!keepPanel) clearPanel(false);
}

let messageListener=false;
let lastLoginSignal=0;

function setMessageListener(fnc)
{
 messageListener=fnc;
}

// The auth popup reports success by postMessage, but iOS Safari drops window.opener
// across the cross-origin trip through Google, so it also writes a same-origin storage
// ping that reaches every other tab regardless. Either channel may arrive (or both),
// and the handler must run only once.
function signalLoginCheck()
{
 const now=Date.now();
 if (now-lastLoginSignal<3000) return;
 lastLoginSignal=now;
 if (messageListener)messageListener({data:'checkLogin'});
}

window.addEventListener("DOMContentLoaded",function()
{
 window.addEventListener('touchstart', function(e)
 {
  var touch = e.touches[0];
  window.touchStartEdge = (touch.clientX < 20 || touch.clientX > window.innerWidth - 20);
 }, { passive: true });
 window.addEventListener('touchmove', function(e)
 {
  if (window.touchStartEdge) e.preventDefault();
 }, { passive: false });
 if (history) history.pushState(null, "", window.location.href);
 window.addEventListener('popstate', function()
 {
  if (history) history.pushState(null, "", window.location.href);
 });
 window.addEventListener('message',function(e)
 {
  if (!e.origin.includes(window.location.hostname)) return;
  if (e.data=='checkLogin') signalLoginCheck();
  else if (messageListener)messageListener(e);
 },false);
 window.addEventListener('storage',function(e)
 {
  if (e.key=='aetherLoginPing' && e.newValue) signalLoginCheck();
 },false);
 header=document.getElementById('header');
 panel=document.getElementById('panel');
});
function renderChatHistory()
{
  const history = document.getElementById('chat-history');
  if (!history) return;
  const messages = history.querySelectorAll('.msg');
  messages.forEach(msg => {
    const contentDiv = msg.querySelector('.msg-content');
    if (contentDiv && !msg.dataset.rendered)
    {
      const raw = contentDiv.innerHTML;
      msg.dataset.raw = raw;
      contentDiv.innerHTML = mdToHtml(raw);
      msg.dataset.rendered = "true";
    }
    const thinkDiv = msg.querySelector('.thinking-content');
    if (thinkDiv && !msg.dataset.renderedThinking)
    {
      thinkDiv.innerHTML = mdToHtml(thinkDiv.innerHTML);
      msg.dataset.renderedThinking = "true";
    }
    
    // Add copy button to meta if missing
    const meta = msg.querySelector('.msg-meta');
    if (meta && !meta.querySelector('.copy-msg-btn'))
    {
      const copyBtn = document.createElement('button');
      copyBtn.className = 'copy-msg-btn';
      copyBtn.title = 'Copy Message';
      copyBtn.textContent = '📋';
      copyBtn.onclick = function(e) {
        e.stopPropagation();
        let content = msg.dataset.raw || (contentDiv ? contentDiv.innerText : "");
        let role = msg.classList.contains('user') ? 'user' : 'ai';
        let textToCopy = content;
        if (role === 'user')
        {
          const prefixMatch = content.match(/^\*\*(.*?)\*\*: /);
          if (prefixMatch) textToCopy = content.substring(prefixMatch[0].length);
        }
        navigator.clipboard.writeText(textToCopy).then(() => {
          showNotification("Copied to clipboard");
        });
      };
      meta.appendChild(copyBtn);
    }
  });
}

function initMenu()
{
 if(!menu)menu=document.getElementById('menu');
 if(!menu)return;
 if(!menubtn)menubtn=document.getElementById('menubtn');
 var lis=menu.getElementsByTagName("li");
 if (lis.length>0)
 {
  if (menubtn)menubtn.style.display="";
  for (var i=0;i<lis.length;i++)
  {
   var page=lis[i].getAttribute('page');
   if (!page)continue;
   lis[i].addEventListener('click',function(event)
   {
    webDisconnect();
    var pagt=event.currentTarget.getAttribute('page');
    if (pagt=="logout") clearSessionState();
    else clearPanel(true);
    clearTimer();
    makeRequest(pagt,'',pagt!="logout");
    },false);
  }
 } else
 {
  if (menubtn)menubtn.style.display="none";
 }
 
 const agentSelect = document.getElementById('agent-select');
 if (agentSelect && agentSelect.dataset.agents) 
 {
  try 
  {
    aiAgents = JSON.parse(agentSelect.dataset.agents);
  } catch (e) 
  {
    console.log('Error parsing AI agents data');
  }
 }
 if (typeof onAgentChange === 'function') onAgentChange();
 restoreAIConfig();
 renderChatHistory();
 if (typeof requestChatSyncIfEmpty === 'function') requestChatSyncIfEmpty();
}


var ws,wsTarget,wsOpenCallBack,wsMessageCallBack,wsCloseCallBack,reconnect,wsFailCount;

function webSocketInit(target,openCallback,messageCallback,closeCallback)
{
  wsTarget=target;
  wsOpenCallBack=openCallback;
  wsMessageCallBack=messageCallback;
  wsCloseCallBack=closeCallback;
  wsFailCount=0;
  webConnect();
}

function webConnect()
{
 if (!window.WebSocket)if(!window.MozWebSocket)showAlert("WebSocket is not supported by this browser.");
 if (ws)
 {
  if (ws.readyState === 0 || ws.readyState === 1) return;
 }
 var port=document.location.port;
 var location=document.location.protocol.replace('http','ws')+"//"+document.location.hostname+":"+port+"/socket/"+wsTarget;
 if (ws)
 {
  if (ws.readyState === 1) ws.close();
  else if (ws.readyState === 0) ws.close();
  ws = null;
 }
 reconnect=true;
 if (window.WebSocket)ws=new WebSocket(location);
 else if (window.MozWebSocket)ws=new MozWebSocket(location);
 if (ws)
 {
  ws.onopen=function(ev){wsFailCount=0;if(wsOpenCallBack)wsOpenCallBack();};
  ws.onmessage=wsMessageCallBack;
  ws.onclose=wsClose;
  ws.onerror=wsClose;
 }
}

function webDisconnect()
{
 if (ws)
 {
  if (ws.readyState === 1) ws.close();
  else if (ws.readyState === 0) ws.close();
  ws = null;
 }
 reconnect=false;
}

function wsClose(event)
{
 if (event.target==ws)
 {
  ws=false;
  if (wsCloseCallBack)wsCloseCallBack();
  if (reconnect)
  {
   wsFailCount++;
   if (wsFailCount<5) setTimeout(webConnect,1000);
   else setTimeout(webConnect,15000);
  }
 }
}

let editor;
let ghostMarker = null;

function initEditor() {
  const container = document.getElementById('editor-container');
  if (!container || editor) return;
  editor = CodeMirror(container, {
    mode: "markdown",
    theme: "aether",
    lineNumbers: true,
    extraKeys: {
      "Ctrl-Space": "autocomplete",
      "Tab": handleTabKey
    }
  });
  editor.on('change', triggerAutoSave);
  editor.on('cursorActivity', updateSelectionPreview);
}

let lastHadSelection = false;
function updateSelectionPreview() {
  const preview = document.getElementById('selection-preview');
  const textEl = document.getElementById('selection-text');
  if (!preview || !textEl || !editor) return;

  const selection = editor.getSelection();
  if (selection && selection.trim().length > 0) {
    textEl.textContent = selection;
    preview.classList.remove('hidden');
  } else {
    preview.classList.add('hidden');
  }

  // Re-render command buttons only when selection presence flips, not on every cursor move
  const hasSelection = !!(selection && selection.trim().length > 0);
  if (hasSelection !== lastHadSelection)
  {
    lastHadSelection = hasSelection;
    renderAgentCommands();
  }
}

function clearEditorSelection() {
  if (!editor) return;
  editor.setSelection(editor.getCursor());
  updateSelectionPreview();
}

function handleTabKey(cm) {
  if (cm.state.currentGhostText && ghostMarker) {
    const cursor = cm.getCursor();
    cm.replaceRange(cm.state.currentGhostText, cursor);
    clearGhostText();
  } else {
    return CodeMirror.Pass;
  }
}

function clearGhostText() {
  if (ghostMarker) { ghostMarker.clear(); ghostMarker = null; }
  if (editor) editor.state.currentGhostText = null;
}

function insertPrompt(text) {
  const chatInput = document.querySelector('.chat-input');
  if (chatInput) chatInput.value = text;
}

function toggleFolder(element) {
  element.classList.toggle('collapsed');
  const content = element.nextElementSibling;
  if (content && (content.classList.contains('folder-content') || content.tagName == 'UL')) {
    content.classList.toggle('collapsed');
  }
}

function searchFiles(query) {
  if (!query) return makeRequest('menu', '', false);
  makeRequest('search', 'q=' + encodeURIComponent(query), false);
}

function copyPubkey()
{
  var el = document.getElementById('aether-pubkey');
  if (!el) return;
  el.select();
  if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(el.value);
  else document.execCommand('copy');
  showNotification('Public key copied to clipboard');
}

function downloadPubkey()
{
  var el = document.getElementById('aether-pubkey');
  if (!el) return;
  var blob = new Blob([el.value + '\n'], { type: 'text/plain' });
  var a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'aether.pub';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(a.href);
}

let lastClickTime = 0;
let clickTimer = null;

let saveTimeout = null;
const AUTO_SAVE_DELAY = 2000; // 2 seconds
let lastSavedContent = null; // baseline: content as of last successful save (mirrors server bufferedContent)
let lastSavedFileUrl = null; // file the baseline belongs to

// FNV-1a 32-bit; must stay identical to fnv1a() in SaveBean.java
function fnv1aHash(str)
{
  var hash = 0x811c9dc5;
  for (var i = 0; i < str.length; i++)
  {
    hash ^= str.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193);
  }
  return hash >>> 0;
}

// Single contiguous changed region between two strings (common prefix/suffix scan)
function computeSplice(oldStr, newStr)
{
  var oldLen = oldStr.length, newLen = newStr.length;
  var minLen = Math.min(oldLen, newLen);
  var start = 0;
  while (start < minLen && oldStr.charCodeAt(start) === newStr.charCodeAt(start)) start++;
  var suffix = 0;
  while (suffix < minLen - start && oldStr.charCodeAt(oldLen - 1 - suffix) === newStr.charCodeAt(newLen - 1 - suffix)) suffix++;
  return { start: start, deleteCount: oldLen - start - suffix, text: newStr.substring(start, newLen - suffix) };
}

function isSaveSuccess(httpRequest)
{
  try
  {
    var msgs = httpRequest.responseXML.getElementsByTagName('message');
    return msgs.length > 0 && msgs.item(0).firstChild.data === "Saved successfully";
  } catch (e) { return false; }
}

function setSaveStatus(status) {
  const el = document.getElementById('save-status');
  if (el) {
    if (status === 'saved') el.textContent = '(saved)';
    else if (status === 'unsaved') el.textContent = '(unsaved)';
    else if (status === 'saving') el.textContent = '(saving...)';
    else el.textContent = '';
  }
}

function autoSave(callback) {
  if (!editor || !currentFileUrl) {
    if (typeof callback === 'function') callback();
    return;
  }
  
  const content = editor.getValue();
  setSaveStatus('saving');

  if (lastSavedContent !== null && lastSavedFileUrl === currentFileUrl)
  {
    if (content === lastSavedContent)
    {
      setSaveStatus('saved');
      if (typeof callback === 'function') callback();
      return;
    }
    var splice = computeSplice(lastSavedContent, content);
    var data = 'patchstart=' + splice.start + '&patchdelete=' + splice.deleteCount +
               '&baselen=' + lastSavedContent.length + '&basehash=' + fnv1aHash(lastSavedContent) +
               '&content=' + encodeURIComponent(splice.text);
    makeRequest('save/' + currentFileUrl, data, false, (req) => {
      if (isSaveSuccess(req))
      {
        lastSavedContent = content;
        if (typeof callback === 'function') callback();
      }
      else fullSave(content, callback); // server buffer out of sync — resend everything once
    });
    console.log("Auto-saving (delta):", currentFileUrl, splice.deleteCount + " deleted, " + splice.text.length + " inserted");
    return;
  }

  fullSave(content, callback);
}

function fullSave(content, callback)
{
  // Capture the target now: the user may switch files before the response arrives
  var url = currentFileUrl;
  makeRequest('save/' + url, 'content=' + encodeURIComponent(content), false, (req) => {
    if (isSaveSuccess(req)) { lastSavedContent = content; lastSavedFileUrl = url; }
    if (typeof callback === 'function') callback();
  });
  console.log("Auto-saving (full):", url);
}

function triggerAutoSave() {
  setSaveStatus('unsaved');
  if (saveTimeout) clearTimeout(saveTimeout);
  saveTimeout = setTimeout(autoSave, AUTO_SAVE_DELAY);
}

function updateActionButtons() {
  const newFileBtn = document.getElementById('new-file-btn');
  const newFolderBtn = document.getElementById('new-folder-btn');
  const canCreate = window.workingDir || currentFileUrl;
  if (newFileBtn) newFileBtn.disabled = !canCreate;
  if (newFolderBtn) newFolderBtn.disabled = !canCreate;
}

function createNewFile() {
  showNewItemDialog('file');
}

function createNewFolder() {
  showNewItemDialog('folder');
}

function showNewItemDialog(type) {
  const dialog = document.getElementById('new-item-dialog');
  const title = document.getElementById('dialog-title');
  const input = document.getElementById('new-item-name');
  const submit = document.getElementById('new-item-submit');
  if (!dialog || !input || !title) return;

  title.textContent = type === 'file' ? 'New File' : 'New Folder';
  if (submit) submit.textContent = 'Create';
  input.value = '';
  dialog.showModal();
  setTimeout(() => input.focus(), 10);

  const onClose = () => {
    dialog.removeEventListener('close', onClose);
    if (dialog.returnValue === 'create') {
      let name = input.value.trim();
      if (!name) return;
      
      if (type === 'file' && !name.includes('.')) name += '.md';

      let parentUrl = window.workingDir;
      if (currentFileUrl) {
        const lastSlash = currentFileUrl.lastIndexOf('/');
        if (lastSlash !== -1) parentUrl = currentFileUrl.substring(0, lastSlash);
      }
      
      if (!parentUrl) return;
      makeRequest('new/' + parentUrl, 'name=' + encodeURIComponent(name) + '&type=' + type, false);
    }
  };
  dialog.addEventListener('close', onClose);
}

// ===== In-app confirm/alert, replacing the native dialogs =====
// Native confirm()/alert() block the whole page, are unstyled and look foreign on mobile.
// These are asynchronous, so callers pass a callback instead of branching on a return value.

function showConfirm(message, onConfirm, okLabel, title)
{
  const dialog = document.getElementById('confirm-dialog');
  const msgEl = document.getElementById('confirm-message');
  const titleEl = document.getElementById('confirm-title');
  const okBtn = document.getElementById('confirm-ok');
  const cancelBtn = document.getElementById('confirm-cancel');
  // No dialog in the DOM (e.g. a partial page): fall back rather than silently doing nothing
  if (!dialog || !msgEl || !okBtn)
  {
    if (confirm(message) && onConfirm) onConfirm();
    return;
  }

  msgEl.textContent = message;
  if (titleEl) titleEl.textContent = title || 'Confirm';
  okBtn.textContent = okLabel || 'OK';
  if (cancelBtn) cancelBtn.classList.remove('hidden');

  const onClose = () => {
    dialog.removeEventListener('close', onClose);
    if (dialog.returnValue === 'ok' && onConfirm) onConfirm();
  };
  dialog.addEventListener('close', onClose);
  dialog.showModal();
}

function showAlert(message, title)
{
  const dialog = document.getElementById('confirm-dialog');
  const msgEl = document.getElementById('confirm-message');
  const titleEl = document.getElementById('confirm-title');
  const okBtn = document.getElementById('confirm-ok');
  const cancelBtn = document.getElementById('confirm-cancel');
  if (!dialog || !msgEl || !okBtn)
  {
    alert(message);
    return;
  }

  msgEl.textContent = message;
  if (titleEl) titleEl.textContent = title || 'Notice';
  okBtn.textContent = 'OK';
  // An alert has nothing to cancel
  if (cancelBtn) cancelBtn.classList.add('hidden');

  const onClose = () => {
    dialog.removeEventListener('close', onClose);
    if (cancelBtn) cancelBtn.classList.remove('hidden');
  };
  dialog.addEventListener('close', onClose);
  dialog.showModal();
}

// ===== File management kebab menu: rename / move / delete =====

function showItemMenu(event, kebabEl) {
  event.stopPropagation();
  closeItemMenu();
  const li = kebabEl.closest('li');
  if (!li) return;
  const itemPath = li.dataset.url;
  const itemName = li.getAttribute('title') || itemPath.substring(itemPath.lastIndexOf('/') + 1);

  const menu = document.createElement('div');
  menu.id = 'item-menu';
  menu.className = 'item-menu';

  // Replaces the old ✏️ pin: only pinnable directories can become the working directory
  if (li.dataset.pinnable === 'true') {
    const setWd = document.createElement('div');
    setWd.className = 'dropdown-item';
    setWd.textContent = 'Set Work Dir';
    setWd.onclick = function() { closeItemMenu(); selectWorkingDirectory(li); };
    menu.appendChild(setWd);
  }

  const rename = document.createElement('div');
  rename.className = 'dropdown-item';
  rename.textContent = 'Rename';
  rename.onclick = function() { closeItemMenu(); showRenameDialog(itemPath, itemName); };
  menu.appendChild(rename);

  const move = document.createElement('div');
  move.className = 'dropdown-item';
  move.textContent = 'Move';
  move.onclick = function() { closeItemMenu(); startMoveItem(itemPath, itemName); };
  menu.appendChild(move);

  const del = document.createElement('div');
  del.className = 'dropdown-item';
  del.textContent = 'Delete';
  del.onclick = function() {
    closeItemMenu();
    showConfirm('Delete "' + itemName + '"?', function() { makeRequest('deleteitem/' + itemPath, '', false); }, 'Delete');
  };
  menu.appendChild(del);

  document.body.appendChild(menu);
  menu.classList.add('show');
  // Keep the popup inside the viewport: rows near the bottom or right edge would otherwise
  // open a menu that is partly unreachable, which is most visible on a phone
  const rect = kebabEl.getBoundingClientRect();
  const menuRect = menu.getBoundingClientRect();
  let top = rect.bottom;
  let left = rect.left;
  if (top + menuRect.height > window.innerHeight) top = Math.max(0, rect.top - menuRect.height);
  if (left + menuRect.width > window.innerWidth) left = Math.max(0, window.innerWidth - menuRect.width - 8);
  menu.style.top = top + 'px';
  menu.style.left = left + 'px';
}

function closeItemMenu() {
  const existing = document.getElementById('item-menu');
  if (existing) existing.remove();
}

// Capture phase: tree rows call stopPropagation() in treeItemClick, so a bubbling listener
// never sees a tap on another row and the menu could only be dismissed by picking an entry
document.addEventListener('click', function(e)
{
  if (e.target.closest && e.target.closest('#item-menu')) return;
  closeItemMenu();
}, true);

document.addEventListener('keydown', function(e)
{
  if (e.key === 'Escape') closeItemMenu();
});

function showRenameDialog(path, currentName) {
  const dialog = document.getElementById('new-item-dialog');
  const title = document.getElementById('dialog-title');
  const input = document.getElementById('new-item-name');
  const submit = document.getElementById('new-item-submit');
  if (!dialog || !input || !title) return;

  title.textContent = 'Rename';
  if (submit) submit.textContent = 'Rename';
  input.value = currentName;
  dialog.showModal();
  setTimeout(() => input.select(), 10);

  const onClose = () => {
    dialog.removeEventListener('close', onClose);
    if (dialog.returnValue === 'create') {
      const newName = input.value.trim();
      if (!newName || newName === currentName) return;
      makeRequest('rename/' + path, 'newname=' + encodeURIComponent(newName), false);
    }
  };
  dialog.addEventListener('close', onClose);
}

// Move via cut/paste (mobile-friendly: no drag-and-drop needed). Cut marks an item, then the user
// navigates to the destination folder (via the existing working-directory pin) and taps Paste.
window.cutItem = null;

function startMoveItem(path, name) {
  window.cutItem = { path: path, name: name };
  const chip = document.getElementById('move-chip');
  const chipText = document.getElementById('move-chip-text');
  if (chipText) chipText.textContent = 'Moving: ' + name;
  if (chip) chip.classList.remove('hidden');
  const pasteBtn = document.getElementById('paste-btn');
  if (pasteBtn) pasteBtn.classList.remove('hidden');
}

function cancelMoveItem() {
  window.cutItem = null;
  const chip = document.getElementById('move-chip');
  if (chip) chip.classList.add('hidden');
  const pasteBtn = document.getElementById('paste-btn');
  if (pasteBtn) pasteBtn.classList.add('hidden');
}

function pasteMovedItem() {
  if (!window.cutItem || !window.workingDir) return;
  const destination = window.workingDir + '/' + window.cutItem.name;
  makeRequest('movefile/' + window.cutItem.path, 'destination=' + encodeURIComponent(destination), false);
  cancelMoveItem();
}

let currentFileUrl = null;

function isSupportedFile(filename) {
  if (!filename) return false;
  const supportedExtensions = [
    'py', 'java', 'js', 'ts', 'jsx', 'tsx', 'css', 'scss', 'less', 'html', 'htm', 'jsp', 'php', 'asp', 'aspx',
    'sh', 'bat', 'c', 'cpp', 'h', 'hpp', 'cs', 'go', 'rs', 'rb', 'pl', 'pm', 'csv', 'json', 'xml', 'txt', 'md', 'markdown',
    'sql', 'yaml', 'yml', 'properties', 'conf', 'ini', 'gradle', 'dockerfile', 'gitignore', 'env'
  ];
  const lastDot = filename.lastIndexOf('.');
  if (lastDot === -1) return true; // Files without extension are treated as text/supported
  const ext = filename.substring(lastDot + 1).toLowerCase();
  return supportedExtensions.includes(ext);
}

function treeItemClick(event) {
  event.stopPropagation();
  const el = event.currentTarget;
  const url = el.dataset.url;
  const type = el.dataset.type;
  const now = Date.now();
  
  if (type == 1 || type == 2) { // Volume or Directory
    if (now - lastClickTime < 400) { // Double click detected
      if (clickTimer) {
        clearTimeout(clickTimer);
        clickTimer = null;
      }
      // Prevent default double-tap behavior (like zoom) and word selection
      if (event.preventDefault) event.preventDefault();
      if (window.getSelection) window.getSelection().removeAllRanges();

      // Providers (type 1) are storage roots, not projects: only a directory can be the working directory
      if (type == 2) selectWorkingDirectory(el);
      lastClickTime = 0;
      return;
    }
    
    lastClickTime = now;
    clickTimer = setTimeout(() => {
      // Single click action: toggle expansion
      const content = el.nextElementSibling;
      const itemId = el.dataset.id || url; // Use unique ID for the request
      if (content && (content.classList.contains('folder-content') || content.tagName == 'UL')) {
        if (el.classList.contains('collapsed')) {
          const tempId = content.id || 'tree-' + Math.random().toString(36).substr(2, 9);
          content.id = tempId;
          el.classList.add('loading');
          makeRequest('tree/' + itemId, 'targetId=' + tempId, false);
        }
        toggleFolder(el);
      }
      clickTimer = null;
    }, 400);
    
  } else if (type == 3) { // File
    const name = el.getAttribute('title') || '';
    if (!isSupportedFile(name)) {
      showNotification("File type unsupported: " + name);
      return;
    }

    const statusEl = document.getElementById('save-status');
    const isUnsaved = statusEl && statusEl.textContent === '(unsaved)';
    
    if (isUnsaved || saveTimeout) {
      if (saveTimeout) clearTimeout(saveTimeout);
      saveTimeout = null;
      autoSave(() => openFileAction(url, el));
    } else {
      openFileAction(url, el);
    }
  }
}

function openFileAction(url, el) {
  currentFileUrl = url;
  window.openedFile = url;
  updateActionButtons();
  
  // Update highlighting
  document.querySelectorAll('.file-item.opened').forEach(item => {
    item.classList.remove('opened');
  });
  el.classList.add('opened');

  showView('editor');
  
  // IMMEDIATELY show loading state and clear content
  const filenameEl = document.getElementById('current-filename');
  if (filenameEl) {
    const span = el.querySelector('span');
    const name = span ? span.textContent.substring(span.innerHTML.indexOf('>') + 1).trim() : 'Unknown';
    filenameEl.textContent = name;
    filenameEl.classList.add('loading');
  }

  if (editor) {
    editor.off('change', triggerAutoSave);
    editor.setValue('');
    editor.on('change', triggerAutoSave);
    setSaveStatus('');
  }
  lastSavedContent = null;
  lastSavedFileUrl = null;

  // 1. Construct the combined human-readable URL for the address bar
  let combinedUrl = 'read/' + url;
  if (window.workingDir) {
    combinedUrl = 'tree/' + window.workingDir + '/read/' + url;
  }
  
  // 2. Update the Browser URL manually
  if (history) history.replaceState({url: combinedUrl, data:''}, "", "/" + combinedUrl);  

  // 3. Fetch the file content via its simple path
  makeRequest('read/' + url, null, false);
}

function selectWorkingDirectory(el) {
  // Guard for every entry point: a provider is a storage root, never a project working directory
  if (el.dataset.type == 1) return;
  const url = el.dataset.url;
  const itemId = el.dataset.id || url;
  
  // Remove selection from others
  document.querySelectorAll('.tree-folder.selected, .tree-provider.selected').forEach(item => {
    item.classList.remove('selected');
  });
  // Select this one
  el.classList.add('selected');
  window.workingDir = itemId; 
  updateActionButtons();
  console.log("Working Directory selected:", url, "ID:", itemId);
  
  // Update browser URL while preserving current file if any
  let combinedUrl = 'tree/' + itemId;
  if (currentFileUrl) combinedUrl += '/read/' + currentFileUrl;
  if (history) history.replaceState({url: combinedUrl, data:''}, "", "/" + combinedUrl);

  const content = el.nextElementSibling;
  const callback = () => {
    // Refresh menu (AI pane) WITHOUT clobbering the tree explorer
    makeRequest('menu', 'noTree=1', false);
  };
  
  if (content && (content.classList.contains('folder-content') || content.tagName == 'UL')) {
    const tempId = content.id || 'tree-' + Math.random().toString(36).substr(2, 9);
    content.id = tempId;
    el.classList.add('loading');
    // Use targetId to make it surgical - only update THIS folder
    makeRequest('tree/' + itemId, 'targetId=' + tempId + '&setWD=1', false, callback);
    if (el.classList.contains('collapsed')) toggleFolder(el);
  } else {
    makeRequest('tree/' + itemId, 'setWD=1', false, callback);
  }
}

function getSelectedContextFiles() {
  const checked = document.querySelectorAll('.ctx-toggle:checked');
  const files = [];
  checked.forEach(cb => {
    const li = cb.closest('li');
    if (li && li.dataset.type == 3) {
      files.push(li.dataset.url);
    }
  });
  return files;
}

function showView(view) {
  const workspace = document.getElementById('workspace');
  const panel = document.getElementById('panel');
  const editorCont = document.getElementById('editor-container');

  if (panel) panel.classList.toggle('hidden', view !== 'browser');
  if (editorCont) editorCont.classList.toggle('hidden', view !== 'editor');

  if (view === 'editor') {
    initEditor();
    if (editor) {
      setTimeout(() => {
        editor.refresh();
        editor.focus();
      }, 10);
    }
    const editorPane = document.getElementById('pane-editor');
    if (workspace && editorPane && window.innerWidth <= 768) {
      workspace.scrollTo({
        left: editorPane.offsetLeft,
        behavior: 'smooth'
      });
    }
  }
}

function showSettings() {
  showDialog('setting', '');
}

function showDialog(url, data) {
  const dialog = document.getElementById('dialog');
  const content = document.getElementById('dialog-content');
  if (!dialog || !content) return;
  
  content.innerHTML = '<div class="loadsign"><div></div>Loading...</div>';
  dialog.showModal();
  makeRequest(url, data, false);
}
function updateEditorFromBuffer() {
  console.log("DEBUG: updateEditorFromBuffer() started");
  const buffer = document.getElementById('file-content-buffer');
  if (!buffer) { console.log("DEBUG: buffer element not found"); return; }
  if (!editor) { console.log("DEBUG: editor instance not found"); return; }

  let content = buffer.value;
  console.log("DEBUG: Buffer content length:", content.length);
  buffer.value = '';

  // Determine mode from filename
  const filenameEl = document.getElementById('current-filename');
  const filename = filenameEl ? filenameEl.textContent.toLowerCase() : "";
  console.log("DEBUG: Filename for mode detection:", filename);
  
  let mode = "markdown";
  let isCoding = false;
  let indentUnit = 2;

  try {
    if (filename.endsWith('.py')) { mode = "python"; isCoding = true; }
    else if (filename.endsWith('.java')) { mode = "text/x-java"; isCoding = true; }
    else if (filename.endsWith('.js')) { mode = "javascript"; isCoding = true; }
    else if (filename.endsWith('.css')) { mode = "css"; isCoding = true; }
    else if (filename.endsWith('.html') || filename.endsWith('.htm') || filename.endsWith('.jsp') || 
             filename.endsWith('.php') || filename.endsWith('.asp') || filename.endsWith('.aspx')) {
      mode = "htmlmixed";
      isCoding = true;
      if (content.length < 500000) content = formatXml(content); // Only beautify if not too huge
    }
    else if (filename.endsWith('.sh')) { mode = "shell"; isCoding = true; }
    else if (filename.endsWith('.php')) { mode = "application/x-httpd-php"; isCoding = true; }
    else if (filename.endsWith('.c') || filename.endsWith('.cpp')) { mode = "text/x-csrc"; isCoding = true; }
    else if (filename.endsWith('.csv')) { mode = "text/plain"; isCoding = true; }
    else if (filename.endsWith('.json')) {
      mode = {name: "javascript", json: true};
      isCoding = true;
      try { 
        if (content.length < 500000) content = JSON.stringify(JSON.parse(content), null, 2); 
      } catch (e) { console.log("DEBUG: JSON parse failed", e); }
    } else if (filename.endsWith('.xml')) {
      mode = "xml";
      isCoding = true;
      if (content.length < 500000) content = formatXml(content);
    } else if (filename.endsWith('.txt') || filename.endsWith('.md')) { mode = "markdown"; isCoding = false; }

    console.log("DEBUG: Setting editor options - mode:", mode, "isCoding:", isCoding);
    editor.setOption("mode", mode);
    editor.setOption("lineNumbers", isCoding);
    editor.setOption("lineWrapping", !isCoding);
    editor.setOption("indentUnit", indentUnit);
    editor.setOption("tabSize", indentUnit);
    
    console.log("DEBUG: Calling editor.setValue()");
    editor.off('change', triggerAutoSave);
    editor.setValue(content);
    editor.setCursor(editor.lineCount(), 0);
    editor.on('change', triggerAutoSave);
    if (saveTimeout) { clearTimeout(saveTimeout); saveTimeout = null; }
    lastSavedContent = content;
    lastSavedFileUrl = currentFileUrl;
    setSaveStatus(''); // Clear status on load
    setTimeout(() => {
      editor.refresh();
      editor.focus();
    }, 10);
    console.log("DEBUG: updateEditorFromBuffer() finished successfully");
  } catch (err) {
    console.error("DEBUG: Error in updateEditorFromBuffer():", err);
  }
}

function formatXml(xml) {
  let formatted = '';
  let reg = /(>)(<)(\/*)/g;
  xml = xml.replace(reg, '$1\r\n$2$3');
  let pad = 0;
  xml.split('\r\n').forEach(function(node) {
    let indent = 0;
    if (node.match( /.+<\/\w[^>]*>$/ )) indent = 0;
    else if (node.match( /^<\/\w/ )) {
      if (pad !== 0) pad -= 1;
    } else if (node.match( /^<\w[^>]*[^\/]>.*$/ )) indent = 1;
    else indent = 0;
    let padding = '';
    for (let i = 0; i < pad; i++) padding += '  ';
    formatted += padding + node + '\r\n';
    pad += indent;
  });
  return formatted.trim();
}

function initAppState() 
{
  const path = window.location.pathname.substring(1);
  if (path && path !== 'home' && path !== 'welcome' && path !== 'menu') 
  {
    const parts = path.split('/');
    
    // Check for combined URL: tree/WORKING_DIR/read/FILE_PATH
    // We search for 'read' after 'tree'
    let treeIdx = -1;
    let readIdx = -1;
    for (let i = 0; i < parts.length; i++) {
      if (parts[i] === 'tree' && treeIdx === -1) treeIdx = i;
      if (parts[i] === 'read' && readIdx === -1) readIdx = i;
    }

    if (treeIdx !== -1 && readIdx !== -1 && readIdx > treeIdx) {
      // It's a combined URL
      // WD is between tree and read
      const wdParts = parts.slice(treeIdx + 1, readIdx);
      const wd = decodeURIComponent(wdParts.join('/'));
      
      // File is after read
      const fileParts = parts.slice(readIdx + 1);
      const fileUrl = decodeURIComponent(fileParts.join('/'));

      console.log("Restoring Combined State - WD:", wd, "File:", fileUrl);

      window.workingDir = wd;
      window.expandTarget = wd;
      currentFileUrl = fileUrl;
      window.openedFile = fileUrl;

      showView('editor');
      // Sequential requests to ensure session is anchored
      makeRequest('tree/' + wd, '', false, function()
      {
        makeRequest('menu', '', false);
        makeRequest('read/' + fileUrl, null, false);
      });
    }
    else
    {
      const command = parts[0];
      const url = decodeURIComponent(path.substring(command.length + 1));
      
      if (command === 'read') 
      {
        currentFileUrl = url;
        window.openedFile = url;
        window.expandTarget = url;
        if (typeof updateActionButtons === 'function') updateActionButtons();
        showView('editor');
        makeRequest('read/' + url, null, false, function()
        {
          makeRequest('menu', '', false);
        });
      } else if (command === 'tree') 
      {
        window.expandTarget = url;
        window.workingDir = url;
        if (typeof updateActionButtons === 'function') updateActionButtons();
        makeRequest('tree/' + url, '', false, function()
        {
          makeRequest('menu', '', false);
        });
      } else {
        makeRequest(path, '', false, function()
        {
          makeRequest('menu', '', false);
        });
      }
    }
  } else 
  {
    makeRequest('menu', '', false, () => {
      // After menu is loaded, check if session had a working dir or opened file
      if (window.workingDir || window.openedFile) {
        console.log("Restoring from session - WD:", window.workingDir, "File:", window.openedFile);
        if (window.openedFile) {
          showView('editor');
          makeRequest('read/' + window.openedFile, null, false);
        }
        if (window.workingDir) {
          // Sync sidebar to WD
          makeRequest('tree/' + window.workingDir, '', false);
        }
      }
    });
  }
  initAI();
}

function clearChat()
{
  const history = document.getElementById('chat-history');
  if (history)
  {
    history.innerHTML = '<div class="msg ai">Aether AI initialized.</div>';
  }
  wsSend(JSON.stringify({ type: 'clear_chat' }));
}

// ===== Chat session switcher (list / switch / delete-one / clear-all) =====

window.currentChatSessionId = null;

function toggleSessionMenu(event)
{
  event.stopPropagation();
  const dropdown = document.getElementById('session-dropdown');
  if (!dropdown) return;
  const opening = !dropdown.classList.contains('show');
  dropdown.classList.toggle('show');
  if (opening) wsSend(JSON.stringify({ type: 'list_sessions' }));
}

window.addEventListener('click', function() {
  const dropdown = document.getElementById('session-dropdown');
  if (dropdown) dropdown.classList.remove('show');
});

function renderSessionList(sessions, currentId)
{
  window.currentChatSessionId = currentId || null;
  const list = document.getElementById('session-list');
  if (!list) return;
  list.innerHTML = '';
  sessions = sessions || [];
  if (sessions.length === 0)
  {
    const empty = document.createElement('div');
    empty.className = 'dropdown-item';
    empty.textContent = 'No saved chats yet';
    list.appendChild(empty);
    return;
  }
  const busy = !!window.aiWorking;
  for (var i = 0; i < sessions.length; i++)
  {
    (function(sess) {
      const row = document.createElement('div');
      row.className = 'dropdown-item session-row';
      if (sess.id === window.currentChatSessionId) row.classList.add('active');

      const label = document.createElement('span');
      label.className = 'session-label';
      label.textContent = sess.title;
      row.appendChild(label);

      const meta = document.createElement('span');
      meta.className = 'session-meta';
      meta.textContent = sessionRelativeTime(sess.updated) + ' · ' + sess.count;
      row.appendChild(meta);

      const del = document.createElement('span');
      del.className = 'session-delete';
      del.textContent = '🗑';
      del.title = 'Delete this chat';
      del.onclick = function(e) {
        e.stopPropagation();
        if (busy) return;
        showConfirm('Delete "' + sess.title + '"?', function() { wsSend(JSON.stringify({ type: 'delete_session', id: sess.id })); }, 'Delete');
      };
      row.appendChild(del);

      if (busy) row.classList.add('disabled');
      else row.onclick = function() {
        if (sess.id !== window.currentChatSessionId) wsSend(JSON.stringify({ type: 'switch_session', id: sess.id }));
        document.getElementById('session-dropdown').classList.remove('show');
      };
      list.appendChild(row);
    })(sessions[i]);
  }
}

function sessionRelativeTime(ms)
{
  if (!ms) return '';
  const diff = Date.now() - ms;
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return mins + 'm ago';
  const hours = Math.floor(mins / 60);
  if (hours < 24) return hours + 'h ago';
  return Math.floor(hours / 24) + 'd ago';
}

function clearAllSessions()
{
  if (window.aiWorking) return;
  showConfirm('Clear all saved chats? This cannot be undone.', function() { wsSend(JSON.stringify({ type: 'clear_all_sessions' })); }, 'Clear All');
}

function wsSend(message)
{
  if (ws && ws.readyState === 1) ws.send(message);
  else if (ws && ws.readyState === 0) showNotification("AI Assistant is still connecting. Please wait a moment.");
  else
  {
    showNotification("WebSocket Disconnected! Attempting to reconnect...");
    webConnect();
  }
  return false;
}

let aiResponseBuffer = "";
let currentResponseTarget = null;
let variantsRendered = false;
let currentAgent = null;
let aiAgents = [];
let aiModels = [];
let wasAtBottom = true;

function isAtBottom(el)
{
  var threshold = 20;
  return el.scrollHeight - el.scrollTop - el.clientHeight < threshold;
}

function scrollToBottomIfWatching(el)
{
  if (wasAtBottom)
  {
    el.scrollTop = el.scrollHeight;
  }
}

function initAI()
{
  webSocketInit('aetherui', onAIWsOpen, onAIWsMessage, onAIWsClose);

  // Track scroll on chat-history to detect if user scrolled up
  var history = document.getElementById('chat-history');
  if (history)
  {
    history.addEventListener('scroll', function() {
      wasAtBottom = isAtBottom(history);
    });
  }
}

function chatPaneIsEmpty()
{
  var el = document.getElementById('chat-history');
  if (!el) return false;
  var msgs = el.querySelectorAll('.msg');
  if (msgs.length === 0) return true;
  return msgs.length === 1 && msgs[0].textContent.indexOf('Aether AI initialized') !== -1;
}

// Pull-model recovery: an empty chat pane asks the server for history, closing every load-order race
function requestChatSyncIfEmpty()
{
  if (ws && ws.readyState === 1 && chatPaneIsEmpty()) ws.send(JSON.stringify({ type: 'sync_history' }));
}

function onAIWsOpen()
{
  console.log('AI WebSocket Connected');
  var status=document.getElementById('ws-status');
  if (status) status.classList.add('connected');
  requestChatSyncIfEmpty();
}

function onAIWsClose()
{
  console.log('AI WebSocket Disconnected');
  var status=document.getElementById('ws-status');
  if (status) status.classList.remove('connected');
}

function setAIConfig(agents, models)
{
  aiAgents = agents;
  aiModels = models;

  const agentSelect = document.getElementById('agent-select');
  const modelSelect = document.getElementById('model-select');
  const thinkingSelect = document.getElementById('thinking-mode');

  if (agentSelect)
  {
    agentSelect.innerHTML = '';
    for (var i = 0; i < agents.length; i++)
    {
      const opt = document.createElement('option');
      opt.value = agents[i].id;
      opt.textContent = agents[i].name;
      agentSelect.appendChild(opt);
    }
    onAgentChange();
  }

  if (modelSelect)
  {
    modelSelect.innerHTML = '';
    for (var i = 0; i < models.length; i++)
    {
      const opt = document.createElement('option');
      opt.value = models[i];
      // Qualified "Provider::model" ids display as "[Provider] model"
      const sepIdx = models[i].indexOf('::');
      opt.textContent = sepIdx > 0 ? '[' + models[i].substring(0, sepIdx) + '] ' + models[i].substring(sepIdx + 2) : models[i];
      modelSelect.appendChild(opt);
    }
  }

  // Add onchange listeners to save config on change
  if (modelSelect) modelSelect.onchange = saveAIConfig;
  if (thinkingSelect) thinkingSelect.onchange = saveAIConfig;

  // Restore saved settings from aether.ing.json
  restoreAIConfig();
}

function saveAIConfig()
{
  // Server persists AI settings transparently on each process() call
}

function restoreAIConfig()
{
  if (!window.aiConfig) return;
  var cfg = window.aiConfig;
  var modelSelect = document.getElementById('model-select');
  var thinkingSelect = document.getElementById('thinking-mode');
  var agentSelect = document.getElementById('agent-select');

  if (cfg.model && modelSelect) modelSelect.value = cfg.model;
  if (cfg.thinking_mode && thinkingSelect) thinkingSelect.value = cfg.thinking_mode;
  if (cfg.agent_id && agentSelect)
  {
    agentSelect.value = cfg.agent_id;
    onAgentChange();
  }
}

function repairJson(json)
{
  if (!json) return "";
  var repaired = "";
  var stack = [];
  var inString = false;
  var escaped = false;
  for (var i = 0; i < json.length; i++)
  {
    var c = json[i];
    if (inString)
    {
      repaired += c;
      if (escaped) escaped = false;
      else if (c === '\\') escaped = true;
      else if (c === '"') inString = false;
      continue;
    }
    if (c === '"')
    {
      inString = true;
      repaired += c;
      continue;
    }
    if (c === '{' || c === '[')
    {
      stack.push(c);
      repaired += c;
      continue;
    }
    if (c === '}' || c === ']')
    {
      if (stack.length > 0)
      {
        var top = stack[stack.length - 1];
        if ((c === '}' && top === '{') || (c === ']' && top === '['))
        {
          stack.pop();
          var temp = repaired.trimEnd();
          if (temp.endsWith(',')) repaired = temp.slice(0, -1);
          repaired += c;
          if (stack.length === 0) return repaired;
        }
      }
      continue;
    }
    if (/\s/.test(c))
    {
      repaired += c;
      continue;
    }
    repaired += c;
  }
  if (inString) repaired += '"';
  var tempEnd = repaired.trimEnd();
  if (tempEnd.endsWith(',')) repaired = tempEnd.slice(0, -1);
  while (stack.length > 0)
  {
    var top = stack.pop();
    if (top === '{') repaired += '}';
    else if (top === '[') repaired += ']';
  }
  return repaired;
}

function extractAndRepairJsonOld(text)
{
  if (!text) return null;
  var start = text.indexOf('{');
  if (start === -1) return null;
  var rawJson = text.substring(start).trim();
  try { return JSON.parse(rawJson); } catch (e) {}
  var lastBrace = rawJson.lastIndexOf('}');
  if (lastBrace !== -1)
  {
    try { return JSON.parse(rawJson.substring(0, lastBrace + 1)); } catch (e) {}
  }
  try { return JSON.parse(repairJson(rawJson)); } catch (e) {}
  return null;
}

function extractAndRepairJson(text)
{
  if (!text) return null;
  
  // Normalize hidden non-breaking unicode spaces
  var sanitized = text.replace(/\u00a0/g, ' ');
  var rawJson = sanitized;
  
  // Find the start of the JSON-like payload
  var startObj = sanitized.indexOf('{');
  var startArr = sanitized.indexOf('[');
  var start = -1;
  if (startObj !== -1 && startArr !== -1) start = Math.min(startObj, startArr);
  else if (startObj !== -1) start = startObj;
  else if (startArr !== -1) start = startArr;

  if (start !== -1) rawJson = sanitized.substring(start).trim();
  
  // Attempt 1: Polite native parse
  try { return JSON.parse(rawJson); } catch (e) {}
  
  // Attempt 2: Truncate at the very last bracket/brace
  var lastBrace = Math.max(rawJson.lastIndexOf('}'), rawJson.lastIndexOf(']'));
  if (lastBrace !== -1) {
    try { return JSON.parse(rawJson.substring(0, lastBrace + 1)); } catch (e) {}
  }

  // Attempt 3: Deep string repair (catches missing quotes, etc.)
  try { return JSON.parse(repairJson(rawJson)); } catch (e) {}
  
  // --- THE ULTIMATE BRUTE-FORCE EXTRACTOR ---
  // When the LLM hallucinates or breaks syntax, we target the array and extract strings.
  var variants = [];
  var sArr = sanitized.indexOf('[');
  var eArr = sanitized.lastIndexOf(']');
  
  if (sArr !== -1 && eArr > sArr) 
  {
    var content = sanitized.substring(sArr + 1, eArr);
    var i = 0;
    while (i < content.length) 
    {
      var c = content[i];
      // Skip whitespace and commas between elements
      if (/\s/.test(c) || c === ',') { i++; continue; }
      
      if (c === '"') 
      {
        // Parse string literal directly
        var str = "";
        i++; // skip open quote
        while (i < content.length) 
        {
          if (content[i] === '\\') 
          {
            str += (content[i+1] || "");
            i += 2;
          } 
          else if (content[i] === '"') 
          {
            i++; // skip close quote
            break;
          } 
          else 
          {
            str += content[i];
            i++;
          }
        }
        if (str.trim() !== "") variants.push(str);
      } 
      else if (c === '{') 
      {
        // Parse object block while respecting internal strings/escapes
        var depth = 0;
        var inStr = false;
        var escaped = false;
        var objText = "";
        while (i < content.length) 
        {
          var oc = content[i];
          objText += oc;
          if (inStr) 
          {
            if (escaped) escaped = false;
            else if (oc === '\\') escaped = true;
            else if (oc === '"') inStr = false;
          } 
          else 
          {
            if (oc === '"') inStr = true;
            else if (oc === '{') depth++;
            else if (oc === '}') 
            {
              depth--;
              if (depth === 0) { i++; break; }
            }
          }
          i++;
        }
        
        // Extract the value after the last colon in this object block
        var colonIdx = objText.lastIndexOf(':');
        if (colonIdx !== -1) 
        {
          var afterColon = objText.substring(colonIdx + 1).trim();
          // Extract the first string literal after the colon
          var strMatch = afterColon.match(/"([^"\\]*(?:\\.[^"\\]*)*)"/);
          if (strMatch) 
          {
            var val = strMatch[1].replace(/\\"/g, '"').replace(/\\n/g, '\n').replace(/\\r/g, '\r');
            if (val.trim() !== "") variants.push(val);
          }
        }
      }
      else i++;
    }
  }

  if (variants.length > 0) return variants;

  // Total failure
  return null;
}

function onAIWsMessage(event)
{
  const data = JSON.parse(event.data);
  if (data.type === 'chat_delta')
  {
    // SURGICAL FIX: Ignore trailing or replayed delta chunks if variants are already out
    if (variantsRendered) return;

    if (data.thinking) appendToLastMessage('', data.thinking, data.model);
    if (data.responseTarget) currentResponseTarget = data.responseTarget;
    if (data.responseType === 'variants_json')
    {
      if (data.content)
      {
        aiResponseBuffer += data.content;
        var historyEl = document.getElementById('chat-history');
        if (historyEl) wasAtBottom = isAtBottom(historyEl);
        var receivingEl = document.getElementById('variants-receiving');
        if (!receivingEl) receivingEl = createReceivingIndicator(data.model);
        var contentEl = document.getElementById('variants-receiving-content');
        if (contentEl) contentEl.textContent = 'Receiving... ' + aiResponseBuffer.length + ' chars:\n' + aiResponseBuffer;
        if (historyEl) scrollToBottomIfWatching(historyEl);
      }
    }
    else if (data.content)
    {
      appendToLastMessage(data.content, null, data.model);
    }
  } else if (data.type === 'plaintext')
  {
    // Stream content directly into the editor at end of document
    if (editor)
    {
      editor.operation(function() {
        // Place cursor at end of document
        var lastLine = editor.lineCount() - 1;
        var lastCh = editor.getLine(lastLine).length;
        var endCursor = { line: lastLine, ch: lastCh };
        editor.replaceRange(data.content, endCursor);
        // Move cursor to new end
        var newLastLine = editor.lineCount() - 1;
        var newCh = editor.getLine(newLastLine).length;
        editor.setCursor({ line: newLastLine, ch: newCh });
      });
    }
  } else if (data.type === 'chat_done')
  {
    if (data.responseTarget) currentResponseTarget = data.responseTarget;
    
    if (data.responseType === 'variants_json')
    {
      var json = extractAndRepairJson(aiResponseBuffer);
      var extractedVariants = [];

      if (json)
      {
        // Recursive crawler to dig out text variants regardless of strict schema
        function extractVars(item)
        {
          if (typeof item === 'string')
          {
            var clean = item.trim();
            // Stripping malformed prefixes like "text\n" or "text: "
            if (clean.toLowerCase().startsWith('text\n')) clean = clean.substring(5).trim();
            else if (clean.toLowerCase().startsWith('text:')) clean = clean.substring(5).trim();
            
            if (clean !== '') extractedVariants.push({ text: clean });
          }
          else if (Array.isArray(item))
          {
            for (var v = 0; v < item.length; v++) extractVars(item[v]);
          }
          else if (typeof item === 'object' && item !== null)
          {
            // Specifically target 'variants' or 'text' keys if they exist
            if (item.variants) extractVars(item.variants);
            else if (item.text) extractVars(item.text);
            else
            {
              // Fallback: extract string values from all keys
              var keys = Object.keys(item);
              for (var k = 0; k < keys.length; k++) extractVars(item[keys[k]]);
            }
          }
        }
        extractVars(json);
      }
      if (extractedVariants.length > 0 && !variantsRendered)
      {
        variantsRendered = true;
        renderVariants(extractedVariants, currentResponseTarget, data.model);
      }
      else if (!variantsRendered)
      {
        appendMessage('ai', '❌ Error parsing variants JSON — raw data kept above.');
      }
    }
    
    removeToolProgress();
    // Render performance statistics if available (appends to last message, which could be the variants block)
    if (data.eval_count) renderAiStats(data);

    aiResponseBuffer = "";
    currentResponseTarget = null;
    onChatDone();
    setAiWorking(false);
  } else if (data.type === 'variants')
  {
    if (data.responseTarget) currentResponseTarget = data.responseTarget;
    // Server-pre-parsed variants (arrives after chat_done, render only if buffer parse failed)
    if (!variantsRendered && data.variants)
    {
      renderVariants(data.variants, currentResponseTarget);
      variantsRendered = true;
    }
  } else if (data.type === 'chat_history_update')
  {
    renderChatHistory(data.history);
  } else if (data.type === 'chat_error')
  {
    removeToolProgress();
    resolveEditReview(false, true);
    appendMessage('ai', '❌ **Error**: ' + data.error);
    aiResponseBuffer = "";
    setAiWorking(false);
  } else if (data.type === 'permission_request')
  {
    if (!tryEditReview(data))
    {
      // File-edit tools always get the visual diff approval, never the raw JSON modal
      if (data.tool === 'patchFile' || data.tool === 'writeFile' || data.tool === 'createFile') showChatPatchApproval(data);
      else showPermissionModal(data);
    }
  } else if (data.type === 'user_question')
  {
    showUserQuestions(data);
  } else if (data.type === 'tool_progress')
  {
    updateToolProgress(data.tool, data.chars, data.delta);
  } else if (data.type === 'tool_call')
  {
    removeToolProgress();
    appendToolCall(data.tool, data.args, data.log, data.sub);
  } else if (data.type === 'tool_result')
  {
    removeToolProgress();
    appendToolResult(data.tool, data.result, !!data.error, data.sub);
    if (!data.error && (data.tool === 'writeFile' || data.tool === 'createFile' || data.tool === 'deleteFile' || data.tool === 'mkdir' || data.tool === 'renameFile' || data.tool === 'moveFile' || data.tool === 'copyFile')) refreshTreeExplorer();
    if (!data.error && data.path && !pendingEditReview && (data.tool === 'writeFile' || data.tool === 'createFile')) openFileInEditor(data.path);
  } else if (data.type === 'todo_update')
  {
    renderTodoList(data.todos);
  } else if (data.type === 'session_list_update')
  {
    renderSessionList(data.sessions, data.currentId);
  } else if (data.type === 'notify')
  {
    // Server-side background failures (config saves, project setup) surface here
    showNotification(data.text, 8000);
  } else if (data.type === 'ai_config_update')
  {
    window.aiConfig = data.config;
    restoreAIConfig();
  } else if (data.type === 'monitor')
  {
    if (typeof renderMonitor === 'function') renderMonitor(data);
  }
}

function createReceivingIndicator(model)
{
  const history = document.getElementById('chat-history');
  wasAtBottom = isAtBottom(history);
  const div = document.createElement('div');
  div.id = 'variants-receiving';
  div.className = 'msg ai receiving-toggle';
  div.style.cursor = 'pointer';
  const roleName = model || 'AI';
  var metaHtml = '<div class="msg-meta"><span class="msg-role-time"><span class="msg-role">' + roleName + '</span><span class="msg-time">' + new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) + '</span></span></div>';
  var contentDiv = document.createElement('div');
  contentDiv.className = 'msg-content';
  contentDiv.id = 'variants-receiving-content';
  contentDiv.textContent = 'Receiving...';
  div.innerHTML = metaHtml;
  div.appendChild(contentDiv);
  div.onclick = function() {
    var inner = document.getElementById('variants-receiving-content');
    if (inner)
    {
      var isCollapsed = div.classList.contains('collapsed');
      if (isCollapsed)
      {
        div.classList.remove('collapsed');
        inner.style.display = '';
      }
      else
      {
        div.classList.add('collapsed');
        inner.style.display = 'none';
      }
    }
  };
  history.appendChild(div);
  scrollToBottomIfWatching(history);
  return div;
}

// Live indicator while the LLM streams a tool call's arguments (before it can execute)
function updateToolProgress(tool, chars, delta)
{
  var historyEl = document.getElementById('chat-history');
  if (!historyEl) return;
  wasAtBottom = isAtBottom(historyEl);

  var el = document.getElementById('tool-progress');
  if (!el)
  {
    el = document.createElement('div');
    el.id = 'tool-progress';
    el.className = 'msg tool-progress';
    var head = document.createElement('div');
    head.id = 'tool-progress-head';
    el.appendChild(head);
    var body = document.createElement('pre');
    body.id = 'tool-progress-body';
    body.className = 'tool-progress-body';
    el.appendChild(body);
    historyEl.appendChild(el);
  }
  var head = document.getElementById('tool-progress-head');
  var kb = chars > 1024 ? (chars / 1024).toFixed(1) + 'k' : chars;
  if (head) head.textContent = '🛠️ Preparing ' + (tool || 'tool') + ' call... ' + kb + ' chars';

  if (delta)
  {
    var body = document.getElementById('tool-progress-body');
    if (body)
    {
      if (!body.dataset.raw) body.dataset.raw = '';
      body.dataset.raw += delta;
      // Arguments stream as JSON text; unescape the common sequences for readability
      body.textContent = body.dataset.raw.replace(/\\n/g, '\n').replace(/\\t/g, '\t').replace(/\\"/g, '"');
    }
  }
  scrollToBottomIfWatching(historyEl);
}

function removeToolProgress()
{
  var el = document.getElementById('tool-progress');
  if (el) el.remove();
}

// Open a file in the editor pane by its resolved path (no tree element required)
function openFileInEditor(fullPath)
{
  currentFileUrl = fullPath;
  window.openedFile = fullPath;
  lastSavedContent = null;
  lastSavedFileUrl = null;
  const filenameEl = document.getElementById('current-filename');
  if (filenameEl)
  {
    var slash = fullPath.lastIndexOf('/');
    filenameEl.textContent = slash === -1 ? fullPath : fullPath.substring(slash + 1);
    filenameEl.classList.add('loading');
  }
  updateActionButtons();
  showView('editor');
  makeRequest('read/' + fullPath, null, false);
}

// Re-list the working directory in the sidebar after the AI changed files on disk
function refreshTreeExplorer()
{
  if (!window.workingDir) return;
  var items = document.querySelectorAll('.tree-folder, .tree-provider');
  for (var i = 0; i < items.length; i++)
  {
    var el = items[i];
    if (el.dataset.id === window.workingDir || el.dataset.url === window.workingDir)
    {
      var content = el.nextElementSibling;
      if (content && (content.classList.contains('folder-content') || content.tagName === 'UL'))
      {
        if (!content.id) content.id = 'tree-' + Math.random().toString(36).substr(2, 9);
        makeRequest('tree/' + window.workingDir, 'targetId=' + content.id, false);
        if (el.classList.contains('collapsed')) toggleFolder(el);
        return;
      }
    }
  }
  makeRequest('tree/' + window.workingDir, '', false);
}

function appendToolCall(name, args, log, sub)
{
  const history = document.getElementById('chat-history');
  if (!history) return;
  wasAtBottom = isAtBottom(history);
  const div = document.createElement('div');
  div.className = 'msg tool-call';
  if (sub) div.classList.add('sub-tool');
  div.style.color = '#ff5555';
  div.style.backgroundColor = 'rgba(255, 85, 85, 0.05)';
  div.style.padding = '8px';
  div.style.borderRadius = '4px';
  div.style.borderLeft = '3px solid #ff5555';
  div.style.opacity = '0.8';
  div.style.marginBottom = '5px';
  var toolLog = (sub ? '↳ ' : '') + (log || ('🛠️ Calling tool: ' + name));
  div.innerHTML = '<div class="tool-header">' + toolLog + '</div>' +
                  '<div class="tool-args" style="font-size:0.8em;opacity:0.8;margin-left:20px;">' + JSON.stringify(args) + '</div>';
  history.appendChild(div);
  scrollToBottomIfWatching(history);
}

function appendToolResult(name, result, isError, sub)
{
  const history = document.getElementById('chat-history');
  if (!history) return;
  wasAtBottom = isAtBottom(history);
  const div = document.createElement('div');
  div.className = 'msg tool-result';
  if (sub) div.classList.add('sub-tool');
  if (isError)
  {
    div.style.color = '#ff5555';
    div.style.backgroundColor = 'rgba(255, 85, 85, 0.05)';
    div.style.borderLeft = '3px solid #ff5555';
  }
  else
  {
    div.style.color = '#55ff55';
    div.style.backgroundColor = 'rgba(85, 255, 85, 0.05)';
    div.style.borderLeft = '3px solid #55ff55';
  }
  div.style.fontSize = '0.9em';
  div.style.padding = '5px 8px';
  div.style.borderRadius = '4px';
  div.style.opacity = '0.8';
  div.style.marginBottom = '10px';
  div.innerHTML = '<div class="tool-res-header">' + (sub ? '↳ ' : '') + (isError ? '❌' : '✅') + ' Tool <b>' + name + '</b> responded: ' + result + '</div>';
  history.appendChild(div);
  scrollToBottomIfWatching(history);
}

function renderVariants(variants, target, model)
{
  const history = document.getElementById('chat-history');
  wasAtBottom = isAtBottom(history);
  
  // Remove temporary indicator
  var receivingEl = document.getElementById('variants-receiving');
  if (receivingEl) receivingEl.remove();

  // Create a proper AI message block for the variants
  const msgDiv = document.createElement('div');
  msgDiv.className = 'msg ai';
  
  const time = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const roleName = model || 'AI';
  msgDiv.innerHTML = '<div class="msg-meta"><span class="msg-role-time"><span class="msg-role">' + roleName + '</span><span class="msg-time">' + time + '</span></span><button class="copy-msg-btn" title="Copy Message">📋</button></div>';
  
  const contentDiv = document.createElement('div');
  contentDiv.className = 'msg-content';

  const varDiv = document.createElement('div');
  varDiv.className = 'variants-container';
  for (var i = 0; i < variants.length; i++)
  {
    // Support both {text:"..."} objects and plain strings
    var text = typeof variants[i] === 'string' ? variants[i] : (variants[i].text || '').toString();
    const btn = document.createElement('button');
    btn.className = 'p-btn variant-btn';
    btn.textContent = 'Variant ' + (i + 1) + ': ' + text;
    btn.title = text;
    const variantText = text;
    btn.onclick = function() { insertAtCursor(variantText, target); };
    varDiv.appendChild(btn);
  }
  
  contentDiv.appendChild(varDiv);
  msgDiv.appendChild(contentDiv);
  history.appendChild(msgDiv);

  scrollToBottomIfWatching(history);
}

function insertAtCursor(text, target)
{
  if (target === 'userInput')
  {
    const chatInput = document.getElementById('chat-input');
    if (chatInput)
    {
      chatInput.value = text;
      chatInput.focus();
    }
    return;
  }

  if (!editor) return;
  
  if (target === 'endContent')
  {
    const lastLine = editor.lineCount() - 1;
    const lastCh = editor.getLine(lastLine).length;
    const pos = { line: lastLine, ch: lastCh };
    editor.replaceRange('\n' + text, pos);
    editor.setCursor(editor.lineCount(), 0);
  }
  else if (editor.somethingSelected() || target === 'selectedContent') 
  {
    editor.replaceSelection(text);
  } 
  else 
  {
    const cursor = editor.getCursor();
    editor.replaceRange(text, cursor);
  }
  
  showView('editor');
  if (editor)
  {
    setTimeout(() => {
      editor.refresh();
      editor.focus();
    }, 10);
  }
}

function onAgentChange()
{
  const agentSelect = document.getElementById('agent-select');
  if (!agentSelect) return;

  if (agentSelect.dataset.agents)
  {
    try {
      const data = JSON.parse(agentSelect.dataset.agents);
      if (data && data.length > 0) aiAgents = data;
    } catch (e) { console.log('Error parsing AI agents data in onAgentChange', e); }
  }

  const agentId = agentSelect.value;
  currentAgent = null;
  if (aiAgents && aiAgents.length > 0)
  {
    for (var i = 0; i < aiAgents.length; i++)
    {
      if (aiAgents[i].id === agentId)
      {
        currentAgent = aiAgents[i];
        break;
      }
    }
  }

  // Fallback to first agent if none matched and we have agents
  if (!currentAgent && aiAgents && aiAgents.length > 0) currentAgent = aiAgents[0];

  console.log('Agent changed to:', currentAgent ? currentAgent.id : 'null', 'Commands:', currentAgent && currentAgent.commands ? currentAgent.commands.length : 0);
  renderAgentCommands();
}

function renderAgentCommands() 
{
  const container = document.getElementById('agent-commands');
  if (!container) return;
  container.innerHTML = '';
  if (!currentAgent || !currentAgent.commands) return;

  // Buttons follow the editor selection: [[SELECTED_TEXT]] commands only make sense with a selection, the rest only without
  const hasSelection = !!(editor && editor.getSelection() && editor.getSelection().trim().length > 0);
  for (var i = 0; i < currentAgent.commands.length; i++)
  {
    const cmd = currentAgent.commands[i];
    if (cmd.id === 'default') continue; // Hide default command from buttons
    const needsSelection = !!(cmd.prompt && cmd.prompt.indexOf('[[SELECTED_TEXT]]') !== -1);
    if (needsSelection !== hasSelection) continue;
    const btn = document.createElement('button');
    btn.className = 'p-btn';
    btn.textContent = cmd.label;
    btn.onclick = (function(commandId, commandLabel) {
      return function() { sendChatMessage(null, commandId, commandLabel); };
    })(cmd.id, cmd.label);
    container.appendChild(btn);
  }
}
function sendChatMessage(message, commandId, commandLabel)
{
  variantsRendered = false;
  aiResponseBuffer = "";
  // Remove any leftover receiving indicator from a stopped/aborted variants stream so the new turn renders at the bottom
  var staleReceiving = document.getElementById('variants-receiving');
  if (staleReceiving) staleReceiving.remove();
  const input = document.getElementById('chat-input');
  let msg = message || (input ? input.value : '');
  if (!msg && !commandId) return;

  const agentId = document.getElementById('agent-select').value;
  const modelId = document.getElementById('model-select').value;
  const thinkingMode = document.getElementById('thinking-mode').value;
  
  const fullAgentId = commandId ? agentId + ':' + commandId : agentId;

  let displayMsg = msg;
  if (commandLabel) displayMsg = msg ? '**' + commandLabel + '**: ' + msg : '**' + commandLabel + '**';

  if (displayMsg) appendMessage('user', displayMsg);
  if (input) input.value = '';

  const history = document.getElementById('chat-history');
  if (history)
  {
    history.scrollTop = history.scrollHeight;
    wasAtBottom = true;
  }

  const selectedText = editor ? editor.getSelection() : '';

  var payload = {
    type: 'chat',
    message: msg, // Use raw message for processing, displayMsg was for UI
    agent_id: fullAgentId,
    model_id: modelId,
    thinking_mode: thinkingMode,
    context_files: getSelectedContextFiles(),
    selected_text: selectedText
  };
  if (pendingTerminalOutput) payload.terminal_output = pendingTerminalOutput;
  wsSend(JSON.stringify(payload));
  clearTerminalContext();
  setAiWorking(true);
}

function stopAiActivity()
{
  resolveEditReview(false, true);
  wsSend(JSON.stringify({ type: 'stop' }));
  setAiWorking(false);
}

function setAiWorking(working)
{
  window.aiWorking = working; // terminal auto-error reporting must not interrupt a running turn
  const sendBtn = document.getElementById('send-btn');
  const stopBtn = document.getElementById('stop-btn');
  if (working)
  {
    if (sendBtn) sendBtn.classList.add('hidden');
    if (stopBtn) stopBtn.classList.remove('hidden');
  }
  else
  {
    if (sendBtn) sendBtn.classList.remove('hidden');
    if (stopBtn) stopBtn.classList.add('hidden');
  }
}

function respondPermission(granted)
{
  var response = { type: 'permission_response', granted: granted };
  var reasonInput = document.getElementById('permission-reason');
  if (!granted && reasonInput && reasonInput.value.trim()) response.reason = reasonInput.value.trim();
  if (reasonInput) reasonInput.value = '';
  wsSend(JSON.stringify(response));
  const modal = document.getElementById('permission-modal');
  if (modal) modal.classList.add('hidden');
}

function showPermissionModal(data)
{
  const modal = document.getElementById('permission-modal');
  const details = document.getElementById('permission-details');
  const msg = document.getElementById('permission-msg');
  if (modal && details && msg)
  {
    msg.textContent = (data.message || ('The AI agent wants to use tool: ' + data.tool)) + (data.sub ? ' (sub-agent)' : '');
    var explainLine = data.args && data.args.explain ? 'Why: ' + data.args.explain + '\n\n' : '';
    details.textContent = explainLine + JSON.stringify(data.args, null, 2);
    modal.classList.remove('hidden');
  }
}

let pendingEditReview = null;

// Mirrors AITools.ensureFullPath so client and server resolve tool paths identically
function resolveToolPath(p)
{
  if (!p) return null;
  var wd = window.workingDir;
  if (!wd) return p;
  if (p === wd || p.indexOf(wd + '/') === 0) return p;
  var clean = p.trim().replace(/\\/g, '/').replace(/\/+/g, '/').replace(/\.\./g, '').replace(/^\/+/, '');
  var lastSlash = wd.lastIndexOf('/');
  var wdName = lastSlash === -1 ? wd : wd.substring(lastSlash + 1);
  if (clean.indexOf(wdName + '/') === 0) clean = clean.substring(wdName.length + 1);
  else if (clean === wdName) clean = '';
  return wd + (clean === '' ? '' : '/' + clean);
}

// Inline diff review for AI file edits; returns false to fall back to the JSON permission modal
function tryEditReview(data)
{
  if (!editor || pendingEditReview) return false;
  var args = data.args || {};
  // Prefer the server-resolved path; the client-side guess can differ and load the wrong (empty) file
  var fullPath = data.fullpath || resolveToolPath(args.path);
  if (!fullPath) return false;

  var edits = null, newContent = null;
  if (data.tool === 'patchFile')
  {
    if (args.edits && args.edits.length) edits = args.edits;
    else if (typeof args.old_text === 'string') edits = [{ old_text: args.old_text, new_text: args.new_text }];
    if (!edits) return false;
    for (var i = 0; i < edits.length; i++)
    {
      if (!edits[i] || typeof edits[i].old_text !== 'string' || edits[i].old_text === '') return false;
      if (typeof edits[i].new_text !== 'string') edits[i].new_text = '';
    }
  }
  else if (data.tool === 'writeFile')
  {
    // Whole-file rewrite: only previewable against the file already open in the editor
    if (typeof args.content !== 'string' || fullPath !== currentFileUrl) return false;
    newContent = args.content;
  }
  else return false;

  if (fullPath === currentFileUrl)
  {
    if (saveTimeout)
    {
      // Flush pending edits first so the server patches the same content we preview
      clearTimeout(saveTimeout);
      saveTimeout = null;
      autoSave(function() { if (!applyEditPreview(data, edits, newContent)) showChatPatchApproval(data); });
      return true;
    }
    return applyEditPreview(data, edits, newContent);
  }

  // Target file is not open: load it through the normal read flow, then preview
  currentFileUrl = fullPath;
  window.openedFile = fullPath;
  lastSavedContent = null;
  lastSavedFileUrl = null;
  const filenameEl = document.getElementById('current-filename');
  if (filenameEl)
  {
    var slash = fullPath.lastIndexOf('/');
    filenameEl.textContent = slash === -1 ? fullPath : fullPath.substring(slash + 1);
  }
  showView('editor');
  makeRequest('read/' + fullPath, null, false, function() {
    if (!applyEditPreview(data, edits, newContent)) showChatPatchApproval(data);
  });
  return true;
}

// Chat-pane diff approval: used when the inline editor preview is not possible
function showChatPatchApproval(data)
{
  var historyEl = document.getElementById('chat-history');
  if (!historyEl) { showPermissionModal(data); return; }
  var args = data.args || {};
  wasAtBottom = isAtBottom(historyEl);

  var block = document.createElement('div');
  block.className = 'msg ai patch-block';
  // Full-content proposals show at natural height (the chat log scrolls); patchFile keeps the capped diff area
  if (data.tool === 'createFile' || data.tool === 'writeFile') block.classList.add('patch-full');

  var header = document.createElement('div');
  header.className = 'patch-header';
  header.textContent = (data.sub ? '↳ (sub-agent) ' : '') + (data.message || ('🩹 ' + data.tool)) + (args.path ? ' — ' + args.path : '');
  block.appendChild(header);

  // The AI's explanation of what this change is for and how it relates to the plan
  if (args.explain)
  {
    var explain = document.createElement('div');
    explain.className = 'patch-explain';
    explain.textContent = args.explain;
    block.appendChild(explain);
  }

  var content = document.createElement('div');
  content.className = 'patch-content';
  var edits = [];
  if (args.edits && args.edits.length) edits = args.edits;
  else if (typeof args.old_text === 'string') edits = [{ old_text: args.old_text, new_text: args.new_text }];
  for (var i = 0; i < edits.length; i++)
  {
    if (edits[i] && typeof edits[i].old_text === 'string' && edits[i].old_text !== '')
    {
      var oldDiv = document.createElement('div');
      oldDiv.className = 'patch-remove';
      oldDiv.textContent = edits[i].old_text;
      content.appendChild(oldDiv);
    }
    if (edits[i] && typeof edits[i].new_text === 'string' && edits[i].new_text !== '')
    {
      var newDiv = document.createElement('div');
      newDiv.className = 'patch-add';
      newDiv.textContent = edits[i].new_text;
      content.appendChild(newDiv);
    }
  }
  if (edits.length === 0 && typeof args.content === 'string' && args.content !== '')
  {
    var contentDiv = document.createElement('div');
    contentDiv.className = 'patch-add';
    contentDiv.textContent = args.content;
    content.appendChild(contentDiv);
  }
  if (!content.hasChildNodes())
  {
    var raw = document.createElement('div');
    raw.textContent = JSON.stringify(args, null, 2);
    content.appendChild(raw);
  }
  block.appendChild(content);

  var actions = document.createElement('div');
  actions.className = 'patch-actions';
  buildPatchActions(actions, block);
  block.appendChild(actions);

  historyEl.appendChild(block);
  scrollToBottomIfWatching(historyEl);
}

function buildPatchActions(actions, block)
{
  actions.innerHTML = '';
  actions.classList.remove('reason-mode');
  var approve = document.createElement('button');
  approve.className = 'p-btn review-approve';
  approve.textContent = '✔ Approve';
  approve.onclick = function() { resolveChatPatch(block, true, null); };
  var reject = document.createElement('button');
  reject.className = 'p-btn review-reject';
  reject.textContent = '✖ Reject';
  reject.onclick = function() {
    actions.innerHTML = '';
    actions.classList.add('reason-mode');
    var input = document.createElement('input');
    input.type = 'text';
    input.className = 'review-reason-input';
    input.placeholder = 'Why reject? The AI will revise based on this...';
    var send = document.createElement('button');
    send.className = 'p-btn review-reject';
    send.textContent = 'Send Rejection';
    var doReject = function() { resolveChatPatch(block, false, input.value.trim()); };
    send.onclick = doReject;
    input.onkeydown = function(e) { if (e.key === 'Enter') doReject(); };
    var back = document.createElement('button');
    back.className = 'p-btn';
    back.textContent = 'Back';
    back.onclick = function() { buildPatchActions(actions, block); };
    actions.appendChild(input);
    actions.appendChild(send);
    actions.appendChild(back);
    input.focus();
  };
  actions.appendChild(approve);
  actions.appendChild(reject);
}

function resolveChatPatch(block, granted, reason)
{
  var actions = block.querySelector('.patch-actions');
  if (actions) actions.remove();
  var header = block.querySelector('.patch-header');
  if (header) header.textContent = (granted ? '✅ Approved — ' : '❌ Rejected — ') + header.textContent;
  var response = { type: 'permission_response', granted: granted };
  if (!granted && reason) response.reason = reason;
  wsSend(JSON.stringify(response));
}

// Inline card for the askUser tool: the AI asks one or more questions, each with option buttons plus a free-text answer
function showUserQuestions(data)
{
  var historyEl = document.getElementById('chat-history');
  if (!historyEl) return;
  wasAtBottom = isAtBottom(historyEl);

  var block = document.createElement('div');
  block.className = 'msg ai question-block';

  var header = document.createElement('div');
  header.className = 'question-header';
  header.textContent = '❓ The AI needs your input';
  block.appendChild(header);

  var questions = data.questions || [];
  for (var i = 0; i < questions.length; i++)
  {
    var q = questions[i] || {};
    var item = document.createElement('div');
    item.className = 'question-item';
    item.dataset.question = q.question || '';

    var text = document.createElement('div');
    text.className = 'question-text';
    text.textContent = q.question || '';
    item.appendChild(text);

    var opts = document.createElement('div');
    opts.className = 'question-options';

    var input = document.createElement('input');
    input.type = 'text';
    input.className = 'review-reason-input question-free';
    input.placeholder = 'Or type your own answer...';
    input.oninput = (function(container, field) {
      return function() {
        // A typed answer overrides any picked option
        if (field.value.trim() === '') return;
        var all = container.querySelectorAll('.q-opt');
        for (var k = 0; k < all.length; k++) all[k].classList.remove('selected');
      };
    })(opts, input);
    input.onkeydown = (function(card) { return function(e) { if (e.key === 'Enter') resolveUserQuestions(card); }; })(block);

    var options = q.options || [];
    for (var j = 0; j < options.length; j++)
    {
      var opt = document.createElement('button');
      opt.className = 'p-btn q-opt';
      opt.textContent = options[j];
      opt.onclick = (function(btn, container, field) {
        return function() {
          var wasSelected = btn.classList.contains('selected');
          var all = container.querySelectorAll('.q-opt');
          for (var k = 0; k < all.length; k++) all[k].classList.remove('selected');
          if (!wasSelected)
          {
            btn.classList.add('selected');
            field.value = '';
          }
        };
      })(opt, opts, input);
      opts.appendChild(opt);
    }

    item.appendChild(opts);
    item.appendChild(input);
    block.appendChild(item);
  }

  var actions = document.createElement('div');
  actions.className = 'question-actions';
  var send = document.createElement('button');
  send.className = 'p-btn review-approve';
  send.textContent = 'Send Answers';
  send.onclick = function() { resolveUserQuestions(block); };
  actions.appendChild(send);
  block.appendChild(actions);

  historyEl.appendChild(block);
  scrollToBottomIfWatching(historyEl);
}

function resolveUserQuestions(block)
{
  var answers = [];
  var items = block.querySelectorAll('.question-item');
  for (var i = 0; i < items.length; i++)
  {
    var free = items[i].querySelector('.question-free');
    var selected = items[i].querySelector('.q-opt.selected');
    var answer = free && free.value.trim() !== '' ? free.value.trim() : (selected ? selected.textContent : '');
    answers.push({ question: items[i].dataset.question, answer: answer });
  }

  // Freeze the card: keep the picked options visible, drop the empty inputs and the send button
  var actions = block.querySelector('.question-actions');
  if (actions) actions.remove();
  var frees = block.querySelectorAll('.question-free');
  for (var i = 0; i < frees.length; i++)
  {
    if (frees[i].value.trim() === '') frees[i].remove();
    else frees[i].disabled = true;
  }
  var btns = block.querySelectorAll('.q-opt');
  for (var i = 0; i < btns.length; i++) btns[i].disabled = true;
  var header = block.querySelector('.question-header');
  if (header) header.textContent = '✅ Answered';

  wsSend(JSON.stringify({ type: 'question_response', answers: answers }));
}

function applyEditPreview(data, edits, wholeNewContent)
{
  if (!editor) return false;
  var content = editor.getValue();
  var planned = [];

  if (wholeNewContent !== null && wholeNewContent !== undefined)
  {
    // writeFile: reduce the whole-file replacement to its changed region
    var splice = computeSplice(content, wholeNewContent);
    if (splice.deleteCount === 0 && splice.text.length === 0) return false;
    planned.push({ idx: splice.start, oldLen: splice.deleteCount, text: splice.text });
  }
  else
  {
    for (var i = 0; i < edits.length; i++)
    {
      var oldText = edits[i].old_text;
      var pos = content.indexOf(oldText);
      if (pos === -1 || content.indexOf(oldText, pos + 1) !== -1) return false;
      planned.push({ idx: pos, oldLen: oldText.length, text: edits[i].new_text });
    }
    // Overlapping regions cannot be previewed reliably
    planned.sort(function(a, b) { return a.idx - b.idx; });
    for (var i = 1; i < planned.length; i++)
      if (planned[i].idx < planned[i - 1].idx + planned[i - 1].oldLen) return false;
  }

  editor.off('change', triggerAutoSave);
  var marked = [];
  // Apply from the last region backwards so earlier character indices stay valid
  for (var i = planned.length - 1; i >= 0; i--)
  {
    var p = planned[i];
    var from = editor.posFromIndex(p.idx);
    var mid = editor.posFromIndex(p.idx + p.oldLen);
    editor.replaceRange(p.text, mid, mid);
    var end = editor.posFromIndex(p.idx + p.oldLen + p.text.length);
    var entry = {};
    if (p.oldLen > 0) entry.oldMark = editor.markText(from, mid, { className: 'diff-removed' });
    if (p.text.length > 0) entry.newMark = editor.markText(mid, end, { className: 'diff-added' });
    marked.push(entry);
  }
  editor.setOption('readOnly', true);
  editor.on('change', triggerAutoSave);

  pendingEditReview = { edits: marked };
  showReviewBar(data);
  showView('editor');
  editor.scrollIntoView(editor.posFromIndex(planned[0].idx), 100);
  return true;
}

function showReviewBar(data)
{
  var container = document.getElementById('editor-container');
  if (!container) return;
  var bar = document.getElementById('edit-review-bar');
  if (!bar)
  {
    bar = document.createElement('div');
    bar.id = 'edit-review-bar';
    bar.className = 'edit-review-bar';
    container.appendChild(bar);
  }
  bar.innerHTML = '';
  bar.classList.remove('reason-mode');
  var label = document.createElement('span');
  label.textContent = '🩹 AI edit: ' + (data.args && data.args.path ? data.args.path : '');
  var approve = document.createElement('button');
  approve.className = 'p-btn review-approve';
  approve.textContent = '✔ Approve';
  approve.onclick = function() { resolveEditReview(true, false); };
  var reject = document.createElement('button');
  reject.className = 'p-btn review-reject';
  reject.textContent = '✖ Reject';
  reject.onclick = function() { showRejectReasonInput(bar, data); };
  bar.appendChild(label);
  if (data.args && data.args.explain)
  {
    var why = document.createElement('span');
    why.className = 'review-explain';
    why.textContent = data.args.explain;
    bar.appendChild(why);
  }
  bar.appendChild(approve);
  bar.appendChild(reject);
}

// Ask why the patch is rejected; the reason is fed back to the LLM as the tool result
function showRejectReasonInput(bar, data)
{
  bar.innerHTML = '';
  bar.classList.add('reason-mode');
  var input = document.createElement('input');
  input.type = 'text';
  input.className = 'review-reason-input';
  input.placeholder = 'Why reject? The AI will revise based on this...';
  var send = document.createElement('button');
  send.className = 'p-btn review-reject';
  send.textContent = 'Send Rejection';
  var doReject = function() { resolveEditReview(false, false, input.value.trim()); };
  send.onclick = doReject;
  input.onkeydown = function(e) { if (e.key === 'Enter') doReject(); };
  var back = document.createElement('button');
  back.className = 'p-btn';
  back.textContent = 'Back';
  back.onclick = function() { showReviewBar(data); };
  bar.appendChild(input);
  bar.appendChild(send);
  bar.appendChild(back);
  input.focus();
}

function resolveEditReview(granted, skipResponse, reason)
{
  if (!pendingEditReview || !editor) return;
  var r = pendingEditReview;
  pendingEditReview = null;

  var bar = document.getElementById('edit-review-bar');
  if (bar) bar.remove();

  editor.setOption('readOnly', false);
  editor.off('change', triggerAutoSave);
  // Markers track positions through each deletion, so multiple regions resolve safely in any order
  for (var i = 0; i < r.edits.length; i++)
  {
    var e = r.edits[i];
    var target = granted ? e.oldMark : e.newMark; // approve keeps new text, reject keeps old
    var keep = granted ? e.newMark : e.oldMark;
    if (target)
    {
      var range = target.find();
      target.clear();
      if (range) editor.replaceRange('', range.from, range.to);
    }
    if (keep) keep.clear();
  }
  editor.on('change', triggerAutoSave);

  if (granted)
  {
    // Server writes this exact content via patchFile/writeFile; align the delta-save baseline
    lastSavedContent = editor.getValue();
    lastSavedFileUrl = currentFileUrl;
    setSaveStatus('saved');
  }
  if (!skipResponse)
  {
    var response = { type: 'permission_response', granted: granted };
    if (!granted && reason) response.reason = reason;
    wsSend(JSON.stringify(response));
  }
}

function mdToHtml(md) 
{
  if (!md) return "";
  let html = md
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\*\*(.*?)\*\*/g, '<b>$1</b>')
    .replace(/\*(.*?)\*/g, '<i>$1</i>')
    .replace(/`(.*?)`/g, '<code>$1</code>')
    .replace(/^### (.*)$/gm, '<h3>$1</h3>')
    .replace(/^## (.*)$/gm, '<h2>$1</h2>')
    .replace(/^# (.*)$/gm, '<h1>$1</h1>')
    .replace(/^\s*\n\* (.*)/gm, '<ul>\n<li>$1</li>\n</ul>')
    .replace(/<\/ul>\n<ul>/g, '');
  
  let openCount = (html.match(/```/g) || []).length;
  if (openCount % 2 !== 0) html += '\n```';
  
  html = html.replace(/```(\w+)?\r?\n([\s\S]*?)```/g, (match, lang, code) => '<div class="code-block-wrapper"><div class="code-header">' + (lang || 'code') + '</div><pre><code>' + code + '</code></pre></div>');
  html = html.replace(/\r?\n/g, '<br>');
  html = html
    .replace(/<(h1|h2|h3|ul|li|pre|div)><br>/g, '<$1>')
    .replace(/<br><\/(h1|h2|h3|ul|li|pre|div)>/g, '</$1>');
  return html;
}

function appendMessage(role, content, thinking, timeStr, model)
{
  const history = document.getElementById('chat-history');
  if (!history) return;
  wasAtBottom = isAtBottom(history);

  const msgDiv = document.createElement('div');
  msgDiv.className = 'msg ' + role;

  const time = timeStr || new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const roleName = (role === 'ai' && model) ? model : role.toUpperCase();
  let html = '<div class="msg-meta"><span class="msg-role-time"><span class="msg-role">' + roleName + '</span><span class="msg-time">' + time + '</span></span><button class="copy-msg-btn" title="Copy Message">📋</button></div>';

  if (thinking)
    html += '<div class="thinking-block collapsed" onclick="this.classList.toggle(\'collapsed\')"><div class="thinking-header" onclick="event.stopPropagation(); this.closest(\'.thinking-block\').classList.toggle(\'collapsed\');">Thought Process ▾</div><div class="thinking-content">' + mdToHtml(thinking) + '</div></div>';

  html += '<div class="msg-content">' + mdToHtml(content) + '</div>';
  msgDiv.innerHTML = html;
  
  const copyBtn = msgDiv.querySelector('.copy-msg-btn');
  if (copyBtn)
  {
    copyBtn.onclick = function(e) {
      e.stopPropagation();
      let textToCopy = content;
      if (role === 'user')
      {
        const prefixMatch = content.match(/^\*\*(.*?)\*\*: /);
        if (prefixMatch) textToCopy = content.substring(prefixMatch[0].length);
      }
      navigator.clipboard.writeText(textToCopy).then(() => {
        showNotification("Copied to clipboard");
      }).catch(err => {
        console.error('Failed to copy text: ', err);
      });
    };
  }

  history.appendChild(msgDiv);
  scrollToBottomIfWatching(history);
  return msgDiv;
}

function appendToLastMessage(delta, thinking, model)
{
  const history = document.getElementById('chat-history');
  if (!history) return;
  wasAtBottom = isAtBottom(history);

  let lastMsg = history.lastElementChild;
  
  // If we are in variants mode, the last element might be the receiving indicator.
  // We want to append thinking/content to the AI message BEFORE the indicator.
  if (lastMsg && lastMsg.id === 'variants-receiving')
    lastMsg = lastMsg.previousElementSibling;

  if (!lastMsg || !lastMsg.classList.contains('ai') || lastMsg.classList.contains('receiving-toggle'))
    lastMsg = appendMessage('ai', '', thinking, null, model);

  if (model)
  {
    const roleEl = lastMsg.querySelector('.msg-role');
    if (roleEl && roleEl.textContent === 'AI') roleEl.textContent = model;
  }

  if (thinking)
  {
    // Check if there's already a thinking block; if not, add one
    var thinkBlock = lastMsg.querySelector('.thinking-block');
    if (!thinkBlock)
    {
      // Insert thinking block after msg-meta
      var meta = lastMsg.querySelector('.msg-meta');
      var thinkDiv = document.createElement('div');
      thinkDiv.className = 'thinking-block collapsed';
      thinkDiv.onclick = function() { this.classList.toggle('collapsed'); };
      var header = document.createElement('div');
      header.className = 'thinking-header';
      header.onclick = function(e) {
        e.stopPropagation();
        header.closest('.thinking-block').classList.toggle('collapsed');
      };
      header.textContent = 'Thought Process ▾';
      thinkDiv.appendChild(header);
      var content = document.createElement('div');
      content.className = 'thinking-content';
      thinkDiv.appendChild(content);
      if (meta) meta.parentNode.insertBefore(thinkDiv, meta.nextSibling);
      else lastMsg.prepend(thinkDiv);
    }
    var thinkContent = lastMsg.querySelector('.thinking-content');
    if (thinkContent)
    {
      // The server streams thinking incrementally; accumulate the raw text and re-render
      if (!lastMsg.dataset.rawThinking) lastMsg.dataset.rawThinking = "";
      lastMsg.dataset.rawThinking += thinking;
      thinkContent.innerHTML = mdToHtml(lastMsg.dataset.rawThinking);
    }
  }

  if (delta)
  {
    let contentDiv = lastMsg.querySelector('.msg-content');
    if (contentDiv)
    {
      // We buffer the raw markdown content to re-render as HTML correctly
      if (!lastMsg.dataset.raw) lastMsg.dataset.raw = "";
      lastMsg.dataset.raw += delta;
      contentDiv.innerHTML = mdToHtml(lastMsg.dataset.raw);
    }
  }

  scrollToBottomIfWatching(history);
}

function renderChatHistory(history)
{
  const historyEl = document.getElementById('chat-history');
  if (!historyEl) return;
  
  historyEl.innerHTML = history && history.length > 0 ? "" : '<div class="msg ai">Aether AI initialized.</div>';
  
  if (history)
  {
    history.forEach(msg => {
      const role = msg.role;
      const content = msg.content;
      const thinking = msg.thinking;
      const timeStr = msg.time ? new Date(msg.time).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : "";
      
      if (role === 'tool')
      {
         appendToolResult(msg.tool, msg.log_summary || msg.content, !!msg.error);
      }
      else if (role === 'assistant' && msg.tool_calls)
      {
         // Find tool calls and append them
         const calls = msg.tool_calls;
         calls.forEach(c => {
           appendToolCall(c.function.name, c.function.arguments, null);
         });
      }
      else
      {
         appendMessage(role === 'assistant' ? 'ai' : role, content, thinking, timeStr, msg.model);
      }
    });
  }
  
  historyEl.scrollTop = historyEl.scrollHeight;
  wasAtBottom = true;
}

function onChatDone() 
{
}

function renderAiStats(stats)
{
  const history = document.getElementById('chat-history');
  if (!history) return;
  const lastMsg = history.lastElementChild;
  if (!lastMsg || !lastMsg.classList.contains('ai')) return;
  // A message gets exactly one stats block; a replayed chat_done must not stack another
  if (lastMsg.querySelector('.ai-stats-block')) return;

  const statsDiv = document.createElement('div');
  statsDiv.className = 'ai-stats-block';
  
  const totalTokens = (stats.prompt_eval_count || 0) + (stats.eval_count || 0);
  const genTps = stats.eval_duration > 0 ? (stats.eval_count * 1000000000 / stats.eval_duration).toFixed(2) : 0;

  statsDiv.innerHTML = '<button class="stats-toggle-btn" onclick="this.nextElementSibling.classList.toggle(\'hidden\')">📊 Statistics ▾</button>' +
                       '<div class="stats-content hidden">' +
                       '<div class="stats-item"><span class="stats-label">Total Tokens:</span><span class="stats-value">' + totalTokens + '</span></div>' +
                       '<div class="stats-item"><span class="stats-label">Prompt Tokens:</span><span class="stats-value">' + (stats.prompt_eval_count || 0) + '</span></div>' +
                       '<div class="stats-item"><span class="stats-label">Generated Tokens:</span><span class="stats-value">' + stats.eval_count + '</span></div>' +
                       '<div class="stats-item"><span class="stats-label">Generated TPS:</span><span class="stats-value">' + genTps + '</span></div>' +
                       '</div>';
  
  lastMsg.appendChild(statsDiv);
  scrollToBottomIfWatching(history);
}

function toggleMonitor()
{
  makeRequest('setting', 'action=toggleMonitor', false);
}

function renderMonitor(data)
{
  console.log("DEBUG: renderMonitor received data", data);
  const container = document.getElementById('hw-monitor');
  if (!container) {
    console.warn("DEBUG: hw-monitor container not found");
    return;
  }
  
  if (!data.cpu && !data.gpu) {
    container.innerHTML = '';
    return;
  }

  let html = '';
  if (data.cpu && data.cpu.usage) {
    html += '<div class="hw-section cpu">';
    html += '<div class="hw-graph">';
    data.cpu.usage.forEach(u => {
      html += '<div class="hw-bar" style="height:' + u + '%"></div>';
    });
    html += '</div>';
    if (data.cpu.temp) html += '<div class="hw-stat">' + Math.round(data.cpu.temp) + '°C</div>';
    html += '</div>';
  }

  if (data.gpu) {
    html += '<div class="hw-section gpu">';
    if (data.gpu.usage) {
      html += '<div class="hw-graph">';
      Object.keys(data.gpu.usage).forEach(key => {
        const u = data.gpu.usage[key];
        html += '<div class="hw-bar gpu-bar" style="height:' + u + '%" title="' + key + '"></div>';
      });
      html += '</div>';
    }
    html += '<div class="hw-stats-column">';
    if (data.gpu.temp) html += '<div class="hw-stat">' + Math.round(data.gpu.temp) + '°C</div>';
    if (data.gpu.power) html += '<div class="hw-stat">' + Math.round(data.gpu.power) + 'W</div>';
    html += '</div></div>';
  }

  container.innerHTML = html;
}

// ===== Agentic todo list (todoWrite tool -> todo_update messages) =====

function renderTodoList(todos)
{
  var card = document.getElementById('todo-card');
  var items = document.getElementById('todo-items');
  var progress = document.getElementById('todo-progress');
  if (!card || !items) return;
  todos = todos || [];
  if (todos.length === 0)
  {
    card.classList.add('hidden');
    items.innerHTML = '';
    return;
  }
  card.classList.remove('hidden');
  items.innerHTML = '';
  var done = 0;
  for (var i = 0; i < todos.length; i++)
  {
    var t = todos[i] || {};
    var status = typeof t.status === 'string' ? t.status : 'pending';
    if (status === 'done') done++;
    var div = document.createElement('div');
    // Model-provided text goes through textContent only, never innerHTML
    div.className = 'todo-item todo-' + status.replace(/_/g, '-');
    div.textContent = t.content || '';
    items.appendChild(div);
  }
  if (progress) progress.textContent = done + '/' + todos.length;
}

function toggleTodoCard()
{
  var card = document.getElementById('todo-card');
  if (card) card.classList.toggle('collapsed');
}

// ===== SSH PTY terminal (xterm.js over /socket/terminal) =====

let termWs = null;
let term = null;
let termFit = null;
let termSegments = [];   // last 5 finished command segments {command, output, head, exit}
let termCurSeg = null;
let termInputLine = '';
let termBootSeen = false;
let pendingTerminalOutput = null;
let lastAutoReport = { key: null, time: 0 };

function toggleTerminal()
{
  var drawer = document.getElementById('terminal');
  if (!drawer) return;
  if (drawer.classList.contains('open'))
  {
    drawer.classList.remove('open');
    return;
  }
  if (!window.workingDir || window.workingDir.indexOf('SSH ') !== 0)
  {
    showNotification('Terminal needs an SSH working directory: double-click a project folder on an SSH provider first.', 8000);
    return;
  }
  drawer.classList.add('open');
  if (!term) initTerminal();
  else
  {
    if (!termWs) connectTerminal();
    setTimeout(fitTerminal, 320);
  }
}

function initTerminal()
{
  var body = document.getElementById('term-body');
  if (!body || typeof Terminal === 'undefined')
  {
    showNotification('Terminal library not loaded.', 5000);
    return;
  }
  term = new Terminal({ fontSize: 13, cursorBlink: true, theme: { background: '#1e1e1e' } });
  termFit = new FitAddon.FitAddon();
  term.loadAddon(termFit);
  term.open(body);
  // Shell integration: the injected precmd reports "exit;cwd" through OSC 7770 before every prompt
  term.parser.registerOscHandler(7770, function(payload) { onTermBoundary(payload); return true; });
  term.onData(function(d) {
    trackInputLine(d);
    if (termWs && termWs.readyState === WebSocket.OPEN) termWs.send(JSON.stringify({ type: 'data', data: b64FromText(d) }));
  });
  connectTerminal();
  setTimeout(fitTerminal, 320); // after the drawer slide-in transition
  window.addEventListener('resize', fitTerminal);
}

function connectTerminal()
{
  var proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
  termWs = new WebSocket(proto + location.host + '/socket/terminal');
  termWs.onopen = function() {
    termCurSeg = { command: '', output: '', head: null, exit: null };
    termBootSeen = false;
    termWs.send(JSON.stringify({ type: 'start', cols: term.cols, rows: term.rows }));
  };
  termWs.onmessage = function(event) {
    var msg = JSON.parse(event.data);
    if (msg.type === 'data')
    {
      var bytes = bytesFromB64(msg.data);
      term.write(bytes);
      feedSegment(textFromBytes(bytes));
    }
    else if (msg.type === 'error') showNotification('Terminal: ' + msg.message, 8000);
    else if (msg.type === 'exit')
    {
      if (term) term.write('\r\n[session ended]\r\n');
      termWs = null;
    }
  };
  termWs.onclose = function() { termWs = null; };
}

function fitTerminal()
{
  var drawer = document.getElementById('terminal');
  if (!term || !termFit || !drawer || !drawer.classList.contains('open')) return;
  try { termFit.fit(); } catch (e) { return; }
  if (termWs && termWs.readyState === WebSocket.OPEN) termWs.send(JSON.stringify({ type: 'resize', cols: term.cols, rows: term.rows }));
}

// Base64 <-> bytes: PTY data must travel as bytes because chunks can split UTF-8 mid-character
function b64FromText(s)
{
  var bytes = new TextEncoder().encode(s);
  var bin = '';
  for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin);
}

function bytesFromB64(b64)
{
  var bin = atob(b64);
  var bytes = new Uint8Array(bin.length);
  for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes;
}

function textFromBytes(bytes)
{
  return new TextDecoder('utf-8', { fatal: false }).decode(bytes);
}

// Per-command output capture, hard-capped so cat/ffmpeg floods cannot grow memory:
// keep the first 2KB once, then a rolling last 8KB
function feedSegment(text)
{
  if (!termCurSeg) termCurSeg = { command: '', output: '', head: null, exit: null };
  var seg = termCurSeg;
  seg.output += text;
  if (seg.output.length > 10240)
  {
    if (!seg.head) seg.head = seg.output.substring(0, 2048);
    seg.output = seg.output.substring(seg.output.length - 8192);
  }
}

function segmentText(seg)
{
  if (!seg) return '';
  return seg.head ? seg.head + '\n[... output truncated ...]\n' + seg.output : seg.output;
}

// Best-effort echo of what the user typed (history recall and multi-line editing are not tracked;
// the PTY echo inside the captured output covers those cases for the AI)
function trackInputLine(d)
{
  for (var i = 0; i < d.length; i++)
  {
    var c = d[i];
    if (c === '\r' || c === '\n')
    {
      if (termCurSeg && termInputLine.trim() !== '') termCurSeg.command = termInputLine.trim();
      termInputLine = '';
    }
    else if (c === '\x7f') termInputLine = termInputLine.substring(0, termInputLine.length - 1);
    else if (c >= ' ') termInputLine += c;
  }
}

// OSC 7770 fires right before each prompt: close the current segment and check for failures
function onTermBoundary(payload)
{
  var sep = payload.indexOf(';');
  var exit = parseInt(sep === -1 ? payload : payload.substring(0, sep), 10);
  var cwd = sep === -1 ? '' : payload.substring(sep + 1);
  var title = document.getElementById('term-title');
  if (title && cwd) title.textContent = cwd;

  // The first boundary belongs to the bootstrap line itself: start segmenting from here
  if (!termBootSeen)
  {
    termBootSeen = true;
    termCurSeg = { command: '', output: '', head: null, exit: null };
    return;
  }

  var seg = termCurSeg;
  seg.exit = isNaN(exit) ? null : exit;
  termSegments.push(seg);
  if (termSegments.length > 5) termSegments.shift();
  termCurSeg = { command: '', output: '', head: null, exit: null };

  maybeReportTerminalError(seg);
}

// Auto-diagnosis on failed commands: transparent — it goes through the normal chat as a visible message
function maybeReportTerminalError(seg)
{
  if (seg.exit === null || seg.exit === 0) return;
  var monitor = document.getElementById('term-ai-monitor');
  if (!monitor || !monitor.checked) return;
  if (window.aiWorking) return;
  var text = segmentText(seg);
  if (text.trim() === '') return;
  var key = (seg.command || '') + '#' + seg.exit;
  if (lastAutoReport.key === key && Date.now() - lastAutoReport.time < 60000) return;
  lastAutoReport = { key: key, time: Date.now() };

  pendingTerminalOutput = text;
  sendChatMessage('The terminal command ' + (seg.command ? '`' + seg.command + '`' : '(see attached output)') + ' failed with exit code ' + seg.exit + '. Diagnose the failure and propose a concrete fix.');
}

// "Attach to chat": capped output of the last finished command rides along with the next message
function attachTerminalContext()
{
  var seg = termSegments.length > 0 ? termSegments[termSegments.length - 1] : termCurSeg;
  var text = segmentText(seg);
  if (!text || text.trim() === '')
  {
    showNotification('No terminal output to attach yet.', 5000);
    return;
  }
  pendingTerminalOutput = text;
  var chip = document.getElementById('terminal-attach-chip');
  var chipText = document.getElementById('terminal-attach-text');
  if (chipText) chipText.textContent = text.length > 300 ? '…' + text.substring(text.length - 300) : text;
  if (chip) chip.classList.remove('hidden');
}

function clearTerminalContext()
{
  pendingTerminalOutput = null;
  var chip = document.getElementById('terminal-attach-chip');
  if (chip) chip.classList.add('hidden');
}
