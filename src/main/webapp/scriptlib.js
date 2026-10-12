

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
    spellcheck: true,
    autocorrect: true,
    autocapitalize: true,
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
  // An open image is never saved as text: the hidden editor must not write into it
  if (!editor || !currentFileUrl || isViewerFile(currentFileUrl)) {
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
  if (isViewerFile(currentFileUrl)) return;
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

  if (imgUrl && currentFileUrl === imgUrl && li.dataset.type == 3 && isImageFile(itemName) && itemPath !== imgUrl) {
    const addLayer = document.createElement('div');
    addLayer.className = 'dropdown-item';
    addLayer.textContent = 'Add as Layer';
    addLayer.onclick = function() { closeItemMenu(); addLayerFromPath(itemPath, itemName); };
    menu.appendChild(addLayer);
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

// Images open in the layer viewer instead of the text editor
const IMAGE_EXTENSIONS = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg', 'avif', 'ico'];

function isImageFile(filename) {
  if (!filename) return false;
  const lastDot = filename.lastIndexOf('.');
  if (lastDot === -1) return false;
  return IMAGE_EXTENSIONS.includes(filename.substring(lastDot + 1).toLowerCase());
}

// Videos and 3D models open in their own viewers, like images; none of them is ever treated as text
const VIDEO_EXTENSIONS = ['mp4', 'm4v', 'webm', 'mov'];
const MODEL_EXTENSIONS = ['glb'];

function fileExtension(filename) {
  if (!filename) return '';
  const lastDot = filename.lastIndexOf('.');
  return lastDot === -1 ? '' : filename.substring(lastDot + 1).toLowerCase();
}

function isVideoFile(filename) {
  return VIDEO_EXTENSIONS.includes(fileExtension(filename));
}

function is3dFile(filename) {
  return MODEL_EXTENSIONS.includes(fileExtension(filename));
}

function isViewerFile(filename) {
  return isImageFile(filename) || isVideoFile(filename) || is3dFile(filename);
}

function viewForFile(filename) {
  if (isImageFile(filename)) return 'image';
  if (isVideoFile(filename)) return 'video';
  if (is3dFile(filename)) return 'model';
  return 'editor';
}

function isSupportedFile(filename) {
  if (!filename) return false;
  if (isViewerFile(filename)) return true;
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
  flushImageSave();
  currentFileUrl = url;
  window.openedFile = url;
  updateActionButtons();
  
  // Update highlighting
  document.querySelectorAll('.file-item.opened').forEach(item => {
    item.classList.remove('opened');
  });
  el.classList.add('opened');

  showView(viewForFile(url));
  
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

// ===== Characters (co-writer): the cast is extracted from AGENTS.md on the server and cached in aether.ing.json =====
function characterStorageKey()
{
  return 'aether.character.' + (window.workingDir || '');
}

function updateCharacterBar()
{
  const bar = document.getElementById('character-bar');
  if (!bar) return;
  const on = !!(currentAgent && currentAgent.characters);
  bar.classList.toggle('hidden', !on);
  if (on) syncCharacters(false);
}

// force re-reads AGENTS.md even when it did not change; otherwise the server only answers with the cached list
function syncCharacters(force)
{
  // Quiet on purpose: wsSend would queue it with an "offline" toast, and the socket open handler asks again
  if (!(ws && ws.readyState === 1)) return;
  const btn = document.getElementById('character-resync');
  if (force && btn)
  {
    btn.disabled = true;
    setTimeout(function() { btn.disabled = false; }, 120000);
  }
  ws.send(JSON.stringify({ type: 'characters_sync', force: !!force }));
}

function renderCharacters(data)
{
  const select = document.getElementById('character-select');
  if (!select) return;
  // An answer for another project (the user switched while it was running) must not fill this list
  if (data.workingDir && window.workingDir && data.workingDir !== window.workingDir) return;
  let wanted = select.value;
  if (!wanted) try { wanted = localStorage.getItem(characterStorageKey()) || ''; } catch (e) {}
  select.textContent = '';
  const narrator = document.createElement('option');
  narrator.value = '';
  narrator.textContent = 'Narrator';
  select.appendChild(narrator);
  (data.characters || []).forEach(function(c) {
    const opt = document.createElement('option');
    opt.value = c.name;
    opt.textContent = c.name;
    select.appendChild(opt);
  });
  select.value = wanted;
  if (select.value !== wanted) select.value = '';
  const btn = document.getElementById('character-resync');
  if (btn) btn.disabled = false;
}

function onCharacterChange()
{
  const select = document.getElementById('character-select');
  try { localStorage.setItem(characterStorageKey(), select ? select.value : ''); } catch (e) {}
}

// The pictures an image agent receives, numbered exactly as the server numbers them (ImageWorker.inputImages):
// ticked images in explorer order, with the open image first when it is not ticked
let imageOrderSignature = '';

function getInputImageFiles() {
  if (!(currentAgent && currentAgent.image)) return [];
  const files = getSelectedContextFiles().filter(isImageFile);
  if (currentFileUrl && isImageFile(currentFileUrl) && files.indexOf(currentFileUrl) < 0) files.unshift(currentFileUrl);
  return files;
}

// @-mentions in the chat input: typing @ offers the ticked files. For image agents these are the numbered input
// images; the server turns "@name" into "image N" before the prompt reaches the image model
let mentionItems = [];
let mentionIndex = 0;

function mentionCandidates() {
  const names = [];
  if (currentAgent && currentAgent.image) getInputImageFiles().forEach(function(f, i) { names.push({ name: f.substring(f.lastIndexOf('/') + 1), label: 'Image ' + (i + 1) }); });
  else
  {
    const files = getSelectedContextFiles();
    if (currentFileUrl && files.indexOf(currentFileUrl) < 0) files.unshift(currentFileUrl);
    files.forEach(function(f) { names.push({ name: f.substring(f.lastIndexOf('/') + 1), label: f === currentFileUrl ? 'open' : 'ticked' }); });
  }
  return names;
}

function closeMentionPopup() {
  const pop = document.getElementById('mention-popup');
  if (pop) pop.classList.add('hidden');
  mentionItems = [];
}

function updateMentionPopup() {
  const input = document.getElementById('chat-input');
  const pop = document.getElementById('mention-popup');
  if (!input || !pop) return;
  const m = /@([^\s@]*)$/.exec(input.value.substring(0, input.selectionStart));
  if (!m) { closeMentionPopup(); return; }
  const q = m[1].toLowerCase();
  mentionItems = mentionCandidates().filter(function(c) { return c.name.toLowerCase().indexOf(q) >= 0; });
  if (!mentionItems.length) { closeMentionPopup(); return; }
  if (mentionIndex >= mentionItems.length) mentionIndex = 0;
  pop.textContent = '';
  mentionItems.forEach(function(c, i) {
    const row = document.createElement('div');
    row.className = 'mention-item' + (i === mentionIndex ? ' active' : '');
    const label = document.createElement('b');
    label.textContent = c.label;
    row.appendChild(label);
    row.appendChild(document.createTextNode(' ' + c.name));
    row.onmousedown = function(e) { e.preventDefault(); pickMention(i); };
    pop.appendChild(row);
  });
  pop.classList.remove('hidden');
}

function pickMention(i) {
  const input = document.getElementById('chat-input');
  const item = mentionItems[i];
  if (!input || !item) return;
  const before = input.value.substring(0, input.selectionStart).replace(/@[^\s@]*$/, '@' + item.name + ' ');
  const after = input.value.substring(input.selectionStart);
  input.value = before + after;
  input.selectionStart = input.selectionEnd = before.length;
  closeMentionPopup();
  input.focus();
}

document.addEventListener('input', function(e) {
  if (e.target && e.target.id === 'chat-input') { mentionIndex = 0; updateMentionPopup(); }
});
document.addEventListener('keydown', function(e) {
  if (!e.target || e.target.id !== 'chat-input' || !mentionItems.length) return;
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp')
  {
    mentionIndex = (mentionIndex + (e.key === 'ArrowDown' ? 1 : mentionItems.length - 1)) % mentionItems.length;
    updateMentionPopup();
  }
  else if (e.key === 'Enter' || e.key === 'Tab') pickMention(mentionIndex);
  else if (e.key === 'Escape') closeMentionPopup();
  else return;
  e.preventDefault();
  e.stopImmediatePropagation();
}, true);
document.addEventListener('focusout', function(e) { if (e.target && e.target.id === 'chat-input') setTimeout(closeMentionPopup, 150); });

function updateImageOrderStrip() {
  const strip = document.getElementById('image-order-strip');
  if (!strip) return;
  const files = getInputImageFiles();
  const signature = files.join('|') + '#' + currentFileUrl;
  if (signature === imageOrderSignature) return;
  imageOrderSignature = signature;
  strip.textContent = '';
  strip.classList.toggle('hidden', files.length === 0);
  files.forEach(function(f, i) {
    const chip = document.createElement('span');
    chip.className = 'image-order-chip' + (f === currentFileUrl ? ' open' : '');
    const num = document.createElement('b');
    num.textContent = 'Image ' + (i + 1);
    chip.appendChild(num);
    chip.appendChild(document.createTextNode(' ' + f.substring(f.lastIndexOf('/') + 1) + (f === currentFileUrl ? ' (open)' : '')));
    strip.appendChild(chip);
  });
}

document.addEventListener('change', function(e) {
  if (e.target && e.target.classList && e.target.classList.contains('ctx-toggle')) updateImageOrderStrip();
});
// The open file changes in many places; a cheap signature check keeps the strip in step without hooking each one
setInterval(updateImageOrderStrip, 800);

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

  const imageCont = document.getElementById('image-container');
  if (panel) panel.classList.toggle('hidden', view !== 'browser');
  if (editorCont) editorCont.classList.toggle('hidden', view !== 'editor');
  if (imageCont) imageCont.classList.toggle('hidden', view !== 'image');
  const videoCont = document.getElementById('video-container');
  const modelCont = document.getElementById('model-container');
  if (videoCont) videoCont.classList.toggle('hidden', view !== 'video');
  if (modelCont) modelCont.classList.toggle('hidden', view !== 'model');
  // A hidden player must not keep sounding, and a hidden 3D canvas must not keep the GPU busy
  if (view !== 'video') pauseVideoViewer();
  if (view === 'model') requestAnimationFrame(resizeModelViewer);

  if (view === 'video' || view === 'model') {
    const mediaPane = document.getElementById('pane-editor');
    if (workspace && mediaPane && window.innerWidth <= 768) workspace.scrollTo({ left: mediaPane.offsetLeft, behavior: 'smooth' });
  }

  if (view === 'image') {
    const editorPane = document.getElementById('pane-editor');
    if (workspace && editorPane && window.innerWidth <= 768) workspace.scrollTo({ left: editorPane.offsetLeft, behavior: 'smooth' });
    requestAnimationFrame(renderImageCanvas);
  }

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
    // Prose gets the phone keyboard's word suggestions, autocorrect and capitals; CodeMirror turns all three
    // off by default, and iOS then inserts only the first letter of a chosen suggestion. Code keeps them off
    editor.setOption("spellcheck", !isCoding);
    editor.setOption("autocorrect", !isCoding);
    editor.setOption("autocapitalize", !isCoding);
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
  else
  {
    // Kept and sent after the reconnect (phones drop the socket whenever the tab is backgrounded)
    if (wsOutbox.length < 50) wsOutbox.push(message);
    if (wsOutbox.length === 1) showNotification("Offline — your message will be sent when the connection is back.");
    wsFailCount = 0;
    webConnect();
  }
  return false;
}

// ===== Agent runs: cursors, reconnect, steering, cross-device approvals =====
let wsOutbox = [];
let runCursors = {};      // runId -> last applied event seq (replay de-duplication)
let runList = [];         // run summaries of this user (all devices)
let viewedRun = null;     // summary of the run working on the chat shown here
let wsPingTimer = null;
const aiClientId = (function() {
  var id = null;
  try { id = sessionStorage.getItem('aetherClientId'); } catch (e) {}
  if (!id)
  {
    id = 'c-' + Math.random().toString(36).substring(2) + Date.now().toString(36);
    try { sessionStorage.setItem('aetherClientId', id); } catch (e) {}
  }
  return id;
})();

function sendHello()
{
  if (!ws || ws.readyState !== 1) return;
  ws.send(JSON.stringify({ type: 'hello', clientId: aiClientId, visible: !document.hidden, cursors: runCursors }));
  while (wsOutbox.length > 0) ws.send(wsOutbox.shift());
}

function startWsKeepalive()
{
  if (wsPingTimer) clearInterval(wsPingTimer);
  wsPingTimer = setInterval(function() {
    if (ws && ws.readyState === 1) ws.send(JSON.stringify({ type: 'ping', visible: !document.hidden }));
  }, 25000);
}

// Phones freeze background tabs: coming back must reconnect at once and fetch what was missed
document.addEventListener('visibilitychange', function() {
  if (typeof wsTarget === 'undefined' || wsTarget !== 'aetherui') return;
  if (ws && ws.readyState === 1) ws.send(JSON.stringify({ type: 'ping', visible: !document.hidden }));
  else if (!document.hidden) { wsFailCount = 0; webConnect(); }
});
window.addEventListener('online', function() {
  if (typeof wsTarget === 'undefined' || wsTarget !== 'aetherui') return;
  wsFailCount = 0;
  webConnect();
});

function runRef(obj)
{
  if (window.pendingRunId) obj.runId = window.pendingRunId;
  return obj;
}

function isActiveRunState(state)
{
  return state === 'WORKING' || state === 'WAITING_INPUT' || state === 'REVIEWING';
}

function updateRunInList(run)
{
  if (!run) return;
  var found = false;
  for (var i = 0; i < runList.length; i++) if (runList[i].runId === run.runId) { runList[i] = run; found = true; }
  if (!found) runList.unshift(run);
  renderRunsPanel();
}

function renderRunsPanel()
{
  var needs = 0, active = 0;
  for (var i = 0; i < runList.length; i++)
  {
    if (runList[i].needsInput) needs++;
    if (isActiveRunState(runList[i].state)) active++;
  }
  var badge = document.getElementById('runs-badge');
  if (badge)
  {
    badge.textContent = needs > 0 ? String(needs) : (active > 0 ? String(active) : '');
    badge.classList.toggle('hidden', needs === 0 && active === 0);
    badge.classList.toggle('attention', needs > 0);
  }
  var headerBadge = document.getElementById('run-attention-badge');
  if (headerBadge)
  {
    headerBadge.textContent = String(needs);
    headerBadge.classList.toggle('hidden', needs === 0);
  }
  var baseTitle = document.title.replace(/^\(\d+\) /, '');
  document.title = needs > 0 ? '(' + needs + ') ' + baseTitle : baseTitle;

  var list = document.getElementById('runs-list');
  if (!list) return;
  list.innerHTML = '';
  if (runList.length === 0)
  {
    list.innerHTML = '<div class="run-empty">No agent runs yet.</div>';
    return;
  }
  runList.forEach(function(run) {
    var row = document.createElement('div');
    row.className = 'run-item';
    var chip = document.createElement('span');
    chip.className = 'run-chip state-' + String(run.state).toLowerCase();
    chip.textContent = runStateLabel(run);
    var title = document.createElement('div');
    title.className = 'run-title';
    title.textContent = run.objective || '(no message)';
    var meta = document.createElement('div');
    meta.className = 'run-meta';
    var where = run.workingDir ? run.workingDir.substring(run.workingDir.lastIndexOf('/') + 1) : '';
    meta.textContent = (run.behavior === 'autonomous' ? '🤖 task · ' : '💬 chat · ') + where + (run.todoTotal ? ' · ' + run.todoDone + '/' + run.todoTotal : '') + ' · ' + sessionRelativeTime(run.updated);
    var acts = document.createElement('div');
    acts.className = 'run-actions';
    var open = document.createElement('button');
    open.className = 'p-btn';
    open.textContent = 'Open';
    open.onclick = function(e) { e.stopPropagation(); openRun(run); };
    acts.appendChild(open);
    if (isActiveRunState(run.state))
    {
      var stop = document.createElement('button');
      stop.className = 'p-btn review-reject';
      stop.textContent = 'Stop';
      stop.onclick = function(e) { e.stopPropagation(); wsSend(JSON.stringify({ type: 'cancel', runId: run.runId })); };
      acts.appendChild(stop);
    }
    if (run.state === 'PARKED')
    {
      var resume = document.createElement('button');
      resume.className = 'p-btn review-approve';
      resume.textContent = 'Resume';
      resume.onclick = function(e) { e.stopPropagation(); resumeRun(run); };
      acts.appendChild(resume);
      var dismiss = document.createElement('button');
      dismiss.className = 'p-btn';
      dismiss.textContent = 'Dismiss';
      dismiss.onclick = function(e) { e.stopPropagation(); wsSend(JSON.stringify({ type: 'dismiss_run', runId: run.runId })); };
      acts.appendChild(dismiss);
    }
    row.appendChild(chip);
    row.appendChild(title);
    row.appendChild(meta);
    row.appendChild(acts);
    list.appendChild(row);
  });
}

function runStateLabel(run)
{
  if (run.state === 'WAITING_INPUT') return 'needs you';
  if (run.state === 'PARKED') return run.stopReason === 'interrupted' ? 'interrupted' : 'parked';
  if (run.state === 'REVIEWING') return 'reviewing';
  if (run.state === 'WORKING') return 'working';
  if (run.state === 'COMPLETED') return 'done';
  if (run.state === 'FAILED') return 'failed';
  if (run.state === 'CANCELLED') return 'stopped';
  return String(run.state).toLowerCase();
}

function toggleRunsPanel(event)
{
  if (event) event.stopPropagation();
  var panel = document.getElementById('runs-dropdown');
  if (!panel) return;
  var opening = !panel.classList.contains('show');
  panel.classList.toggle('show', opening);
  if (opening) wsSend(JSON.stringify({ type: 'list_runs' }));
}

// A run lives in one chat of one project: bring this device to that chat
function openRun(run)
{
  var panel = document.getElementById('runs-dropdown');
  if (panel) panel.classList.remove('show');
  if (run.workingDir && run.workingDir !== window.workingDir)
  {
    window.location.href = '/run/' + run.runId;
    return;
  }
  if (run.chatSessionId && run.chatSessionId !== window.currentChatSessionId) wsSend(JSON.stringify({ type: 'switch_session', id: run.chatSessionId }));
  showPane('chat');
}

function resumeRun(run)
{
  if (run.workingDir && run.workingDir !== window.workingDir)
  {
    showAlert('Open the project ' + run.workingDir + ' first, then resume the run from there.', 'Resume run');
    return;
  }
  wsSend(JSON.stringify({ type: 'resume_run', runId: run.runId }));
}

// Deep link /run/<id> (from a push notification or another device)
function openRunDeepLink(runId, wd, chatId)
{
  if (!wd) { showNotification('This run is no longer available.'); return; }
  window.workingDir = wd;
  window.expandTarget = wd;
  try { history.replaceState(null, '', '/tree/' + wd); } catch (e) {}
  makeRequest('tree/' + wd, '', false, function() {
    if (chatId) wsSend(JSON.stringify({ type: 'switch_session', id: chatId }));
    showPane('chat');
  });
}

// Scrolls the phone layout to one pane (the desktop shows all three anyway)
function showPane(name)
{
  var workspace = document.getElementById('workspace');
  var pane = document.getElementById('pane-' + name);
  if (workspace && pane && window.innerWidth <= 768) workspace.scrollTo({ left: pane.offsetLeft, behavior: 'smooth' });
}

function setViewedRun(run)
{
  viewedRun = run && (isActiveRunState(run.state) || run.state === 'PARKED') ? run : null;
  renderRunBanner();
  if (viewedRun && isActiveRunState(viewedRun.state))
  {
    if (!window.aiWorking) { setAiWorking(true); startAiStatus('Working…'); }
  }
  else if (window.aiWorking && run) setAiWorking(false);
}

function renderRunBanner()
{
  var banner = document.getElementById('run-banner');
  if (!banner) return;
  banner.innerHTML = '';
  if (!viewedRun)
  {
    banner.classList.add('hidden');
    return;
  }
  banner.classList.remove('hidden');
  var run = viewedRun;
  var chip = document.createElement('span');
  chip.className = 'run-chip state-' + String(run.state).toLowerCase();
  chip.textContent = runStateLabel(run);
  var text = document.createElement('span');
  text.className = 'run-banner-text';
  text.textContent = (run.behavior === 'autonomous' ? '🤖 ' : '') + (run.objective || '') + (run.todoTotal ? ' · ' + run.todoDone + '/' + run.todoTotal : '');
  banner.appendChild(chip);
  banner.appendChild(text);
  var acts = document.createElement('span');
  acts.className = 'run-actions';
  function btn(label, cls, fn)
  {
    var b = document.createElement('button');
    b.className = 'p-btn ' + (cls || '');
    b.textContent = label;
    b.onclick = fn;
    acts.appendChild(b);
  }
  if (run.state === 'PARKED')
  {
    btn('Resume', 'review-approve', function() { resumeRun(run); });
    btn('Dismiss', '', function() { wsSend(JSON.stringify({ type: 'dismiss_run', runId: run.runId })); });
  }
  else
  {
    if (run.behavior === 'autonomous') btn('Pause', '', function() { wsSend(JSON.stringify({ type: 'pause', runId: run.runId })); showNotification('The run pauses at the next step.'); });
    btn('Changes', '', function() { wsSend(JSON.stringify({ type: 'get_changes', runId: run.runId })); });
    btn('Stop', 'review-reject', function() { stopAiActivity(); });
  }
  banner.appendChild(acts);
}

// Plan / budget / human gate / pause cards: answered from any device with decision_response
function showDecisionCard(data)
{
  var historyEl = document.getElementById('chat-history');
  if (!historyEl) return;
  if (historyEl.querySelector('[data-request-id="' + data.requestId + '"]')) return;
  wasAtBottom = isAtBottom(historyEl);
  var block = document.createElement('div');
  block.className = 'msg ai decision-block';
  block.dataset.requestId = data.requestId;
  block.dataset.runId = data.runId;
  var header = document.createElement('div');
  header.className = 'question-header';
  var body = document.createElement('div');
  body.className = 'decision-body';
  var actions = document.createElement('div');
  actions.className = 'question-actions';
  function answer(obj) { freezeDecision(block, '✅ Answered'); wsSend(JSON.stringify({ type: 'decision_response', runId: data.runId, requestId: data.requestId, answer: obj })); }
  function button(label, cls, fn) { var b = document.createElement('button'); b.className = 'p-btn ' + (cls || ''); b.textContent = label; b.onclick = fn; actions.appendChild(b); return b; }

  if (data.type === 'plan_request')
  {
    header.textContent = '🗺️ Plan for approval';
    if (data.summary)
    {
      var sum = document.createElement('div');
      sum.className = 'patch-explain';
      sum.textContent = data.summary;
      body.appendChild(sum);
    }
    var dodLabel = document.createElement('div');
    dodLabel.className = 'decision-label';
    dodLabel.textContent = 'Definition of Done (one per line)';
    var dod = document.createElement('textarea');
    dod.className = 'decision-edit';
    dod.value = (data.dod || []).join('\n');
    var todoLabel = document.createElement('div');
    todoLabel.className = 'decision-label';
    todoLabel.textContent = 'Todo list (one per line)';
    var todos = document.createElement('textarea');
    todos.className = 'decision-edit';
    todos.value = (data.todos || []).join('\n');
    var note = document.createElement('input');
    note.type = 'text';
    note.className = 'review-reason-input';
    note.placeholder = 'Note for the AI (optional)...';
    body.appendChild(dodLabel);
    body.appendChild(dod);
    body.appendChild(todoLabel);
    body.appendChild(todos);
    body.appendChild(note);
    function lines(t) { return t.value.split('\n').map(function(x) { return x.trim(); }).filter(function(x) { return x !== ''; }); }
    button('✔ Approve', 'review-approve', function() { answer({ decision: 'approve', dod: lines(dod), todos: lines(todos), note: note.value.trim() }); });
    button('✖ Reject', 'review-reject', function() { answer({ decision: 'reject', note: note.value.trim() }); });
  }
  else if (data.type === 'budget_request')
  {
    header.textContent = '⏳ Budget reached';
    body.textContent = data.message || 'The run used its model-call budget.';
    button('+10', 'review-approve', function() { answer({ extend: 10 }); });
    button('+25', 'review-approve', function() { answer({ extend: 25 }); });
    button('+50', 'review-approve', function() { answer({ extend: 50 }); });
    button('Stop here', 'review-reject', function() { answer({ extend: 0 }); });
  }
  else if (data.type === 'human_gate')
  {
    header.textContent = '🏁 Final approval';
    var msg = document.createElement('div');
    msg.innerHTML = mdToHtml(data.message || '');
    body.appendChild(msg);
    if (data.changes && data.changes.length)
    {
      var ch = document.createElement('div');
      ch.className = 'decision-label';
      ch.textContent = 'Changed: ' + data.changes.join(', ');
      body.appendChild(ch);
    }
    var hnote = document.createElement('input');
    hnote.type = 'text';
    hnote.className = 'review-reason-input';
    hnote.placeholder = 'What should change? (for Request changes)';
    body.appendChild(hnote);
    button('✔ Accept', 'review-approve', function() { answer({ decision: 'accept' }); });
    button('↺ Request changes', 'review-reject', function() { answer({ decision: 'revise', note: hnote.value.trim() }); });
  }
  else if (data.type === 'pause_request')
  {
    header.textContent = '⏸️ Paused';
    body.textContent = data.message || 'The run is paused.';
    button('▶ Resume', 'review-approve', function() { answer({ resume: true }); });
  }
  block.appendChild(header);
  block.appendChild(body);
  block.appendChild(actions);
  historyEl.appendChild(block);
  scrollToBottomIfWatching(historyEl);
}

function freezeDecision(block, label)
{
  var actions = block.querySelector('.question-actions');
  if (actions) actions.remove();
  var inputs = block.querySelectorAll('textarea, input');
  for (var i = 0; i < inputs.length; i++) inputs[i].disabled = true;
  var header = block.querySelector('.question-header');
  if (header && label) header.textContent = label + ' — ' + header.textContent.replace(/^[^ ]+ /, '');
}

// Another device (or this one) answered: close every card still waiting for that answer
function onApprovalResolved(data)
{
  if (data.requestId)
  {
    var blocks = document.querySelectorAll('.decision-block[data-request-id="' + data.requestId + '"]');
    for (var i = 0; i < blocks.length; i++) if (blocks[i].querySelector('.question-actions')) freezeDecision(blocks[i], '✅ Answered');
    return;
  }
  if (data.kind === 'permission')
  {
    if (pendingEditReview) resolveEditReview(!!data.granted, true);
    var modal = document.getElementById('permission-modal');
    if (modal) modal.classList.add('hidden');
    var patches = document.querySelectorAll('.patch-block');
    for (var j = 0; j < patches.length; j++)
    {
      var acts = patches[j].querySelector('.patch-actions');
      if (!acts) continue;
      acts.remove();
      var header = patches[j].querySelector('.patch-header');
      if (header) header.textContent = (data.granted ? '✅ Approved — ' : '❌ Rejected — ') + header.textContent;
    }
  }
  else if (data.kind === 'question')
  {
    var qs = document.querySelectorAll('.question-block');
    for (var k = 0; k < qs.length; k++)
    {
      var qa = qs[k].querySelector('.question-actions');
      if (!qa) continue;
      qa.remove();
      var inputs = qs[k].querySelectorAll('input, button');
      for (var m = 0; m < inputs.length; m++) inputs[m].disabled = true;
      var qh = qs[k].querySelector('.question-header');
      if (qh) qh.textContent = '✅ Answered';
    }
  }
}

function appendGateCard(data)
{
  var historyEl = document.getElementById('chat-history');
  if (!historyEl) return;
  var div = document.createElement('div');
  div.className = 'msg tool gate-card ' + (data.pass ? 'gate-pass' : 'gate-fail');
  div.textContent = (data.pass ? '✅ ' : '⛔ ') + 'Stop gate "' + data.gate + '": ' + (data.detail || '');
  historyEl.appendChild(div);
  scrollToBottomIfWatching(historyEl);
}

function appendReviewCard(data)
{
  var historyEl = document.getElementById('chat-history');
  if (!historyEl) return;
  var div = document.createElement('div');
  div.className = 'msg ai review-card verdict-' + data.verdict;
  var head = document.createElement('div');
  head.className = 'question-header';
  head.textContent = '🧐 Reviewer (' + (data.model || '') + '): ' + String(data.verdict).toUpperCase();
  div.appendChild(head);
  var issues = data.issues || [];
  for (var i = 0; i < issues.length; i++)
  {
    var it = document.createElement('div');
    it.className = 'review-issue sev-' + (issues[i].severity || 'low');
    it.textContent = '[' + (issues[i].severity || '?') + '] ' + (issues[i].file ? issues[i].file + ': ' : '') + (issues[i].problem || '');
    div.appendChild(it);
  }
  var checks = data.dodChecks || [];
  for (var j = 0; j < checks.length; j++)
  {
    var c = document.createElement('div');
    c.className = 'review-check';
    c.textContent = (checks[j].pass ? '☑ ' : '☐ ') + checks[j].item;
    div.appendChild(c);
  }
  historyEl.appendChild(div);
  scrollToBottomIfWatching(historyEl);
}

function showRunChanges(data)
{
  var modal = document.getElementById('run-changes-modal');
  var body = document.getElementById('run-changes-body');
  if (!modal || !body) return;
  body.innerHTML = '';
  var files = document.createElement('div');
  files.className = 'decision-label';
  files.textContent = data.files && data.files.length ? 'Changed files: ' + data.files.join(', ') : 'No file changes recorded for this run.';
  body.appendChild(files);
  if (data.diff)
  {
    var pre = document.createElement('pre');
    pre.className = 'diff-view';
    data.diff.split('\n').forEach(function(line) {
      var span = document.createElement('span');
      if (line.indexOf('+') === 0 && line.indexOf('+++') !== 0) span.className = 'diff-line-add';
      else if (line.indexOf('-') === 0 && line.indexOf('---') !== 0) span.className = 'diff-line-del';
      else if (line.indexOf('@@') === 0) span.className = 'diff-line-hunk';
      span.textContent = line + '\n';
      pre.appendChild(span);
    });
    body.appendChild(pre);
  }
  else if (data.git === false)
  {
    var hint = document.createElement('div');
    hint.className = 'run-empty';
    hint.textContent = 'A line-by-line diff is available for git projects on SSH.';
    body.appendChild(hint);
  }
  modal.classList.remove('hidden');
}

// Web Push: lets the phone ring when a run needs input or finishes while no Aether tab is open
function enablePushNotifications()
{
  if (!('serviceWorker' in navigator) || !('PushManager' in window))
  {
    showAlert('This browser does not support push notifications. On iPhone, add Aether to the Home Screen first.', 'Notifications');
    return;
  }
  if (!window.aetherVapidKey) { showAlert('Push is not configured on this server.', 'Notifications'); return; }
  navigator.serviceWorker.register('/sw.js').then(function(reg) {
    return Notification.requestPermission().then(function(perm) {
      if (perm !== 'granted') throw new Error('permission ' + perm);
      return reg.pushManager.getSubscription().then(function(existing) {
        if (existing) return existing;
        var key = window.aetherVapidKey.replace(/-/g, '+').replace(/_/g, '/');
        while (key.length % 4) key += '=';
        var raw = atob(key);
        var bytes = new Uint8Array(raw.length);
        for (var i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
        return reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: bytes });
      });
    });
  }).then(function(sub) {
    wsSend(JSON.stringify({ type: 'push_subscribe', subscription: sub.toJSON() }));
  }).catch(function(e) {
    showAlert('Could not enable notifications: ' + e.message, 'Notifications');
  });
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
  sendHello();
  startWsKeepalive();
  requestChatSyncIfEmpty();
  updateCharacterBar();
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

  var imageModelSelect = document.getElementById('image-model-select');
  if (cfg.model && modelSelect) modelSelect.value = cfg.model;
  if (cfg.image_model && imageModelSelect) imageModelSelect.value = cfg.image_model;
  var videoModelSelect = document.getElementById('video-model-select');
  var model3dSelect = document.getElementById('model3d-model-select');
  if (cfg.video_model && videoModelSelect) videoModelSelect.value = cfg.video_model;
  if (cfg.model3d_model && model3dSelect) model3dSelect.value = cfg.model3d_model;
  if (cfg.thinking_mode && thinkingSelect) thinkingSelect.value = cfg.thinking_mode;
  if (cfg.agent_id && agentSelect)
  {
    agentSelect.value = cfg.agent_id;
    onAgentChange();
  }
  // menu.jsp re-renders the selectors hidden: re-apply the agent's visibility even without a saved agent
  else if (agentSelect) onAgentChange();
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
  // Run events carry a sequence number: a replay after a reconnect must not apply anything twice
  if (data.runId && data.seq)
  {
    if (data.seq <= (runCursors[data.runId] || 0)) return;
    runCursors[data.runId] = data.seq;
  }
  if (data.runId && (data.type === 'permission_request' || data.type === 'user_question')) window.pendingRunId = data.runId;
  if (data.type === 'run_list')
  {
    runList = data.runs || [];
    renderRunsPanel();
    return;
  }
  if (data.type === 'run_update')
  {
    updateRunInList(data.run);
    if (data.viewing) setViewedRun(data.run);
    return;
  }
  if (data.type === 'view_state')
  {
    setViewedRun(data.run);
    if (!data.run && window.aiWorking) setAiWorking(false);
    return;
  }
  if (data.type === 'run_attach')
  {
    runCursors[data.runId] = data.fromSeq;
    return;
  }
  if (data.type === 'user_message')
  {
    // The sender shows its own message at once; other devices learn about it here
    if (data.origin !== aiClientId) appendMessage('user', data.content);
    return;
  }
  if (data.type === 'approval_resolved') { onApprovalResolved(data); return; }
  if (data.type === 'plan_request' || data.type === 'budget_request' || data.type === 'human_gate' || data.type === 'pause_request') { showDecisionCard(data); return; }
  if (data.type === 'gate_result') { appendGateCard(data); return; }
  if (data.type === 'review_start') { updateAiStatus({ stage: 'thinking', text: 'Reviewer ' + (data.model || '') + ' is checking the work…' }); return; }
  if (data.type === 'review_verdict') { appendReviewCard(data); return; }
  if (data.type === 'run_changes') { showRunChanges(data); return; }
  if (data.type === 'steer_applied') { updateAiStatus({ stage: 'sending', text: 'Applying your message…' }); return; }
  if (data.type === 'error') { showNotification(data.message, 5000); return; }
  if (data.type === 'pong' || data.type === 'checkpoint' || data.type === 'run_created' || data.type === 'run_state') return;
  // Editor insertions belong to the device that asked for them
  if ((data.type === 'plaintext' || data.type === 'variants') && data.origin && data.origin !== aiClientId) return;
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
    // Stream content directly into the editor at end of document (never into a hidden editor behind an image)
    if (editor && !isViewerFile(currentFileUrl))
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
      var extractedVariants = extractVariantList(aiResponseBuffer);
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
    if ((data.eval_count || data.total_duration) && data.responseType !== 'image' && data.responseType !== 'video' && data.responseType !== '3d') renderAiStats(data);

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
  } else if (data.type === 'characters_update')
  {
    renderCharacters(data);
  } else if (data.type === 'chat_status')
  {
    updateAiStatus(data);
  } else if (data.type === 'image_done')
  {
    onImageDone(data);
  } else if (data.type === 'media_done')
  {
    onMediaDone(data);
  } else if (data.type === 'monitor')
  {
    if (typeof renderMonitor === 'function') renderMonitor(data);
  }
}

