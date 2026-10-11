package ing.aether.tools.ai.run;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;

import javax.crypto.SecretKey;

import org.eclipse.jetty.util.ajax.JSON;

import ing.aether.Portal;
import ing.aether.SessionTracker;
import ing.aether.data.Agent;
import ing.aether.socket.AetherWebSocket;
import ing.aether.tools.VibedCrypto;
import ing.aether.tools.ai.AgentWorker;
import jakarta.servlet.http.HttpSession;
import jakarta.websocket.Session;

// Owns every agent run: start, steer, cancel, resume, decisions, the encrypted event log, fan-out of run
// events to all devices of the owner, replay for devices that attach mid-run, and the crash registry that
// lets runs interrupted by a restart be resumed.
public class RunManager
{
  // Virtual threads on the Java 21+ runtime (a parked approval then costs no platform thread); the build
  // targets an older release, so they are looked up reflectively with a cached pool as fallback
  public static final ExecutorService AI_EXECUTOR = newExecutor();
  private static final JSON json = new JSON();

  private static final Map<String, AgentRun> runs = new ConcurrentHashMap<>();
  private static final Map<String, SynchronousQueue<Map<String, Object>>> decisionQueues = new ConcurrentHashMap<>();
  private static final Map<String, List<Map<String, Object>>> userIndexCache = new ConcurrentHashMap<>();
  private static final ScheduledExecutorService housekeeping = Executors.newSingleThreadScheduledExecutor(r ->
  {
    Thread t = new Thread(r, "run-housekeeping");
    t.setDaemon(true);
    return t;
  });

  // Live-only: progress lines and the hardware monitor are worthless on replay
  private static final Set<String> EPHEMERAL = Set.of("chat_status", "tool_progress", "monitor");
  // Full-state snapshots: sent live to viewers, never logged (replaying a stale snapshot would overwrite newer state)
  private static final Set<String> SNAPSHOT = Set.of("chat_history_update", "todo_update", "session_list_update", "ai_config_update");
  // Events that put the run into WAITING_INPUT until the user answers on any device
  private static final Set<String> WAITING = Set.of("permission_request", "user_question", "plan_request", "budget_request", "human_gate", "pause_request");

  private static final long EVICT_AFTER_MS = 30L * 60 * 1000;
  private static final long LOG_RETENTION_MS = 30L * 24 * 3600 * 1000;
  private static final int INDEX_SIZE = 30;
  private static final String REGISTRY_FILE = "aether.runs.vibed";
  private static volatile boolean initialized = false;

  public static ExecutorService newExecutor()
  {
    try { return (ExecutorService) Executors.class.getMethod("newVirtualThreadPerTaskExecutor").invoke(null); }
    catch (Exception e)
    {
      return Executors.newCachedThreadPool(r ->
      {
        Thread t = new Thread(r, "ai-run");
        t.setDaemon(true);
        return t;
      });
    }
  }

  // ===== lifecycle =====

  public static synchronized void init()
  {
    if (initialized) return;
    initialized = true;
    loadRegistry();
    housekeeping.scheduleWithFixedDelay(RunManager::housekeep, 10, 10, TimeUnit.MINUTES);
  }

  public static void shutdown()
  {
    for (AgentRun run : runs.values())
    {
      if (!run.isActive()) continue;
      run.state = AgentRun.PARKED;
      run.stopReason = "interrupted";
      if (run.log != null)
      {
        run.log.append(Map.of("type", "run_state", "state", AgentRun.PARKED, "stopReason", "interrupted"), true);
        run.log.close();
      }
    }
    persistRegistry();
    housekeeping.shutdownNow();
  }

  public static HttpSession getRunSession(String runId)
  {
    AgentRun run = runId != null ? runs.get(runId) : null;
    return run != null && run.session != null ? run.session.asHttpSession() : null;
  }

  public static boolean isRun(String id)
  {
    return id != null && runs.containsKey(id);
  }

  public static AgentRun get(String runId)
  {
    return runId != null ? runs.get(runId) : null;
  }

  public static AgentRun getOwned(String runId, String issSubId)
  {
    AgentRun run = get(runId);
    if (run == null || issSubId == null || !issSubId.equals(run.issSubId)) return null;
    return run;
  }

  // The run that is currently working on this exact chat of this project, if any
  public static AgentRun activeRunFor(String issSubId, String wd, String chatId)
  {
    if (issSubId == null || chatId == null) return null;
    for (AgentRun r : runs.values())
    {
      if (!r.isActive() || !issSubId.equals(r.issSubId) || !chatId.equals(r.chatSessionId)) continue;
      if (wd == null || wd.equals(r.workingDir)) return r;
    }
    return null;
  }

