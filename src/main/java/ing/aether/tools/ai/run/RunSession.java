package ing.aether.tools.ai.run;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import ing.aether.Portal;
import ing.aether.SessionTracker;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;

// A detached, never-expiring session owned by one agent run. It snapshots the browser session's
// attributes when the run starts (identity, working directory, open file, chat state), so AITools,
// PathMap, FileProviderRegistry and the whole AgentWorker loop run unchanged without a live browser
// session behind them. getId() is the run id: every AetherWebSocket.sendToSession(runId, ...) is
// therefore routed into the run's event log and fanned out to all of the owner's devices.
// Exposed as a dynamic proxy: the build compiles against an older Servlet API than the container
// runs, so implementing HttpSession directly would pin the wrong interface version.
public class RunSession implements InvocationHandler
{
  // Chat state the browser sessions viewing the same chat must see too (reload renders from them)
  private static final Set<String> SHARED_CHAT_ATTRS = new HashSet<>(Arrays.asList("chatHistory", "todoList"));

  private final String runId;
  private final long created = System.currentTimeMillis();
  private final Map<String, Object> attributes = new ConcurrentHashMap<>();
  private final ServletContext context;
  private final HttpSession proxy;

  public RunSession(String runId, HttpSession origin)
  {
    this.runId = runId;
    this.context = origin != null ? origin.getServletContext() : null;
    if (origin != null) for (Enumeration<String> names = origin.getAttributeNames(); names.hasMoreElements();)
    {
      String name = names.nextElement();
      Object value = origin.getAttribute(name);
      if (value != null) attributes.put(name, value);
    }
    this.proxy = (HttpSession) Proxy.newProxyInstance(HttpSession.class.getClassLoader(), new Class<?>[] { HttpSession.class }, this);
  }

  public HttpSession asHttpSession()
  {
    return proxy;
  }

  public String getIssSubId()
  {
    return (String) attributes.get(Portal.SessionAccountID);
  }

  public Object getAttribute(String name)
  {
    return name == null ? null : attributes.get(name);
  }

  public void setAttribute(String name, Object value)
  {
    if (name == null) return;
    if (value == null)
    {
      removeAttribute(name);
      return;
    }
    attributes.put(name, value);
    if (SHARED_CHAT_ATTRS.contains(name)) propagateToViewers(name, value);
    // AITools.writeFile keeps the editor's save baseline in step with an AI write of the open file:
    // it must reach the browser sessions that actually have that file open
    if ("bufferedPath".equals(name)) propagateSaveBaseline((String) value);
  }

  public void removeAttribute(String name)
  {
    if (name == null) return;
    attributes.remove(name);
    if ("todoList".equals(name)) propagateToViewers(name, null);
  }

  @Override
  public Object invoke(Object self, Method method, Object[] args)
  {
    switch (method.getName())
    {
      case "getId": return runId;
      case "getAttribute": case "getValue": return getAttribute((String) args[0]);
      case "setAttribute": case "putValue": setAttribute((String) args[0], args[1]); return null;
      case "removeAttribute": case "removeValue": removeAttribute((String) args[0]); return null;
      case "getAttributeNames": return Collections.enumeration(new ArrayList<>(attributes.keySet()));
      case "getValueNames": return attributes.keySet().toArray(new String[0]);
      case "getCreationTime": return created;
      case "getLastAccessedTime": return System.currentTimeMillis();
      case "getServletContext": return context;
      case "getMaxInactiveInterval": return -1;
      case "isNew": return false;
      case "hashCode": return System.identityHashCode(self);
      case "equals": return self == args[0];
      case "toString": return "RunSession[" + runId + "]";
      default: return defaultValue(method.getReturnType()); // setMaxInactiveInterval, invalidate, ...
    }
  }

  private static Object defaultValue(Class<?> type)
  {
    if (type == boolean.class) return false;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    return null;
  }

  private void propagateToViewers(String name, Object value)
  {
    String chatId = (String) attributes.get("currentChatSessionId");
    String wd = (String) attributes.get("workingDirectory");
    if (chatId == null) return;
    for (HttpSession s : SessionTracker.sessionsOf(getIssSubId()))
    {
      try
      {
        if (!chatId.equals(s.getAttribute("currentChatSessionId"))) continue;
        if (wd != null && !wd.equals(s.getAttribute("workingDirectory"))) continue;
        if (value == null) s.removeAttribute(name);
        else s.setAttribute(name, value);
      }
      catch (IllegalStateException e) {} // invalidated meanwhile
    }
  }

  private void propagateSaveBaseline(String path)
  {
    Object content = attributes.get("bufferedContent");
    if (path == null || content == null) return;
    for (HttpSession s : SessionTracker.sessionsOf(getIssSubId()))
    {
      try
      {
        if (!path.equals(s.getAttribute("lastFilePath"))) continue;
        s.setAttribute("bufferedContent", content);
        s.setAttribute("bufferedPath", path);
      }
      catch (IllegalStateException e) {}
    }
  }
}