// Pulls the variant texts out of a model's JSON reply regardless of the exact schema it chose;
// shared by the live chat_done and by saved command entries re-rendered from history
function extractVariantList(raw)
{
  var json = extractAndRepairJson(raw);
  var extractedVariants = [];
  if (!json) return extractedVariants;

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
  return extractedVariants;
}

// Puts a message back into the chat input to edit and resend it
function reuseInChat(text)
{
  const input = document.getElementById('chat-input');
  if (!input) return;
  input.value = text;
  input.focus();
  input.setSelectionRange(text.length, text.length);
}

// A saved non-chatlog command: the request as the user sent it, then its result as it was delivered
function renderCommandEntry(entry, timeStr)
{
  const label = entry.label || entry.commandId || 'Command';
  appendMessage('user', entry.input ? '**' + label + '**: ' + entry.input : '**' + label + '**', null, timeStr);
  if (entry.error)
  {
    appendMessage('ai', '❌ **Error**: ' + entry.error, null, timeStr, entry.model);
    return;
  }
  if (entry.responseType === 'variants_json')
  {
    const variants = extractVariantList(entry.content || '');
    if (variants.length > 0) renderVariants(variants, entry.target, entry.model, timeStr);
    else appendMessage('ai', entry.content || '', null, timeStr, entry.model);
  }
  else if (entry.responseType === 'image') appendImageResult(entry.content, entry.input, timeStr, entry.model);
  else if (entry.responseType === 'video' || entry.responseType === '3d') appendMediaResult(entry.responseType, entry.content, timeStr, entry.model);
  else appendMessage('ai', entry.content || '', null, timeStr, entry.model);
}