  public static AgentRun activeRunForSession(HttpSession http)
  {
    if (http == null) return null;
    try
    {
      return activeRunFor((String) http.getAttribute(Portal.SessionAccountID), (String) http.getAttribute("workingDirectory"), (String) http.getAttribute("currentChatSessionId"));
    }
    catch (IllegalStateException e) { return null; }
  }

  public static boolean hasActiveRunInWd(String issSubId, String wd)
  {
    for (AgentRun r : runs.values()) if (r.isActive() && Objects.equals(issSubId, r.issSubId) && Objects.equals(wd, r.workingDir)) return true;
    return false;
  }

  // Live history list of a chat that a run is working on: switching to that chat must show the same list
  public static List<Map<String, Object>> liveHistory(String issSubId, String wd, String chatId)
  {
    AgentRun run = activeRunFor(issSubId, wd, chatId);
    if (run == null || run.session == null) return null;
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> h = (List<Map<String, Object>>) run.session.getAttribute("chatHistory");
    return h;
  }

  // ===== starting runs and steering =====

  // Entry point for a "chat" message: a new run, or a steer into the run already working on this chat
  @SuppressWarnings("unchecked")
  public static void handleChat(HttpSession http, String clientId, Map<String, Object> data, List<String> contextFiles)
  {
    String issSubId = (String) http.getAttribute(Portal.SessionAccountID);
    if (issSubId == null)
    {
      AetherWebSocket.notify(http.getId(), "Please sign in to use the AI assistant.");
      return;
    }
    String wd = (String) http.getAttribute("workingDirectory");
    String chatId = (String) http.getAttribute("currentChatSessionId");
    // A brand-new project has no chat yet: fix its id now so the run and this browser agree on it
    if (chatId == null)
    {
      chatId = UUID.randomUUID().toString();
      http.setAttribute("currentChatSessionId", chatId);
    }
    String msg = (String) data.get("message");
    String mode = "interrupt".equals(data.get("steer_mode")) ? "interrupt" : "queued";

    AgentRun active = activeRunFor(issSubId, wd, chatId);
    if (active != null && isStalled(active))
    {
      setState(active, AgentRun.FAILED, "stalled: the run stopped without finishing");
      active = null;
    }
    if (active != null)
    {
      if (!active.includeHistory)
      {
        AetherWebSocket.notify(http.getId(), "A command is still running in this chat. Wait for it or stop it first.");
        return;
      }
      steer(active, msg, mode, clientId);
      return;
    }

    // Image agents: the image model and pane settings travel with every message; the run snapshots them
    Object imageModel = data.get("image_model_id");
    if (imageModel instanceof String && !((String) imageModel).isEmpty()) http.setAttribute("imageModel", imageModel);
    if (data.get("image_params") instanceof Map) http.setAttribute("imageParams", data.get("image_params"));
    // Video and 3D agents: the model of that kind and the pane's generated settings form
    if (data.get("video_model_id") instanceof String && !((String) data.get("video_model_id")).isEmpty()) http.setAttribute("videoModel", data.get("video_model_id"));
    if (data.get("model3d_model_id") instanceof String && !((String) data.get("model3d_model_id")).isEmpty()) http.setAttribute("model3dModel", data.get("model3d_model_id"));
    if (data.get("video_params") instanceof Map) http.setAttribute("videoParams", data.get("video_params"));
    if (data.get("model3d_params") instanceof Map) http.setAttribute("model3dParams", data.get("model3d_params"));
    // The character picked in the chat pane; every message restates it, so an empty value is the narrator
    if (data.get("character") instanceof String && !((String) data.get("character")).trim().isEmpty()) http.setAttribute("turnCharacter", ((String) data.get("character")).trim());
    else http.removeAttribute("turnCharacter");

    String agentRef = (String) data.get("agent_id");
    String modelId = (String) data.get("model_id");
    String thinking = (String) data.get("thinking_mode");

    AgentRun run = newRun(http, issSubId, wd, chatId);
    if (run == null) return;
    run.agentId = agentRef;
    run.model = modelId;
    run.thinkingMode = thinking;
    run.originClientId = clientId;
    run.objective = msg != null ? (msg.length() > 200 ? msg.substring(0, 200) + "…" : msg) : "";
    run.planGate = Boolean.TRUE.equals(data.get("plan_gate")) || "true".equals(String.valueOf(data.get("plan_gate")));
    if (data.get("reviewer_model") instanceof String && !((String) data.get("reviewer_model")).isEmpty()) run.reviewerModel = (String) data.get("reviewer_model");
    applyAgentDefaults(run, agentRef, data.containsKey("plan_gate"));
    if (run.reviewerModel == null)
    {
      Agent agent = AgentWorker.findAgent(agentRef);
      if (agent != null) run.reviewerModel = agent.getReviewerModel();
    }

    emit(run.runId, userMessage(msg, clientId, false), true);
    emit(run.runId, Map.of("type", "chat_status", "stage", "accepted", "text", "Request accepted"), false);
    broadcastRunUpdate(run);
    persistRegistry();

    final String sel = (String) data.get("selected_text");
    final String term = (String) data.get("terminal_output");
    final HttpSession rs = run.session.asHttpSession();
    AI_EXECUTOR.submit(() ->
    {
      try { AgentWorker.process(rs, agentRef, modelId, thinking, contextFiles, msg, sel, term); }
      catch (Exception e)
      {
        e.printStackTrace();
        emit(run.runId, Map.of("type", "chat_error", "error", "Error: " + e.getMessage()), true);
      }
    });
  }

