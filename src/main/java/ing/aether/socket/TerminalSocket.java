package ing.aether.socket;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.util.ajax.JSON;

import com.jcraft.jsch.ChannelShell;

import ing.aether.data.FileProvider;
import ing.aether.data.PathMap;
import ing.aether.data.SshFileProvider;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;

/**
 * Dedicated PTY terminal endpoint: bridges xterm.js in the browser to a JSch shell channel
 * on the working directory's SSH provider. Deliberately separate from AetherWebSocket —
 * terminal bytes must NEVER enter the chat replay buffer or its fan-out.
 *
 * Protocol (JSON text frames, data payloads base64 because PTY chunks split UTF-8 mid-character):
 *   in : {type:'start', cols, rows} {type:'data', data} {type:'resize', cols, rows} {type:'close'}
 *   out: {type:'ready'} {type:'data', data} {type:'exit'} {type:'error', message}
 */
@ServerEndpoint(value = "/socket/terminal", configurator = AetherWebSocket.Configurator.class)
public class TerminalSocket
{
  private static final JSON json = new JSON();

  private static class Term
  {
    ChannelShell channel;
    OutputStream stdin;
    Session ws;
  }

  // One live terminal per HTTP session; a new start replaces the old one
  private static final Map<String, Term> terms = new ConcurrentHashMap<>();

  @OnOpen
  public void onOpen(Session session)
  {
    // Log line doubles as the JSR-356 precedence check: the exact path must win over /socket/{target}
    System.out.println("TerminalSocket opened: ws=" + session.getId());
  }

  @OnMessage
  public void onMessage(String message, Session session)
  {
    jakarta.servlet.http.HttpSession httpSession = (jakarta.servlet.http.HttpSession) session.getUserProperties().get("javax.servlet.http.HttpSession");
    if (httpSession == null) { send(session, Map.of("type", "error", "message", "Not logged in.")); return; }

    try
    {
      Map<String, Object> data = (Map<String, Object>) json.parse(new JSON.ReaderSource(new StringReader(message)));
      String type = (String) data.get("type");
      if ("start".equals(type)) start(session, httpSession, asInt(data.get("cols"), 80), asInt(data.get("rows"), 24));
      else if ("data".equals(type)) input(httpSession.getId(), (String) data.get("data"));
      else if ("resize".equals(type)) resize(httpSession.getId(), asInt(data.get("cols"), 80), asInt(data.get("rows"), 24));
      else if ("close".equals(type)) closeFor(httpSession.getId());
    }
    catch (Exception e)
    {
      System.out.println("TerminalSocket message error: " + e);
      send(session, Map.of("type", "error", "message", String.valueOf(e.getMessage())));
    }
  }

  private void start(Session ws, jakarta.servlet.http.HttpSession httpSession, int cols, int rows) throws Exception
  {
    String workingDir = (String) httpSession.getAttribute("workingDirectory");
    if (workingDir == null || workingDir.isEmpty()) { send(ws, Map.of("type", "error", "message", "No working directory set. Open an SSH project first.")); return; }

    PathMap pm = new PathMap("/read/" + workingDir, httpSession);
    FileProvider provider = pm.getProvider();
    if (!(provider instanceof SshFileProvider)) { send(ws, Map.of("type", "error", "message", "Terminal requires the working directory to be on an SSH provider.")); return; }
    SshFileProvider ssh = (SshFileProvider) provider;

    // Replace any previous terminal for this HTTP session
    closeFor(httpSession.getId());

    Term term = new Term();
    term.ws = ws;
    term.channel = ssh.openShell(cols, rows);
    InputStream out = term.channel.getInputStream();
    term.stdin = term.channel.getOutputStream();
    term.channel.connect(15000);
    terms.put(httpSession.getId(), term);

    // Shell integration: cd into the project and hook precmd so the client learns exit code + cwd
    // per command via OSC 7770. Works in bash and zsh; other shells just print one error and go on.
    String cwd = ssh.resolveRemote(pm.getSuffix());
    String bootstrap = "cd '" + cwd.replace("'", "'\\''") + "'; aether_precmd() { printf '\\033]7770;%s;%s\\007' \"$?\" \"$PWD\"; }; if [ -n \"$ZSH_VERSION\" ]; then precmd_functions+=(aether_precmd); else export PROMPT_COMMAND=\"aether_precmd${PROMPT_COMMAND:+;$PROMPT_COMMAND}\"; fi; clear\n";
    term.stdin.write(bootstrap.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    term.stdin.flush();

    String httpSessionId = httpSession.getId();
    Thread reader = new Thread(() -> {
      byte[] buf = new byte[8192];
      try
      {
        int n;
        // Synchronous sends are the backpressure valve: a flood (cat big.log) throttles on the socket
        while ((n = out.read(buf)) > 0) send(term.ws, Map.of("type", "data", "data", Base64.getEncoder().encodeToString(Arrays.copyOf(buf, n))));
      }
      catch (Exception ignore) {}
      send(term.ws, Map.of("type", "exit"));
      terms.remove(httpSessionId, term);
      try { term.channel.disconnect(); } catch (Exception ignore) {}
    }, "aether-terminal-" + httpSessionId);
    reader.setDaemon(true);
    reader.start();

    send(ws, Map.of("type", "ready"));
  }

  private void input(String httpSessionId, String b64) throws Exception
  {
    Term term = terms.get(httpSessionId);
    if (term == null || b64 == null) return;
    term.stdin.write(Base64.getDecoder().decode(b64));
    term.stdin.flush();
  }

  private void resize(String httpSessionId, int cols, int rows)
  {
    Term term = terms.get(httpSessionId);
    if (term != null) term.channel.setPtySize(cols, rows, 0, 0);
  }

  public static void closeFor(String httpSessionId)
  {
    Term term = terms.remove(httpSessionId);
    if (term != null)
    {
      // Disconnecting fails the blocked read() so the reader thread exits and sends {type:'exit'}
      try { term.channel.disconnect(); } catch (Exception ignore) {}
    }
  }

  @OnClose
  public void onClose(Session session)
  {
    dropBySocket(session);
  }

  @OnError
  public void onError(Session session, Throwable throwable)
  {
    if (session != null) dropBySocket(session);
  }

  private static void dropBySocket(Session ws)
  {
    for (Map.Entry<String, Term> e : terms.entrySet())
    {
      if (e.getValue().ws == ws)
      {
        terms.remove(e.getKey(), e.getValue());
        try { e.getValue().channel.disconnect(); } catch (Exception ignore) {}
      }
    }
  }

  private static void send(Session ws, Map<String, Object> data)
  {
    if (ws == null || !ws.isOpen()) return;
    String msg = json.toJSON(data);
    try
    {
      synchronized (ws) { ws.getBasicRemote().sendText(msg); }
    }
    catch (IOException ignore) {}
  }

  private static int asInt(Object o, int def)
  {
    return o instanceof Number ? ((Number) o).intValue() : def;
  }
}