// Chat card for an AI image: a thumbnail that opens the file
function appendImageResult(path, prompt, timeStr, model)
{
  if (!path) return;
  const msgDiv = appendMessage('ai', '🖼️ ' + pathName(path), null, timeStr, model);
  if (!msgDiv) return;
  const img = document.createElement('img');
  img.className = 'chat-image-thumb';
  img.src = imageUrlFor(path);
  img.alt = prompt || '';
  img.title = 'Open ' + pathName(path);
  img.onclick = function() { openFileInEditor(path); };
  msgDiv.querySelector('.msg-content').appendChild(img);
  scrollToBottomIfWatching(document.getElementById('chat-history'));
}

function onImageDone(data)
{
  appendImageResult(data.path, data.prompt, null, data.model);
  refreshTreeExplorer();
  if (data.imagePath && data.imagePath === imgUrl && imgDoc)
  {
    // The server already appended this layer to the layer file: mirror it so the next autosave keeps it
    if (data.layer && !imgDoc.layers.some(function(l) { return l.id === data.layer.id; }))
    {
      imgDoc.layers.push(data.layer);
      imgSelectedId = data.layer.id;
      loadLayerImages(function() { renderImageCanvas(); renderLayerPanel(); });
    }
  }
  else if (!data.imagePath) openFileInEditor(data.path);
}

// Image settings from the chat pane, sent with every message of an image agent
function imageParamsFromPane()
{
  const val = function(id) { const el = document.getElementById(id); return el ? el.value : null; };
  const chk = function(id) { const el = document.getElementById(id); return !!(el && el.checked); };
  return {
    size: val('image-size') || 'auto',
    steps: parseInt(val('image-steps'), 10) || 14,
    transparent: chk('image-transparent'),
    source: val('image-source') || 'canvas',
    vision: chk('image-vision'),
    selected: imgSelectedId || ''
  };
}

// ===== Live request status (chat_status messages) =====
let aiStatusStart = 0;
let aiStatusTimer = null;
let aiStatusText = '';
let aiStatusTtft = null;
let aiStatusEstimate = null;

function formatSeconds(s)
{
  s = Math.max(0, Math.round(s));
  return s < 60 ? s + 's' : Math.floor(s / 60) + 'm' + String(s % 60).padStart(2, '0') + 's';
}

function startAiStatus(text)
{
  aiStatusStart = Date.now();
  aiStatusTtft = null;
  aiStatusEstimate = null;
  aiStatusText = text;
  if (!aiStatusTimer) aiStatusTimer = setInterval(renderAiStatus, 500);
  renderAiStatus();
}