  private static AgentRun newRun(HttpSession http, String issSubId, String wd, String chatId)
  {
    String runId = UUID.randomUUID().toString();
    AgentRun run = new AgentRun(runId, issSubId, (String) http.getAttribute(Portal.SessionEmail), wd, System.currentTimeMillis());
    run.chatSessionId = chatId;
    try { run.log = RunEventLog.open(issSubId, runId); }
    catch (Exception e)
    {
      System.out.println("WARN: run log unavailable, run is not crash-safe: " + e.getMessage());
      run.log = null;
    }
    attachSession(run, http);
    runs.put(runId, run);
    SessionTracker.pinUser(issSubId);
    if (run.log != null) run.log.append(Map.of("type", "run_created", "chatSessionId", chatId, "workingDir", String.valueOf(wd)), true);
    return run;
  }

  // Detached session + history list that knows its log position; shared with the browsers viewing the chat
  @SuppressWarnings("unchecked")
  private static void attachSession(AgentRun run, HttpSession http)
  {
    RunSession rs = new RunSession(run.runId, http);
    List<Map<String, Object>> h = (List<Map<String, Object>>) http.getAttribute("chatHistory");
    rs.setAttribute("currentChatSessionId", run.chatSessionId);
    rs.setAttribute("chatHistory", new AgentRun.RunHistory(run, h != null ? h : new ArrayList<>()));
    run.session = rs;
  }

  private static void applyAgentDefaults(AgentRun run, String agentRef, boolean planGateChosen)
  {
    Agent agent = AgentWorker.findAgent(agentRef);
    run.roundLimit = AgentWorker.MAX_ROUNDS;
    if (agent == null) return;
    if (agent.getBehavior() != null) run.behavior = agent.getBehavior();
    if (agent.getBudget() != null)
    {
      Object mc = agent.getBudget().get("modelCalls");
      if (mc instanceof Number) run.roundLimit = ((Number) mc).intValue();
      Object cont = agent.getBudget().get("continuations");
      if (cont instanceof Number) run.maxContinuations = ((Number) cont).intValue();
    }
    // A command without chatlog runs on an ephemeral turn list: it cannot take steers
    run.includeHistory = agent.isChatlogCommand(agentRef != null && agentRef.contains(":") ? agentRef.substring(agentRef.indexOf(':') + 1) : "default");
    // The agent's plan-gate default applies only when the user did not choose per run
    if (agent.getPlanGate() != null && !planGateChosen && run.isAutonomous()) run.planGate = agent.getPlanGate();
  }

  private static Map<String, Object> userMessage(String text, String clientId, boolean steer)
  {
    Map<String, Object> m = new HashMap<>();
    m.put("type", "user_message");
    m.put("content", text != null ? text : "");
    m.put("time", System.currentTimeMillis());
    m.put("steer", steer);
    if (clientId != null) m.put("origin", clientId);
    return m;
  }

