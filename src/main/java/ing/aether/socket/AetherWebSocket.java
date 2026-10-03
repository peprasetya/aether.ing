package ing.aether.socket;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.ajax.JSON;

import jakarta.websocket.*;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;

@ServerEndpoint(value = "/socket/{target}", configurator = AetherWebSocket.Configurator.class)
public class AetherWebSocket
{
  public static class Configurator extends ServerEndpointConfig.Configurator
  {
    @Override
    public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request, HandshakeResponse response)
    {
      jakarta.servlet.http.HttpSession httpSession = (jakarta.servlet.http.HttpSession) request.getHttpSession();
      if (httpSession != null)
      {
        config.getUserProperties().put("javax.servlet.http.HttpSession", httpSession);
      }
    }
  }

  private static final Map<String, List<Session>> targetSessions = new ConcurrentHashMap<>();
  private static final Map<String, List<String>> sessionBuffers = new ConcurrentHashMap<>();
  private static final Map<String, Object[]> lastNotify = new ConcurrentHashMap<>();
  private static final JSON json = new JSON();
  private static final String CLIENT_ID = "aetherClientId";
  private static final String VISIBLE = "aetherVisible";

  // Toast in the client's notification-container; deduplicated so a repeating background failure doesn't spam
  public static void notify(String httpSessionId, String text)
  {
    Object[] last = lastNotify.get(httpSessionId);
    if (last != null && text.equals(last[0]) && System.currentTimeMillis() - (Long) last[1] < 30000L) return;
    lastNotify.put(httpSessionId, new Object[] { text, System.currentTimeMillis() });
    Map<String, Object> msg = new HashMap<>();
    msg.put("type", "notify");
    msg.put("text", text);
    sendToSession(httpSessionId, msg, false);
  }

  @OnOpen
  public void onOpen(Session session, @PathParam("target") String target)
  {
    session.setMaxIdleTimeout(0);
    targetSessions.computeIfAbsent(target, k -> new CopyOnWriteArrayList<>()).add(session);
    System.out.println("WebSocket opened: " + target + " for session " + session.getId());

    // If this is a reconnecting chat session, send buffered messages
    if ("aetherui".equals(target))
    {
      String httpSessionId = getHttpSessionId(session);
      if (httpSessionId != null)
      {
        List<String> buffer = sessionBuffers.get(httpSessionId);
        if (buffer != null)
        {
          for (String msg : buffer)
          {
            sendRaw(session, msg);
          }
          // Replay-once: if no AI turn is streaming, the buffer held a finished turn — drop it so further reconnects don't duplicate it
          if (!ing.aether.tools.ai.AgentWorker.isProcessing(httpSessionId)) sessionBuffers.remove(httpSessionId);
        }
      }
    }
  }

  @OnMessage
  public void onMessage(String message, Session session, @PathParam("target") String target)
  {
    jakarta.servlet.http.HttpSession httpSession = (jakarta.servlet.http.HttpSession) session.getUserProperties().get("javax.servlet.http.HttpSession");
    if (httpSession != null) ing.aether.SessionTracker.sessionCheck(httpSession);

    if ("aetherui".equals(target))
    {
      try
      {
        Map<String, Object> data = (Map<String, Object>) json.parse(new org.eclipse.jetty.util.ajax.JSON.ReaderSource(new java.io.StringReader(message)));
        String type = (String) data.get("type");
        if (httpSession == null)
        {
          System.err.println("AetherWebSocket: CRITICAL - HttpSession not found in WebSocket session properties!");
          return;
        }
        String issSubId = (String) httpSession.getAttribute(ing.aether.Portal.SessionAccountID);
        String runId = data.get("runId") instanceof String ? (String) data.get("runId") : null;
        if ("hello".equals(type))
        {
          // A (re)connecting tab: remember who it is, then replay what it missed of the runs it shows
          if (data.get("clientId") instanceof String) session.getUserProperties().put(CLIENT_ID, data.get("clientId"));
          session.getUserProperties().put(VISIBLE, !Boolean.FALSE.equals(data.get("visible")));
          Map<String, Object> cursors = data.get("cursors") instanceof Map ? (Map<String, Object>) data.get("cursors") : null;
          ing.aether.tools.ai.run.RunManager.attach(session, cursors);
        } else if ("ping".equals(type))
        {
          session.getUserProperties().put(VISIBLE, !Boolean.FALSE.equals(data.get("visible")));
          sendRaw(session, "{\"type\":\"pong\"}");
        } else if ("chat".equals(type))
        {
          String msg = (String) data.get("message");
          String agentId = (String) data.get("agent_id");
          String modelId = (String) data.get("model_id");
          Object contextObj = data.get("context_files");
          List<String> contextFiles = null;
          if (contextObj instanceof Object[]) {
              contextFiles = new ArrayList<>();
              for (Object o : (Object[])contextObj) contextFiles.add(o.toString());
          } else if (contextObj instanceof List) {
              contextFiles = (List<String>) contextObj;
          }
          System.out.println("AetherWebSocket: Routing chat request. Agent=" + agentId + ", Model=" + modelId + ", ContextFiles=" + (contextFiles != null ? contextFiles.size() : 0));
          // A chat message starts a run, or steers the run already working on this chat
          ing.aether.tools.ai.run.RunManager.handleChat(httpSession, (String) session.getUserProperties().get(CLIENT_ID), data, contextFiles);
        } else if ("clear_chat".equals(type))
        {
          sessionBuffers.remove(httpSession.getId());
          ing.aether.tools.ai.AgentWorker.createNewSession(httpSession);
          ing.aether.tools.ai.run.RunManager.viewChanged(httpSession);
          System.out.println("AetherWebSocket: New chat session created.");
        } else if ("sync_history".equals(type))
        {
          ing.aether.tools.ai.AgentWorker.syncChatHistory(httpSession);
        } else if ("stop".equals(type) || "cancel".equals(type))
        {
          ing.aether.tools.ai.run.AgentRun run = runId != null ? ing.aether.tools.ai.run.RunManager.getOwned(runId, issSubId) : ing.aether.tools.ai.run.RunManager.activeRunForSession(httpSession);
          if (run != null) ing.aether.tools.ai.run.RunManager.cancel(run);
          else ing.aether.tools.ai.AgentWorker.interruptProcessing(httpSession.getId());
        } else if ("pause".equals(type))
        {
          ing.aether.tools.ai.run.RunManager.pause(ing.aether.tools.ai.run.RunManager.getOwned(runId, issSubId));
        } else if ("resume_run".equals(type))
        {
          if (!ing.aether.tools.ai.run.RunManager.resume(runId, httpSession)) sendError(session, "not_resumable", "This run cannot be resumed.");
        } else if ("dismiss_run".equals(type))
        {
          ing.aether.tools.ai.run.RunManager.dismiss(ing.aether.tools.ai.run.RunManager.getOwned(runId, issSubId));
        } else if ("list_runs".equals(type))
        {
          ing.aether.tools.ai.run.RunManager.attach(session, null);
        } else if ("get_changes".equals(type))
        {
          ing.aether.tools.ai.run.StopGates.sendChanges(session, runId, issSubId);
        } else if ("permission_response".equals(type))
        {
          boolean granted = Boolean.TRUE.equals(data.get("granted"));
          String reason = data.get("reason") instanceof String ? (String) data.get("reason") : null;
          String runTarget = resolveRunTarget(httpSession, runId, issSubId);
          if (!ing.aether.tools.ai.AgentWorker.handlePermission(runTarget, granted, reason, Boolean.TRUE.equals(data.get("always")))) sendError(session, "already_resolved", "This request was already answered on another device.");
        } else if ("question_response".equals(type))
        {
          String runTarget = resolveRunTarget(httpSession, runId, issSubId);
          if (!ing.aether.tools.ai.AgentWorker.handleQuestionAnswers(runTarget, data.get("answers"))) sendError(session, "already_resolved", "These questions were already answered on another device.");
        } else if ("decision_response".equals(type))
        {
          Map<String, Object> answer = data.get("answer") instanceof Map ? (Map<String, Object>) data.get("answer") : new HashMap<>();
          if (!ing.aether.tools.ai.run.RunManager.resolveDecision(runId, (String) data.get("requestId"), answer, issSubId)) sendError(session, "already_resolved", "This request was already answered on another device.");
        } else if ("push_subscribe".equals(type))
        {
          ing.aether.tools.push.WebPush.subscribe(httpSession, data.get("subscription"));
        } else if ("list_sessions".equals(type))
        {
          ing.aether.tools.ai.AgentWorker.broadcastSessionList(httpSession);
        } else if ("switch_session".equals(type))
        {
          ing.aether.tools.ai.AgentWorker.switchSession(httpSession, (String) data.get("id"));
          ing.aether.tools.ai.run.RunManager.viewChanged(httpSession);
        } else if ("delete_session".equals(type))
        {
          ing.aether.tools.ai.AgentWorker.deleteSession(httpSession, (String) data.get("id"));
          ing.aether.tools.ai.run.RunManager.viewChanged(httpSession);
        } else if ("clear_all_sessions".equals(type))
        {
          ing.aether.tools.ai.AgentWorker.clearAllSessions(httpSession);
          ing.aether.tools.ai.run.RunManager.viewChanged(httpSession);
        }
      } catch (Exception e)
      {
        System.out.println("Web Socket on message Error:");
        e.printStackTrace();
      }
    }
  }

  @OnClose
  public void onClose(Session session, @PathParam("target") String target)
  {
    List<Session> sessions = targetSessions.get(target);
    if (sessions != null) sessions.remove(session);
    System.out.println("WebSocket closed: " + target);
  }

  @OnError
  public void onError(Session session, Throwable throwable)
  {
    // Fix: Early exit to cleanly ignore benign infrastructure drops
    if (throwable instanceof SocketTimeoutException || throwable instanceof java.nio.channels.ClosedChannelException)
    {
      return;
    }
    
    if (throwable instanceof IOException && throwable.getMessage() != null)
    {
      String msg = throwable.getMessage();
      if (msg.contains("Broken pipe") || msg.contains("Connection reset") || msg.contains("Closed"))
      {
        return;
      }
    }
    
    throwable.printStackTrace();
  }

  public static void broadcast(String target, Object data)
  {
    String msg = json.toJSON(data);
    List<Session> sessions = targetSessions.get(target);
    if (sessions != null)
    {
      for (Session s : sessions)
      {
        if (s.isOpen())
        {
          sendRaw(s, msg);
        }
      }
    }
  }

  public static boolean sendToSession(String httpSessionId, Object data)
  {
    return sendToSession(httpSessionId, data, true);
  }

  public static boolean sendToSession(String httpSessionId, Object data, boolean buffer)
  {
    // Agent runs use their run id as session id: log the event and fan it out to the owner's devices
    if (ing.aether.tools.ai.run.RunManager.isRun(httpSessionId)) return ing.aether.tools.ai.run.RunManager.emit(httpSessionId, data, buffer);
    String msg = json.toJSON(data);

    // Never buffer real-time or full-state snapshot types: replaying a stale snapshot on reconnect overwrites newer client state
    if (data instanceof Map)
    {
      Object type = ((Map<?, ?>) data).get("type");
      if ("monitor".equals(type) || "chat_history_update".equals(type) || "ai_config_update".equals(type) || "tool_progress".equals(type) || "todo_update".equals(type) || "session_list_update".equals(type)) buffer = false;
    }

    if (buffer)
    {
      // Store in buffer
      sessionBuffers.computeIfAbsent(httpSessionId, k -> new CopyOnWriteArrayList<>()).add(msg);
      if (sessionBuffers.get(httpSessionId).size() > 500) sessionBuffers.get(httpSessionId).remove(0);
    }

    // Find active WebSocket sessions for this HTTP session
    boolean delivered = false;
    for (List<Session> sessions : targetSessions.values())
    {
      for (Session s : sessions)
      {
        // Prune dead connections that never fired onClose (idle timeout is disabled)
        if (!s.isOpen()) { sessions.remove(s); continue; }
        if (httpSessionId.equals(getHttpSessionId(s)))
        {
          if (sendRaw(s, msg)) delivered = true;
        }
      }
    }
    return delivered;
  }

  public static void clearBuffer(String httpSessionId)
  {
    sessionBuffers.remove(httpSessionId);
  }

  // Drop buffered messages of one type, e.g. an answered permission_request that must not replay on reconnect
  public static void removeBufferedType(String httpSessionId, String type)
  {
    List<String> buffer = sessionBuffers.get(httpSessionId);
    if (buffer != null) buffer.removeIf(m -> m.contains("\"type\":\"" + type + "\""));
  }

  private static String getHttpSessionId(Session s)
  {
    Object session = s.getUserProperties().get("javax.servlet.http.HttpSession");
    if (session instanceof jakarta.servlet.http.HttpSession)
    {
      return ((jakarta.servlet.http.HttpSession)session).getId();
    }
    return null;
  }

  // Blocking sends on one socket must never overlap: run threads, the monitor and socket threads all send
  public static boolean sendRaw(Session s, String text)
  {
    if (s == null || !s.isOpen()) return false;
    synchronized (s)
    {
      try
      {
        s.getBasicRemote().sendText(text);
        return true;
      } catch (IOException | IllegalStateException e) { return false; }
    }
  }

  private static void sendError(Session s, String code, String message)
  {
    Map<String, Object> m = new HashMap<>();
    m.put("type", "error");
    m.put("code", code);
    m.put("message", message);
    sendRaw(s, json.toJSON(m));
  }

  public static jakarta.servlet.http.HttpSession httpSessionOf(Session s)
  {
    Object session = s.getUserProperties().get("javax.servlet.http.HttpSession");
    return session instanceof jakarta.servlet.http.HttpSession ? (jakarta.servlet.http.HttpSession) session : null;
  }

  // Open AI sockets of one account across all its browsers and devices
  public static List<Session> socketsOfUser(String issSubId)
  {
    List<Session> result = new ArrayList<>();
    List<Session> sessions = targetSessions.get("aetherui");
    if (sessions == null || issSubId == null) return result;
    for (Session s : sessions)
    {
      if (!s.isOpen()) continue;
      jakarta.servlet.http.HttpSession hs = httpSessionOf(s);
      try
      {
        if (hs != null && issSubId.equals(hs.getAttribute(ing.aether.Portal.SessionAccountID))) result.add(s);
      } catch (IllegalStateException e) {}
    }
    return result;
  }

  public static List<Session> socketsOfHttpSession(String httpSessionId)
  {
    List<Session> result = new ArrayList<>();
    List<Session> sessions = targetSessions.get("aetherui");
    if (sessions == null) return result;
    for (Session s : sessions) if (s.isOpen() && httpSessionId.equals(getHttpSessionId(s))) result.add(s);
    return result;
  }

  public static boolean isVisible(Session s)
  {
    return !Boolean.FALSE.equals(s.getUserProperties().get(VISIBLE));
  }

  // Answers name the run they belong to; older clients only know their own chat's active run
  private static String resolveRunTarget(jakarta.servlet.http.HttpSession httpSession, String runId, String issSubId)
  {
    if (runId != null && ing.aether.tools.ai.run.RunManager.getOwned(runId, issSubId) != null) return runId;
    ing.aether.tools.ai.run.AgentRun run = ing.aether.tools.ai.run.RunManager.activeRunForSession(httpSession);
    return run != null ? run.runId : httpSession.getId();
  }
}