function updateAiStatus(data)
{
  // A reconnect in the middle of a turn resumes the line without a local start
  if (!aiStatusTimer) startAiStatus(data.text);
  aiStatusText = data.text;
  if (data.ttftMs) aiStatusTtft = data.ttftMs;
  if (data.estimateSec) aiStatusEstimate = { start: Date.now(), sec: data.estimateSec };
  else if (data.stage !== 'image') aiStatusEstimate = null;
  renderAiStatus();
}

function renderAiStatus()
{
  const el = document.getElementById('ai-status');
  if (!el) return;
  let text = '⏳ ' + aiStatusText + ' · ' + formatSeconds((Date.now() - aiStatusStart) / 1000);
  if (aiStatusTtft) text += ' · first token ' + (aiStatusTtft / 1000).toFixed(1) + 's';
  if (aiStatusEstimate)
  {
    const left = aiStatusEstimate.sec - (Date.now() - aiStatusEstimate.start) / 1000;
    text += left > 0 ? ' · ~' + formatSeconds(left) + ' left' : ' · longer than estimated';
  }
  el.textContent = text;
  el.classList.remove('hidden');
}

function stopAiStatus()
{
  if (aiStatusTimer) clearInterval(aiStatusTimer);
  aiStatusTimer = null;
  const el = document.getElementById('ai-status');
  if (el) el.classList.add('hidden');
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
  flushImageSave();
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
  showView(viewForFile(fullPath));
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

function renderVariants(variants, target, model, timeStr)
{
  const history = document.getElementById('chat-history');
  wasAtBottom = isAtBottom(history);
  
  // Remove temporary indicator
  var receivingEl = document.getElementById('variants-receiving');
  if (receivingEl) receivingEl.remove();

  // Create a proper AI message block for the variants
  const msgDiv = document.createElement('div');
  msgDiv.className = 'msg ai';
  
  const time = timeStr || new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const roleName = model || 'AI';
  msgDiv.innerHTML = '<div class="msg-meta"><span class="msg-role-time"><span class="msg-role">' + roleName + '</span><span class="msg-time">' + time + '</span></span><span class="msg-actions"><button class="reuse-msg-btn" title="Reuse in chat input">↩</button><button class="copy-msg-btn" title="Copy Message">📋</button></span></div>';
  // Copy and reuse act on all variants at once; each variant button still inserts only itself
  const allText = variants.map(function(v) { return typeof v === 'string' ? v : (v.text || '').toString(); }).join('\n\n');
  msgDiv.querySelector('.reuse-msg-btn').onclick = function(e) { e.stopPropagation(); reuseInChat(allText); };
  msgDiv.querySelector('.copy-msg-btn').onclick = function(e) {
    e.stopPropagation();
    navigator.clipboard.writeText(allText).then(function() { showNotification("Copied to clipboard"); });
  };
  
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

  // Image agents drive a second, image-only model plus the image settings; text agents never show them
  const isImageAgent = !!(currentAgent && currentAgent.image);
  const imageModelSelect = document.getElementById('image-model-select');
  if (imageModelSelect) imageModelSelect.classList.toggle('hidden', !isImageAgent);
  const imageControls = document.getElementById('image-ai-controls');
  if (imageControls) imageControls.classList.toggle('hidden', !isImageAgent);
  updateImageOrderStrip();
  updateCharacterBar();
  updateMediaControls();
  const taskOptions = document.getElementById('task-options');
  if (taskOptions) taskOptions.classList.toggle('hidden', !(currentAgent && currentAgent.behavior === 'autonomous'));
  fillReviewerSelect();

  console.log('Agent changed to:', currentAgent ? currentAgent.id : 'null', 'Commands:', currentAgent && currentAgent.commands ? currentAgent.commands.length : 0);
  renderAgentCommands();
}

// Reviewer choices mirror the text models of the model select; empty means no reviewer gate
function fillReviewerSelect()
{
  var sel = document.getElementById('task-reviewer-select');
  var models = document.getElementById('model-select');
  if (!sel || !models) return;
  var previous = sel.value || (currentAgent && currentAgent.reviewer ? currentAgent.reviewer.model : '');
  sel.innerHTML = '';
  var none = document.createElement('option');
  none.value = '';
  none.textContent = 'Reviewer: none';
  sel.appendChild(none);
  for (var i = 0; i < models.options.length; i++)
  {
    var o = document.createElement('option');
    o.value = models.options[i].value;
    o.textContent = 'Reviewer: ' + models.options[i].textContent;
    sel.appendChild(o);
  }
  if (previous) sel.value = previous;
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
  // The server composes the canvas from the saved layer file: an unsaved move must land before the request
  if (imgSaveTimer || imgSaving)
  {
    flushImageSave(function() { sendChatMessage(message, commandId, commandLabel); });
    return;
  }
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
  // Autonomous task agents: per-run plan approval and reviewer model
  if (currentAgent && currentAgent.behavior === 'autonomous')
  {
    var planGate = document.getElementById('task-plan-gate');
    var reviewer = document.getElementById('task-reviewer-select');
    payload.plan_gate = planGate ? planGate.checked : true;
    if (reviewer && reviewer.value) payload.reviewer_model = reviewer.value;
  }
  // While a run works on this chat the message steers it; interrupt mode also cuts the current answer
  if (window.aiWorking && window.steerInterrupt) payload.steer_mode = 'interrupt';
  window.steerInterrupt = false;
  if (currentAgent && currentAgent.characters)
  {
    const characterSelect = document.getElementById('character-select');
    if (characterSelect && characterSelect.value) payload.character = characterSelect.value;
  }
  ['video', 'model3d'].forEach(function(kind) {
    if (!(currentAgent && currentAgent[kind])) return;
    const mediaModel = document.getElementById(kind + '-model-select');
    if (mediaModel && mediaModel.value) payload[kind + '_model_id'] = mediaModel.value;
    payload[kind + '_params'] = mediaParamsFromPane(kind);
  });
  if (currentAgent && currentAgent.image)
  {
    const imageModel = document.getElementById('image-model-select');
    if (imageModel && imageModel.value) payload.image_model_id = imageModel.value;
    payload.image_params = imageParamsFromPane();
  }
  var steering = !!window.aiWorking;
  wsSend(JSON.stringify(payload));
  clearTerminalContext();
  if (steering) return;
  setAiWorking(true);
  startAiStatus('Sending…');
}

function sendSteerInterrupt()
{
  window.steerInterrupt = true;
  sendChatMessage();
}

function stopAiActivity()
{
  resolveEditReview(false, true);
  var stop = { type: 'stop' };
  if (viewedRun) stop.runId = viewedRun.runId;
  wsSend(JSON.stringify(stop));
  setAiWorking(false);
}

function setAiWorking(working)
{
  window.aiWorking = working; // terminal auto-error reporting must not interrupt a running turn
  const sendBtn = document.getElementById('send-btn');
  const stopBtn = document.getElementById('stop-btn');
  const interruptBtn = document.getElementById('steer-interrupt-btn');
  const input = document.getElementById('chat-input');
  // Send stays available while the AI works: a message then steers the running task
  if (sendBtn) sendBtn.classList.remove('hidden');
  if (working)
  {
    if (stopBtn) stopBtn.classList.remove('hidden');
    if (interruptBtn) interruptBtn.classList.remove('hidden');
    if (input) input.placeholder = 'Steer the running task… (applied after the current step)';
  }
  else
  {
    if (stopBtn) stopBtn.classList.add('hidden');
    if (interruptBtn) interruptBtn.classList.add('hidden');
    if (input) input.placeholder = 'Ask AI...';
    stopAiStatus();
  }
}

function respondPermission(granted, always)
{
  var response = runRef({ type: 'permission_response', granted: granted });
  if (granted && always) response.always = true;
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
    var alwaysBtn = document.getElementById('permission-always-btn');
    if (alwaysBtn) alwaysBtn.classList.toggle('hidden', !data.canAlways);
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
  if (data.canAlways) block.dataset.canAlways = '1';
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
  if (block.dataset.canAlways === '1')
  {
    var always = document.createElement('button');
    always.className = 'p-btn review-approve';
    always.textContent = '✔ Always (this run)';
    always.title = 'Approve, and let this run use the same tool without asking again';
    always.onclick = function() { resolveChatPatch(block, true, null, true); };
    actions.appendChild(always);
  }
  actions.appendChild(reject);
}

function resolveChatPatch(block, granted, reason, always)
{
  var actions = block.querySelector('.patch-actions');
  if (actions) actions.remove();
  var header = block.querySelector('.patch-header');
  if (header) header.textContent = (granted ? '✅ Approved — ' : '❌ Rejected — ') + header.textContent;
  var response = runRef({ type: 'permission_response', granted: granted });
  if (!granted && reason) response.reason = reason;
  if (granted && always) response.always = true;
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

  wsSend(JSON.stringify(runRef({ type: 'question_response', answers: answers })));
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
    var response = runRef({ type: 'permission_response', granted: granted });
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
  let html = '<div class="msg-meta"><span class="msg-role-time"><span class="msg-role">' + roleName + '</span><span class="msg-time">' + time + '</span></span><span class="msg-actions"><button class="reuse-msg-btn" title="Reuse in chat input">↩</button><button class="copy-msg-btn" title="Copy Message">📋</button></span></div>';

  if (thinking)
    html += '<div class="thinking-block collapsed" onclick="this.classList.toggle(\'collapsed\')"><div class="thinking-header" onclick="event.stopPropagation(); this.closest(\'.thinking-block\').classList.toggle(\'collapsed\');">Thought Process ▾</div><div class="thinking-content">' + mdToHtml(thinking) + '</div></div>';

  html += '<div class="msg-content">' + mdToHtml(content) + '</div>';
  msgDiv.innerHTML = html;
  
  // Read at click time: a live-streamed reply starts empty and grows in dataset.raw
  function messageText()
  {
    let text = msgDiv.dataset.raw || content || '';
    if (role === 'user')
    {
      const prefixMatch = text.match(/^\*\*(.*?)\*\*: /);
      if (prefixMatch) text = text.substring(prefixMatch[0].length);
    }
    return text;
  }

  const reuseBtn = msgDiv.querySelector('.reuse-msg-btn');
  if (reuseBtn) reuseBtn.onclick = function(e) { e.stopPropagation(); reuseInChat(messageText()); };

  const copyBtn = msgDiv.querySelector('.copy-msg-btn');
  if (copyBtn)
  {
    copyBtn.onclick = function(e) {
      e.stopPropagation();
      let textToCopy = messageText();
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
      else if (role === 'command')
      {
         renderCommandEntry(msg, timeStr);
      }
      else if (role === 'assistant' && msg.tool_calls)
      {
         if (content) appendMessage('ai', content, thinking, timeStr, msg.model);
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
  // The last AI message, even when a tool card or the receiving placeholder sits after it
  const aiMsgs = history.querySelectorAll('.msg.ai');
  const lastMsg = aiMsgs.length ? aiMsgs[aiMsgs.length - 1] : null;
  if (!lastMsg) return;
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
                       '<div class="stats-item"><span class="stats-label">Generated Tokens:</span><span class="stats-value">' + (stats.stats_approx ? '~' : '') + (stats.eval_count || 0) + '</span></div>' +
                       '<div class="stats-item"><span class="stats-label">Generated TPS:</span><span class="stats-value">' + genTps + '</span></div>' +
                       '<div class="stats-item"><span class="stats-label">First Token:</span><span class="stats-value">' + (stats.ttft_ms ? (stats.ttft_ms / 1000).toFixed(2) + 's' : '—') + '</span></div>' +
                       '<div class="stats-item"><span class="stats-label">Total Time:</span><span class="stats-value">' + (stats.total_duration ? (stats.total_duration / 1e9).toFixed(1) + 's' : '—') + '</span></div>' +
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

// ===== Image viewer: a layered canvas backed by "<image>.layers.json" =====
// A layer is drawn exactly as ImageWorker.compose draws it on the server: translate to (x, y),
// rotate, scale, then the image centred on that point. Keep the two in step, or the AI edits a
// picture that differs from what the user sees.
const IMAGE_SERVER_READABLE = ['png', 'jpg', 'jpeg', 'gif', 'bmp'];
const IMAGE_HANDLE_RADIUS = 18;
let imgUrl = null;            // full path of the open image
let imgDoc = null;            // { version, width, height, layers: [{ id, name, src | url, x, y, scale, rotation, opacity, visible }] }
let imgCache = {};            // layer file path -> loaded HTMLImageElement (null when it failed to load)
let imgSelectedId = null;
let imgView = { scale: 1, ox: 0, oy: 0 };
let imgDrag = null;
let imgSaveTimer = null;
let imgSaving = false;
let imgSaveWaiters = [];
let imgViewerReady = false;
let imgChecker = null;

function encodePath(path)
{
  return path.split('/').map(encodeURIComponent).join('/');
}

function imageUrlFor(path)
{
  return '/image/' + encodePath(path);
}

function pathDir(path)
{
  const i = path.lastIndexOf('/');
  return i === -1 ? '' : path.substring(0, i);
}

function pathName(path)
{
  const i = path.lastIndexOf('/');
  return i === -1 ? path : path.substring(i + 1);
}

function pathExt(path)
{
  const n = pathName(path);
  const i = n.lastIndexOf('.');
  return i === -1 ? '' : n.substring(i + 1).toLowerCase();
}

function siblingPath(of, rel)
{
  return (pathDir(of) ? pathDir(of) + '/' : '') + rel;
}

// A layer names its file relative to the image's folder ("src") or by full path ("url")
function layerFilePath(layer, baseUrl)
{
  return layer.url ? layer.url : siblingPath(baseUrl || imgUrl, layer.src);
}

function fileTimestamp()
{
  const d = new Date();
  const p = function(n) { return String(n).padStart(2, '0'); };
  return d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + '-' + p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds());
}

function selectedLayer()
{
  if (!imgDoc || !imgSelectedId) return null;
  for (var i = 0; i < imgDoc.layers.length; i++) if (imgDoc.layers[i].id === imgSelectedId) return imgDoc.layers[i];
  return null;
}

// Called by read.jsp when the opened file is an image; the buffer holds its layer file, or nothing
function openImageViewer()
{
  ensureImageViewer();
  const buffer = document.getElementById('file-content-buffer');
  const raw = buffer ? buffer.value : '';
  if (buffer) buffer.value = '';
  const openedFor = currentFileUrl;
  imgUrl = openedFor;
  imgCache = {};
  imgSelectedId = null;
  imgDoc = null;
  renderImageCanvas();
  showView('image');

  if (raw && raw.trim())
  {
    try { imgDoc = JSON.parse(raw); } catch (e) { showNotification('Layer file unreadable, showing the image alone'); }
  }
  if (imgDoc && Array.isArray(imgDoc.layers))
  {
    loadLayerImages(function() { if (imgUrl === openedFor) { imageZoom(0); renderLayerPanel(); } });
    return;
  }

  // Never layered: the image itself is the only layer, at its natural size
  imgDoc = null;
  const img = new Image();
  img.onload = function()
  {
    if (imgUrl !== openedFor) return;
    imgCache[openedFor] = img;
    const w = img.naturalWidth || 512;
    const h = img.naturalHeight || 512;
    imgDoc = { version: 1, width: w, height: h, layers: [{ id: 'base', name: pathName(openedFor), src: pathName(openedFor), x: w / 2, y: h / 2, scale: 1, rotation: 0, opacity: 1, visible: true }] };
    imageZoom(0);
    renderLayerPanel();
  };
  img.onerror = function() { if (imgUrl === openedFor) showNotification('Cannot display ' + pathName(openedFor)); };
  img.src = imageUrlFor(openedFor);
}

function loadLayerImages(done)
{
  const pending = imgDoc.layers.filter(function(l) { return !(layerFilePath(l) in imgCache); });
  if (pending.length === 0) { done(); return; }
  let left = pending.length;
  pending.forEach(function(layer)
  {
    const path = layerFilePath(layer);
    const img = new Image();
    img.onload = function() { imgCache[path] = img; if (--left === 0) done(); };
    img.onerror = function() { imgCache[path] = null; if (--left === 0) done(); };
    img.src = imageUrlFor(path);
  });
}

// Pointer, wheel and resize wiring, attached once
function ensureImageViewer()
{
  if (imgViewerReady) return;
  const canvas = document.getElementById('image-canvas');
  const stage = document.getElementById('image-stage');
  if (!canvas || !stage) return;
  imgViewerReady = true;
  canvas.addEventListener('pointerdown', onImagePointerDown);
  canvas.addEventListener('pointermove', onImagePointerMove);
  canvas.addEventListener('pointerup', onImagePointerUp);
  canvas.addEventListener('pointercancel', onImagePointerUp);
  canvas.addEventListener('wheel', function(e)
  {
    if (!imgDoc) return;
    e.preventDefault();
    const rect = canvas.getBoundingClientRect();
    zoomImageAt(e.clientX - rect.left, e.clientY - rect.top, e.deltaY < 0 ? 1.1 : 1 / 1.1);
    renderImageCanvas();
  }, { passive: false });
  if (window.ResizeObserver) new ResizeObserver(function() { renderImageCanvas(); }).observe(stage);
}

function screenToDoc(sx, sy)
{
  return { x: (sx - imgView.ox) / imgView.scale, y: (sy - imgView.oy) / imgView.scale };
}

function docToScreen(x, y)
{
  return { x: imgView.ox + x * imgView.scale, y: imgView.oy + y * imgView.scale };
}

function zoomImageAt(sx, sy, factor)
{
  const p = screenToDoc(sx, sy);
  imgView.scale = Math.max(0.02, Math.min(32, imgView.scale * factor));
  imgView.ox = sx - p.x * imgView.scale;
  imgView.oy = sy - p.y * imgView.scale;
}

// dir 0 fits the canvas into the pane; +1 / -1 zoom around the pane centre
function imageZoom(dir)
{
  const stage = document.getElementById('image-stage');
  if (!stage || !imgDoc) return;
  if (dir === 0)
  {
    const pad = 16;
    const s = Math.min((stage.clientWidth - pad * 2) / imgDoc.width, (stage.clientHeight - pad * 2) / imgDoc.height);
    imgView.scale = s > 0 ? s : 1;
    imgView.ox = (stage.clientWidth - imgDoc.width * imgView.scale) / 2;
    imgView.oy = (stage.clientHeight - imgDoc.height * imgView.scale) / 2;
  }
  else zoomImageAt(stage.clientWidth / 2, stage.clientHeight / 2, dir > 0 ? 1.25 : 0.8);
  renderImageCanvas();
}

// Corner and rotation-knob positions of a layer, in document coordinates
function layerGeometry(layer)
{
  const img = imgCache[layerFilePath(layer)];
  if (!img) return null;
  const s = layer.scale || 1;
  const r = (layer.rotation || 0) * Math.PI / 180;
  const hw = img.naturalWidth * s / 2;
  const hh = img.naturalHeight * s / 2;
  const cos = Math.cos(r);
  const sin = Math.sin(r);
  const pt = function(dx, dy) { return { x: layer.x + dx * cos - dy * sin, y: layer.y + dx * sin + dy * cos }; };
  return { corners: [pt(-hw, -hh), pt(hw, -hh), pt(hw, hh), pt(-hw, hh)], topMid: pt(0, -hh), cos: cos, sin: sin, hw: hw, hh: hh };
}

function rotateKnob(g)
{
  const top = docToScreen(g.topMid.x, g.topMid.y);
  return { x: top.x + g.sin * 28, y: top.y - g.cos * 28 };
}

function drawLayer(ctx, layer, alphaFactor)
{
  const img = imgCache[layerFilePath(layer)];
  if (!img || layer.visible === false) return;
  ctx.save();
  ctx.globalAlpha = (layer.opacity == null ? 1 : layer.opacity) * alphaFactor;
  ctx.translate(layer.x, layer.y);
  ctx.rotate((layer.rotation || 0) * Math.PI / 180);
  ctx.scale(layer.scale || 1, layer.scale || 1);
  ctx.drawImage(img, -img.naturalWidth / 2, -img.naturalHeight / 2);
  ctx.restore();
}

function renderImageCanvas()
{
  const stage = document.getElementById('image-stage');
  const canvas = document.getElementById('image-canvas');
  if (!stage || !canvas) return;
  const dpr = window.devicePixelRatio || 1;
  const cw = stage.clientWidth;
  const ch = stage.clientHeight;
  if (cw === 0 || ch === 0) return;
  if (canvas.width !== Math.round(cw * dpr) || canvas.height !== Math.round(ch * dpr))
  {
    canvas.width = Math.round(cw * dpr);
    canvas.height = Math.round(ch * dpr);
    canvas.style.width = cw + 'px';
    canvas.style.height = ch + 'px';
  }
  const ctx = canvas.getContext('2d');
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  if (!imgDoc) return;

  ctx.setTransform(dpr * imgView.scale, 0, 0, dpr * imgView.scale, dpr * imgView.ox, dpr * imgView.oy);
  // Checkerboard shows transparency, the way the result will be stored
  if (!imgChecker)
  {
    const c = document.createElement('canvas');
    c.width = 16;
    c.height = 16;
    const cc = c.getContext('2d');
    cc.fillStyle = '#d9d9d9';
    cc.fillRect(0, 0, 16, 16);
    cc.fillStyle = '#f4f4f4';
    cc.fillRect(0, 0, 8, 8);
    cc.fillRect(8, 8, 8, 8);
    imgChecker = ctx.createPattern(c, 'repeat');
  }
  ctx.fillStyle = imgChecker;
  ctx.fillRect(0, 0, imgDoc.width, imgDoc.height);
  // Parts outside the canvas are cut from the result: show them faintly so a layer can still be found
  imgDoc.layers.forEach(function(l) { drawLayer(ctx, l, 0.25); });
  ctx.save();
  ctx.beginPath();
  ctx.rect(0, 0, imgDoc.width, imgDoc.height);
  ctx.clip();
  ctx.clearRect(0, 0, imgDoc.width, imgDoc.height);
  ctx.fillStyle = imgChecker;
  ctx.fillRect(0, 0, imgDoc.width, imgDoc.height);
  imgDoc.layers.forEach(function(l) { drawLayer(ctx, l, 1); });
  ctx.restore();

  // Selection frame and handles in screen pixels, so they keep their size at any zoom
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  const sel = selectedLayer();
  const g = sel ? layerGeometry(sel) : null;
  if (g)
  {
    const pts = g.corners.map(function(p) { return docToScreen(p.x, p.y); });
    const knob = rotateKnob(g);
    const top = docToScreen(g.topMid.x, g.topMid.y);
    ctx.strokeStyle = '#2563eb';
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.moveTo(pts[0].x, pts[0].y);
    for (var i = 1; i < 4; i++) ctx.lineTo(pts[i].x, pts[i].y);
    ctx.closePath();
    ctx.moveTo(top.x, top.y);
    ctx.lineTo(knob.x, knob.y);
    ctx.stroke();
    ctx.fillStyle = '#ffffff';
    pts.forEach(function(p) { ctx.fillRect(p.x - 5, p.y - 5, 10, 10); ctx.strokeRect(p.x - 5, p.y - 5, 10, 10); });
    ctx.beginPath();
    ctx.arc(knob.x, knob.y, 6, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();
  }
  updateImageInfo();
}

function updateImageInfo()
{
  const info = document.getElementById('image-info');
  if (info && imgDoc) info.textContent = imgDoc.width + '×' + imgDoc.height + ' · ' + imgDoc.layers.length + (imgDoc.layers.length === 1 ? ' layer' : ' layers') + ' · ' + Math.round(imgView.scale * 100) + '%';
}

function layerContains(layer, p)
{
  const g = layerGeometry(layer);
  if (!g) return false;
  const dx = p.x - layer.x;
  const dy = p.y - layer.y;
  const lx = dx * g.cos + dy * g.sin;
  const ly = -dx * g.sin + dy * g.cos;
  return Math.abs(lx) <= g.hw && Math.abs(ly) <= g.hh;
}

// Handles of the selected layer first, then the topmost visible layer under the point, else panning
function imageHitTest(sx, sy)
{
  const sel = selectedLayer();
  const g = sel ? layerGeometry(sel) : null;
  if (g)
  {
    const knob = rotateKnob(g);
    if (Math.hypot(sx - knob.x, sy - knob.y) <= IMAGE_HANDLE_RADIUS) return { mode: 'rotate', layer: sel };
    for (var i = 0; i < 4; i++)
    {
      const c = docToScreen(g.corners[i].x, g.corners[i].y);
      if (Math.hypot(sx - c.x, sy - c.y) <= IMAGE_HANDLE_RADIUS) return { mode: 'scale', layer: sel };
    }
  }
  const p = screenToDoc(sx, sy);
  for (var j = imgDoc.layers.length - 1; j >= 0; j--)
  {
    const l = imgDoc.layers[j];
    if (l.visible !== false && layerContains(l, p)) return { mode: 'move', layer: l };
  }
  return { mode: 'pan', layer: null };
}

function onImagePointerDown(e)
{
  if (!imgDoc) return;
  const canvas = e.currentTarget;
  const rect = canvas.getBoundingClientRect();
  const sx = e.clientX - rect.left;
  const sy = e.clientY - rect.top;
  const hit = imageHitTest(sx, sy);
  imgSelectedId = hit.layer ? hit.layer.id : null;
  const l = hit.layer;
  imgDrag = { mode: hit.mode, sx: sx, sy: sy, x: l ? l.x : 0, y: l ? l.y : 0, scale: l ? (l.scale || 1) : 1, rotation: l ? (l.rotation || 0) : 0, ox: imgView.ox, oy: imgView.oy, changed: false };
  canvas.setPointerCapture(e.pointerId);
  e.preventDefault();
  renderImageCanvas();
  renderLayerPanel();
}

function onImagePointerMove(e)
{
  if (!imgDrag) return;
  const rect = e.currentTarget.getBoundingClientRect();
  const sx = e.clientX - rect.left;
  const sy = e.clientY - rect.top;
  const sel = selectedLayer();
  if (imgDrag.mode === 'pan')
  {
    imgView.ox = imgDrag.ox + (sx - imgDrag.sx);
    imgView.oy = imgDrag.oy + (sy - imgDrag.sy);
  }
  else if (sel && imgDrag.mode === 'move')
  {
    sel.x = imgDrag.x + (sx - imgDrag.sx) / imgView.scale;
    sel.y = imgDrag.y + (sy - imgDrag.sy) / imgView.scale;
    imgDrag.changed = true;
  }
  else if (sel && imgDrag.mode === 'scale')
  {
    const c = docToScreen(imgDrag.x, imgDrag.y);
    const d0 = Math.hypot(imgDrag.sx - c.x, imgDrag.sy - c.y);
    const d1 = Math.hypot(sx - c.x, sy - c.y);
    if (d0 > 0) sel.scale = Math.max(0.02, imgDrag.scale * d1 / d0);
    imgDrag.changed = true;
  }
  else if (sel && imgDrag.mode === 'rotate')
  {
    const c = docToScreen(imgDrag.x, imgDrag.y);
    const a0 = Math.atan2(imgDrag.sy - c.y, imgDrag.sx - c.x);
    const a1 = Math.atan2(sy - c.y, sx - c.x);
    let deg = imgDrag.rotation + (a1 - a0) * 180 / Math.PI;
    if (e.shiftKey) deg = Math.round(deg / 15) * 15;
    deg = ((deg + 180) % 360 + 360) % 360 - 180;
    sel.rotation = deg;
    imgDrag.changed = true;
  }
  renderImageCanvas();
}

function onImagePointerUp(e)
{
  if (!imgDrag) return;
  if (imgDrag.changed) scheduleImageSave();
  imgDrag = null;
  renderLayerPanel();
}

// Layer list, top layer first
function renderLayerPanel()
{
  const panel = document.getElementById('layer-panel');
  if (!panel) return;
  panel.innerHTML = '';
  if (!imgDoc) return;
  const count = imgDoc.layers.length;
  for (let i = count - 1; i >= 0; i--)
  {
    const layer = imgDoc.layers[i];
    const row = document.createElement('div');
    row.className = 'layer-row' + (layer.id === imgSelectedId ? ' selected' : '') + (layer.visible === false ? ' layer-off' : '');

    const eye = document.createElement('button');
    eye.className = 'layer-btn';
    eye.textContent = layer.visible === false ? '◌' : '👁';
    eye.title = layer.visible === false ? 'Show layer' : 'Hide layer';
    eye.onclick = function(e) { e.stopPropagation(); layer.visible = layer.visible === false; afterLayerChange(); };

    const name = document.createElement('span');
    name.className = 'layer-name';
    name.textContent = (imgCache[layerFilePath(layer)] === null ? '⚠ ' : '') + (layer.name || layer.src);
    name.title = layerFilePath(layer);

    const up = document.createElement('button');
    up.className = 'layer-btn';
    up.textContent = '▲';
    up.title = 'Move up';
    up.disabled = i === count - 1;
    up.onclick = function(e) { e.stopPropagation(); swapLayers(i, i + 1); };

    const down = document.createElement('button');
    down.className = 'layer-btn';
    down.textContent = '▼';
    down.title = 'Move down';
    down.disabled = i === 0;
    down.onclick = function(e) { e.stopPropagation(); swapLayers(i, i - 1); };

    const del = document.createElement('button');
    del.className = 'layer-btn';
    del.textContent = '✕';
    del.title = 'Remove layer';
    del.disabled = count === 1;
    del.onclick = function(e)
    {
      e.stopPropagation();
      showConfirm('Remove layer "' + (layer.name || layer.src) + '" from this image? Its file stays on disk.', function()
      {
        imgDoc.layers = imgDoc.layers.filter(function(l) { return l !== layer; });
        if (imgSelectedId === layer.id) imgSelectedId = null;
        afterLayerChange();
      }, 'Remove');
    };

    row.onclick = function() { imgSelectedId = layer.id; renderImageCanvas(); renderLayerPanel(); };
    row.appendChild(eye);
    row.appendChild(name);
    row.appendChild(up);
    row.appendChild(down);
    row.appendChild(del);
    panel.appendChild(row);
  }
}

function swapLayers(a, b)
{
  const t = imgDoc.layers[a];
  imgDoc.layers[a] = imgDoc.layers[b];
  imgDoc.layers[b] = t;
  afterLayerChange();
}

function afterLayerChange()
{
  renderImageCanvas();
  renderLayerPanel();
  scheduleImageSave();
}

function addLayerObject(props, img)
{
  const scale = Math.min(1, imgDoc.width / img.naturalWidth, imgDoc.height / img.naturalHeight);
  const layer = Object.assign({ id: 'l' + Date.now(), x: imgDoc.width / 2, y: imgDoc.height / 2, scale: scale, rotation: 0, opacity: 1, visible: true }, props);
  imgDoc.layers.push(layer);
  imgSelectedId = layer.id;
  afterLayerChange();
}

// Tree menu "Add as Layer": a sibling is referenced by name, anything else by its full path
function addLayerFromPath(path, name)
{
  if (!imgDoc || !imgUrl) return;
  const img = new Image();
  img.onload = function()
  {
    imgCache[path] = img;
    if (pathDir(path) === pathDir(imgUrl)) addLayerObject({ src: pathName(path), name: name }, img);
    else addLayerObject({ url: path, src: pathName(path), name: name }, img);
  };
  img.onerror = function() { showNotification('Cannot load ' + name); };
  img.src = imageUrlFor(path);
}

function imageToPngBlob(img, callback)
{
  const c = document.createElement('canvas');
  c.width = img.naturalWidth || 512;
  c.height = img.naturalHeight || 512;
  c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
  c.toBlob(callback, 'image/png');
}

// "+ Layer": a picture from this device, stored as PNG in "<image>.layers/"
function uploadLayerFile(input)
{
  const file = input.files && input.files[0];
  input.value = '';
  if (!file || !imgDoc || !imgUrl) return;
  const baseUrl = imgUrl;
  const objectUrl = URL.createObjectURL(file);
  const img = new Image();
  img.onload = function()
  {
    URL.revokeObjectURL(objectUrl);
    imageToPngBlob(img, function(blob)
    {
      const stem = file.name.replace(/\.[^.]+$/, '').replace(/[^A-Za-z0-9._-]+/g, '-') || 'layer';
      const rel = pathName(baseUrl) + '.layers/' + stem + '-' + fileTimestamp() + '.png';
      const target = siblingPath(baseUrl, rel);
      uploadBytes(target, blob, 'layer.png', function(ok)
      {
        if (!ok || imgUrl !== baseUrl) return;
        imgCache[target] = img;
        addLayerObject({ src: rel, name: file.name }, img);
        refreshTreeExplorer();
      });
    });
  };
  img.onerror = function() { URL.revokeObjectURL(objectUrl); showNotification('Cannot read ' + file.name); };
  img.src = objectUrl;
}

// "Merge ⇩": every visible layer flattened into one new PNG; the layers themselves stay as they are
function mergeLayers()
{
  if (!imgDoc || !imgUrl) return;
  const baseUrl = imgUrl;
  const c = document.createElement('canvas');
  c.width = imgDoc.width;
  c.height = imgDoc.height;
  const ctx = c.getContext('2d');
  imgDoc.layers.forEach(function(l) { drawLayer(ctx, l, 1); });
  c.toBlob(function(blob)
  {
    const target = siblingPath(baseUrl, pathName(baseUrl).replace(/\.[^.]+$/, '') + '-merged-' + fileTimestamp() + '.png');
    uploadBytes(target, blob, 'merged.png', function(ok)
    {
      if (!ok) return;
      showNotification('Saved ' + pathName(target));
      refreshTreeExplorer();
    });
  }, 'image/png');
}

// Multipart upload to the imagesave command; the callback receives true on success
function uploadBytes(path, blob, filename, callback)
{
  const form = new FormData();
  form.append('ajaxcall', '1');
  form.append('file', blob, filename);
  const xhr = new XMLHttpRequest();
  xhr.open('POST', '/imagesave/' + encodePath(path), true);
  xhr.onreadystatechange = function()
  {
    if (xhr.readyState !== 4) return;
    let ok = false;
    try
    {
      const r = xhr.responseXML.getElementsByTagName('result');
      ok = r.length > 0 && r.item(0).textContent === 'ok';
    } catch (e) {}
    if (xhr.status === 200 && xhr.responseXML) alertContents(xhr);
    else showNotification('Upload failed (' + xhr.status + ')');
    if (typeof callback === 'function') callback(ok);
  };
  xhr.send(form);
}

function scheduleImageSave()
{
  if (!imgDoc || !imgUrl) return;
  if (imgSaveTimer) clearTimeout(imgSaveTimer);
  setSaveStatus('unsaved');
  imgSaveTimer = setTimeout(function() { imgSaveTimer = null; saveImageDoc(); }, 800);
}

// Saves a pending layer change now; the callback runs once nothing is left to save
function flushImageSave(callback)
{
  if (imgSaveTimer)
  {
    clearTimeout(imgSaveTimer);
    imgSaveTimer = null;
    saveImageDoc(callback);
    return;
  }
  if (imgSaving)
  {
    if (typeof callback === 'function') imgSaveWaiters.push(callback);
    return;
  }
  if (typeof callback === 'function') callback();
}

function saveImageDoc(callback)
{
  if (typeof callback === 'function') imgSaveWaiters.push(callback);
  if (!imgDoc || !imgUrl)
  {
    drainImageWaiters();
    return;
  }
  const url = imgUrl;
  const doc = imgDoc;
  imgSaving = true;
  setSaveStatus('saving');
  ensureServerReadable(doc, url, function()
  {
    uploadBytes(url + '.layers.json', new Blob([JSON.stringify(doc)], { type: 'application/json' }), 'layers.json', function(ok)
    {
      imgSaving = false;
      if (imgUrl === url) setSaveStatus(ok ? 'saved' : 'unsaved');
      drainImageWaiters();
    });
  });
}

function drainImageWaiters()
{
  const waiters = imgSaveWaiters;
  imgSaveWaiters = [];
  waiters.forEach(function(f) { f(); });
}

// The server composes layers with ImageIO, which cannot read WebP, AVIF or SVG: those layers are
// converted to PNG copies in "<image>.layers/" before the layer file is written
function ensureServerReadable(doc, url, done)
{
  const pending = doc.layers.filter(function(l) { return IMAGE_SERVER_READABLE.indexOf(pathExt(layerFilePath(l, url))) === -1 && imgCache[layerFilePath(l, url)]; });
  if (pending.length === 0) { done(); return; }
  const layer = pending[0];
  const oldPath = layerFilePath(layer, url);
  const img = imgCache[oldPath];
  const rel = pathName(url) + '.layers/' + pathName(oldPath).replace(/\.[^.]+$/, '') + '-' + fileTimestamp() + '.png';
  imageToPngBlob(img, function(blob)
  {
    uploadBytes(siblingPath(url, rel), blob, 'layer.png', function(ok)
    {
      // On failure the layer keeps its original file: the viewer still shows it, only AI edits cannot use it
      if (!ok) { done(); return; }
      delete layer.url;
      layer.src = rel;
      imgCache[layerFilePath(layer, url)] = img;
      ensureServerReadable(doc, url, done);
    });
  });
}

// =====================================================================================================
// Video viewer, 3D viewer and the video / 3D agents
// =====================================================================================================

function mediaUrlFor(path)
{
  return '/media/' + encodePath(path);
}

function clearFilenameLoading()
{
  const filenameEl = document.getElementById('current-filename');
  if (filenameEl) filenameEl.classList.remove('loading');
  const buffer = document.getElementById('file-content-buffer');
  if (buffer) buffer.value = '';
}

// ----- Video -----
let videoUrl = null;

function openVideoViewer()
{
  clearFilenameLoading();
  const player = document.getElementById('video-player');
  const info = document.getElementById('video-info');
  if (!player) return;
  videoUrl = currentFileUrl;
  if (info) info.textContent = 'Loading…';
  player.onloadedmetadata = function()
  {
    if (info) info.textContent = player.videoWidth + '×' + player.videoHeight + ' · ' + formatSeconds(player.duration);
  };
  player.onerror = function()
  {
    if (info) info.textContent = 'The browser cannot play this video';
  };
  player.src = mediaUrlFor(videoUrl);
  player.load();
  showView('video');
}

function pauseVideoViewer()
{
  const player = document.getElementById('video-player');
  if (player && !player.paused) player.pause();
}

function toggleVideoLoop(on)
{
  const player = document.getElementById('video-player');
  if (player) player.loop = !!on;
}

// The frame on screen as a PNG beside the video: to edit it, or to use it as the first frame of the next clip
function saveVideoFrame()
{
  const player = document.getElementById('video-player');
  if (!player || !player.videoWidth || !videoUrl) return;
  const canvas = document.createElement('canvas');
  canvas.width = player.videoWidth;
  canvas.height = player.videoHeight;
  canvas.getContext('2d').drawImage(player, 0, 0);
  canvas.toBlob(function(blob)
  {
    if (!blob) return;
    const name = pathName(videoUrl).replace(/\.[^.]+$/, '') + '-frame-' + Math.round(player.currentTime * 1000) + 'ms.png';
    const target = siblingPath(videoUrl, name);
    uploadBytes(target, blob, name, function(ok)
    {
      if (!ok) return;
      showNotification('Saved ' + name);
      refreshTreeExplorer();
    });
  }, 'image/png');
}

// ----- 3D (three.js, loaded from the CDN only when a model is first opened) -----
const THREE_CDN = 'https://cdn.jsdelivr.net/npm/three@0.170.0/';
let m3 = null;          // viewer state once three.js is loaded
let m3Loading = null;   // the pending import
let modelUrl = null;

function ensureModelViewer()
{
  if (m3) return Promise.resolve(m3);
  if (m3Loading) return m3Loading;
  m3Loading = Promise.all([
    import('three'),
    import('three/addons/loaders/GLTFLoader.js'),
    import('three/addons/loaders/DRACOLoader.js'),
    import('three/addons/controls/OrbitControls.js'),
    import('three/addons/environments/RoomEnvironment.js'),
    import('three/addons/libs/meshopt_decoder.module.js')
  ]).then(function(mods)
  {
    const THREE = mods[0];
    const stage = document.getElementById('model-stage');
    const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    renderer.toneMapping = THREE.NeutralToneMapping;
    stage.appendChild(renderer.domElement);

    const scene = new THREE.Scene();
    const pmrem = new THREE.PMREMGenerator(renderer);
    scene.environment = pmrem.fromScene(new mods[4].RoomEnvironment(), 0.04).texture;

    const camera = new THREE.PerspectiveCamera(40, 1, 0.01, 1000);
    const controls = new mods[3].OrbitControls(camera, renderer.domElement);
    controls.addEventListener('change', renderModel);

    const loader = new mods[1].GLTFLoader();
    const draco = new mods[2].DRACOLoader();
    draco.setDecoderPath(THREE_CDN + 'examples/jsm/libs/draco/gltf/');
    loader.setDRACOLoader(draco);
    loader.setMeshoptDecoder(mods[5].MeshoptDecoder);

    m3 = { THREE: THREE, renderer: renderer, scene: scene, camera: camera, controls: controls, loader: loader, root: null, meshes: [], mode: 'pbr', stats: null };
    if (window.ResizeObserver) new ResizeObserver(resizeModelViewer).observe(stage);
    else window.addEventListener('resize', resizeModelViewer);
    resizeModelViewer();
    return m3;
  });
  return m3Loading;
}

function resizeModelViewer()
{
  if (!m3) return;
  const stage = document.getElementById('model-stage');
  if (!stage || !stage.clientWidth || !stage.clientHeight) return;
  m3.renderer.setSize(stage.clientWidth, stage.clientHeight, false);
  m3.camera.aspect = stage.clientWidth / stage.clientHeight;
  m3.camera.updateProjectionMatrix();
  renderModel();
}

// Rendered on demand (camera moved, mode changed, resized): a still model costs no GPU time
function renderModel()
{
  if (!m3) return;
  const cont = document.getElementById('model-container');
  if (cont && cont.classList.contains('hidden')) return;
  m3.renderer.render(m3.scene, m3.camera);
}

function disposeModel()
{
  if (!m3 || !m3.root) return;
  m3.scene.remove(m3.root);
  const seen = [];
  const disposeMaterial = function(mat)
  {
    if (!mat || seen.indexOf(mat) >= 0) return;
    seen.push(mat);
    ['map', 'normalMap', 'roughnessMap', 'metalnessMap', 'aoMap', 'emissiveMap'].forEach(function(k) { if (mat[k] && mat[k].dispose) mat[k].dispose(); });
    mat.dispose();
  };
  m3.meshes.forEach(function(entry)
  {
    entry.mesh.geometry.dispose();
    (Array.isArray(entry.original) ? entry.original : [entry.original]).forEach(disposeMaterial);
    Object.keys(entry.cache).forEach(function(k) { (Array.isArray(entry.cache[k]) ? entry.cache[k] : [entry.cache[k]]).forEach(function(mat) { if (mat) mat.dispose(); }); });
  });
  m3.root = null;
  m3.meshes = [];
  m3.stats = null;
}

function openModelViewer()
{
  clearFilenameLoading();
  showView('model');
  const info = document.getElementById('model-info');
  const openedFor = currentFileUrl;
  modelUrl = openedFor;
  if (info) info.textContent = 'Loading 3D viewer…';
  ensureModelViewer().then(function()
  {
    if (modelUrl !== openedFor) return;
    disposeModel();
    renderModel();
    m3.loader.load(mediaUrlFor(openedFor), function(gltf)
    {
      if (modelUrl !== openedFor) return;
      showModel(gltf.scene);
    }, function(ev)
    {
      if (modelUrl !== openedFor || !info) return;
      info.textContent = ev.total ? 'Loading ' + Math.round(ev.loaded / ev.total * 100) + '% of ' + Math.round(ev.total / 1048576) + ' MB' : 'Loading ' + Math.round(ev.loaded / 1048576) + ' MB…';
    }, function(err)
    {
      if (modelUrl !== openedFor) return;
      console.log('GLB load failed', err);
      if (info) info.textContent = 'This model could not be loaded';
    });
  }).catch(function(err)
  {
    console.log('three.js load failed', err);
    m3Loading = null;
    if (info) info.textContent = 'The 3D viewer could not be loaded (no connection to the CDN?)';
  });
}

function showModel(root)
{
  const THREE = m3.THREE;
  m3.root = root;
  m3.meshes = [];
  const stats = { triangles: 0, vertices: 0, materials: [], hasUv: false, hasVertexColor: false, maps: {} };
  root.traverse(function(obj)
  {
    if (!obj.isMesh) return;
    const geo = obj.geometry;
    m3.meshes.push({ mesh: obj, original: obj.material, cache: {} });
    const pos = geo.attributes.position;
    stats.vertices += pos ? pos.count : 0;
    stats.triangles += geo.index ? geo.index.count / 3 : (pos ? pos.count / 3 : 0);
    if (geo.attributes.uv) stats.hasUv = true;
    if (geo.attributes.color) stats.hasVertexColor = true;
    (Array.isArray(obj.material) ? obj.material : [obj.material]).forEach(function(mat)
    {
      if (stats.materials.indexOf(mat) < 0) stats.materials.push(mat);
      [['map', 'base colour'], ['normalMap', 'normal'], ['roughnessMap', 'roughness'], ['metalnessMap', 'metallic'], ['aoMap', 'occlusion'], ['emissiveMap', 'emissive']].forEach(function(pair)
      {
        const tex = mat[pair[0]];
        if (tex && tex.image && !stats.maps[pair[1]]) stats.maps[pair[1]] = (tex.image.width || '?') + '²';
      });
    });
  });
  m3.stats = stats;
  m3.scene.add(root);

  // Which views make sense for this model
  const select = document.getElementById('model-view-mode');
  const available = { pbr: true, clay: true, normals: true, base: !!stats.maps['base colour'] || stats.hasVertexColor, vertex: stats.hasVertexColor, normalmap: !!stats.maps['normal'], roughness: !!stats.maps['roughness'], metallic: !!stats.maps['metallic'], ao: !!stats.maps['occlusion'], uvchecker: stats.hasUv };
  if (select)
  {
    Array.prototype.forEach.call(select.options, function(opt) { opt.disabled = !available[opt.value]; });
    select.value = 'pbr';
  }
  m3.mode = 'pbr';
  const wire = document.getElementById('model-wireframe');
  if (wire) wire.checked = false;
  const uvToggle = document.getElementById('model-uv-toggle');
  if (uvToggle) uvToggle.disabled = !stats.hasUv;
  if (!stats.hasUv) toggleModelUv(false);

  const info = document.getElementById('model-info');
  if (info)
  {
    const maps = Object.keys(stats.maps).map(function(k) { return k + ' ' + stats.maps[k]; });
    info.textContent = Math.round(stats.triangles).toLocaleString() + ' triangles · ' + stats.vertices.toLocaleString() + ' vertices · '
      + (stats.hasUv ? 'UV' : 'no UV') + (stats.hasVertexColor ? ' · vertex colours' : '') + (maps.length ? ' · ' + maps.join(', ') : ' · no texture maps');
    info.title = info.textContent;
  }
  resetModelCamera();
  const uvPanel = document.getElementById('model-uv');
  if (uvPanel && !uvPanel.classList.contains('hidden')) drawModelUv();
}

function resetModelCamera()
{
  if (!m3 || !m3.root) return;
  const THREE = m3.THREE;
  const box = new THREE.Box3().setFromObject(m3.root);
  const size = box.getSize(new THREE.Vector3());
  const center = box.getCenter(new THREE.Vector3());
  const radius = Math.max(size.x, size.y, size.z) * 0.5 || 1;
  const distance = radius / Math.tan(m3.camera.fov * Math.PI / 360) * 1.5;
  m3.camera.near = distance / 100;
  m3.camera.far = distance * 100;
  m3.camera.position.set(center.x + distance * 0.55, center.y + distance * 0.35, center.z + distance * 0.75);
  m3.camera.updateProjectionMatrix();
  m3.controls.target.copy(center);
  m3.controls.update();
  renderModel();
}

// One texture channel shown as grey (roughness, metallic, occlusion), or a whole map shown as stored (normal map)
function channelMaterial(texture, mask)
{
  const THREE = m3.THREE;
  return new THREE.ShaderMaterial({
    uniforms: { map: { value: texture }, mask: { value: mask ? new THREE.Vector3(mask[0], mask[1], mask[2]) : new THREE.Vector3(0, 0, 0) }, grey: { value: mask ? 1 : 0 } },
    vertexShader: 'varying vec2 vUv; void main() { vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }',
    fragmentShader: 'uniform sampler2D map; uniform vec3 mask; uniform float grey; varying vec2 vUv; void main() { vec3 c = texture2D(map, vUv).rgb; gl_FragColor = vec4(mix(c, vec3(dot(c, mask)), grey), 1.0); }'
  });
}

function uvCheckerTexture()
{
  if (m3.checker) return m3.checker;
  const THREE = m3.THREE;
  const size = 1024, cells = 8, step = size / cells;
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = size;
  const ctx = canvas.getContext('2d');
  ctx.font = 'bold 44px sans-serif';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  for (let y = 0; y < cells; y++) for (let x = 0; x < cells; x++)
  {
    ctx.fillStyle = (x + y) % 2 === 0 ? 'hsl(' + (x * 45) + ', 55%, ' + (38 + y * 4) + '%)' : '#e8e8e8';
    ctx.fillRect(x * step, y * step, step, step);
    ctx.fillStyle = (x + y) % 2 === 0 ? '#ffffff' : '#333333';
    ctx.fillText(String.fromCharCode(65 + x) + (y + 1), x * step + step / 2, y * step + step / 2);
  }
  const tex = new THREE.CanvasTexture(canvas);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.flipY = false;
  tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
  m3.checker = tex;
  return tex;
}

function modelMaterialFor(original, mode, geometry)
{
  const THREE = m3.THREE;
  const hasColor = !!geometry.attributes.color;
  const hasUv = !!geometry.attributes.uv;
  const clay = function() { return new THREE.MeshStandardMaterial({ color: 0xbdbdbd, roughness: 0.75, metalness: 0 }); };
  switch (mode)
  {
    case 'base': return new THREE.MeshBasicMaterial({ map: original.map || null, color: original.color ? original.color.clone() : 0xffffff, vertexColors: hasColor && !!original.vertexColors });
    case 'vertex': return hasColor ? new THREE.MeshBasicMaterial({ vertexColors: true }) : clay();
    case 'clay': return clay();
    case 'normals': return new THREE.MeshNormalMaterial();
    case 'normalmap': return original.normalMap && hasUv ? channelMaterial(original.normalMap, null) : clay();
    case 'roughness': return original.roughnessMap && hasUv ? channelMaterial(original.roughnessMap, [0, 1, 0]) : clay();
    case 'metallic': return original.metalnessMap && hasUv ? channelMaterial(original.metalnessMap, [0, 0, 1]) : clay();
    case 'ao': return original.aoMap && hasUv ? channelMaterial(original.aoMap, [1, 0, 0]) : clay();
    case 'uvchecker': return hasUv ? new THREE.MeshBasicMaterial({ map: uvCheckerTexture() }) : clay();
    default: return original;
  }
}

function setModelViewMode(mode)
{
  if (!m3 || !m3.root) return;
  m3.mode = mode;
  m3.meshes.forEach(function(entry)
  {
    if (mode === 'pbr') { entry.mesh.material = entry.original; return; }
    if (!entry.cache[mode])
    {
      entry.cache[mode] = Array.isArray(entry.original)
        ? entry.original.map(function(mat) { return modelMaterialFor(mat, mode, entry.mesh.geometry); })
        : modelMaterialFor(entry.original, mode, entry.mesh.geometry);
    }
    entry.mesh.material = entry.cache[mode];
  });
  toggleModelWireframe();
}

function toggleModelWireframe()
{
  if (!m3 || !m3.root) return;
  const box = document.getElementById('model-wireframe');
  const on = !!(box && box.checked);
  m3.meshes.forEach(function(entry)
  {
    const all = [entry.original].concat(Object.keys(entry.cache).map(function(k) { return entry.cache[k]; }));
    all.forEach(function(m) { (Array.isArray(m) ? m : [m]).forEach(function(mat) { if (mat) mat.wireframe = on; }); });
  });
  renderModel();
}

function toggleModelUv(show)
{
  const panel = document.getElementById('model-uv');
  const toggle = document.getElementById('model-uv-toggle');
  if (!panel) return;
  panel.classList.toggle('hidden', !show);
  if (toggle) toggle.checked = !!show;
  requestAnimationFrame(function() { resizeModelViewer(); if (show) drawModelUv(); });
}

// The UV unwrap as a flat picture: the base colour map (when there is one) with every triangle's edges on top
const UV_MAX_TRIANGLES = 200000;

function drawModelUv()
{
  const canvas = document.getElementById('model-uv-canvas');
  const note = document.getElementById('model-uv-note');
  if (!canvas || !m3 || !m3.root) return;
  const size = 1024;
  canvas.width = canvas.height = size;
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#1e1e1e';
  ctx.fillRect(0, 0, size, size);

  let background = null;
  m3.meshes.forEach(function(entry)
  {
    (Array.isArray(entry.original) ? entry.original : [entry.original]).forEach(function(mat) { if (!background && mat.map && mat.map.image) background = mat.map.image; });
  });
  if (background) try { ctx.globalAlpha = 0.85; ctx.drawImage(background, 0, 0, size, size); } catch (e) {}
  ctx.globalAlpha = 1;

  let total = 0;
  m3.meshes.forEach(function(entry) { const g = entry.mesh.geometry; if (g.attributes.uv) total += g.index ? g.index.count / 3 : g.attributes.uv.count / 3; });
  const stride = Math.max(1, Math.ceil(total / UV_MAX_TRIANGLES));
  ctx.strokeStyle = background ? 'rgba(255, 255, 255, 0.55)' : 'rgba(120, 200, 255, 0.8)';
  ctx.lineWidth = 0.6;
  ctx.beginPath();
  m3.meshes.forEach(function(entry)
  {
    const g = entry.mesh.geometry;
    const uv = g.attributes.uv;
    if (!uv) return;
    const index = g.index;
    const count = index ? index.count / 3 : uv.count / 3;
    for (let t = 0; t < count; t += stride)
    {
      const a = index ? index.getX(t * 3) : t * 3, b = index ? index.getX(t * 3 + 1) : t * 3 + 1, c = index ? index.getX(t * 3 + 2) : t * 3 + 2;
      // glTF keeps the UV origin at the top left, exactly like a canvas
      ctx.moveTo(uv.getX(a) * size, uv.getY(a) * size);
      ctx.lineTo(uv.getX(b) * size, uv.getY(b) * size);
      ctx.lineTo(uv.getX(c) * size, uv.getY(c) * size);
      ctx.closePath();
    }
  });
  ctx.stroke();
  if (note) note.textContent = stride > 1 ? 'Showing every ' + stride + (stride === 2 ? 'nd' : stride === 3 ? 'rd' : 'th') + ' triangle of ' + Math.round(total).toLocaleString() : Math.round(total).toLocaleString() + ' triangles';
}

// What the 3D view shows right now, as a PNG beside the model
function saveModelSnapshot()
{
  if (!m3 || !m3.root || !modelUrl) return;
  // Rendered and read in the same tick: the drawing buffer is not kept between frames
  m3.renderer.render(m3.scene, m3.camera);
  m3.renderer.domElement.toBlob(function(blob)
  {
    if (!blob) return;
    const name = pathName(modelUrl).replace(/\.[^.]+$/, '') + '-view-' + fileTimestamp() + '.png';
    uploadBytes(siblingPath(modelUrl, name), blob, name, function(ok)
    {
      if (!ok) return;
      showNotification('Saved ' + name);
      refreshTreeExplorer();
    });
  }, 'image/png');
}

// ----- Results of the video / 3D agents -----
function appendMediaResult(kind, path, timeStr, model)
{
  if (!path) return;
  const msgDiv = appendMessage('ai', (kind === 'video' ? '🎬 ' : '🧊 ') + pathName(path), null, timeStr, model);
  if (!msgDiv) return;
  const content = msgDiv.querySelector('.msg-content');
  if (kind === 'video')
  {
    const video = document.createElement('video');
    video.className = 'chat-image-thumb';
    video.controls = true;
    video.preload = 'none';
    video.playsInline = true;
    video.src = mediaUrlFor(path);
    content.appendChild(video);
  }
  const open = document.createElement('button');
  open.className = 'p-btn';
  open.textContent = kind === 'video' ? 'Open in player' : 'Open in 3D viewer';
  open.onclick = function() { openFileInEditor(path); };
  content.appendChild(open);
  scrollToBottomIfWatching(document.getElementById('chat-history'));
}

function onMediaDone(data)
{
  appendMediaResult(data.kind, data.path, null, data.model);
  refreshTreeExplorer();
  openFileInEditor(data.path);
}

// ----- Settings of the video / 3D model, generated from the parameter list the model publishes -----
function mediaModelInfoFor(kind)
{
  const select = document.getElementById(kind + '-model-select');
  return select && select.value && window.mediaModelInfo ? window.mediaModelInfo[select.value] || null : null;
}

function mediaStorageKey(kind)
{
  const select = document.getElementById(kind + '-model-select');
  return 'aether.media.' + kind + '.' + (select ? select.value : '');
}

function updateMediaControls()
{
  ['video', 'model3d'].forEach(function(kind)
  {
    const on = !!(currentAgent && currentAgent[kind]);
    const select = document.getElementById(kind + '-model-select');
    const box = document.getElementById(kind + '-ai-controls');
    if (select) select.classList.toggle('hidden', !on);
    if (box) box.classList.toggle('hidden', !on);
    if (on) renderMediaParams(kind);
  });
}

function renderMediaParams(kind)
{
  const box = document.getElementById(kind + '-ai-controls');
  if (!box) return;
  box.textContent = '';
  const info = mediaModelInfoFor(kind);
  let saved = {};
  try { saved = JSON.parse(localStorage.getItem(mediaStorageKey(kind)) || '{}'); } catch (e) {}

  const addField = function(name, spec)
  {
    const label = document.createElement('label');
    label.title = spec.description || name;
    label.appendChild(document.createTextNode(name.replace(/_/g, ' ') + ' '));
    let input;
    if (spec.type === 'enum' || spec.type === 'bool')
    {
      input = document.createElement('select');
      const auto = document.createElement('option');
      auto.value = '';
      auto.textContent = spec.default !== undefined ? 'default (' + spec.default + ')' : 'default';
      input.appendChild(auto);
      (spec.type === 'bool' ? ['true', 'false'] : (spec.values || [])).forEach(function(v)
      {
        const opt = document.createElement('option');
        opt.value = String(v);
        opt.textContent = spec.type === 'bool' ? (v === 'true' ? 'on' : 'off') : String(v);
        input.appendChild(opt);
      });
    }
    else
    {
      input = document.createElement('input');
      input.type = spec.type === 'int' || spec.type === 'number' ? 'number' : 'text';
      if (spec.min !== undefined) input.min = spec.min;
      if (spec.max !== undefined) input.max = spec.max;
      if (spec.type === 'number') input.step = 'any';
      else if (spec.step !== undefined) input.step = spec.step;
      input.placeholder = spec.default !== undefined ? String(spec.default) : 'auto';
      if (input.type === 'text') input.className = 'media-text-field';
    }
    input.dataset.param = name;
    if (saved[name] !== undefined) input.value = saved[name];
    input.onchange = function() { saveMediaParams(kind); };
    label.appendChild(input);
    box.appendChild(label);
  };

  const params = info && info.parameters ? info.parameters : null;
  if (kind === 'video')
  {
    // The longest clip the model accepts, from its own frame limit; its default length applies when the field is empty
    const frames = params && params.frames ? params.frames : null;
    const fps = params && params.fps && typeof params.fps.default === 'number' ? params.fps.default : 24;
    const maxSeconds = frames && frames.max ? Math.floor(frames.max / fps) : 20;
    const defaultSeconds = frames && typeof frames.default === 'number' ? Math.round(frames.default / fps) : undefined;
    addField('seconds', { type: 'number', min: 1, max: maxSeconds, default: defaultSeconds, description: 'Clip length in seconds, up to ' + maxSeconds + ' for this model. Empty uses the model\'s own default. Longer clips take proportionally longer to generate.' });
  }
  if (params)
  {
    Object.keys(params).forEach(function(name)
    {
      const spec = params[name];
      // Pictures come from the order strip, the prompt from the chat box, and the length is asked as seconds
      if (!spec || spec.type === 'image' || name === 'prompt' || name === 'frames') return;
      addField(name, spec);
    });
  }
  else if (kind === 'video')
  {
    addField('size', { type: 'string', description: 'WIDTHxHEIGHT, e.g. 768x448' });
    addField('steps', { type: 'int', min: 1, max: 100 });
    addField('seed', { type: 'int', min: 0 });
  }

  const addFrameBox = function(id, text, title, on)
  {
    const label = document.createElement('label');
    label.title = title;
    const check = document.createElement('input');
    check.type = 'checkbox';
    check.id = id;
    check.checked = on;
    check.onchange = function() { saveMediaParams(kind); };
    label.appendChild(check);
    label.appendChild(document.createTextNode(' ' + text));
    box.appendChild(label);
  };
  if (kind === 'video' && (!params || params.image))
  {
    const label = document.createElement('label');
    label.title = 'The size of a clip that starts from a picture. Matching the aspect ratio keeps the picture from being stretched or cropped; the width and height fields then only set how many pixels the clip has.';
    const select = document.createElement('select');
    [['aspect', 'Size: Image 1 aspect ratio'], ['image', 'Size: Image 1 resolution'], ['set', 'Size: as set']].forEach(function(pair)
    {
      const opt = document.createElement('option');
      opt.value = pair[0];
      opt.textContent = pair[1];
      select.appendChild(opt);
    });
    select.dataset.param = 'size_from';
    select.value = saved.size_from || 'aspect';
    select.onchange = function() { saveMediaParams(kind); };
    label.appendChild(select);
    box.appendChild(label);
  }
  if (kind === 'video' && (!params || params.image)) addFrameBox('video-first-frame', 'First frame: Image 1', 'Animate Image 1 of the strip above the prompt (image-to-video). Untick for text-to-video.', saved.__first !== false);
  if (kind === 'video' && params && params.end_image) addFrameBox('video-last-frame', 'Last frame: Image 2', 'End the clip on Image 2 of the strip above the prompt.', saved.__last === true);
  if (kind === 'model3d')
  {
    const note = document.createElement('span');
    note.textContent = 'Builds from Image 1';
    note.title = 'The 3D model is built from Image 1 of the strip above the prompt: one object, whole, on a plain or transparent background.';
    box.appendChild(note);
  }
  if (info && (info.title || info.description))
  {
    box.title = (info.title ? info.title + '. ' : '') + (info.description || '');
  }
}

function saveMediaParams(kind)
{
  const box = document.getElementById(kind + '-ai-controls');
  if (!box) return;
  const values = {};
  box.querySelectorAll('[data-param]').forEach(function(el) { if (el.value !== '') values[el.dataset.param] = el.value; });
  const first = document.getElementById('video-first-frame');
  const last = document.getElementById('video-last-frame');
  if (kind === 'video' && first) values.__first = first.checked;
  if (kind === 'video' && last) values.__last = last.checked;
  try { localStorage.setItem(mediaStorageKey(kind), JSON.stringify(values)); } catch (e) {}
}

// The form as request fields; an empty field is left out so the model's own default applies
function mediaParamsFromPane(kind)
{
  const box = document.getElementById(kind + '-ai-controls');
  const values = {};
  if (!box) return values;
  box.querySelectorAll('[data-param]').forEach(function(el) { if (el.value !== '') values[el.dataset.param] = el.value; });
  if (kind === 'video')
  {
    const inputs = getInputImageFiles();
    const first = document.getElementById('video-first-frame');
    const last = document.getElementById('video-last-frame');
    if (first && first.checked && inputs.length > 0) values.image = 'Image 1';
    if (last && last.checked && inputs.length > 1) values.end_image = 'Image 2';
  }
  return values;
}

// ----- Mobile: the keyboard follows the pane -----
// The three panes are swiped sideways on a phone, and the keyboard kept typing into whatever had the cursor
// before the swipe. When a text field has focus and another pane snaps into place, focus moves with it: to the
// prompt box on the AI pane, back to the editor (CodeMirror keeps its cursor and selection) on the editor pane.
// With no field focused nothing happens, so a swipe never pops the keyboard up by itself.
let paneFocusTimer = null;

function settlePaneFocus()
{
  if (window.innerWidth > 768) return;
  const workspace = document.getElementById('workspace');
  const active = document.activeElement;
  if (!workspace || !active || active === document.body) return;
  const typing = active.tagName === 'TEXTAREA' || active.tagName === 'INPUT' || active.isContentEditable;
  if (!typing) return;

  let current = null;
  ['pane-files', 'pane-editor', 'pane-chat'].forEach(function(id)
  {
    const pane = document.getElementById(id);
    if (pane && Math.abs(pane.offsetLeft - workspace.scrollLeft) < pane.offsetWidth / 2) current = pane;
  });
  if (!current || current.contains(active)) return;

  if (current.id === 'pane-chat')
  {
    const input = document.getElementById('chat-input');
    if (input) input.focus({ preventScroll: true });
  }
  else if (current.id === 'pane-editor' && editor && !isViewerFile(currentFileUrl))
  {
    const editorCont = document.getElementById('editor-container');
    if (editorCont && !editorCont.classList.contains('hidden')) editor.focus();
    else active.blur();
  }
  else active.blur();
}

document.addEventListener('DOMContentLoaded', function()
{
  const workspace = document.getElementById('workspace');
  if (!workspace) return;
  workspace.addEventListener('scroll', function()
  {
    if (paneFocusTimer) clearTimeout(paneFocusTimer);
    paneFocusTimer = setTimeout(settlePaneFocus, 180);
  }, { passive: true });
});