  // Queued steers are drained at the next round boundary (never between tool calls of one batch, which
  // would break the assistant/tool pairing); "interrupt" also aborts the current model stream
  public static void steer(AgentRun run, String text, String mode, String clientId)
  {
    if (text == null || text.trim().isEmpty()) return;
    run.steers.add(new AgentRun.Steer(text, mode, clientId));
    emit(run.runId, userMessage(text, clientId, true), true);
    emit(run.runId, Map.of("type", "chat_status", "stage", "steer", "text", "interrupt".equals(mode) ? "Interrupting to apply your message…" : "Your message will be applied after the current step"), false);
    if ("interrupt".equals(mode))
    {
      run.interruptRequested = true;
      AgentWorker.abortStream(run.runId);
    }
  }

  // Round boundary hook (called on the run's thread before each model call): pause, then steers
  public static int drainSteers(String runId, List<Map<String, Object>> history) throws InterruptedException
  {
    AgentRun run = runs.get(runId);
    if (run == null) return 0;
    if (run.pauseRequested)
    {
      Map<String, Object> req = new HashMap<>();
      req.put("type", "pause_request");
      req.put("message", "Paused by you. The run waits here until you resume it.");
      awaitDecision(run, req);
      run.pauseRequested = false;
    }
    run.interruptRequested = false;
    int count = 0;
    for (AgentRun.Steer s; (s = run.steers.poll()) != null;)
    {
      Map<String, Object> m = new HashMap<>();
      m.put("role", "user");
      m.put("content", s.text);
      m.put("steer", true);
      m.put("time", s.time);
      history.add(m);
      count++;
    }
    if (count > 0) emit(runId, Map.of("type", "steer_applied", "count", count), true);
    return count;
  }

  public static boolean hasPendingSteers(String runId)
  {
    AgentRun run = runs.get(runId);
    return run != null && !run.steers.isEmpty();
  }

  public static boolean consumeInterrupt(String runId)
  {
    AgentRun run = runs.get(runId);
    if (run == null || !run.interruptRequested) return false;
    run.interruptRequested = false;
    return true;
  }

  // A delegated worker sees the user's new instructions too; the manager still gets them afterwards
  public static void forwardSteersToSub(String runId, List<Map<String, Object>> subHistory)
  {
    AgentRun run = runs.get(runId);
    if (run == null) return;
    for (AgentRun.Steer s : run.steers)
    {
      if (s.forwardedToSub) continue;
      s.forwardedToSub = true;
      Map<String, Object> m = new HashMap<>();
      m.put("role", "user");
      m.put("content", "[New message from the user while you work on this delegated task — take it into account]\n" + s.text);
      subHistory.add(m);
    }
  }

  public static void cancel(AgentRun run)
  {
    if (run == null || run.isTerminal()) return;
    boolean wasParked = AgentRun.PARKED.equals(run.state);
    run.steers.clear();
    setState(run, AgentRun.CANCELLED, "stopped by user");
    if (!wasParked) AgentWorker.interruptProcessing(run.runId);
    for (String key : new ArrayList<>(decisionQueues.keySet())) if (key.startsWith(run.runId + "|")) decisionQueues.remove(key);
  }

  public static void pause(AgentRun run)
  {
    if (run != null && run.isActive()) run.pauseRequested = true;
  }

  // Continue a run interrupted by a server restart on the same chat and log
  public static boolean resume(String runId, HttpSession http)
  {
    String issSubId = (String) http.getAttribute(Portal.SessionAccountID);
    AgentRun run = getOwned(runId, issSubId);
    if (run == null || !AgentRun.PARKED.equals(run.state)) return false;
    if (run.workingDir != null && !run.workingDir.equals(http.getAttribute("workingDirectory")))
    {
      AetherWebSocket.notify(http.getId(), "Open the project " + run.workingDir + " first to resume this run.");
      return false;
    }
    if (!run.chatSessionId.equals(http.getAttribute("currentChatSessionId"))) AgentWorker.switchSession(http, run.chatSessionId);
    try { run.log = RunEventLog.open(run.issSubId, run.runId); } catch (Exception e) { run.log = null; }
    attachSession(run, http);
    run.stopReason = null;
    SessionTracker.pinUser(run.issSubId);
    setState(run, AgentRun.WORKING, null);
    String msg = "▶️ Resume: the server restarted while you were working on this task. Re-check the todo list and the files to find the current state, then continue with the remaining work.";
    emit(run.runId, userMessage(msg, null, false), true);
    final HttpSession rs = run.session.asHttpSession();
    AI_EXECUTOR.submit(() ->
    {
      try { AgentWorker.process(rs, run.agentId, run.model, run.thinkingMode, null, msg, null, null); }
      catch (Exception e) { emit(run.runId, Map.of("type", "chat_error", "error", "Error: " + e.getMessage()), true); }
    });
    return true;
  }

