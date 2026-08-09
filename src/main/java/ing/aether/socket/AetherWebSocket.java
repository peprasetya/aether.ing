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
            try { session.getBasicRemote().sendText(msg); } catch (IOException e) { e.printStackTrace(); }
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
        if ("chat".equals(type))
        {
          String msg = (String) data.get("message");
          String agentId = (String) data.get("agent_id");
          String modelId = (String) data.get("model_id");
          String thinkingMode = (String) data.get("thinking_mode");
          String selectedText = (String) data.get("selected_text");
          String terminalOutput = (String) data.get("terminal_output");
          Object contextObj = data.get("context_files");
          List<String> contextFiles = null;
          if (contextObj instanceof Object[]) {
              contextFiles = new ArrayList<>();
              for (Object o : (Object[])contextObj) contextFiles.add(o.toString());
          } else if (contextObj instanceof List) {
              contextFiles = (List<String>) contextObj;
          }
          
          System.out.println("AetherWebSocket: Routing chat request. Agent=" + agentId + ", Model=" + modelId + ", ContextFiles=" + (contextFiles != null ? contextFiles.size() : 0));
          
          if (httpSession != null)
          {
            // SURGICAL FIX: Clear historical stream buffer before starting a fresh chat turn
            sessionBuffers.remove(httpSession.getId());
            ing.aether.tools.ai.AgentWorker.process(httpSession, agentId, modelId, thinkingMode, contextFiles, msg, selectedText, terminalOutput);
          } else
          {
            System.err.println("AetherWebSocket: CRITICAL - HttpSession not found in WebSocket session properties!");
          }
        } else if ("clear_chat".equals(type))
        {
          if (httpSession != null)
          {
            // SURGICAL FIX: Remove the buffer on clear explicitly
            sessionBuffers.remove(httpSession.getId());
            ing.aether.tools.ai.AgentWorker.createNewSession(httpSession);
            System.out.println("AetherWebSocket: New chat session created.");
          }
        } else if ("sync_history".equals(type))
        {
          if (httpSession != null) ing.aether.tools.ai.AgentWorker.syncChatHistory(httpSession);
        } else if ("stop".equals(type))
        {
          if (httpSession != null)
          {
            ing.aether.tools.ai.AgentWorker.interruptProcessing(httpSession.getId());
          }
        } else if ("permission_response".equals(type))
        {
          if (httpSession != null)
          {
            boolean granted = Boolean.TRUE.equals(data.get("granted"));
            String reason = data.get("reason") instanceof String ? (String) data.get("reason") : null;
            ing.aether.tools.ai.AgentWorker.handlePermission(httpSession.getId(), granted, reason);
          }
        } else if ("question_response".equals(type))
        {
          if (httpSession != null) ing.aether.tools.ai.AgentWorker.handleQuestionAnswers(httpSession.getId(), data.get("answers"));
        } else if ("list_sessions".equals(type))
        {
          if (httpSession != null) ing.aether.tools.ai.AgentWorker.broadcastSessionList(httpSession);
        } else if ("switch_session".equals(type))
        {
          if (httpSession != null) ing.aether.tools.ai.AgentWorker.switchSession(httpSession, (String) data.get("id"));
        } else if ("delete_session".equals(type))
        {
          if (httpSession != null) ing.aether.tools.ai.AgentWorker.deleteSession(httpSession, (String) data.get("id"));
        } else if ("clear_all_sessions".equals(type))
        {
          if (httpSession != null) ing.aether.tools.ai.AgentWorker.clearAllSessions(httpSession);
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
          try { s.getBasicRemote().sendText(msg); } catch (IOException e) { e.printStackTrace(); }
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
          try { s.getBasicRemote().sendText(msg); delivered = true; } catch (IOException e) { e.printStackTrace(); }
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
}