  public static void dismiss(AgentRun run)
  {
    if (run == null) return;
    if (run.isActive()) cancel(run);
    else if (AgentRun.PARKED.equals(run.state)) setState(run, AgentRun.CANCELLED, "dismissed");
  }

  // ===== decisions (plan gate, budget, human gate, pause) =====

  // Blocks the run's virtual thread until any of the owner's devices answers; survives disconnects
  public static Map<String, Object> awaitDecision(AgentRun run, Map<String, Object> request) throws InterruptedException
  {
    String requestId = UUID.randomUUID().toString();
    request.put("requestId", requestId);
    SynchronousQueue<Map<String, Object>> q = new SynchronousQueue<>();
    decisionQueues.put(run.runId + "|" + requestId, q);
    emit(run.runId, request, true);
    try { return q.take(); }
    finally { decisionQueues.remove(run.runId + "|" + requestId); }
  }

  public static boolean resolveDecision(String runId, String requestId, Map<String, Object> answer, String issSubId)
  {
    AgentRun run = getOwned(runId, issSubId);
    if (run == null || requestId == null) return false;
    SynchronousQueue<Map<String, Object>> q = decisionQueues.get(runId + "|" + requestId);
    if (q == null) return false;
    try
    {
      if (!q.offer(answer, 500, TimeUnit.MILLISECONDS)) return false;
    }
    catch (InterruptedException e)
    {
      Thread.currentThread().interrupt();
      return false;
    }
    emit(runId, Map.of("type", "approval_resolved", "requestId", requestId), true);
    return true;
  }

  // ===== event routing =====

  // Every AetherWebSocket.sendToSession(runId, ...) lands here: log it, update run state, fan it out
  @SuppressWarnings("unchecked")
  public static boolean emit(String runId, Object data, boolean buffer)
  {
    AgentRun run = runs.get(runId);
    if (run == null || !(data instanceof Map)) return false;
    Map<String, Object> evt = new HashMap<>((Map<String, Object>) data);
    String type = String.valueOf(evt.get("type"));
    evt.put("runId", runId);
    evt.put("chatSessionId", run.chatSessionId);
    if (run.originClientId != null && !evt.containsKey("origin")) evt.put("origin", run.originClientId);

    if ("notify".equals(type)) return deliver(run, evt, true);
    if (EPHEMERAL.contains(type) || SNAPSHOT.contains(type) || !buffer)
    {
      if ("todo_update".equals(type)) countTodos(run, evt.get("todos"));
      return deliver(run, evt, false);
    }

    if (run.log != null)
    {
      boolean durable = !"chat_delta".equals(type) && !"plaintext".equals(type);
      evt = run.log.append(evt, durable);
    }
    run.updated = System.currentTimeMillis();
    boolean delivered = deliver(run, evt, false);
    trackState(run, type, evt);
    return delivered;
  }

  private static void trackState(AgentRun run, String type, Map<String, Object> evt)
  {
    if (run.isTerminal()) return;
    if (WAITING.contains(type))
    {
      run.pendingRequest = evt;
      setState(run, AgentRun.WAITING_INPUT, null);
    }
    else if ("approval_resolved".equals(type))
    {
      run.pendingRequest = null;
      if (AgentRun.WAITING_INPUT.equals(run.state)) setState(run, AgentRun.WORKING, null);
    }
    else if ("review_start".equals(type)) setState(run, AgentRun.REVIEWING, null);
    else if ("review_verdict".equals(type)) setState(run, AgentRun.WORKING, null);
    else if ("chat_done".equals(type)) setState(run, AgentRun.COMPLETED, null);
    else if ("chat_error".equals(type)) setState(run, AgentRun.FAILED, String.valueOf(evt.get("error")));
  }

  private static void countTodos(AgentRun run, Object todos)
  {
    Iterable<?> iter = todos instanceof Object[] ? Arrays.asList((Object[]) todos) : todos instanceof Iterable ? (Iterable<?>) todos : null;
    int done = 0, total = 0;
    if (iter != null) for (Object o : iter)
    {
      if (!(o instanceof Map)) continue;
      total++;
      if ("done".equals(((Map<?, ?>) o).get("status"))) done++;
    }
    boolean changed = done != run.todoDone || total != run.todoTotal;
    run.todoDone = done;
    run.todoTotal = total;
    if (changed) broadcastRunUpdate(run);
  }

  public static void setState(AgentRun run, String state, String reason)
  {
    if (run.isTerminal()) return;
    String previous = run.state;
    run.state = state;
    run.stopReason = reason;
    run.updated = System.currentTimeMillis();
    if (!state.equals(previous) && run.log != null) run.log.append(Map.of("type", "run_state", "state", state, "stopReason", reason != null ? reason : ""), true);
    broadcastRunUpdate(run);
    if (run.isTerminal())
    {
      run.pendingRequest = null;
      // Late background work (chat title, personality) still needs the user's providers for a moment
      housekeeping.schedule(() -> SessionTracker.unpinUser(run.issSubId), 2, TimeUnit.MINUTES);
      persistIndex(run.issSubId);
    }
    if (!state.equals(previous)) persistRegistry();
    if (!state.equals(previous) && (AgentRun.WAITING_INPUT.equals(state) || run.isTerminal())) notifyAway(run);
  }

  // Push to the phone only when none of the user's screens shows Aether right now
  private static void notifyAway(AgentRun run)
  {
    for (Session s : AetherWebSocket.socketsOfUser(run.issSubId)) if (AetherWebSocket.isVisible(s)) return;
    String title;
    if (AgentRun.WAITING_INPUT.equals(run.state)) title = "Aether needs your input";
    else if (AgentRun.COMPLETED.equals(run.state)) title = "Aether task finished";
    else if (AgentRun.FAILED.equals(run.state)) title = "Aether task failed";
    else return;
    ing.aether.tools.push.WebPush.notifyUser(run.issSubId, run.email, title, run.objective, "/run/" + run.runId, run.runId);
  }

  private static boolean views(HttpSession hs, AgentRun run)
  {
    if (hs == null || run.chatSessionId == null) return false;
    try
    {
      if (!run.chatSessionId.equals(hs.getAttribute("currentChatSessionId"))) return false;
      return run.workingDir == null || run.workingDir.equals(hs.getAttribute("workingDirectory"));
    }
    catch (IllegalStateException e) { return false; }
  }

  // Run events go to every device of the owner that currently shows the run's chat
  private static boolean deliver(AgentRun run, Map<String, Object> evt, boolean allDevices)
  {
    String text = json.toJSON(evt);
    boolean delivered = false;
    for (Session s : AetherWebSocket.socketsOfUser(run.issSubId))
    {
      if (!allDevices && !views(AetherWebSocket.httpSessionOf(s), run)) continue;
      if (AetherWebSocket.sendRaw(s, text)) delivered = true;
    }
    return delivered;
  }

  public static void broadcastRunUpdate(AgentRun run)
  {
    Map<String, Object> summary = run.summary();
    for (Session s : AetherWebSocket.socketsOfUser(run.issSubId))
    {
      Map<String, Object> msg = new HashMap<>();
      msg.put("type", "run_update");
      msg.put("run", summary);
      msg.put("viewing", views(AetherWebSocket.httpSessionOf(s), run));
      AetherWebSocket.sendRaw(s, json.toJSON(msg));
    }
  }

  // A socket (re)connects or its browser switched chat: send the run list, then bring the viewed run
  // up to date — replay from the device's cursor, or a history snapshot plus the events after it
  @SuppressWarnings("unchecked")
  public static void attach(Session socket, Map<String, Object> cursors)
  {
    HttpSession hs = AetherWebSocket.httpSessionOf(socket);
    if (hs == null) return;
    String issSubId;
    try { issSubId = (String) hs.getAttribute(Portal.SessionAccountID); } catch (IllegalStateException e) { return; }
    if (issSubId == null) return;

    Map<String, Object> list = new HashMap<>();
    list.put("type", "run_list");
    list.put("runs", listFor(issSubId));
    AetherWebSocket.sendRaw(socket, json.toJSON(list));

    AgentRun viewed = null;
    for (AgentRun run : runs.values())
    {
      if (!issSubId.equals(run.issSubId) || !views(hs, run)) continue;
      Object cursor = cursors != null ? cursors.get(run.runId) : null;
      if (cursor instanceof Number) replay(socket, run, ((Number) cursor).longValue());
      else if (run.isActive()) snapshot(socket, run);
      if (run.isActive() || AgentRun.PARKED.equals(run.state)) viewed = run;
    }
    Map<String, Object> view = new HashMap<>();
    view.put("type", "view_state");
    view.put("run", viewed != null ? viewed.summary() : null);
    AetherWebSocket.sendRaw(socket, json.toJSON(view));
  }

  public static void viewChanged(HttpSession http)
  {
    if (http == null) return;
    for (Session s : AetherWebSocket.socketsOfHttpSession(http.getId())) attach(s, null);
  }

  @SuppressWarnings("unchecked")
  private static void snapshot(Session socket, AgentRun run)
  {
    RunSession rs = run.session;
    if (rs == null) return;
    long from = run.historySeq;
    if (run.includeHistory)
    {
      Map<String, Object> h = new HashMap<>();
      h.put("type", "chat_history_update");
      h.put("history", rs.getAttribute("chatHistory"));
      AetherWebSocket.sendRaw(socket, json.toJSON(h));
    }
    else from = 0;
    Object todos = rs.getAttribute("todoList");
    Map<String, Object> t = new HashMap<>();
    t.put("type", "todo_update");
    t.put("todos", todos != null ? todos : new ArrayList<>());
    AetherWebSocket.sendRaw(socket, json.toJSON(t));
    Map<String, Object> a = new HashMap<>();
    a.put("type", "run_attach");
    a.put("runId", run.runId);
    a.put("fromSeq", from);
    AetherWebSocket.sendRaw(socket, json.toJSON(a));
    replay(socket, run, from);
  }

  private static void replay(Session socket, AgentRun run, long afterSeq)
  {
    if (run.log == null) return;
    for (Map<String, Object> evt : run.log.read(afterSeq))
    {
      String type = String.valueOf(evt.get("type"));
      if ("run_created".equals(type) || "run_state".equals(type)) continue;
      Map<String, Object> e = new HashMap<>(evt);
      e.put("replay", true);
      AetherWebSocket.sendRaw(socket, json.toJSON(e));
    }
  }

  // Timeline of any run the user owns (also finished ones, read back from the encrypted log)
  public static List<Map<String, Object>> events(String runId, String issSubId, long afterSeq)
  {
    AgentRun run = getOwned(runId, issSubId);
    if (run != null && run.log != null) return run.log.read(afterSeq);
    try
    {
      List<Map<String, Object>> out = new ArrayList<>();
      for (Map<String, Object> e : RunEventLog.readAll(issSubId, runId)) if (((Number) e.get("seq")).longValue() > afterSeq) out.add(e);
      return out;
    }
    catch (Exception e) { return new ArrayList<>(); }
  }

  public static List<Map<String, Object>> listFor(String issSubId)
  {
    Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
    for (Map<String, Object> e : loadIndex(issSubId)) byId.put(String.valueOf(e.get("runId")), e);
    for (AgentRun r : runs.values()) if (issSubId.equals(r.issSubId)) byId.put(r.runId, r.summary());
    List<Map<String, Object>> list = new ArrayList<>(byId.values());
    list.sort((a, b) -> Long.compare(asLong(b.get("updated")), asLong(a.get("updated"))));
    return list.size() > INDEX_SIZE ? new ArrayList<>(list.subList(0, INDEX_SIZE)) : list;
  }

  private static long asLong(Object o)
  {
    return o instanceof Number ? ((Number) o).longValue() : 0;
  }

  // ===== persistence: server registry (non-terminal runs) and per-user index (recent runs) =====

  private static synchronized void persistRegistry()
  {
    if (!VibedCrypto.isReady()) return;
    List<Map<String, Object>> entries = new ArrayList<>();
    for (AgentRun r : runs.values()) if (!r.isTerminal()) entries.add(r.registryEntry());
    try
    {
      SecretKey key = VibedCrypto.serverKey("run-registry-v1");
      VibedCrypto.writeSealedFile(new File(VibedCrypto.dataDir(), REGISTRY_FILE), key, "aether-run-registry", json.toJSON(entries));
    }
    catch (Exception e) { System.out.println("WARN: run registry not saved: " + e.getMessage()); }
  }

  @SuppressWarnings("unchecked")
  private static void loadRegistry()
  {
    if (!VibedCrypto.isReady()) return;
    try
    {
      String text = VibedCrypto.readSealedFile(new File(VibedCrypto.dataDir(), REGISTRY_FILE), VibedCrypto.serverKey("run-registry-v1"), "aether-run-registry");
      if (text == null) return;
      Object parsed = json.parse(new JSON.ReaderSource(new java.io.StringReader(text)));
      Object[] arr = parsed instanceof Object[] ? (Object[]) parsed : new Object[0];
      for (Object o : arr)
      {
        Map<String, Object> m = (Map<String, Object>) o;
        String runId = (String) m.get("runId");
        String issSubId = (String) m.get("issSubId");
        if (runId == null || issSubId == null) continue;
        AgentRun run = new AgentRun(runId, issSubId, (String) m.get("email"), (String) m.get("workingDir"), asLong(m.get("created")));
        run.chatSessionId = (String) m.get("chatSessionId");
        run.agentId = (String) m.get("agentId");
        run.model = (String) m.get("model");
        run.thinkingMode = (String) m.get("thinkingMode");
        if (m.get("behavior") != null) run.behavior = (String) m.get("behavior");
        run.objective = m.get("objective") != null ? (String) m.get("objective") : "";
        run.reviewerModel = (String) m.get("reviewerModel");
        run.planGate = Boolean.TRUE.equals(m.get("planGate"));
        run.todoDone = (int) asLong(m.get("todoDone"));
        run.todoTotal = (int) asLong(m.get("todoTotal"));
        run.roundLimit = AgentWorker.MAX_ROUNDS;
        // Every thread died with the old JVM: whatever the run was doing, it now waits for a resume
        run.state = AgentRun.PARKED;
        run.stopReason = "interrupted";
        run.updated = asLong(m.get("updated"));
        runs.put(runId, run);
        System.out.println("RunManager: recovered interrupted run " + runId + " (" + run.objective + ")");
      }
    }
    catch (Exception e) { System.out.println("WARN: run registry unreadable: " + e.getMessage()); }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> loadIndex(String issSubId)
  {
    List<Map<String, Object>> cached = userIndexCache.get(issSubId);
    if (cached != null) return cached;
    List<Map<String, Object>> list = new CopyOnWriteArrayList<>();
    try
    {
      File f = new File(VibedCrypto.dataDir(), VibedCrypto.userFileBase(issSubId) + ".runs.vibed");
      String text = VibedCrypto.readSealedFile(f, VibedCrypto.subKey(VibedCrypto.userKey(issSubId), "runindex-v1"), issSubId);
      if (text != null)
      {
        Object parsed = json.parse(new JSON.ReaderSource(new java.io.StringReader(text)));
        if (parsed instanceof Object[]) for (Object o : (Object[]) parsed) list.add((Map<String, Object>) o);
      }
    }
    catch (Exception e) { System.out.println("WARN: run index unreadable: " + e.getMessage()); }
    userIndexCache.put(issSubId, list);
    return list;
  }

  private static void persistIndex(String issSubId)
  {
    List<Map<String, Object>> merged = listFor(issSubId);
    userIndexCache.put(issSubId, new CopyOnWriteArrayList<>(merged));
    try
    {
      File f = new File(VibedCrypto.dataDir(), VibedCrypto.userFileBase(issSubId) + ".runs.vibed");
      VibedCrypto.writeSealedFile(f, VibedCrypto.subKey(VibedCrypto.userKey(issSubId), "runindex-v1"), issSubId, json.toJSON(merged));
    }
    catch (Exception e) { System.out.println("WARN: run index not saved: " + e.getMessage()); }
  }

  // WORKING with no live thread and no event for a minute: the run died without its final event
  // (e.g. an Error escaped the loop). Waiting runs are fine without a thread; long tools keep theirs.
  private static boolean isStalled(AgentRun run)
  {
    if (!AgentRun.WORKING.equals(run.state) && !AgentRun.REVIEWING.equals(run.state)) return false;
    return !AgentWorker.hasLiveThread(run.runId) && System.currentTimeMillis() - run.updated > 60000L;
  }

  private static void housekeep()
  {
    long now = System.currentTimeMillis();
    for (AgentRun r : new ArrayList<>(runs.values())) if (isStalled(r)) setState(r, AgentRun.FAILED, "stalled: the run stopped without finishing");
    for (AgentRun r : new ArrayList<>(runs.values()))
    {
      if (!r.isTerminal() || now - r.updated < EVICT_AFTER_MS) continue;
      if (r.log != null) r.log.close();
      runs.remove(r.runId);
    }
    File[] logs = VibedCrypto.dataDir().listFiles((d, n) -> n.endsWith(".vibedlog"));
    if (logs != null) for (File f : logs)
    {
      String name = f.getName();
      String runId = name.substring(name.indexOf('.') + 1, name.length() - ".vibedlog".length());
      if (runs.containsKey(runId)) continue;
      if (now - f.lastModified() > LOG_RETENTION_MS) f.delete();
    }
  }
}
