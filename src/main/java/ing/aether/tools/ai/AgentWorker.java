package ing.aether.tools.ai;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;

import org.eclipse.jetty.util.ajax.JSON;

import ing.aether.Portal;
import ing.aether.SessionTracker;
import ing.aether.data.Agent;
import ing.aether.data.AgentCommand;
import ing.aether.data.FileProvider;
import ing.aether.data.GoogleDriveProvider;
import ing.aether.data.PathMap;
import ing.aether.socket.AetherWebSocket;
import ing.aether.tools.ai.run.AgentRun;
import ing.aether.tools.ai.run.RunManager;
import ing.aether.tools.ai.run.RunPolicy;
import ing.aether.tools.ai.run.StopGates;
import jakarta.servlet.http.HttpSession;

public class AgentWorker
{
  private static final JSON json = new JSON();
  private static final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(10))
      .build();

  private static final Map<String, Agent> agents = new ConcurrentHashMap<>();
  private static final List<Map<String, Object>> agentMaps = new CopyOnWriteArrayList<>();

  // Thinking tag constants (uppercase and lowercase for model inconsistency)
  private static final String THINKING_OPEN = "<think>";
  private static final String THINKING_OPEN_LOWER = "<antThinking>";
  private static final String THINKING_CLOSE = "</think>";
  private static final String THINKING_CLOSE_LOWER = "</antThinking>";

  // Per-path locks to serialize read-modify-write cycles on aether.ing.json
  private static final Map<String, java.util.concurrent.locks.ReentrantLock> configLocks = new ConcurrentHashMap<>();

  // Backoff for failing config writes (e.g. read-only project dir): retry at most once per minute per file
  private static final Map<String, Long> configWriteFailures = new ConcurrentHashMap<>();
  
  // Track active AI threads per session ID for interruption
  private static final Map<String, Thread> activeAiThreads = new ConcurrentHashMap<>();

  // Track the active response stream per session ID; interrupt alone cannot unblock a socket read
  private static final Map<String, InputStream> activeAiStreams = new ConcurrentHashMap<>();
  
  // Wait queues for tool permissions; answer is {Boolean granted, String reason}
  private static final Map<String, SynchronousQueue<Object[]>> permissionQueues = new ConcurrentHashMap<>();

  // Wait queues for askUser tool answers; answer is the raw answers array sent by the client
  private static final Map<String, SynchronousQueue<Object>> questionQueues = new ConcurrentHashMap<>();

  // Guard against runaway tool loops: total LLM rounds per user turn (main and sub-agent turns share the budget)
  private static final Map<String, Integer> activeAiRounds = new ConcurrentHashMap<>();
  public static final int MAX_ROUNDS = 40;

  // Reality audit for todo-capable agents: weak models claim work is done without executing anything.
  // The server keeps a per-turn ledger of tools that actually ran and checks completion claims against it.
  private static final Map<String, List<String>> turnToolLedger = new ConcurrentHashMap<>();
  private static final Map<String, Integer> actionsSinceTodoWrite = new ConcurrentHashMap<>();
  private static final Map<String, Integer> todoNudges = new ConcurrentHashMap<>();
  private static final Set<String> todoRejectedTurn = ConcurrentHashMap.newKeySet();
  private static final int MAX_TODO_NUDGES = 2;

  // Non-chatlog commands leave a short trail in the chat session so a result produced while the user
  // was away survives a reload. The entry is opened when the command starts and completed by whichever
  // path ends the turn; the model never sees these entries (streamOllama skips them)
  private static final Map<String, Map<String, Object>> pendingCommands = new ConcurrentHashMap<>();
  private static final int MAX_COMMAND_ENTRIES = 3;
  private static final int MAX_VISION_IMAGES = 6;

  // Flattened canvas attached to this turn's user message for vision-capable models (image agents)
  // Pictures shown to the chat model this turn: {label, data URL}, in the order of ImageWorker.inputImages
  private static final Map<String, List<String[]>> turnImages = new ConcurrentHashMap<>();
  // Per turn: {generated tokens, generation nanos, first-token ms, start nanos}, summed over tool rounds
  private static final Map<String, long[]> turnStats = new ConcurrentHashMap<>();

  public static boolean handlePermission(String httpSessionId, boolean granted)
  {
    return handlePermission(httpSessionId, granted, null, false);
  }

  // Any device of the owner may answer; the first answer wins and every device drops its card
  public static boolean handlePermission(String httpSessionId, boolean granted, String reason, boolean always)
  {
    SynchronousQueue<Object[]> queue = permissionQueues.get(httpSessionId);
    if (queue == null) return false;
    // Bounded offer: put() would block this WebSocket thread forever if the waiting AI thread is already gone
    try
    {
      if (!queue.offer(new Object[] { granted, reason, always }, 500, TimeUnit.MILLISECONDS))
      {
        System.out.println("AgentWorker: Permission answer dropped, no waiting tool call for session " + httpSessionId);
        return false;
      }
    } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    AetherWebSocket.sendToSession(httpSessionId, Map.of("type", "approval_resolved", "kind", "permission", "granted", granted));
    return true;
  }

  public static boolean handleQuestionAnswers(String httpSessionId, Object answers)
  {
    SynchronousQueue<Object> queue = questionQueues.get(httpSessionId);
    if (queue == null) return false;
    // Bounded offer: put() would block this WebSocket thread forever if the waiting AI thread is already gone
    try
    {
      if (!queue.offer(answers != null ? answers : new Object[0], 500, TimeUnit.MILLISECONDS))
      {
        System.out.println("AgentWorker: Question answers dropped, no waiting tool call for session " + httpSessionId);
        return false;
      }
    } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    AetherWebSocket.sendToSession(httpSessionId, Map.of("type", "approval_resolved", "kind", "question"));
    return true;
  }

  // Resolves "agentId" or "agentId:commandId" to its agent definition
  public static Agent findAgent(String agentRef)
  {
    if (agentRef == null || agentRef.isEmpty()) return agents.get("general");
    String id = agentRef.contains(":") ? agentRef.substring(0, agentRef.indexOf(':')) : agentRef;
    return agents.get(id);
  }

  // "Interrupt now" steer: closing the response stream ends the model's current answer immediately;
  // a tool that is running is never touched (the steer is applied after the batch)
  public static void abortStream(String runId)
  {
    InputStream s = activeAiStreams.get(runId);
    if (s != null) try { s.close(); } catch (Exception ignore) {}
  }

  public static String repairJson(String text)
  {
    return extractAndFixJson(text);
  }

  public static boolean hasLiveThread(String id)
  {
    Thread t = activeAiThreads.get(id);
    return t != null && t.isAlive();
  }

  public static boolean isProcessing(String httpSessionId)
  {
    if (activeAiThreads.containsKey(httpSessionId)) return true;
    // A browser session counts as busy while a run works on the chat it shows
    HttpSession s = SessionTracker.getSessionById(httpSessionId);
    return s != null && RunManager.activeRunForSession(s) != null;
  }

  public static void interruptProcessing(String httpSessionId)
  {
    Thread t = activeAiThreads.remove(httpSessionId);
    if (t != null)
    {
      System.out.println("AgentWorker: Interrupting AI task for session " + httpSessionId);
      t.interrupt();
    }
    // An image run holds the GPU for minutes: tell the image server to stop, not just our thread
    ImageWorker.cancel(httpSessionId);
    MediaWorker.cancel(httpSessionId);
    // Close the response stream: a thread blocked in readLine() does not react to interrupt
    InputStream s = activeAiStreams.remove(httpSessionId);
    if (s != null) try { s.close(); } catch (Exception ignore) {}
    // Also release any waiting permissions with 'false'
    handlePermission(httpSessionId, false, null, false);
    // And any waiting askUser question with empty answers
    handleQuestionAnswers(httpSessionId, null);
  }

  private static java.util.concurrent.locks.ReentrantLock configLock(String configPath)
  {
    return configLocks.computeIfAbsent(configPath, k -> new java.util.concurrent.locks.ReentrantLock());
  }

  // Atomic read-modify-write: reads file once, applies fn, writes once
  @SuppressWarnings("unchecked")
  static void atomicConfigWrite(HttpSession session, String configPath, java.util.function.BiConsumer<HttpSession, Map<String, Object>> fn) throws Exception
  {
    Long lastFail = configWriteFailures.get(configPath);
    if (lastFail != null && System.currentTimeMillis() - lastFail < 60000L) throw new Exception("Skipping save of " + configPath + ": previous write failed less than a minute ago");
    configLock(configPath).lock();
    try {
      String configContent;
      try { configContent = AITools.readFile(session, configPath); }
      catch (Exception e)
      {
        // Only start from an empty config when the file truly doesn't exist; a transient read failure must not wipe it
        if (AITools.exists(session, configPath)) throw new Exception("Config read failed for " + configPath + ", skipping write to avoid data loss: " + e.getMessage(), e);
        configContent = "{}";
      }
      if (configContent == null || configContent.trim().isEmpty()) configContent = "{}";
      Map<String, Object> config = (Map<String, Object>) json.parse(new JSON.ReaderSource(new java.io.StringReader(configContent)));
      fn.accept(session, config);
      AITools.writeFile(session, configPath, json.toJSON(config));
      configWriteFailures.remove(configPath);
    } catch (Exception e) {
      configWriteFailures.put(configPath, System.currentTimeMillis());
      // Background saves (chat history, titles, config) fail invisibly otherwise — tell the user in the UI
      String name = configPath.contains("/") ? configPath.substring(configPath.lastIndexOf('/') + 1) : configPath;
      AetherWebSocket.notify(session.getId(), "⚠️ Cannot save " + name + ": " + e.getMessage());
      throw e;
    } finally {
      configLock(configPath).unlock();
    }
  }

  // Progress line under the chat. Unbuffered: after a reconnect only the next stage matters, not a replay of old ones
  public static void sendStatus(String httpSessionId, String stage, String text)
  {
    sendStatus(httpSessionId, stage, text, null);
  }

  public static void sendStatus(String httpSessionId, String stage, String text, Map<String, Object> extra)
  {
    Map<String, Object> m = new HashMap<>();
    if (extra != null) m.putAll(extra);
    m.put("type", "chat_status");
    m.put("stage", stage);
    m.put("text", text);
    AetherWebSocket.sendToSession(httpSessionId, m, false);
  }

  // Appends a finished command to the chat session and keeps only the newest MAX_COMMAND_ENTRIES of them
  @SuppressWarnings("unchecked")
  private static void recordCommand(HttpSession session, Map<String, Object> entry)
  {
    List<Map<String, Object>> history = (List<Map<String, Object>>) session.getAttribute("chatHistory");
    if (history == null)
    {
      history = new ArrayList<>();
      session.setAttribute("chatHistory", history);
    }
    entry.put("doneTime", System.currentTimeMillis());
    history.add(entry);
    // Collected first: a run's history is a copy-on-write list whose iterator cannot remove
    List<Map<String, Object>> commands = new ArrayList<>();
    for (Map<String, Object> m : history) if ("command".equals(m.get("role"))) commands.add(m);
    for (int i = 0; i < commands.size() - MAX_COMMAND_ENTRIES; i++) history.remove(commands.get(i));
    saveChatSession(session, history);
  }

  // Completes the pending command entry of this turn, if any, with its result or failure
  private static void finishCommand(HttpSession session, String httpSessionId, String content, String error)
  {
    Map<String, Object> entry = pendingCommands.remove(httpSessionId);
    if (entry == null) return;
    if (content != null) entry.put("content", content);
    if (error != null) entry.put("error", error);
    try { recordCommand(session, entry); } catch (Exception e) { System.out.println("WARN: command trail not saved: " + e.getMessage()); }
  }

  public static void loadAgents(String webInfPath)
  {
    File dir = new File(webInfPath, "agents");
    if (!dir.exists()) return;
    File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
    if (files == null) return;

    agents.clear();
    agentMaps.clear();

    for (File f : files)
    {
      try
      {
        String content = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        Map<String, Object> map = (Map<String, Object>) json.parse(new JSON.ReaderSource(new java.io.StringReader(content)));
        Agent agent = new Agent();
        agent.setId((String) map.get("id"));
        agent.setName((String) map.get("name"));
        agent.setPrompt((String) map.get("prompt"));
        agent.setImage(Boolean.TRUE.equals(map.get("image")));
        agent.setPersonality(!Boolean.FALSE.equals(map.get("personality")));
        agent.setVideo(Boolean.TRUE.equals(map.get("video")));
        agent.setModel3d(Boolean.TRUE.equals(map.get("model3d")));
        agent.setBehavior((String) map.get("behavior"));
        if (map.get("reviewer") instanceof Map) agent.setReviewerModel((String) ((Map<String, Object>) map.get("reviewer")).get("model"));
        if (map.get("budget") instanceof Map) agent.setBudget((Map<String, Object>) map.get("budget"));
        if (map.get("stopGates") instanceof Map) agent.setStopGates((Map<String, Object>) map.get("stopGates"));
        if (map.get("planGate") instanceof Boolean) agent.setPlanGate((Boolean) map.get("planGate"));
        Object policyObj = map.get("policy");
        Iterable<?> policyIter = policyObj instanceof Object[] ? Arrays.asList((Object[]) policyObj) : policyObj instanceof Iterable ? (Iterable<?>) policyObj : null;
        if (policyIter != null)
        {
          List<Map<String, Object>> rules = new ArrayList<>();
          for (Object o : policyIter) if (o instanceof Map) rules.add((Map<String, Object>) o);
          agent.setPolicy(rules);
        }
        
        Object toolsObj = map.get("tools");
        if (toolsObj instanceof Object[]) agent.setTools(Arrays.asList(Arrays.copyOf((Object[])toolsObj, ((Object[])toolsObj).length, String[].class)));
        
        Object cmdsArr = map.get("commands");
        if (cmdsArr != null)
        {
          List<AgentCommand> commands = new ArrayList<>();
          Iterable<?> iter = null;
          if (cmdsArr instanceof Object[]) iter = Arrays.asList((Object[])cmdsArr);
          else if (cmdsArr instanceof Iterable) iter = (Iterable<?>)cmdsArr;
          
          if (iter != null)
          {
            for (Object o : iter)
            {
              Map<String, Object> cMap = (Map<String, Object>) o;
              AgentCommand cmd = new AgentCommand();
              cmd.setId((String) cMap.get("id"));
              cmd.setLabel((String) cMap.get("label"));
              cmd.setPrompt((String) cMap.get("prompt"));
              cmd.setChatlog(Boolean.TRUE.equals(cMap.get("chatlog")));
              cmd.setResponse((String) cMap.get("response"));
              cmd.setTarget((String) cMap.get("target"));
              cmd.setMode((String) cMap.get("mode"));
              commands.add(cmd);
            }
            agent.setCommands(commands);
          }
        }
        agents.put(agent.getId(), agent);
        agentMaps.add(map);
      } catch (Exception e) { e.printStackTrace(); }
    }
  }

  public static List<Agent> getAgents()
  {
    return new ArrayList<>(agents.values());
  }
  
  public static List<Map<String, Object>> getAgentMaps()
  {
    return new ArrayList<>(agentMaps);
  }

  public static void process(HttpSession session, String agentId, String model, String userMessage)
  {
    process(session, agentId, model, "off", null, userMessage, null);
  }

  public static void process(HttpSession session, String agentId, String model, String thinkingMode, List<String> contextFiles, String userMessage)
  {
    process(session, agentId, model, thinkingMode, contextFiles, userMessage, null);
  }

  public static void process(HttpSession session, String agentId, String model, String thinkingMode, List<String> contextFiles, String userMessage, String selectedText)
  {
    process(session, agentId, model, thinkingMode, contextFiles, userMessage, selectedText, null);
  }

  public static void process(HttpSession session, String agentId, String model, String thinkingMode, List<String> contextFiles, String userMessage, String selectedText, String terminalOutput)
  {
    if (agentId == null || agentId.isEmpty()) agentId = "general";
    String realAgentId = agentId;
    String commandId = null;
    if (agentId.contains(":"))
    {
      String[] parts = agentId.split(":", 2);
      realAgentId = parts[0];
      commandId = parts[1];
    }

    Agent agent = agents.get(realAgentId);
    if (agent == null) agent = agents.get("general");
    if (agent == null)
    {
      Map<String, Object> errorMsg = new HashMap<>();
      errorMsg.put("type", "chat_error");
      errorMsg.put("error", "No agent definition found for '" + realAgentId + "' (and no 'general' fallback).");
      AetherWebSocket.sendToSession(session.getId(), errorMsg);
      return;
    }

    AgentCommand cmd = null;
    if (commandId != null && agent.getCommands() != null)
    {
      for (AgentCommand c : agent.getCommands())
      {
        if (c.getId().equals(commandId)) { cmd = c; break; }
      }
    }

    if (cmd == null && agent.getCommands() != null)
    {
      for (AgentCommand c : agent.getCommands())
      {
        if ("default".equals(c.getId())) { cmd = c; break; }
      }
    }

    // Ticked files of this turn: image commands and image tools read them as input pictures
    session.setAttribute("turnContextFiles", contextFiles == null ? new ArrayList<String>() : new ArrayList<String>(contextFiles));
    turnStats.remove(session.getId());

    // Direct image commands never involve the chat model: the prompt goes straight to the image model
    if (cmd != null && "image".equals(cmd.getResponse()))
    {
      runImageCommand(session, realAgentId, cmd, userMessage);
      return;
    }
    // Direct video / 3D commands: one job on that kind's model, the chat model is not involved either
    if (cmd != null && (MediaWorker.VIDEO.equals(cmd.getResponse()) || MediaWorker.MODEL3D.equals(cmd.getResponse())))
    {
      runMediaCommand(session, realAgentId, cmd, userMessage);
      return;
    }

    // Prepare context
    sendStatus(session.getId(), "preparing", describePreparation(session, contextFiles));
    Map<String, String> contextMap = buildContextMap(session, contextFiles, selectedText);
    // Command templates carry [[CHARACTER]]; the narrator (nothing selected) expands to nothing
    Object selectedCharacter = session.getAttribute("turnCharacter");
    contextMap.put("CHARACTER", selectedCharacter instanceof String ? CharacterWorker.directive((String) selectedCharacter) : "");
    contextMap.put("USER_INPUT", (userMessage == null || userMessage.isEmpty()) ? "(User does not give input, you can decide by your own)" : userMessage);
    // Build rich System Prompt
    StringBuilder sbSystem = new StringBuilder();
    sbSystem.append(agent.getPrompt());

    // The tool description alone is often ignored by small models: reinforce askUser in the system prompt
    if (agent.getTools() != null && agent.getTools().contains("askUser")) sbSystem.append("\n\nYou can ask the user questions at any time by calling the askUser tool. Whenever the request is ambiguous, information is missing, or a decision could go several ways, do NOT guess — ask first. You may bundle SEVERAL questions into one askUser call, and each question should offer 2-4 multiple-choice options; the user can pick one or type their own free answer. Continue the task using the answers you receive.");

    // Nothing volatile belongs in the system prompt: it is the prefix of every request, so a single
    // changed byte here re-evaluates the entire file that follows. The project structure changes
    // whenever a file is added or renamed, so it moved down into the volatile tail of the context turn.
    // Last in the system prompt on purpose: these are the rules the model must weigh against a
    // whole file sitting in front of it, so they take the closest position to the conversation
    // Autonomous task runs: the stop-gate protocol and the plan gate setting of THIS run
    AgentRun run = RunManager.get(session.getId());
    if (run != null && run.isAutonomous())
    {
      sbSystem.append("\n\n# Task run protocol (server-enforced)\nYou run autonomously in the background; the user may be away and follows along on any device.");
      sbSystem.append("\n- Start by calling proposePlan with a Definition of Done (verifiable acceptance criteria) and the todo list. ").append(run.planGate ? "The user must APPROVE the plan before you execute: proposePlan waits for their decision." : "The plan is recorded without waiting for approval.");
      sbSystem.append("\n- The task is only complete when you call finish and ALL stop gates pass: every todo done, the project's verify command succeeds, and the reviewer model approves. A failed gate returns its reasons: fix them and call finish again.");
      sbSystem.append("\n- Ending your reply without calling finish does NOT end the task: you will be asked to continue.");
      sbSystem.append("\n- The user may send new messages while you work. They appear as user messages marked as sent during your work: acknowledge them in one sentence and adapt the plan and todo list.");
      if (agent.getTools() != null && agent.getTools().contains("delegate")) sbSystem.append("\n- Delegate isolated chunks to worker agents with delegate; keep small edits yourself.");
      StopGates.captureGitBase(session, run);
    }

    String projectMd = contextMap.get("AGENTS.MD");
    if (projectMd != null && !projectMd.isEmpty()) sbSystem.append("\n\nProject Overview (AGENTS.md) — authoritative project rules. Follow them in every response. When the user asks about the project's rules, conventions or writing style, answer from this document and quote it.\n").append(projectMd);

    String finalUserPrompt = userMessage;
    session.setAttribute("lastModel", model);
    String responseType = "chat";
    String responseTarget = null;
    boolean useHistory = true;

    // Real selection sent by the client (buildContextMap falls back to the last paragraph, which must not count here)
    boolean hasSelection = selectedText != null && !selectedText.trim().isEmpty();

    // Workspace context travels as its own message placed straight after the system prompt and
    // ahead of the conversation. Prepending it to the newest user turn instead buried the actual
    // question under the whole open file, so the model answered the file rather than the user.
    // Ordered for prefix-cache reuse: the open file comes first because a story grows at its end,
    // so every earlier token stays byte-identical between turns and can be served from cache.
    // Everything that changes per turn is appended after it, where it invalidates only itself.
    StringBuilder sbContext = new StringBuilder();

    if (cmd != null)
    {
      String rawPrompt = cmd.getPrompt();
      // The default command runs with or without a selection: surface a real one when its template lacks the placeholder
      if ("default".equals(cmd.getId()) && hasSelection && !rawPrompt.contains("[[SELECTED_TEXT]]")) rawPrompt += "\n\nUser selection text:\n[[SELECTED_TEXT]]";

      // A template carrying [[CONTENT]] renders the open file itself; never send it twice
      if (!rawPrompt.contains("[[CONTENT]]") && contextMap.containsKey("CONTENT"))
        sbContext.append("\n\n--- CURRENT FILE: ").append(contextMap.get("RELATIVE_PATH")).append(" ---\n").append(contextMap.get("CONTENT"));

      finalUserPrompt = expandPrompt(rawPrompt, contextMap);

      responseType = cmd.getResponse();
      responseTarget = cmd.getTarget();
      useHistory = cmd.isChatlog();
    } else {
      String rawPrompt = userMessage == null ? "" : userMessage;
      if (hasSelection && !rawPrompt.contains("[[SELECTED_TEXT]]")) rawPrompt += "\n\nUser selection text:\n[[SELECTED_TEXT]]";
      if (contextMap.containsKey("CONTENT"))
        sbContext.append("\n\n--- CURRENT FILE: ").append(contextMap.get("RELATIVE_PATH")).append(" ---\n").append(contextMap.get("CONTENT"));
      finalUserPrompt = expandPrompt(rawPrompt, contextMap);
    }
 // Hard execution constraints must occupy the absolute tail-end position of the prompt string
    if (cmd != null && "variants_json".equals(responseType))
    {
      finalUserPrompt += "\n\n**IMPORTANT**: You MUST return your response as a valid JSON object with exactly this structure:\n{\"variants\": [{\"text\": \"variant one here\"}, {\"text\": \"variant two here\"}, {\"text\": \"variant three here\"}]}\nDo NOT use markdown code blocks like ```json. Return ONLY the raw JSON, no markdown, no explanation.";
    }
    
    // --- volatile tail of the context turn: everything below changes between turns ---
    if (contextMap.containsKey("PROJECT_STRUCTURE")) sbContext.append("\n\nProject Structure:\n").append(contextMap.get("PROJECT_STRUCTURE"));

    if (contextMap.containsKey("CONTEXT_FILES")) sbContext.append("\n\nChecked files:\n").append(contextMap.get("CONTEXT_FILES"));

    // The image tools default to what the user set in the chat pane; the model sees it so it can reason about cost
    if (agent.isImage()) sbContext.append("\n\n").append(describeImageSettings(session));
    if (agent.isVideo()) sbContext.append("\n\n").append(describeMediaSettings(session, MediaWorker.VIDEO));
    if (agent.isModel3d()) sbContext.append("\n\n").append(describeMediaSettings(session, MediaWorker.MODEL3D));

    // Rewritten by the psychologist on almost every turn, so it sits last, behind the open file
    String personality = agent.isPersonality() ? contextMap.get("USER_PERSONALITY") : null;
    if (personality != null && !personality.isEmpty()) sbContext.append("\n\nUser Personality Profile (background only — adapt your tone to match it; never quote, summarise or discuss this profile):\n").append(personality);

    // Unlabelled, this turn reads like something the user typed and the model answers it instead
    // of the real question. Prepended only once the block actually has content.
    if (sbContext.length() > 0) sbContext.insert(0, "[Workspace context supplied by the system, not typed by the user. This is the material you work on: use it to carry out the task. Do not summarise, quote or comment on it unless asked.]\n\n");

    // Attached terminal output is turn-specific like the selection; history keeps the clean message
    if (terminalOutput != null && !terminalOutput.trim().isEmpty()) finalUserPrompt += "\n\nTerminal output from the project shell:\n```\n" + capTerminal(terminalOutput) + "\n```";

    finalUserPrompt += "\n\nCurrent Date and Time: " + contextMap.get("DATE_TIME");
    
    
    // Prepare chat history (Visible to user)
    List<Map<String, Object>> history = (List<Map<String, Object>>) session.getAttribute("chatHistory");
    if (history == null) history = new ArrayList<>();
    
    if (useHistory)
    {
      Map<String, Object> msg = new HashMap<>();
      msg.put("role", "user");
      msg.put("content", userMessage); // Store clean message
      msg.put("time", System.currentTimeMillis());
      history.add(msg);
      session.setAttribute("chatHistory", history);
      saveChatSession(session, history);
    }

    // Pass the EXPANDED prompt to Ollama, but history contains CLEAN messages

    // Opened now, completed by whichever path ends the turn; a stopped command is simply never completed
    pendingCommands.remove(session.getId());
    if (cmd != null && !useHistory)
    {
      Map<String, Object> entry = new HashMap<>();
      entry.put("role", "command");
      entry.put("time", System.currentTimeMillis());
      entry.put("label", cmd.getLabel());
      entry.put("commandId", cmd.getId());
      entry.put("agent", realAgentId);
      entry.put("input", userMessage == null ? "" : userMessage);
      entry.put("responseType", responseType);
      if (responseTarget != null) entry.put("target", responseTarget);
      entry.put("model", model);
      pendingCommands.put(session.getId(), entry);
    }

    // Image agents look at the canvas through a vision-capable chat model when the user allows it
    turnImages.remove(session.getId());
    if (agent.isImage() && !"false".equals(String.valueOf(imageParams(session).get("vision"))))
    {
      // Every ticked image plus the open canvas, numbered: "Image 2" means the same here, in the prompt and in editImage
      List<String[]> pics = new ArrayList<>();
      int n = 1;
      for (String p : ImageWorker.inputImages(session))
      {
        String dataUrl = n <= MAX_VISION_IMAGES ? ImageWorker.inputDataUrl(session, p) : null;
        if (dataUrl != null) pics.add(new String[] { "Image " + n + ": " + AITools.resolveRelativePath(session, p) + (p.equals(session.getAttribute("lastFilePath")) ? " (the open image, all visible layers flattened)" : ""), dataUrl });
        n++;
      }
      if (!pics.isEmpty()) turnImages.put(session.getId(), pics);
      String shown = pics.isEmpty() ? "No picture is attached for the chat model (nothing ticked or open, or none could be read)" : "Showing the chat model " + pics.size() + (pics.size() == 1 ? " picture" : " pictures");
      System.out.println("Vision: " + shown + " [inputs=" + ImageWorker.inputImages(session).size() + "]");
      sendStatus(session.getId(), "preparing", shown + "…");
    }
    else if (agent.isImage())
    {
      System.out.println("Vision: off (AI sees image = " + imageParams(session).get("vision") + ")");
      sendStatus(session.getId(), "preparing", "Pictures not attached: \"AI sees image\" is off");
    }

    // Persist AI config transparently (non-blocking, Google Drive API may be slow)
    final String persistAgentId = realAgentId;
    final String httpSessionId = session.getId();
    CompletableFuture.runAsync(() -> 
    {
      try 
      {
        HttpSession liveSession = SessionTracker.getSessionById(httpSessionId);
        if (liveSession != null) saveAiConfig(liveSession, model, thinkingMode, persistAgentId);
      } 
      catch (Exception e) 
      { 
        e.printStackTrace(); 
      }
    });

    // Fresh user turn: reset the shared round budget and remember the agent for subTask defaults
    activeAiRounds.put(httpSessionId, 0);
    session.setAttribute("currentAgentId", realAgentId);

    // Fresh reality-audit state for this turn
    turnToolLedger.remove(httpSessionId);
    actionsSinceTodoWrite.remove(httpSessionId);
    todoNudges.remove(httpSessionId);
    todoRejectedTurn.remove(httpSessionId);

    // The model only sees tools it can actually use here: runCommand needs an SSH working directory
    List<String> effectiveTools = agent.getTools() != null ? new ArrayList<>(agent.getTools()) : null;
    if (effectiveTools != null && effectiveTools.contains("runCommand") && !AITools.isSshWorkingDir(session)) effectiveTools.remove("runCommand");

    // Non-chatlog commands get an ephemeral turn list so their tool rounds neither read nor pollute the chat session
    streamOllama(session, model, thinkingMode, sbSystem.toString(), sbContext.toString(), finalUserPrompt, useHistory ? history : new ArrayList<>(), responseType, responseTarget, effectiveTools, useHistory);
    if (agent.isPersonality()) runPsychologist(session, userMessage);

  }

  // Server-side cap for attached terminal output: head + tail up to 16KB total
  private static String capTerminal(String s)
  {
    int max = 16384;
    if (s.length() <= max) return s;
    return s.substring(0, 2048) + "\n[... " + (s.length() - max) + " chars truncated ...]\n" + s.substring(s.length() - (max - 2048));
  }

  private static String describePreparation(HttpSession session, List<String> contextFiles)
  {
    List<String> parts = new ArrayList<>();
    if (session.getAttribute("workingDirectory") != null) parts.add("AGENTS.md");
    String openFile = (String) session.getAttribute("lastFilePath");
    if (openFile != null) parts.add(ImageWorker.nameOf(openFile));
    if (contextFiles != null && !contextFiles.isEmpty()) parts.add(contextFiles.size() + (contextFiles.size() == 1 ? " checked file" : " checked files"));
    return parts.isEmpty() ? "Preparing request…" : "Reading " + String.join(", ", parts) + "…";
  }

  // Image settings from the chat pane, sent with every chat message (see AetherWebSocket)
  @SuppressWarnings("unchecked")
  private static Map<String, Object> imageParams(HttpSession session)
  {
    Object p = session.getAttribute("imageParams");
    return p instanceof Map ? (Map<String, Object>) p : new HashMap<>();
  }

  private static String describeImageSettings(HttpSession session)
  {
    Map<String, Object> p = imageParams(session);
    Object model = session.getAttribute("imageModel");
    return "Image settings chosen by the user (defaults for generateImage/editImage): image model " + (model != null ? model : "none selected")
      + ", size " + p.getOrDefault("size", "auto") + ", steps " + p.getOrDefault("steps", 14)
      + ", transparent background " + ("true".equals(String.valueOf(p.get("transparent"))) ? "on" : "off")
      + ", edit source " + p.getOrDefault("source", "canvas") + ".";
  }

  // Session attribute names of the model and the pane settings of a media kind
  private static String mediaModelAttr(String kind)
  {
    return MediaWorker.VIDEO.equals(kind) ? "videoModel" : "model3dModel";
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mediaParams(HttpSession session, String kind)
  {
    Object p = session.getAttribute(MediaWorker.VIDEO.equals(kind) ? "videoParams" : "model3dParams");
    return p instanceof Map ? (Map<String, Object>) p : new HashMap<>();
  }

  // The selected video / 3D model, what the user set in the pane and which fields the model accepts,
  // so the chat model can fill generateVideo / generate3d correctly and reason about cost
  @SuppressWarnings("unchecked")
  private static String describeMediaSettings(HttpSession session, String kind)
  {
    boolean video = MediaWorker.VIDEO.equals(kind);
    String model = (String) session.getAttribute(mediaModelAttr(kind));
    StringBuilder sb = new StringBuilder();
    sb.append(video ? "Video settings (defaults for generateVideo): video model " : "3D settings (defaults for generate3d): 3D model ").append(model != null ? model : "none selected");
    Map<String, Object> info = LlmProviders.modelInfo(model);
    if (info != null && info.get("title") != null) sb.append(" (").append(info.get("title")).append(")");
    if (info != null && info.get("description") != null) sb.append(". ").append(info.get("description"));
    Map<String, Object> chosen = mediaParams(session, kind);
    if (!chosen.isEmpty()) sb.append("\nSet by the user: ").append(json.toJSON(chosen));
    Map<String, Object> accepted = MediaWorker.parametersOf(model);
    if (accepted != null)
    {
      sb.append("\nParameters this model accepts:");
      for (Map.Entry<String, Object> e : accepted.entrySet())
      {
        if (!(e.getValue() instanceof Map)) continue;
        Map<String, Object> spec = (Map<String, Object>) e.getValue();
        sb.append("\n- ").append(e.getKey()).append(" (").append(spec.get("type"));
        if (spec.get("values") instanceof Object[]) sb.append(": ").append(Arrays.toString((Object[]) spec.get("values")));
        if (spec.get("min") != null || spec.get("max") != null) sb.append(", ").append(spec.get("min")).append("–").append(spec.get("max"));
        if (spec.get("default") != null) sb.append(", default ").append(spec.get("default"));
        if (Boolean.TRUE.equals(spec.get("required"))) sb.append(", required");
        sb.append(")");
        if (spec.get("description") != null) sb.append(": ").append(spec.get("description"));
      }
    }
    return sb.toString();
  }

  // A direct media command (agent command with "response": "video" or "3d"), with the same working-state,
  // status, stop and command-trail behaviour as a chat turn
  private static void runMediaCommand(HttpSession session, String agentId, AgentCommand cmd, String userMessage)
  {
    final String httpSessionId = session.getId();
    final String kind = cmd.getResponse();
    final String mediaModel = (String) session.getAttribute(mediaModelAttr(kind));
    Map<String, Object> params = new HashMap<>(mediaParams(session, kind));
    Map<String, String> ctx = new HashMap<>();
    ctx.put("USER_INPUT", userMessage == null ? "" : ImageWorker.resolveMentions(session, userMessage));
    String expanded = expandPrompt(cmd.getPrompt(), ctx).trim();
    if (MediaWorker.VIDEO.equals(kind)) params.put("prompt", expanded);
    else if (!expanded.isEmpty()) params.put("name", expanded);

    Map<String, Object> entry = new HashMap<>();
    entry.put("role", "command");
    entry.put("time", System.currentTimeMillis());
    entry.put("label", cmd.getLabel());
    entry.put("commandId", cmd.getId());
    entry.put("agent", agentId);
    entry.put("input", userMessage == null ? "" : userMessage);
    entry.put("responseType", kind);
    entry.put("model", mediaModel);
    pendingCommands.put(httpSessionId, entry);

    CompletableFuture.runAsync(() ->
    {
      activeAiThreads.put(httpSessionId, Thread.currentThread());
      long start = System.nanoTime();
      try
      {
        Map<String, Object> result = MediaWorker.run(session, httpSessionId, kind, mediaModel, params);
        finishCommand(session, httpSessionId, String.valueOf(result.get("path")), null);
        Map<String, Object> done = new HashMap<>();
        done.put("type", "chat_done");
        done.put("responseType", kind);
        done.put("total_duration", System.nanoTime() - start);
        if (AetherWebSocket.sendToSession(httpSessionId, done)) AetherWebSocket.clearBuffer(httpSessionId);
      }
      catch (Exception e)
      {
        boolean stopped = Thread.currentThread().isInterrupted() || e instanceof InterruptedException;
        if (stopped) pendingCommands.remove(httpSessionId);
        else
        {
          finishCommand(session, httpSessionId, null, e.getMessage());
          AetherWebSocket.sendToSession(httpSessionId, Map.of("type", "chat_error", "error", (MediaWorker.VIDEO.equals(kind) ? "Video: " : "3D: ") + e.getMessage()));
        }
      }
      finally
      {
        activeAiThreads.remove(httpSessionId, Thread.currentThread());
      }
    }, RunManager.AI_EXECUTOR);
  }

  // Tool entry point for generateVideo / generate3d: the model's arguments override the user's pane settings
  @SuppressWarnings("unchecked")
  private static String runMediaTool(HttpSession session, String httpSessionId, String name, Map<String, Object> args) throws Exception
  {
    String kind = "generateVideo".equals(name) ? MediaWorker.VIDEO : MediaWorker.MODEL3D;
    Map<String, Object> params = new HashMap<>(mediaParams(session, kind));
    // The pane's first/last frame choice belongs to the buttons; the chat model names its pictures itself
    params.remove("image");
    params.remove("end_image");
    if (args.get("options") instanceof Map) params.putAll((Map<String, Object>) args.get("options"));
    for (Map.Entry<String, Object> e : args.entrySet()) if (e.getValue() != null && !"options".equals(e.getKey()) && !"explain".equals(e.getKey())) params.put(e.getKey(), e.getValue());
    Map<String, Object> r = MediaWorker.run(session, httpSessionId, kind, (String) session.getAttribute(mediaModelAttr(kind)), params);
    String rel = AITools.resolveRelativePath(session, String.valueOf(r.get("path")));
    StringBuilder sb = new StringBuilder();
    sb.append(MediaWorker.VIDEO.equals(kind) ? "Video generated in " : "3D model built in ").append(ImageWorker.formatDuration(((Number) r.get("seconds")).longValue()));
    sb.append(" and saved as ").append(rel).append(" (").append(((Number) r.get("bytes")).longValue() / (1024 * 1024)).append(" MB)");
    if (r.get("seed") != null) sb.append(", seed ").append(r.get("seed"));
    if (r.get("parameters") != null) sb.append(", parameters used ").append(json.toJSON(r.get("parameters")));
    sb.append(". It is open in the viewer for the user; you cannot see it yourself.");
    return sb.toString();
  }

  // A direct image command (agent command with "response": "image"): one run on the image model,
  // with the same working-state, status, stop and command-trail behaviour as a chat turn
  private static void runImageCommand(HttpSession session, String agentId, AgentCommand cmd, String userMessage)
  {
    final String httpSessionId = session.getId();
    String imageModel = (String) session.getAttribute("imageModel");
    Map<String, Object> params = new HashMap<>(imageParams(session));
    params.put("mode", "edit".equals(cmd.getMode()) ? "edit" : "generate");
    Map<String, String> ctx = new HashMap<>();
    ctx.put("USER_INPUT", userMessage == null ? "" : ImageWorker.resolveMentions(session, userMessage));
    params.put("prompt", expandPrompt(cmd.getPrompt(), ctx));

    Map<String, Object> entry = new HashMap<>();
    entry.put("role", "command");
    entry.put("time", System.currentTimeMillis());
    entry.put("label", cmd.getLabel());
    entry.put("commandId", cmd.getId());
    entry.put("agent", agentId);
    entry.put("input", userMessage == null ? "" : userMessage);
    entry.put("responseType", "image");
    entry.put("model", imageModel);
    pendingCommands.put(httpSessionId, entry);

    CompletableFuture.runAsync(() ->
    {
      activeAiThreads.put(httpSessionId, Thread.currentThread());
      long start = System.nanoTime();
      try
      {
        Map<String, Object> result = ImageWorker.run(session, httpSessionId, imageModel, params);
        finishCommand(session, httpSessionId, String.valueOf(result.get("path")), null);
        Map<String, Object> done = new HashMap<>();
        done.put("type", "chat_done");
        done.put("responseType", "image");
        done.put("total_duration", System.nanoTime() - start);
        if (AetherWebSocket.sendToSession(httpSessionId, done)) AetherWebSocket.clearBuffer(httpSessionId);
      }
      catch (Exception e)
      {
        boolean stopped = Thread.currentThread().isInterrupted() || e instanceof InterruptedException;
        if (stopped) pendingCommands.remove(httpSessionId);
        else
        {
          finishCommand(session, httpSessionId, null, e.getMessage());
          AetherWebSocket.sendToSession(httpSessionId, Map.of("type", "chat_error", "error", "Image: " + e.getMessage()));
        }
      }
      finally
      {
        activeAiThreads.remove(httpSessionId, Thread.currentThread());
      }
    }, RunManager.AI_EXECUTOR);
  }

  // Tool entry point for generateImage / editImage: the model's arguments override the user's pane settings
  private static String runImageTool(HttpSession session, String httpSessionId, String name, Map<String, Object> args) throws Exception
  {
    Map<String, Object> params = new HashMap<>(imageParams(session));
    for (String k : new String[] { "prompt", "size", "steps", "transparent", "source", "layers", "name" }) if (args.get(k) != null) params.put(k, args.get(k));
    params.put("mode", "editImage".equals(name) ? "edit" : "generate");
    Map<String, Object> r = ImageWorker.run(session, httpSessionId, (String) session.getAttribute("imageModel"), params);
    String rel = AITools.resolveRelativePath(session, String.valueOf(r.get("path")));
    StringBuilder sb = new StringBuilder();
    sb.append("Image ").append("editImage".equals(name) ? "edited" : "generated").append(" in ").append(ImageWorker.formatDuration(((Number) r.get("seconds")).longValue()));
    sb.append(" and saved as ").append(rel).append(" (").append(r.get("width")).append("x").append(r.get("height")).append(").");
    if (r.get("layer") != null) sb.append(" It was added as the new top layer \"").append(((Map<?, ?>) r.get("layer")).get("name")).append("\" of the open image.");
    else sb.append(" It is shown to the user in the chat.");
    return sb.toString();
  }

  public static void createNewSession(HttpSession session)
  {
    String workingDir = (String) session.getAttribute("workingDirectory");
    if (workingDir == null) return;

    String sessionId = UUID.randomUUID().toString();
    session.setAttribute("currentChatSessionId", sessionId);
    session.setAttribute("chatHistory", new ArrayList<Map<String, Object>>());
    session.removeAttribute("todoList");
    broadcastTodos(session);

    try
    {
      String configPath = workingDir + "/aether.ing.json";
      atomicConfigWrite(session, configPath, (s, config) -> {
        Object[] sessionsArr = (Object[]) config.get("sessions");
        List<Map<String, Object>> sessions = new ArrayList<>();
        if (sessionsArr != null) for (Object sess : sessionsArr) sessions.add((Map<String, Object>) sess);

        Map<String, Object> currentSession = new HashMap<>();
        currentSession.put("id", sessionId);
        currentSession.put("created", System.currentTimeMillis());
        currentSession.put("updated", System.currentTimeMillis());
        currentSession.put("history", new ArrayList<>());
        currentSession.put("title", "New Chat Session");

        sessions.add(0, currentSession);
        config.put("sessions", sessions.toArray());
      });
    } catch (Exception e) { e.printStackTrace(); }

    broadcastSessionList(session);

    // Load persisted AI config
    loadAiConfig(session);
  }

  public static void saveAiConfig(HttpSession session, String model, String thinkingMode, String agentId)
  {
    String workingDir = (String) session.getAttribute("workingDirectory");
    if (workingDir == null) return;
    try
    {
      String configPath = workingDir + "/aether.ing.json";
      atomicConfigWrite(session, configPath, (s, config) -> {
        if (model != null) config.put("model", model);
        if (thinkingMode != null) config.put("thinking_mode", thinkingMode);
        if (agentId != null) config.put("agent_id", agentId);
        Object imageModel = session.getAttribute("imageModel");
        if (imageModel != null) config.put("image_model", imageModel);
        if (session.getAttribute("videoModel") != null) config.put("video_model", session.getAttribute("videoModel"));
        if (session.getAttribute("model3dModel") != null) config.put("model3d_model", session.getAttribute("model3dModel"));
      });
    } catch (Exception e) { e.printStackTrace(); }
  }

  public static Map<String, String> getAiConfig(HttpSession session)
  {
    String workingDir = (String) session.getAttribute("workingDirectory");
    if (workingDir == null) return null;
    try
    {
      String configPath = workingDir + "/aether.ing.json";
      String content = AITools.readFile(session, configPath);
      Map<String, Object> config = (Map<String, Object>) json.parse(new JSON.ReaderSource(new java.io.StringReader(content)));
      Map<String, String> result = new HashMap<>();
      Object model = config.get("model");
      Object thinkingMode = config.get("thinking_mode");
      Object agentId = config.get("agent_id");
      Object imageModel = config.get("image_model");
      if (imageModel != null) result.put("image_model", imageModel.toString());
      if (config.get("video_model") != null) result.put("video_model", config.get("video_model").toString());
      if (config.get("model3d_model") != null) result.put("model3d_model", config.get("model3d_model").toString());
      // Configs saved before multi-provider support hold bare model ids; qualify them so
      // they keep matching the dropdown options, which are always "Provider::model"
      if (model != null) result.put("model", LlmProviders.qualify(model.toString()));
      if (thinkingMode != null) result.put("thinking_mode", thinkingMode.toString());
      if (agentId != null) result.put("agent_id", agentId.toString());
      return result.isEmpty() ? null : result;
    } catch (Exception e) { return null; }
  }

  public static void loadAiConfig(HttpSession session)
  {
    Map<String, String> config = getAiConfig(session);
    if (config != null)
    {
      session.setAttribute("aiModel", config.get("model"));
      session.setAttribute("aiThinkingMode", config.get("thinking_mode"));
      session.setAttribute("aiAgentId", config.get("agent_id"));
    }
    else
    {
      session.removeAttribute("aiModel");
      session.removeAttribute("aiThinkingMode");
      session.removeAttribute("aiAgentId");
    }
  }

  public static void broadcastAiConfig(HttpSession session)
  {
    Map<String, String> config = getAiConfig(session);
    Map<String, Object> msg = new HashMap<>();
    msg.put("type", "ai_config_update");
    msg.put("config", config != null ? config : new HashMap<String, String>());
    AetherWebSocket.sendToSession(session.getId(), msg);
  }

  // Client-requested sync: re-resolve history from session or disk and push it; retries reads that failed during page load
  @SuppressWarnings("unchecked")
  public static void syncChatHistory(HttpSession session)
  {
    List<Map<String, Object>> history = (List<Map<String, Object>>) session.getAttribute("chatHistory");
    if ((history == null || history.isEmpty()) && session.getAttribute("workingDirectory") != null)
    {
      history = loadLatestSession(session);
      if (history != null) session.setAttribute("chatHistory", history);
    }
    if (history != null && !history.isEmpty()) broadcastChatHistory(session, history);
    // Todos and the session list are full-state snapshots like the history: always re-sync them
    broadcastTodos(session);
    if (session.getAttribute("workingDirectory") != null) broadcastSessionList(session);
  }

  public static void broadcastChatHistory(HttpSession session, List<Map<String, Object>> history)
  {
    Map<String, Object> msg = new HashMap<>();
    msg.put("type", "chat_history_update");
    msg.put("history", history != null ? history : new ArrayList<>());
    AetherWebSocket.sendToSession(session.getId(), msg);
  }

  public static List<Map<String, Object>> loadLatestSession(HttpSession session)
  {
    String workingDir = (String) session.getAttribute("workingDirectory");
    if (workingDir == null) return null;

    String configPath = workingDir + "/aether.ing.json";
    configLock(configPath).lock();
    try
    {
      String configContent = AITools.readFile(session, configPath);
      Map<String, Object> config = (Map<String, Object>) json.parse(new JSON.ReaderSource(new java.io.StringReader(configContent)));
      Object[] sessionsArr = (Object[]) config.get("sessions");
      if (sessionsArr != null && sessionsArr.length > 0)
      {
        // Pick the most-recently-updated entry, not index 0: switchSession lets a user resume an
        // older session out of creation order, so array position alone no longer implies recency
        Map<String, Object> latest = null;
        long latestUpdated = -1;
        for (Object o : sessionsArr)
        {
          Map<String, Object> sess = (Map<String, Object>) o;
          long updated = sess.get("updated") instanceof Number ? ((Number) sess.get("updated")).longValue() : 0;
          if (latest == null || updated > latestUpdated) { latest = sess; latestUpdated = updated; }
        }
        return applySessionToAttributes(session, latest);
      }
    } catch (Exception e) {
      System.out.println("WARN: loadLatestSession failed for " + configPath + ": " + e);
    } finally {
      configLock(configPath).unlock();
    }
    return null;
  }

  // Sets currentChatSessionId/todoList session attrs from a session map and returns its history list
  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> applySessionToAttributes(HttpSession session, Map<String, Object> sessionMap)
  {
    session.setAttribute("currentChatSessionId", sessionMap.get("id"));

    Object[] todosArr = (Object[]) sessionMap.get("todos");
    if (todosArr != null)
    {
      List<Map<String, Object>> todos = new ArrayList<>();
      for (Object t : todosArr) todos.add((Map<String, Object>) t);
      session.setAttribute("todoList", todos);
    }
    else session.removeAttribute("todoList");

    // A chat that a run is working on right now is shown from the run's live list, not the saved copy
    List<Map<String, Object>> live = RunManager.liveHistory((String) session.getAttribute(Portal.SessionAccountID), (String) session.getAttribute("workingDirectory"), (String) sessionMap.get("id"));
    if (live != null)
    {
      HttpSession runSession = RunManager.getRunSession(RunManager.activeRunFor((String) session.getAttribute(Portal.SessionAccountID), (String) session.getAttribute("workingDirectory"), (String) sessionMap.get("id")).runId);
      Object liveTodos = runSession != null ? runSession.getAttribute("todoList") : null;
      if (liveTodos != null) session.setAttribute("todoList", liveTodos);
      session.setAttribute("chatHistory", live);
      return live;
    }
    Object[] historyArr = (Object[]) sessionMap.get("history");
    List<Map<String, Object>> history = new ArrayList<>();
    if (historyArr != null) for (Object h : historyArr) history.add((Map<String, Object>) h);
    session.setAttribute("chatHistory", history);
    return history;
  }

  // Summaries only (id/title/updated/message count) for the session-switcher dropdown — no history payload
  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> listSessions(HttpSession session)
  {
    String workingDir = (String) session.getAttribute("workingDirectory");
    if (workingDir == null) return new ArrayList<>();

    String configPath = workingDir + "/aether.ing.json";
    configLock(configPath).lock();
    try
    {
      String configContent = AITools.readFile(session, configPath);
      Map<String, Object> config = (Map<String, Object>) json.parse(new JSON.ReaderSource(new java.io.StringReader(configContent)));
      Object[] sessionsArr = (Object[]) config.get("sessions");
      List<Map<String, Object>> summaries = new ArrayList<>();
      if (sessionsArr != null) for (Object o : sessionsArr)
      {
        Map<String, Object> sess = (Map<String, Object>) o;
        Map<String, Object> summary = new HashMap<>();
        summary.put("id", sess.get("id"));
        Object title = sess.get("title");
        summary.put("title", (title instanceof String && !((String) title).isEmpty()) ? title : "Untitled Session");
        summary.put("updated", sess.get("updated"));
        Object[] historyArr = (Object[]) sess.get("history");
        summary.put("count", historyArr != null ? historyArr.length : 0);
        summaries.add(summary);
      }
      summaries.sort((a, b) -> {
        long ua = a.get("updated") instanceof Number ? ((Number) a.get("updated")).longValue() : 0;
        long ub = b.get("updated") instanceof Number ? ((Number) b.get("updated")).longValue() : 0;
        return Long.compare(ub, ua);
      });
      return summaries;
    } catch (Exception e) {
      System.out.println("WARN: listSessions failed: " + e);
      return new ArrayList<>();
    } finally {
      configLock(configPath).unlock();
    }
  }

  public static void broadcastSessionList(HttpSession session)
  {
    Map<String, Object> msg = new HashMap<>();
    msg.put("type", "session_list_update");
    msg.put("sessions", listSessions(session));
    msg.put("currentId", session.getAttribute("currentChatSessionId"));
    AetherWebSocket.sendToSession(session.getId(), msg);
  }

  // Switch the active chat session; refused while a turn is in flight, since process()'s in-flight
  // history reference is keyed by currentChatSessionId re-read fresh on every saveChatSession call
  @SuppressWarnings("unchecked")
  public static void switchSession(HttpSession session, String sessionId)
  {
    // Allowed while a run works: the run owns a detached session and keeps going on its own chat
    String workingDir = (String) session.getAttribute("workingDirectory");
    if (workingDir == null || sessionId == null) return;

    String configPath = workingDir + "/aether.ing.json";
    configLock(configPath).lock();
    try
    {
      String configContent = AITools.readFile(session, configPath);
      Map<String, Object> config = (Map<String, Object>) json.parse(new JSON.ReaderSource(new java.io.StringReader(configContent)));
      Object[] sessionsArr = (Object[]) config.get("sessions");
      if (sessionsArr == null) return;
      for (Object o : sessionsArr)
      {
        Map<String, Object> sess = (Map<String, Object>) o;
        if (sessionId.equals(sess.get("id")))
        {
          List<Map<String, Object>> history = applySessionToAttributes(session, sess);
          broadcastChatHistory(session, history);
          broadcastTodos(session);
          broadcastSessionList(session);
          return;
        }
      }
    } catch (Exception e) {
      System.out.println("WARN: switchSession failed: " + e);
    } finally {
      configLock(configPath).unlock();
    }
  }

  // Delete one stored session; refused only if it's the currently active one and a turn is in flight
  @SuppressWarnings("unchecked")
  public static void deleteSession(HttpSession session, String sessionId)
  {
    String httpSessionId = session.getId();
    String workingDir = (String) session.getAttribute("workingDirectory");
    if (workingDir == null || sessionId == null) return;

    String currentId = (String) session.getAttribute("currentChatSessionId");
    boolean deletingCurrent = sessionId.equals(currentId);
    if (RunManager.activeRunFor((String) session.getAttribute(Portal.SessionAccountID), workingDir, sessionId) != null)
    {
      AetherWebSocket.notify(httpSessionId, "Cannot delete a chat while the AI is working in it. Stop the run first.");
      return;
    }

    try
    {
      String configPath = workingDir + "/aether.ing.json";
      atomicConfigWrite(session, configPath, (s, config) -> {
        Object[] sessionsArr = (Object[]) config.get("sessions");
        List<Map<String, Object>> sessions = new ArrayList<>();
        if (sessionsArr != null) for (Object o : sessionsArr) sessions.add((Map<String, Object>) o);
        sessions.removeIf(sess -> sessionId.equals(sess.get("id")));
        config.put("sessions", sessions.toArray());
      });
    } catch (Exception e) { e.printStackTrace(); return; }

    if (deletingCurrent)
    {
      // Switch to the most-recently-updated remaining session, or reset fresh if none remain
      List<Map<String, Object>> history = loadLatestSession(session);
      if (history == null)
      {
        createNewSession(session);
        // createNewSession doesn't push history (clearChat clears the DOM client-side); this path must
        broadcastChatHistory(session, new ArrayList<>());
      }
      else { broadcastChatHistory(session, history); broadcastTodos(session); }
    }
    broadcastSessionList(session);
  }

  // Wipe every stored session and start with one fresh empty one; refused while a turn is in flight
  public static void clearAllSessions(HttpSession session)
  {
    String httpSessionId = session.getId();
    String workingDir = (String) session.getAttribute("workingDirectory");
    if (workingDir == null) return;
    if (RunManager.hasActiveRunInWd((String) session.getAttribute(Portal.SessionAccountID), workingDir))
    {
      AetherWebSocket.notify(httpSessionId, "Cannot clear chats while the AI is working in this project. Stop the running tasks first.");
      return;
    }

    String sessionId = UUID.randomUUID().toString();
    session.setAttribute("currentChatSessionId", sessionId);
    session.setAttribute("chatHistory", new ArrayList<Map<String, Object>>());
    session.removeAttribute("todoList");

    try
    {
      String configPath = workingDir + "/aether.ing.json";
      atomicConfigWrite(session, configPath, (s, config) -> {
        Map<String, Object> fresh = new HashMap<>();
        fresh.put("id", sessionId);
        fresh.put("created", System.currentTimeMillis());
        fresh.put("updated", System.currentTimeMillis());
        fresh.put("history", new ArrayList<>());
        fresh.put("title", "New Chat Session");
        List<Map<String, Object>> sessions = new ArrayList<>();
        sessions.add(fresh);
        config.put("sessions", sessions.toArray());
      });
    } catch (Exception e) { e.printStackTrace(); }

    broadcastChatHistory(session, new ArrayList<>());
    broadcastTodos(session);
    broadcastSessionList(session);
  }

  private static void saveChatSession(HttpSession session, List<Map<String, Object>> history)
  {
    String workingDir = (String) session.getAttribute("workingDirectory");
    if (workingDir == null) return;

    String sessionId = (String) session.getAttribute("currentChatSessionId");
    if (sessionId == null)
    {
      sessionId = UUID.randomUUID().toString();
      session.setAttribute("currentChatSessionId", sessionId);
    }
    final String finalSessionId = sessionId;

    try
    {
      String configPath = workingDir + "/aether.ing.json";
      atomicConfigWrite(session, configPath, (s, config) -> {
        Object[] sessionsArr = (Object[]) config.get("sessions");
        List<Map<String, Object>> sessions = new ArrayList<>();
        if (sessionsArr != null) for (Object sess : sessionsArr) sessions.add((Map<String, Object>) sess);

        Map<String, Object> currentSession = null;
        for (Map<String, Object> sess : sessions)
        {
          if (finalSessionId.equals(sess.get("id"))) { currentSession = sess; break; }
        }

        if (currentSession == null)
        {
          currentSession = new HashMap<>();
          currentSession.put("id", finalSessionId);
          currentSession.put("created", System.currentTimeMillis());
          sessions.add(0, currentSession);
        }

        currentSession.put("updated", System.currentTimeMillis());
        currentSession.put("history", history);

        config.put("sessions", sessions.toArray());
      });
    } catch (Exception e) { e.printStackTrace(); }
  }

  // One non-streaming query on the admin-selected Internal Model, shared by the background
  // features (chat titles, personality analysis). Any failure is logged AND toasted to the
  // requesting user, because only an admin can fix a broken Internal Model selection.
  // Diagnostic switch: dumps the exact payload sent to the LLM. Set to false to silence.
  private static final boolean LOG_PROMPT = true;

  // The Jetty JSON writer emits one dense line; this reflows it for reading. Whitespace is only
  // touched outside string literals, so the output stays valid JSON (escapes are left intact).
  private static String prettyJson(String s)
  {
    StringBuilder out = new StringBuilder(s.length() + 512);
    int indent = 0;
    boolean inString = false;
    boolean escape = false;
    for (int i = 0; i < s.length(); i++)
    {
      char c = s.charAt(i);
      if (inString)
      {
        out.append(c);
        if (escape) escape = false;
        else if (c == '\\') escape = true;
        else if (c == '"') inString = false;
        continue;
      }
      if (c == '"')
      {
        inString = true;
        out.append(c);
      }
      else if (c == '{' || c == '[')
      {
        indent++;
        out.append(c).append('\n').append("  ".repeat(indent));
      }
      else if (c == '}' || c == ']')
      {
        indent--;
        out.append('\n').append("  ".repeat(Math.max(0, indent))).append(c);
      }
      else if (c == ',') out.append(c).append('\n').append("  ".repeat(indent));
      else if (c == ':') out.append(": ");
      else if (!Character.isWhitespace(c)) out.append(c);
    }
    return out.toString();
  }

  @SuppressWarnings("unchecked")
  static void internalQuery(String httpSessionId, String task, List<Map<String, String>> messages, java.util.function.Consumer<String> onResult)
  {
    LlmProviders.Target target = LlmProviders.resolve(LlmProviders.internalModel());
    if (target == null)
    {
      notifyInternalFailure(httpSessionId, task, "no LLM provider configured");
      return;
    }
    CompletableFuture.runAsync(() ->
    {
      try
      {
        Map<String, Object> request = new HashMap<>();
        request.put("model", target.model);
        request.put("stream", false);
        request.put("messages", messages);
        // Background tasks must never pay for reasoning tokens: a hybrid model (qwen3, deepseek-r1...)
        // left to think freely burns minutes rewriting a profile it could emit directly. Ollama maps
        // reasoning_effort onto its think switch on /v1, exactly as the main chat stream relies on.
        request.put("reasoning_effort", "none");
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(target.url + "/v1/chat/completions")).timeout(java.time.Duration.ofMinutes(5)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.toJSON(request)));
        if (!target.apiKey.isEmpty()) b.header("Authorization", "Bearer " + target.apiKey);
        HttpResponse<String> response = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
        {
          notifyInternalFailure(httpSessionId, task, "HTTP " + response.statusCode() + " from " + target.provider);
          return;
        }
        Map<String, Object> resp = (Map<String, Object>) json.parse(new JSON.ReaderSource(new java.io.StringReader(response.body())));
        String content = null;
        Object[] choices = resp.get("choices") instanceof Object[] ? (Object[]) resp.get("choices") : null;
        if (choices != null && choices.length > 0)
        {
          Map<String, Object> msg = (Map<String, Object>) ((Map<String, Object>) choices[0]).get("message");
          if (msg != null && msg.get("content") != null) content = msg.get("content").toString();
        }
        // Belt and braces for servers that ignore reasoning_effort and still emit thinking inline
        if (content != null) content = content.replaceAll("(?s)<think>.*?</think>", "").trim();
        if (content == null || content.isEmpty())
        {
          notifyInternalFailure(httpSessionId, task, "empty response from model '" + target.model + "'");
          return;
        }
        onResult.accept(content);
      } catch (Exception e)
      {
        notifyInternalFailure(httpSessionId, task, e.getMessage());
      }
    });
  }

  private static void notifyInternalFailure(String httpSessionId, String task, String detail)
  {
    System.out.println("WARN: internal AI task '" + task + "' failed: " + detail);
    AetherWebSocket.notify(httpSessionId, "Internal AI task failed (" + task + "). Ask the admin to re-select the Internal Model in Settings.");
  }

  private static void generateTitle(HttpSession session, List<Map<String, Object>> history)
  {
    String sessionId = (String) session.getAttribute("currentChatSessionId");
    if (sessionId == null || history.isEmpty()) return;

    StringBuilder chatSummary = new StringBuilder();
    for (Map<String, Object> m : history)
    {
      if ("user".equals(m.get("role")) || "assistant".equals(m.get("role")))
      {
        chatSummary.append(m.get("role")).append(": ").append(m.get("content")).append("\n");
      }
    }

    final String httpSessionId = session.getId();
    List<Map<String, String>> messages = new ArrayList<>();
    messages.add(Map.of("role", "system", "content", "Generate a short, concise title (max 5 words) for this conversation. Return ONLY the title string."));
    messages.add(Map.of("role", "user", "content", chatSummary.toString()));
    internalQuery(httpSessionId, "chat title", messages, title ->
    {
      HttpSession liveSession = SessionTracker.getSessionById(httpSessionId);
      if (liveSession != null) updateSessionTitle(liveSession, sessionId, title);
    });
  }

  private static void updateSessionTitle(HttpSession session, String sessionId, String title)
  {
    String workingDir = (String) session.getAttribute("workingDirectory");
    if (workingDir == null) return;

    try
    {
      String configPath = workingDir + "/aether.ing.json";
      atomicConfigWrite(session, configPath, (s, config) -> {
        Object[] sessionsArr = (Object[]) config.get("sessions");
        if (sessionsArr != null)
        {
          for (Object sess : sessionsArr)
          {
            Map<String, Object> sessMap = (Map<String, Object>) sess;
            if (sessionId.equals(sessMap.get("id"))) { sessMap.put("title", title); break; }
          }
          config.put("sessions", sessionsArr);
        }
      });
    } catch (Exception e) { e.printStackTrace(); }
  }

  private static Map<String, String> buildContextMap(HttpSession session, List<String> contextFiles)
  {
    return buildContextMap(session, contextFiles, null);
  }

  private static Map<String, String> buildContextMap(HttpSession session, List<String> contextFiles, String selectedText)
  {
    Map<String, String> context = new HashMap<>();
    context.put("DATE_TIME", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));

    List<Map<String, Object>> history = (List<Map<String, Object>>) session.getAttribute("chatHistory");    if (history == null) { history = new ArrayList<>(); session.setAttribute("chatHistory", history); }

    String workingDir = (String) session.getAttribute("workingDirectory");
    
    if (workingDir != null)
    {
      try
      {
        String configContent = AITools.readFile(session, workingDir + "/aether.ing.json");
        Map<String, Object> config = (Map<String, Object>) json.parse(new JSON.ReaderSource(new java.io.StringReader(configContent)));
        if (config.containsKey("personality")) context.put("USER_PERSONALITY", config.get("personality").toString());
      } catch (Exception e) {}
      
      try
      {
        String mdContent = AITools.readFile(session, workingDir + "/AGENTS.md");
        context.put("AGENTS.MD", mdContent);
      } catch (Exception e)
      {
        // Swallowing this silently drops the whole project overview from the system prompt
        System.out.println("WARN: AGENTS.md unreadable in " + workingDir + ", project overview omitted from the prompt: " + e.getMessage());
      }

      try
      {
        List<String> allFiles = AITools.listAllFiles(session, workingDir);
        // Sorted: the provider lists files in an order that shifts whenever one is saved, and a reordered list
        // changes the prompt just ahead of the question, which defeats the model server's prefix cache
        List<String> relative = new ArrayList<>();
        for (String f : allFiles) relative.add(AITools.resolveRelativePath(session, f));
        Collections.sort(relative);
        StringBuilder sb = new StringBuilder();
        for (String f : relative) sb.append("- ").append(f).append("\n");
        context.put("PROJECT_STRUCTURE", sb.toString());
      } catch (Exception e) {}
    }

    String activeFile = (String) session.getAttribute("lastFilePath");
    // An open image is described, never read: its bytes decoded as UTF-8 are noise that can run to megabytes
    if (activeFile != null && ImageWorker.isImagePath(activeFile))
    {
      String rel = AITools.resolveRelativePath(session, activeFile);
      context.put("CONTENT", ImageWorker.describe(session, activeFile, rel));
      context.put("RELATIVE_PATH", rel);
    }
    // Same for a video or a 3D model: tens of megabytes of binary must never be read into the prompt
    else if (activeFile != null && MediaWorker.isMediaPath(activeFile))
    {
      String rel = AITools.resolveRelativePath(session, activeFile);
      context.put("CONTENT", (MediaWorker.isVideoPath(activeFile) ? "A video" : "A 3D model (GLB)") + " is open in the viewer: " + rel + ". It is a binary file: you cannot read or watch it, only refer to it by name.");
      context.put("RELATIVE_PATH", rel);
    }
    else if (activeFile != null)
    {
       try
       {
         String content = AITools.readFile(session, activeFile);
         context.put("CONTENT", content);
         context.put("LAST_PARAGRAPH", getLastParagraph(content));
         context.put("RELATIVE_PATH", AITools.resolveRelativePath(session, activeFile));
       } catch (Exception e) {}
    }

    if (contextFiles != null && !contextFiles.isEmpty())
    {
       StringBuilder sb = new StringBuilder();
       for (String f : contextFiles)
       {
         if (ImageWorker.isImagePath(f))
         {
           int idx = ImageWorker.inputImages(session).indexOf(f) + 1;
           sb.append("\n--- FILE: ").append(AITools.resolveRelativePath(session, f)).append(" ---\n[image file, Image ").append(idx).append(": attached to this message as a picture when the agent can see images]\n");
           continue;
         }
         if (MediaWorker.isMediaPath(f))
         {
           sb.append("\n--- FILE: ").append(AITools.resolveRelativePath(session, f)).append(" ---\n[").append(MediaWorker.isVideoPath(f) ? "video" : "3D model").append(" file: binary, not sent as text]\n");
           continue;
         }
         try {
           String content = AITools.readFile(session, f);
           sb.append("\n--- FILE: ").append(AITools.resolveRelativePath(session, f)).append(" ---\n").append(content).append("\n");
         } catch (Exception e) {}
       }
       context.put("CONTEXT_FILES", sb.toString());
    }

    String lastPara = context.getOrDefault("LAST_PARAGRAPH", "");
    String finalSelectedText = (selectedText != null && !selectedText.trim().isEmpty()) ? selectedText : lastPara;
    context.put("SELECTED_TEXT", finalSelectedText);

    return context;
  }

  private static void addToolLog(List<Map<String, Object>> history, String tool, String result)
  {
    Map<String, Object> log = new HashMap<>();
    log.put("role", "tool");
    log.put("tool", tool);
    log.put("content", result);
    log.put("time", System.currentTimeMillis());
    history.add(log);
  }

  private static String expandPrompt(String prompt, Map<String, String> context)
  {
    if (prompt == null) return "";
    String result = prompt;
    for (Map.Entry<String, String> entry : context.entrySet())
    {
      result = result.replace("[[" + entry.getKey() + "]]", entry.getValue() != null ? entry.getValue() : "");
    }
    return result;
  }

  private static String getLastParagraph(String content)
  {
    if (content == null || content.isEmpty()) return "";
    String[] paragraphs = content.split("\\n\\s*\\n");
    if (paragraphs.length == 0) return content;
    return paragraphs[paragraphs.length - 1].trim();
  }

  private static void streamOllama(HttpSession session, String model, String thinkingMode, String systemPrompt, String contextBlock, String userPrompt, List<Map<String, Object>> history, String responseType, String responseTarget, List<String> toolNames, boolean includeHistory)
  {
    String httpSessionId = session.getId();
    CompletableFuture.runAsync(() -> {
      activeAiThreads.put(httpSessionId, Thread.currentThread());
      InputStream bodyStream = null;
      try
      {
        // Round boundary: apply a pause and the user's steering messages before the next model call
        if (includeHistory && RunManager.isRun(httpSessionId) && RunManager.drainSteers(httpSessionId, history) > 0) saveChatSession(session, history);

        // Runaway-loop guard: each LLM round consumes from the per-run budget shared with sub-agents;
        // a run asks the user (on any device) whether to extend it instead of just failing
        int rounds = activeAiRounds.merge(httpSessionId, 1, Integer::sum);
        if (rounds > roundLimit(httpSessionId) && !requestMoreRounds(httpSessionId, rounds - 1))
        {
          AetherWebSocket.sendToSession(httpSessionId, Map.of("type", "chat_error", "error", "Round limit (" + roundLimit(httpSessionId) + ") reached — stopping the tool loop to protect against a runaway turn."));
          finishCommand(session, httpSessionId, null, "Round limit reached");
          AetherWebSocket.sendToSession(httpSessionId, Map.of("type", "chat_done", "responseType", responseType != null ? responseType : "chat"));
          return;
        }

        // The selected model carries its provider ("Provider::model"); a null target means
        // the provider was removed after selection, which must surface, not misroute
        LlmProviders.Target target = LlmProviders.resolve(model);
        if (target == null)
        {
          AetherWebSocket.sendToSession(httpSessionId, Map.of("type", "chat_error", "error", "No LLM provider found for model '" + model + "'. Pick another model, or ask the admin to check the provider list in Settings."));
          finishCommand(session, httpSessionId, null, "No LLM provider found for model " + model);
          AetherWebSocket.sendToSession(httpSessionId, Map.of("type", "chat_done", "responseType", responseType != null ? responseType : "chat"));
          return;
        }

        // OpenAI-compatible chat completions: streams content, delta.reasoning, and tool-call argument fragments
        Map<String, Object> request = new HashMap<>();
        request.put("model", target.model);
        request.put("stream", true);
        request.put("stream_options", Map.of("include_usage", true));
        // Thinking level from the UI selector; Ollama accepts reasoning_effort none/low/medium/high
        if (thinkingMode != null) request.put("reasoning_effort", "off".equals(thinkingMode) ? "none" : thinkingMode);
        // Constrained JSON decoding: the model physically cannot emit malformed JSON (gemma otherwise drifts)
        if ("variants_json".equals(responseType)) request.put("response_format", Map.of("type", "json_object"));

        List<Map<String, Object>> tools = getToolsDefinition(toolNames);
        if (!tools.isEmpty()) request.put("tools", tools);

        // history is the chat session for chatlog commands, or an ephemeral turn list otherwise
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        // Pinned ahead of the conversation so the newest user turn is only what the user typed
        if (contextBlock != null && !contextBlock.isEmpty()) messages.add(Map.of("role", "user", "content", contextBlock));
        int lastUserIdx = -1;
        for (Map<String, Object> m : history)
        {
          String role = String.valueOf(m.get("role"));
          // Command trail entries exist for the user's review only: skipping them keeps the prompt byte-identical for the KV cache
          if ("command".equals(role)) continue;
          Map<String, Object> msg = new HashMap<>();
          msg.put("role", role);
          msg.put("content", m.get("content") != null ? m.get("content") : "");
          // Steers arrive while the model works: labelled so it treats them as an update, not a new task
          if (Boolean.TRUE.equals(m.get("steer"))) msg.put("content", "[Message from the user, sent while you were working. Acknowledge it in one sentence, then adapt your plan and todo list if needed.]\n" + msg.get("content"));
          if (m.containsKey("tool_calls")) msg.put("tool_calls", toOpenAiToolCalls(m.get("tool_calls")));
          if ("tool".equals(role))
          {
            // Tool results need their call id; legacy entries without one are downgraded to user messages
            if (m.get("tool_call_id") != null) msg.put("tool_call_id", m.get("tool_call_id"));
            else { msg.put("role", "user"); msg.put("content", "Tool result: " + msg.get("content")); }
          }
          // The expanded prompt belongs to the turn's own message, never to a steer sent later
          if ("user".equals(role) && !Boolean.TRUE.equals(m.get("steer"))) lastUserIdx = messages.size();
          messages.add(msg);
        }
        compactToolOutputs(messages);
        // The expanded prompt replaces the latest user message IN PLACE so tool results stay last.
        // Appending it after tool rounds made the model see a fresh user request and restart the task.
        if (lastUserIdx >= 0) messages.get(lastUserIdx).put("content", userPrompt);
        else
        {
          Map<String, Object> userMsg = new HashMap<>();
          userMsg.put("role", "user");
          userMsg.put("content", userPrompt);
          messages.add(userMsg);
        }
        // Image agents: the flattened canvas rides on the prompt message, the tail of the request, so the cached prefix is untouched
        List<String[]> turnPics = turnImages.get(httpSessionId);
        if (turnPics != null)
        {
          int promptIdx = lastUserIdx >= 0 ? lastUserIdx : messages.size() - 1;
          List<Object> parts = new ArrayList<>();
          parts.add(Map.of("type", "text", "text", userPrompt));
          for (String[] pic : turnPics)
          {
            parts.add(Map.of("type", "text", "text", pic[0]));
            parts.add(Map.of("type", "image_url", "image_url", Map.of("url", pic[1])));
          }
          messages.get(promptIdx).put("content", parts);
        }
        request.put("messages", messages);

        String jsonRequest = json.toJSON(request);
        if (LOG_PROMPT)
        {
          StringBuilder index = new StringBuilder();
          for (Map<String, Object> m : messages) index.append("  [").append(m.get("role")).append("] ").append(String.valueOf(m.get("content")).length()).append(" chars\n");
          System.out.println("=== LLM REQUEST (" + model + ", " + jsonRequest.length() + " chars) ===\n" + index + prettyJson(jsonRequest.replaceAll("data:image/[a-z]+;base64,[A-Za-z0-9+/=\\\\]+", "data:image/...;base64,<omitted>")) + "\n=== END LLM REQUEST ===");
        }
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder().uri(URI.create(target.url + "/v1/chat/completions")).timeout(java.time.Duration.ofMinutes(5)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonRequest));
        if (!target.apiKey.isEmpty()) reqBuilder.header("Authorization", "Bearer " + target.apiKey);
        HttpRequest httpRequest = reqBuilder.build();

        long promptChars = 0;
        for (Map<String, Object> m : messages) if (m.get("content") instanceof String) promptChars += ((String) m.get("content")).length();
        sendStatus(httpSessionId, "sending", (rounds > 1 ? "Sending tool results to " : "Sending to ") + target.model + " · ~" + approxTokens(promptChars) + " tokens");
        // Time to first token counts from here: prompt upload, queueing and prompt evaluation included
        long sentNanos = System.nanoTime();
        HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200)
        {
          String errBody = "";
          try { errBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8); } catch (Exception ignore) {}
          if (errBody.length() > 300) errBody = errBody.substring(0, 300);
          Map<String, Object> errorMsg = new HashMap<>();
          errorMsg.put("type", "chat_error");
          errorMsg.put("error", "HTTP Error " + response.statusCode() + (errBody.isEmpty() ? "" : ": " + errBody));
          AetherWebSocket.sendToSession(httpSessionId, errorMsg);
          finishCommand(session, httpSessionId, null, "HTTP Error " + response.statusCode());
          return;
        }

        sendStatus(httpSessionId, "waiting", "Processing the prompt · waiting for the first token");
        bodyStream = response.body();
        activeAiStreams.put(httpSessionId, bodyStream);

        StringBuilder fullResponse = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        boolean isThinking = false;
        int thinkingSent = 0;

        // Incremental tool-call assembly: call index -> {id, name, args (JSON string fragments)}
        LinkedHashMap<String, Map<String, Object>> toolAccum = new LinkedHashMap<>();
        long firstTokenNanos = 0;
        long ttftMs = 0;
        String lastStage = null;
        long promptTokens = 0, completionTokens = 0;
        String modelName = model;
        boolean streamDone = false;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(bodyStream, StandardCharsets.UTF_8)))
        {
          String line;
          while ((line = reader.readLine()) != null)
          {
            if (Thread.currentThread().isInterrupted()) break;
            line = line.trim();
            if (line.isEmpty()) continue;
            if (!line.startsWith("data:"))
            {
              // A bare JSON line outside SSE framing is an error body
              if (line.startsWith("{") && line.contains("error")) System.out.println("WARN: LLM returned a non-SSE error line: " + line);
              continue;
            }
            String payload = line.substring(5).trim();
            if ("[DONE]".equals(payload)) { streamDone = true; break; }

            Map<String, Object> part = (Map<String, Object>) json.parse(new JSON.ReaderSource(new java.io.StringReader(payload)));
            if (part.containsKey("error"))
            {
              Map<String, Object> errorMsg = new HashMap<>();
              errorMsg.put("type", "chat_error");
              errorMsg.put("error", String.valueOf(part.get("error")));
              AetherWebSocket.sendToSession(httpSessionId, errorMsg);
              finishCommand(session, httpSessionId, null, String.valueOf(part.get("error")));
              return;
            }

            if (part.get("model") instanceof String) modelName = (String) part.get("model");

            if (part.get("usage") instanceof Map)
            {
              Map<String, Object> usage = (Map<String, Object>) part.get("usage");
              promptTokens = asLong(usage.get("prompt_tokens"));
              completionTokens = asLong(usage.get("completion_tokens"));
            }

            Object[] choices = part.get("choices") instanceof Object[] ? (Object[]) part.get("choices") : null;
            if (choices == null || choices.length == 0) continue;
            Map<String, Object> choice = (Map<String, Object>) choices[0];
            Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
            if (delta == null) continue;
            // Ollama opens the stream with an empty role-only delta: only a delta that carries text,
            // reasoning or a tool call counts as the first token
            boolean carriesToken = delta.get("tool_calls") instanceof Object[];
            for (String k : new String[] { "content", "reasoning", "reasoning_content", "thinking" }) if (delta.get(k) instanceof String && !((String) delta.get(k)).isEmpty()) carriesToken = true;
            if (carriesToken && firstTokenNanos == 0)
            {
              firstTokenNanos = System.nanoTime();
              ttftMs = (firstTokenNanos - sentNanos) / 1_000_000;
            }

            // Streamed tool-call fragments: accumulate per index and mirror the text to the chat log
            if (delta.get("tool_calls") instanceof Object[])
            {
              StringBuilder eventFrags = new StringBuilder();
              for (Object o : (Object[]) delta.get("tool_calls"))
              {
                Map<String, Object> tc = (Map<String, Object>) o;
                String key = String.valueOf(asLong(tc.get("index")));
                Map<String, Object> acc = toolAccum.computeIfAbsent(key, k -> new HashMap<>());
                if (tc.get("id") instanceof String && !((String) tc.get("id")).isEmpty()) acc.put("id", tc.get("id"));
                Map<String, Object> fn = (Map<String, Object>) tc.get("function");
                if (fn != null)
                {
                  if (fn.get("name") instanceof String) acc.put("name", (String) acc.getOrDefault("name", "") + fn.get("name"));
                  if (fn.get("arguments") instanceof String)
                  {
                    acc.put("args", (String) acc.getOrDefault("args", "") + fn.get("arguments"));
                    eventFrags.append((String) fn.get("arguments"));
                  }
                  else if (fn.get("arguments") instanceof Map && !((Map<?, ?>) fn.get("arguments")).isEmpty()) acc.put("argsMap", fn.get("arguments"));
                }
              }
              int chars = 0;
              String toolName = null;
              for (Map<String, Object> a : toolAccum.values())
              {
                chars += ((String) a.getOrDefault("args", "")).length();
                if (toolName == null && a.get("name") != null) toolName = (String) a.get("name");
              }
              Map<String, Object> progress = new HashMap<>();
              progress.put("type", "tool_progress");
              progress.put("tool", toolName != null ? toolName : "tool");
              progress.put("chars", chars);
              if (eventFrags.length() > 0) progress.put("delta", eventFrags.toString());
              AetherWebSocket.sendToSession(httpSessionId, progress, false);
            }

            // Reasoning streams as delta.reasoning (with fallbacks for other server variants)
            String thoughtText = null;
            if (delta.get("reasoning") instanceof String) thoughtText = (String) delta.get("reasoning");
            else if (delta.get("reasoning_content") instanceof String) thoughtText = (String) delta.get("reasoning_content");
            else if (delta.get("thinking") instanceof String) thoughtText = (String) delta.get("thinking");
            if (thoughtText != null && !thoughtText.isEmpty()) thinking.append(thoughtText);

            String chunk = delta.get("content") instanceof String ? (String) delta.get("content") : "";

            if (chunk.contains(THINKING_OPEN) || chunk.contains(THINKING_OPEN_LOWER)) { isThinking = true; chunk = chunk.replace(THINKING_OPEN, "").replace(THINKING_OPEN_LOWER, ""); }
            if (isThinking) {
              if (chunk.contains(THINKING_CLOSE) || chunk.contains(THINKING_CLOSE_LOWER)) {
                String closeTag = chunk.contains(THINKING_CLOSE) ? THINKING_CLOSE : THINKING_CLOSE_LOWER;
                isThinking = false;
                thinking.append(chunk.substring(0, chunk.indexOf(closeTag)));
                chunk = chunk.substring(chunk.indexOf(closeTag) + closeTag.length());
              } else { thinking.append(chunk); chunk = ""; }
            }

            if (!chunk.isEmpty()) fullResponse.append(chunk);

            String stage = !chunk.isEmpty() ? "generating" : ((thoughtText != null && !thoughtText.isEmpty()) || isThinking) ? "thinking" : (delta.get("tool_calls") instanceof Object[]) ? "tool" : null;
            if (stage != null && !stage.equals(lastStage))
            {
              lastStage = stage;
              String label = "generating".equals(stage) ? "Generating" : "thinking".equals(stage) ? "Thinking" : "Writing a tool call";
              sendStatus(httpSessionId, stage, label + " · " + modelName, Map.of("ttftMs", ttftMs));
            }

            // Send only the unsent portion of the thinking buffer; resending the whole of it grew O(n^2)
            boolean hasVisibleDelta = !chunk.isEmpty() || thinking.length() > thinkingSent;
            if (("chat".equals(responseType) || "variants_json".equals(responseType)) && hasVisibleDelta)
            {
              Map<String, Object> deltaMsg = new HashMap<>();
              deltaMsg.put("type", "chat_delta");
              deltaMsg.put("content", chunk);
              deltaMsg.put("thinking", thinking.substring(thinkingSent));
              thinkingSent = thinking.length();
              deltaMsg.put("responseType", responseType);
              if (responseTarget != null) deltaMsg.put("responseTarget", responseTarget);
              deltaMsg.put("model", modelName);
              AetherWebSocket.sendToSession(httpSessionId, deltaMsg);
            }

            if ("plaintext".equals(responseType) && !chunk.isEmpty())
            {
              Map<String, Object> deltaMsg = new HashMap<>();
              deltaMsg.put("type", "plaintext");
              deltaMsg.put("content", chunk);
              AetherWebSocket.sendToSession(httpSessionId, deltaMsg);
              if (thinking.length() > thinkingSent)
              {
                Map<String, Object> thinkDelta = new HashMap<>();
                thinkDelta.put("type", "chat_delta");
                thinkDelta.put("content", "");
                thinkDelta.put("thinking", thinking.substring(thinkingSent));
                thinkingSent = thinking.length();
                thinkDelta.put("responseType", responseType);
                thinkDelta.put("model", modelName);
                AetherWebSocket.sendToSession(httpSessionId, thinkDelta);
              }
            }
          }

          if (Thread.currentThread().isInterrupted()) return;

          // Assembled tool calls: convert to the native shape the executor and history expect
          if (!toolAccum.isEmpty())
          {
            List<Map<String, Object>> calls = new ArrayList<>();
            int i = 0;
            for (Map<String, Object> acc : toolAccum.values())
            {
              Map<String, Object> argsMap = new HashMap<>();
              if (acc.get("argsMap") instanceof Map) argsMap = (Map<String, Object>) acc.get("argsMap");
              else if (acc.get("args") instanceof String)
              {
                argsMap = parseToolArgs((String) acc.get("args"), String.valueOf(acc.get("name")));
              }
              Map<String, Object> fn = new HashMap<>();
              fn.put("name", acc.getOrDefault("name", ""));
              fn.put("arguments", argsMap);
              Map<String, Object> call = new HashMap<>();
              call.put("id", acc.get("id") != null ? acc.get("id") : "call_" + i);
              call.put("function", fn);
              calls.add(call);
              i++;
            }
            Object[] toolCalls = calls.toArray();
            Map<String, Object> aiMsg = new HashMap<>();
            aiMsg.put("role", "assistant");
            aiMsg.put("content", fullResponse.toString());
            aiMsg.put("tool_calls", toolCalls);
            aiMsg.put("time", System.currentTimeMillis());
            history.add(aiMsg);
            if (includeHistory) saveChatSession(session, history);
            // Tool rounds are part of the same turn: keep their tokens and time in the final statistics
            long[] roundStats = turnStats.get(httpSessionId);
            if (roundStats == null) { roundStats = new long[] { 0, 0, ttftMs, sentNanos }; turnStats.put(httpSessionId, roundStats); }
            roundStats[0] += completionTokens > 0 ? completionTokens : (fullResponse.length() + thinking.length()) / 4;
            roundStats[1] += firstTokenNanos > 0 ? System.nanoTime() - firstTokenNanos : 0;
            executeToolsAndRepeat(session, httpSessionId, history, toolCalls, model, thinkingMode, systemPrompt, contextBlock, userPrompt, responseType, responseTarget, toolNames, includeHistory);
            return;
          }

            {
              long endNanos = System.nanoTime();
              Map<String, Object> done = new HashMap<>();
              done.put("type", "chat_done");
              done.put("responseType", responseType);
              if (responseTarget != null) done.put("responseTarget", responseTarget);

              // Performance metrics: token counts from usage; generation time is wall-clock (first token -> end).
              // The OpenAI-compatible /v1/chat/completions stream reports no prefill/prompt-eval duration, so it is omitted.
              // A provider that omits usage still gets figures: ~4 chars per token, flagged approximate
              boolean approx = completionTokens == 0 && (fullResponse.length() + thinking.length()) > 0;
              if (approx) completionTokens = Math.max(1, (fullResponse.length() + thinking.length()) / 4);
              if (promptTokens == 0) { promptTokens = promptChars / 4; approx = true; }
              long[] ts = turnStats.get(httpSessionId);
              if (ts == null) ts = new long[] { 0, 0, ttftMs, sentNanos };
              ts[0] += completionTokens;
              ts[1] += firstTokenNanos > 0 ? endNanos - firstTokenNanos : 0;
              done.put("total_duration", endNanos - ts[3]);
              done.put("ttft_ms", ts[2]);
              done.put("prompt_eval_count", promptTokens);
              done.put("eval_count", ts[0]);
              done.put("eval_duration", ts[1]);
              if (approx) done.put("stats_approx", true);
              turnStats.remove(httpSessionId);

              if ("chat".equals(responseType) && includeHistory && (fullResponse.length() > 0 || thinking.length() > 0))
              {
                Map<String, Object> aiMsg = new HashMap<>();
                aiMsg.put("role", "assistant");
                aiMsg.put("content", fullResponse.toString());
                aiMsg.put("thinking", thinking.toString());
                aiMsg.put("model", model);
                aiMsg.put("time", System.currentTimeMillis());
                history.add(aiMsg);
                session.setAttribute("chatHistory", history);
                saveChatSession(session, history);
              }
              else if ("plaintext".equals(responseType))
              {
                Map<String, Object> delta = new HashMap<>();
                delta.put("type", "plaintext");
                delta.put("content", fullResponse.toString());
                AetherWebSocket.sendToSession(httpSessionId, delta);
              }
              else if ("variants_json".equals(responseType))
              {
                String rawResponse = fullResponse.toString().trim();
                String jsonContent = extractAndFixJson(rawResponse);
                try
                {
                  Map<String, Object> variantsMap = (Map<String, Object>) json.parse(new JSON.ReaderSource(new java.io.StringReader(jsonContent)));
                  Object[] variantsArr = (Object[]) variantsMap.get("variants");
                  List<Map<String, Object>> variants = new ArrayList<>();
                  if (variantsArr != null) for (Object v : variantsArr) variants.add((Map<String, Object>) v);
                  Map<String, Object> delta = new HashMap<>();
                  delta.put("type", "variants");
                  delta.put("variants", variants);
                  if (responseTarget != null) delta.put("responseTarget", responseTarget);
                  AetherWebSocket.sendToSession(httpSessionId, delta);
                }
                catch (Exception e)
                {
                  List<Map<String, Object>> variants = new ArrayList<>();
                  variants.add(Map.of("text", rawResponse));
                  Map<String, Object> delta = new HashMap<>();
                  delta.put("type", "variants");
                  delta.put("variants", variants);
                  if (responseTarget != null) delta.put("responseTarget", responseTarget);
                  AetherWebSocket.sendToSession(httpSessionId, delta);
                }
              }

              // A non-chatlog command's result joins the short command trail in the chat session
              finishCommand(session, httpSessionId, fullResponse.toString(), null);

              // Reality audit for todo-capable agents: don't let the turn end while items sit
              // in_progress/verify or after an unremedied rejected completion claim — weak models
              // otherwise announce success and stop without having done anything
              if (includeHistory && "chat".equals(responseType) && toolNames != null && toolNames.contains("todoWrite"))
              {
                String nudge = pendingTodoNudge(session, httpSessionId);
                if (nudge != null)
                {
                  // Visible note so the user sees exactly why the AI keeps going (no hidden magic)
                  Map<String, Object> note = new HashMap<>();
                  note.put("type", "chat_delta");
                  note.put("content", "\n\n⚙️ *Auto-check: unfinished todo items detected — instructing the model to continue (" + todoNudges.get(httpSessionId) + "/" + MAX_TODO_NUDGES + ").*\n");
                  note.put("thinking", "");
                  note.put("responseType", responseType);
                  note.put("model", modelName);
                  AetherWebSocket.sendToSession(httpSessionId, note);
                  // The nudge rides on the expanded prompt: message assembly replaces the last user
                  // message in place, so a nudge stored as a history message would be clobbered
                  streamOllama(session, model, thinkingMode, systemPrompt, contextBlock, userPrompt + "\n\n" + nudge, history, responseType, responseTarget, toolNames, includeHistory);
                  return;
                }
              }

              // A message the user sent while the final answer streamed must not be lost: continue
              if (includeHistory && "chat".equals(responseType) && RunManager.hasPendingSteers(httpSessionId))
              {
                streamOllama(session, model, thinkingMode, systemPrompt, contextBlock, userPrompt, history, responseType, responseTarget, toolNames, includeHistory);
                return;
              }

              // Autonomous task runs only end through finish + passing stop gates
              AgentRun taskRun = RunManager.get(httpSessionId);
              if (taskRun != null && taskRun.isAutonomous() && includeHistory && "chat".equals(responseType) && !taskRun.finished && taskRun.continuations < taskRun.maxContinuations)
              {
                taskRun.continuations++;
                Map<String, Object> note = new HashMap<>();
                note.put("type", "chat_delta");
                note.put("content", "\n\n⚙️ *Task not finished (no successful finish call) — continuing (" + taskRun.continuations + "/" + taskRun.maxContinuations + ").*\n");
                note.put("thinking", "");
                note.put("responseType", responseType);
                note.put("model", modelName);
                AetherWebSocket.sendToSession(httpSessionId, note);
                String nudge = "AUTO-CONTINUE (server, not the user): you ended your reply without completing the task. Continue working on the next unfinished todo item with real tool calls. When everything is done and verified, call finish. If you are blocked, call askUser.";
                streamOllama(session, model, thinkingMode, systemPrompt, contextBlock, userPrompt + "\n\n" + nudge, history, responseType, responseTarget, toolNames, includeHistory);
                return;
              }

              if (includeHistory) generateTitleIfNeeded(session, history);
              // Always close the turn so the client leaves the working state, even for an empty response.
              // Turn complete and delivered live: drop the replay buffer so reconnects don't re-feed stats/deltas
              if (AetherWebSocket.sendToSession(httpSessionId, done)) AetherWebSocket.clearBuffer(httpSessionId);
            }
        }
      } catch (Exception e)
      {
        // "Interrupt now" steer: the stream was closed on purpose — restart the round with the message applied
        if (!Thread.currentThread().isInterrupted() && RunManager.consumeInterrupt(httpSessionId))
        {
          AetherWebSocket.sendToSession(httpSessionId, Map.of("type", "chat_delta", "content", "\n\n⏸️ *Interrupted to apply your message.*\n", "thinking", "", "responseType", "chat"));
          streamOllama(session, model, thinkingMode, systemPrompt, contextBlock, userPrompt, history, responseType, responseTarget, toolNames, includeHistory);
          return;
        }
        // A user stop arrives as an interrupt or as the response stream being closed under us: end quietly
        boolean stopped = Thread.currentThread().isInterrupted() || e instanceof InterruptedException || e.getCause() instanceof InterruptedException;
        if (!stopped)
        {
          e.printStackTrace();
          finishCommand(session, httpSessionId, null, String.valueOf(e.getMessage()));
          if (AetherWebSocket.sendToSession(httpSessionId, Map.of("type", "chat_error", "error", "Error: " + e.getMessage()))) AetherWebSocket.clearBuffer(httpSessionId);
        }
      } finally {
        // Two-arg remove: a follow-up tool round may already have registered its own thread/stream under this key
        if (bodyStream != null) activeAiStreams.remove(httpSessionId, bodyStream);
        activeAiThreads.remove(httpSessionId, Thread.currentThread());
      }
    }, RunManager.AI_EXECUTOR);
  }

  private static int roundLimit(String id)
  {
    AgentRun run = RunManager.get(id);
    return run != null && run.roundLimit > 0 ? run.roundLimit : MAX_ROUNDS;
  }

  // Budget exhausted: park on the run's thread and let any of the user's devices extend it
  private static boolean requestMoreRounds(String id, int used) throws InterruptedException
  {
    AgentRun run = RunManager.get(id);
    if (run == null) return false;
    Map<String, Object> req = new HashMap<>();
    req.put("type", "budget_request");
    req.put("used", used);
    req.put("limit", run.roundLimit);
    req.put("message", "The run used its budget of " + run.roundLimit + " model calls. Extend it to let the AI continue?");
    Map<String, Object> answer = RunManager.awaitDecision(run, req);
    int extra = answer.get("extend") instanceof Number ? ((Number) answer.get("extend")).intValue() : 0;
    if (extra <= 0) return false;
    run.roundLimit += extra;
    return true;
  }

  // Long task runs outgrow the context: older tool outputs are replaced by a stub in the REQUEST only
  // (history and log keep everything). Applied only past a threshold, so short chats keep a stable
  // prefix for the KV cache.
  private static final int COMPACT_CHARS = 160000;
  private static final int KEEP_RECENT_TOOL_OUTPUTS = 8;

  private static void compactToolOutputs(List<Map<String, Object>> messages)
  {
    long total = 0;
    for (Map<String, Object> m : messages) if (m.get("content") instanceof String) total += ((String) m.get("content")).length();
    if (total <= COMPACT_CHARS) return;
    int toolSeen = 0;
    for (int i = messages.size() - 1; i >= 0; i--)
    {
      Map<String, Object> m = messages.get(i);
      if (!"tool".equals(m.get("role"))) continue;
      toolSeen++;
      if (toolSeen <= KEEP_RECENT_TOOL_OUTPUTS) continue;
      Object c = m.get("content");
      if (c instanceof String && ((String) c).length() > 400) m.put("content", "[Older tool output cleared to save context (" + ((String) c).length() + " chars). Re-run the tool if you need it again.]");
    }
  }

  // Rough prompt size for the status line: about four characters per token
  private static String approxTokens(long chars)
  {
    long t = chars / 4;
    return t >= 1000 ? (t / 1000) + "k" : String.valueOf(t);
  }

  private static long asLong(Object o)
  {
    return o instanceof Number ? ((Number) o).longValue() : 0;
  }

  // Responses API uses a flat tool schema: {type, name, description, parameters} without the nested "function" wrapper
  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> toResponsesTools(List<Map<String, Object>> chatTools)
  {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> t : chatTools)
    {
      Map<String, Object> fn = (Map<String, Object>) t.get("function");
      if (fn == null) continue;
      Map<String, Object> flat = new HashMap<>();
      flat.put("type", "function");
      flat.put("name", fn.get("name"));
      if (fn.get("description") != null) flat.put("description", fn.get("description"));
      if (fn.get("parameters") != null) flat.put("parameters", fn.get("parameters"));
      result.add(flat);
    }
    return result;
  }

  // Convert stored native-style tool calls (arguments as Map) to OpenAI wire format (arguments as JSON string)
  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> toOpenAiToolCalls(Object toolCallsObj)
  {
    List<Map<String, Object>> result = new ArrayList<>();
    Iterable<?> iter = null;
    if (toolCallsObj instanceof Object[]) iter = Arrays.asList((Object[]) toolCallsObj);
    else if (toolCallsObj instanceof Iterable) iter = (Iterable<?>) toolCallsObj;
    if (iter == null) return result;

    int i = 0;
    for (Object o : iter)
    {
      Map<String, Object> tc = (Map<String, Object>) o;
      Map<String, Object> fn = (Map<String, Object>) tc.get("function");
      if (fn == null) continue;
      Object args = fn.get("arguments");
      Map<String, Object> outFn = new HashMap<>();
      outFn.put("name", fn.get("name"));
      outFn.put("arguments", args instanceof String ? args : json.toJSON(args != null ? args : new HashMap<>()));
      Map<String, Object> out = new HashMap<>();
      out.put("id", tc.get("id") != null ? tc.get("id") : "call_" + i);
      out.put("type", "function");
      out.put("function", outFn);
      result.add(out);
      i++;
    }
    return result;
  }

  private static String extractAndFixJson(String text)
  {
    if (text == null || text.isEmpty()) return "{}";
    int start = text.indexOf('{');
    if (start == -1) return "{}";
    
    String raw = text.substring(start).trim();
    
    // Simple stack-based repair for truncated JSON
    StringBuilder repaired = new StringBuilder(raw);
    java.util.Stack<Character> stack = new java.util.Stack<>();
    boolean inString = false;
    boolean escaped = false;
    
    for (int i = 0; i < repaired.length(); i++)
    {
      char c = repaired.charAt(i);
      if (escaped) { escaped = false; continue; }
      if (c == '\\') { escaped = true; continue; }
      if (c == '"') { inString = !inString; continue; }
      if (inString) continue;
      
      if (c == '{' || c == '[') stack.push(c);
      else if (c == '}' || c == ']')
      {
        if (!stack.isEmpty())
        {
          char top = stack.peek();
          if ((c == '}' && top == '{') || (c == ']' && top == '[')) stack.pop();
        }
      }
    }
    
    if (inString) repaired.append('"');
    while (!stack.isEmpty())
    {
      char top = stack.pop();
      if (top == '{') repaired.append('}');
      else if (top == '[') repaired.append(']');
    }
    
    return repaired.toString();
  }

  // Shown on the approval card; optional in the schema so non-coding agents that omit it keep working
  private static final Map<String, Object> EXPLAIN_PROP = Map.of("type", "string", "description", "Explain this change: what it does, why, and how it relates to your plan and the subsequent steps. Shown to the user on the approval card. Coding agents MUST provide it.");

  private static List<Map<String, Object>> getToolsDefinition(List<String> toolNames)
  {
    List<Map<String, Object>> tools = new ArrayList<>();
    if (toolNames == null) return tools;

    for (String name : toolNames)
    {
      Map<String, Object> tool = new HashMap<>();
      tool.put("type", "function");
      Map<String, Object> function = new HashMap<>();
      function.put("name", name);
      
      Map<String, Object> parameters = new HashMap<>();
      parameters.put("type", "object");
      Map<String, Object> properties = new HashMap<>();
      List<String> required = new ArrayList<>();

      switch(name) {
        case "readFile":
          function.put("description", "Read the content of a file.");
          properties.put("path", Map.of("type", "string", "description", "Relative path to the file."));
          required.add("path");
          break;
        case "writeFile":
          function.put("description", "Overwrite a file with new content.");
          properties.put("path", Map.of("type", "string", "description", "Relative path to the file."));
          properties.put("content", Map.of("type", "string", "description", "New content of the file."));
          properties.put("explain", EXPLAIN_PROP);
          required.addAll(Arrays.asList("path", "content"));
          break;
        case "patchFile":
          function.put("description", "Edit a file by applying one or more surgical replacements. Each edit replaces EXACTLY ONE occurrence of old_text with new_text. Use separate edits for changes in different places instead of one large block.");
          properties.put("path", Map.of("type", "string", "description", "Relative path to the file."));
          properties.put("edits", Map.of(
            "type", "array",
            "description", "List of independent changes applied in order.",
            "items", Map.of(
              "type", "object",
              "properties", Map.of(
                "old_text", Map.of("type", "string", "description", "The exact literal text to find (must be unique in the file)."),
                "new_text", Map.of("type", "string", "description", "The text to replace it with.")),
              "required", Arrays.asList("old_text", "new_text"))));
          properties.put("explain", EXPLAIN_PROP);
          required.addAll(Arrays.asList("path", "edits"));
          break;
        case "createFile":
          function.put("description", "Create a new file.");
          properties.put("path", Map.of("type", "string", "description", "Relative path to the file."));
          properties.put("content", Map.of("type", "string", "description", "Initial content."));
          properties.put("explain", EXPLAIN_PROP);
          required.addAll(Arrays.asList("path", "content"));
          break;
        case "deleteFile":
          function.put("description", "Delete a file.");
          properties.put("path", Map.of("type", "string", "description", "Relative path."));
          properties.put("explain", EXPLAIN_PROP);
          required.add("path");
          break;
        case "mkdir":
          function.put("description", "Create a directory.");
          properties.put("path", Map.of("type", "string", "description", "Relative path."));
          properties.put("explain", EXPLAIN_PROP);
          required.add("path");
          break;
        case "renameFile":
          function.put("description", "Rename a file or directory in place (does not change its parent directory — use moveFile for that).");
          properties.put("path", Map.of("type", "string", "description", "Relative path to the file or directory."));
          properties.put("new_name", Map.of("type", "string", "description", "The new name (no slashes — this is a rename, not a move)."));
          properties.put("explain", EXPLAIN_PROP);
          required.addAll(Arrays.asList("path", "new_name"));
          break;
        case "moveFile":
          function.put("description", "Move a file or directory to a new location within the same storage provider.");
          properties.put("path", Map.of("type", "string", "description", "Relative path to the source file or directory."));
          properties.put("destination", Map.of("type", "string", "description", "Relative path to the new location, including the file/directory name."));
          properties.put("explain", EXPLAIN_PROP);
          required.addAll(Arrays.asList("path", "destination"));
          break;
        case "copyFile":
          function.put("description", "Copy a file or directory to a new location within the same storage provider.");
          properties.put("path", Map.of("type", "string", "description", "Relative path to the source file or directory."));
          properties.put("destination", Map.of("type", "string", "description", "Relative path to the copy's location, including the new file/directory name."));
          properties.put("explain", EXPLAIN_PROP);
          required.addAll(Arrays.asList("path", "destination"));
          break;
        case "listFiles":
          function.put("description", "List directory content.");
          properties.put("path", Map.of("type", "string", "description", "Relative path."));
          required.add("path");
          break;
        case "grepSearch":
          function.put("description", "Search for a regex pattern in the project files.");
          properties.put("pattern", Map.of("type", "string", "description", "Regex pattern."));
          required.add("pattern");
          break;
        case "getProjectStructure":
          function.put("description", "Get a map of all project files.");
          break;
        case "webSearch":
          function.put("description", "Perform a web search.");
          properties.put("query", Map.of("type", "string", "description", "Search query."));
          required.add("query");
          break;
        case "todoWrite":
          function.put("description", "Replace your ENTIRE todo list with the given items. Call it right after planning and again after EVERY completed or verified step so the list always reflects reality. The user sees this list live in the UI.");
          properties.put("todos", Map.of(
            "type", "array",
            "description", "The full task list, in execution order.",
            "items", Map.of(
              "type", "object",
              "properties", Map.of(
                "content", Map.of("type", "string", "description", "Short, concrete, verifiable task description."),
                "status", Map.of("type", "string", "enum", Arrays.asList("pending", "in_progress", "verify", "done"), "description", "pending = not started, in_progress = working on it now, verify = implemented but not yet checked, done = verified complete."),
                "id", Map.of("type", "string", "description", "Optional stable id kept across updates.")),
              "required", Arrays.asList("content", "status"))));
          required.add("todos");
          break;
        case "subTask":
          function.put("description", "Delegate ONE self-contained sub-task to a nested agent with a fresh context. It can read, search, and edit files (its edits still require user approval) and returns a final report when finished. It CANNOT see this conversation: the prompt must be fully standalone (include file paths, requirements, conventions). It cannot spawn further sub-tasks. Use it for isolated chunks like researching a subsystem or implementing one file; never delegate the whole task.");
          properties.put("description", Map.of("type", "string", "description", "Very short label of the sub-task, shown to the user."));
          properties.put("prompt", Map.of("type", "string", "description", "Complete standalone instructions for the sub-agent."));
          properties.put("agent", Map.of("type", "string", "description", "Optional 'agentId' or 'agentId:commandId' to run a predefined agent command as the sub-task; defaults to the current agent."));
          required.addAll(Arrays.asList("description", "prompt"));
          break;
        case "runCommand":
          function.put("description", "Run a shell command in the project working directory on the remote SSH host. Requires user approval. Output is capped and returned together with the exit code. Use it for builds, tests, and inspection; never run destructive commands unless the user explicitly asked for them.");
          properties.put("command", Map.of("type", "string", "description", "The shell command to run."));
          properties.put("timeout_seconds", Map.of("type", "number", "description", "Max seconds to wait (default 60, max 300)."));
          properties.put("explain", EXPLAIN_PROP);
          required.add("command");
          break;
        case "generateImage":
          function.put("description", "Create a NEW image with the image model. Slow on this server: roughly a minute for 512x512 at 14 steps, and time grows with pixels x steps (1024x1024 at 20 steps is about nine minutes), so agree on the idea first and do not call it repeatedly. The result is saved as a new PNG; when an image is open it also becomes that image's new top layer. Write the prompt in English as a concrete visual description: subject, composition, style, lighting, colours.");
          properties.put("prompt", Map.of("type", "string", "description", "Detailed visual description of the image to create."));
          properties.put("size", Map.of("type", "string", "description", "WIDTHxHEIGHT in multiples of 32, e.g. 768x512. Omit to use the user's size setting."));
          properties.put("steps", Map.of("type", "number", "description", "Diffusion steps, 4-50. Fewer is faster but rougher. Omit to use the user's setting."));
          properties.put("transparent", Map.of("type", "boolean", "description", "Transparent background, for logos, stickers and cut-out elements meant to be layered."));
          properties.put("name", Map.of("type", "string", "description", "Short file-name hint, e.g. 'red-fox'."));
          required.add("prompt");
          break;
        case "editImage":
          function.put("description", "Change the OPEN image with the image model; the result is saved as a new PNG and added as a new top layer (the original is never overwritten). Same cost as generateImage. source 'canvas' sends the whole composed canvas; 'layers' with a list of layer names sends those layers as separate input images, which is how subjects from different layers are combined into one scene.");
          properties.put("prompt", Map.of("type", "string", "description", "The change to make, described concretely; say what must stay the same."));
          properties.put("source", Map.of("type", "string", "enum", Arrays.asList("canvas", "layers"), "description", "What to send to the image model. Omit to use the user's setting."));
          properties.put("layers", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Input images to send, in this order: layer names, or ticked files by name or as 'Image 1', 'Image 2'. Omit to send every ticked image (in tick order), or the flattened canvas when none is ticked."));
          properties.put("size", Map.of("type", "string", "description", "WIDTHxHEIGHT in multiples of 32. Omit to use the user's size setting."));
          properties.put("steps", Map.of("type", "number", "description", "Diffusion steps, 4-50. Omit to use the user's setting."));
          properties.put("transparent", Map.of("type", "boolean", "description", "Transparent background for the result."));
          properties.put("name", Map.of("type", "string", "description", "Short file-name hint."));
          required.add("prompt");
          break;
        case "generateVideo":
          function.put("description", "Create a video clip (with sound) with the video model. Very slow on this server: 2 to 10 minutes depending on size and length, so agree on the idea first, keep clips short, and never call it twice for one request. Give 'image' to animate an existing picture (image-to-video): refine that picture with generateImage / editImage first when the look matters, because the video follows it closely. The result is saved as an MP4 and opened in the player.");
          properties.put("prompt", Map.of("type", "string", "description", "What happens in the clip, in English: subject, action, camera movement, mood, and the sound (dialogue, ambience, music)."));
          properties.put("image", Map.of("type", "string", "description", "First frame: a ticked or open picture, by file name or as 'Image 1'. Omit for text-to-video."));
          properties.put("end_image", Map.of("type", "string", "description", "Last frame, same naming. Only for models that list end_image."));
          properties.put("seconds", Map.of("type", "number", "description", "Clip length in seconds. Omit to use the user's setting."));
          properties.put("size", Map.of("type", "string", "description", "WIDTHxHEIGHT, e.g. 768x448. Omit to use the user's setting."));
          properties.put("size_from", Map.of("type", "string", "enum", Arrays.asList("set", "aspect", "image"), "description", "With a first frame: 'aspect' gives the clip the picture's aspect ratio at about the usual pixel count (recommended, avoids stretching or cropping), 'image' uses the picture's own resolution (slower for large pictures), 'set' uses size as given. Omit to use the user's setting."));
          properties.put("options", Map.of("type", "object", "description", "Other parameters of the selected video model, exactly as listed in the workspace context (for example steps, seed, negative_prompt)."));
          properties.put("name", Map.of("type", "string", "description", "Short file-name hint."));
          required.add("prompt");
          break;
        case "generate3d":
          function.put("description", "Build a 3D mesh (GLB) from ONE picture with the 3D model. Very slow on this server: a few minutes with vertex colours, 20 to 40 minutes with texture 'pbr', so confirm the picture and the settings first and never call it twice for one request. The picture must show one object, whole and uncropped, ideally on a plain or transparent background: prepare it with generateImage / editImage first when it does not. The result is saved as a .glb and opened in the 3D viewer.");
          properties.put("image", Map.of("type", "string", "description", "The object's picture: a ticked or open picture, by file name or as 'Image 1'. Omit to use Image 1."));
          properties.put("texture", Map.of("type", "string", "enum", Arrays.asList("vertex", "pbr", "none"), "description", "vertex: colour per vertex, minutes. pbr: UV-unwrapped mesh with base colour, metallic, roughness, normal and occlusion maps, tens of minutes. none: geometry only. Only for models that list it."));
          properties.put("faces", Map.of("type", "number", "description", "Triangle budget. Omit for the model's default."));
          properties.put("options", Map.of("type", "object", "description", "Other parameters of the selected 3D model, exactly as listed in the workspace context (for example texture_size, remove_background, steps, resolution, seed)."));
          properties.put("name", Map.of("type", "string", "description", "Short file-name hint, e.g. 'toy-robot'."));
          break;
        case "delegate":
          function.put("description", "Delegate ONE self-contained chunk of the task to a worker agent with its own fresh context (it cannot see this conversation). It reads, searches and edits files (edits follow the same approval rules) and returns a report of what it did and changed. Give it everything it needs: file paths, requirements, conventions, and what 'done' means for this chunk. Keep small edits yourself.");
          properties.put("agent", Map.of("type", "string", "description", "Worker agent id, e.g. 'coder'. Defaults to the current agent."));
          properties.put("task", Map.of("type", "string", "description", "Complete standalone instructions for the worker."));
          required.add("task");
          break;
        case "proposePlan":
          function.put("description", "Record the plan of this task: a Definition of Done (verifiable acceptance criteria the reviewer will check) and the ordered todo list. When plan approval is on, this waits for the user to approve, edit or reject it; follow the returned plan.");
          properties.put("dod", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Acceptance criteria, each concrete and checkable."));
          properties.put("todos", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Ordered, concrete todo items."));
          properties.put("summary", Map.of("type", "string", "description", "One paragraph: the approach and why."));
          required.addAll(Arrays.asList("dod", "todos"));
          break;
        case "finish":
          function.put("description", "Declare the task complete. The server then runs the stop gates: all todos done, the project's verify command (build/tests) passes, and the reviewer model approves the changes against the Definition of Done. Returns PASSED (then write the final summary) or the reasons it FAILED (fix them and call finish again).");
          properties.put("summary", Map.of("type", "string", "description", "What was done and how it was verified, file by file."));
          required.add("summary");
          break;
        case "askUser":
          function.put("description", "Ask the user one or more questions when you need clarification, missing information, or a decision. Do NOT guess when the request is ambiguous — call this tool first. Bundle SEVERAL related questions into a SINGLE call so the user answers them all at once. Give each question 2-4 suggested answers as options; the user can pick one or type a completely free answer. Returns the user's answers.");
          properties.put("questions", Map.of(
            "type", "array",
            "description", "All questions to ask the user, shown together at once.",
            "items", Map.of(
              "type", "object",
              "properties", Map.of(
                "question", Map.of("type", "string", "description", "The question text."),
                "options", Map.of("type", "array", "description", "2-4 suggested answers the user can pick from.", "items", Map.of("type", "string"))),
              "required", Arrays.asList("question"))));
          required.add("questions");
          break;
      }

      parameters.put("properties", properties);
      parameters.put("required", required);
      function.put("parameters", parameters);
      tool.put("function", function);
      tools.add(tool);
    }
    return tools;
  }

  // Tool arguments arrive as a JSON string built by the model. Small models emit invalid escapes
  // (gemma writes \' for an apostrophe, which no JSON parser accepts), so a failed parse is retried
  // once with the illegal escapes repaired rather than losing the whole tool call.
  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseToolArgs(String raw, String toolName)
  {
    if (raw == null || raw.isEmpty()) return new HashMap<>();
    try { return (Map<String, Object>) json.parse(new JSON.ReaderSource(new java.io.StringReader(raw))); }
    catch (Exception first)
    {
      // Walk escape pairs left to right so a legal one is consumed whole: scanning for "a backslash
      // not followed by a legal char" would see the second half of \\ and corrupt escaped backslashes
      java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\\\(u[0-9a-fA-F]{4}|(?s).)").matcher(raw);
      StringBuffer sb = new StringBuffer();
      while (m.find())
      {
        String esc = m.group(1);
        boolean legal = esc.length() > 1 || "\"\\/bfnrt".indexOf(esc.charAt(0)) >= 0;
        m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(legal ? "\\" + esc : esc));
      }
      m.appendTail(sb);
      String repaired = sb.toString();
      try
      {
        Map<String, Object> fixed = (Map<String, Object>) json.parse(new JSON.ReaderSource(new java.io.StringReader(repaired)));
        System.out.println("WARN: repaired invalid escapes in tool args for " + toolName);
        return fixed;
      }
      catch (Exception second)
      {
        System.out.println("WARN: tool args JSON parse failed for " + toolName + ": " + first.getMessage());
        return new HashMap<>();
      }
    }
  }

  private static void executeToolsAndRepeat(HttpSession session, String httpSessionId, List<Map<String, Object>> history, Object[] toolCalls, String model, String thinkingMode, String systemPrompt, String contextBlock, String userPrompt, String responseType, String responseTarget, List<String> toolNames, boolean useHistory)
  {
    for (Object tcObj : toolCalls)
    {
      Map<String, Object> tc = (Map<String, Object>) tcObj;
      Map<String, Object> function = (Map<String, Object>) tc.get("function");
      String name = (String) function.get("name");
      Map<String, Object> args = (Map<String, Object>) function.get("arguments");
      String callId = (String) tc.get("id");
      try { executeSingleTool(session, httpSessionId, history, name, args, callId, model, thinkingMode, useHistory, 0); }
      catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; } // user stop: end the turn, do not resume the model
    }
    if (Thread.currentThread().isInterrupted()) return;
    streamOllama(session, model, thinkingMode, systemPrompt, contextBlock, userPrompt, history, responseType, responseTarget, toolNames, useHistory);
  }

  // One tool execution: permission gating, blocking user round-trips, WS transparency cards, history log.
  // depth > 0 marks a sub-agent's nested call ("sub" flag on every card); InterruptedException always propagates.
  private static void executeSingleTool(HttpSession session, String httpSessionId, List<Map<String, Object>> history, String name, Map<String, Object> args, String callId, String model, String thinkingMode, boolean useHistory, int depth) throws InterruptedException
  {
      String logAction = "🛠️ Calling tool: " + name;
      String pathArg = (String) args.get("path");
      boolean fileExists = (pathArg != null) && AITools.exists(session, pathArg);

      switch(name) {
        case "readFile": logAction = "📖 Reading file: " + pathArg; break;
        case "writeFile": logAction = (fileExists ? "🩹 Editing existing file: " : "💾 Creating new file: ") + pathArg; break;
        case "patchFile": logAction = "🩹 Patching file: " + pathArg; break;
        case "createFile": logAction = "🆕 Creating file: " + pathArg; break;
        case "listFiles": logAction = "📁 Listing directory: " + pathArg; break;
        case "grepSearch": logAction = "🔍 Searching for pattern: " + args.get("pattern"); break;
        case "getProjectStructure": logAction = "🗺️ Mapping project structure"; break;
        case "webSearch": logAction = "🌐 Searching web: " + args.get("query"); break;
        case "askUser": logAction = "❓ Asking for your input"; break;
        case "todoWrite": logAction = "📋 Updating the task list"; break;
        case "runCommand": logAction = "💻 Running command: " + args.get("command"); break;
        case "subTask": logAction = "🤖 Sub-task: " + args.get("description"); break;
        case "generateImage": logAction = "🎨 Generating image: " + args.get("prompt"); break;
        case "editImage": logAction = "🖌️ Editing image: " + args.get("prompt"); break;
        case "generateVideo": logAction = "🎬 Generating video: " + args.get("prompt"); break;
        case "generate3d": logAction = "🧊 Building 3D model" + (args.get("image") != null ? " from " + args.get("image") : ""); break;
        case "renameFile": logAction = "✏️ Renaming: " + pathArg + " → " + args.get("new_name"); break;
        case "moveFile": logAction = "📦 Moving: " + pathArg + " → " + args.get("destination"); break;
        case "copyFile": logAction = "📄 Copying: " + pathArg + " → " + args.get("destination"); break;
        case "delegate": logAction = "🤖 Delegating to " + (args.get("agent") != null ? args.get("agent") : "a worker") + ": " + shortText(args.get("task"), 80); break;
        case "proposePlan": logAction = "🗺️ Proposing the plan"; break;
        case "finish": logAction = "🏁 Checking the stop gates"; break;
      }

      Map<String, Object> toolCallMsg = new HashMap<>();
      toolCallMsg.put("type", "tool_call");
      toolCallMsg.put("tool", name);
      toolCallMsg.put("args", args);
      toolCallMsg.put("log", logAction);
      if (depth > 0) toolCallMsg.put("sub", true);
      AetherWebSocket.sendToSession(httpSessionId, toolCallMsg);
      sendStatus(httpSessionId, "tool", logAction.length() > 80 ? logAction.substring(0, 80) + "…" : logAction);

      String result = "Error executing tool.";
      boolean toolError = false;
      try {
        // Existing files must be modified via patchFile: auto-reject overwrites without user interaction
        // and hand the model the current content so it can patch instead
        if (("writeFile".equals(name) || "createFile".equals(name)) && fileExists)
        {
          String existing = "";
          try { existing = AITools.readFile(session, pathArg); } catch (Exception re) {}
          throw new Exception("Rejected by system: file '" + pathArg + "' already exists. Use the patchFile tool to modify it instead. Current content of the file:\n" + existing);
        }

        // Declarative policy first (agent rules, "always allow" answers of this run, writes outside the
        // project); without rules every mutating tool asks, exactly as before
        String decision = RunPolicy.decide(session, name, pathArg);
        if (RunPolicy.DENY.equals(decision)) throw new Exception("Denied by the agent's tool policy: " + name + (pathArg != null ? " on " + pathArg : "") + " is not allowed. Choose another approach or ask the user.");
        if (RunPolicy.ASK.equals(decision))
        {
          // Register the queue BEFORE asking the UI, so an instant answer cannot be lost
          SynchronousQueue<Object[]> queue = new SynchronousQueue<>();
          permissionQueues.put(httpSessionId, queue);

          Map<String, Object> req = new HashMap<>();
          req.put("type", "permission_request");
          req.put("tool", name);
          req.put("args", args);
          req.put("message", logAction); // Pass the descriptive message for consent
          if (depth > 0) req.put("sub", true);
          if (RunManager.isRun(httpSessionId)) req.put("canAlways", true);
          // Authoritative server-side path resolution so the client opens the right file for review
          if (pathArg != null) req.put("fullpath", AITools.resolveFullPath(session, pathArg));
          AetherWebSocket.sendToSession(httpSessionId, req);

          try {
            Object[] answer = queue.take(); // Blocks until UI responds (a parked virtual thread in a run)
            // "Always allow for this run": later calls of the same tool skip the prompt
            AgentRun permRun = RunManager.get(httpSessionId);
            if (permRun != null && Boolean.TRUE.equals(answer[0]) && answer.length > 2 && Boolean.TRUE.equals(answer[2])) permRun.alwaysAllow.add(name);
            if (!Boolean.TRUE.equals(answer[0]))
            {
              String reason = answer[1] instanceof String ? ((String) answer[1]).trim() : null;
              if (reason != null && !reason.isEmpty()) throw new Exception("Rejected by user with reason: " + reason);
              throw new Exception("Rejected by user.");
            }
          } finally {
            permissionQueues.remove(httpSessionId);
            // Answered (or aborted): drop the buffered card so reconnects don't replay a stale approval
            AetherWebSocket.removeBufferedType(httpSessionId, "permission_request");
          }
        }

        if ("askUser".equals(name))
        {
          // Same blocking round-trip as permissions: register the queue BEFORE asking the UI, so an instant answer cannot be lost
          SynchronousQueue<Object> queue = new SynchronousQueue<>();
          questionQueues.put(httpSessionId, queue);

          Map<String, Object> req = new HashMap<>();
          req.put("type", "user_question");
          req.put("questions", args.get("questions"));
          AetherWebSocket.sendToSession(httpSessionId, req);

          try
          {
            Object answers = queue.take(); // Blocks until the user submits the answer form
            result = formatQuestionAnswers(answers);
          } finally
          {
            questionQueues.remove(httpSessionId);
            // Answered (or aborted): drop the buffered card so reconnects don't replay a stale question
            AetherWebSocket.removeBufferedType(httpSessionId, "user_question");
          }
        }

        switch(name) {
          case "readFile": result = AITools.readFile(session, pathArg); break;
          case "writeFile": AITools.writeFile(session, pathArg, (String)args.get("content")); result = "File written."; break;
          case "patchFile": result = AITools.patchFile(session, pathArg, extractEdits(args)); break;
          case "createFile": AITools.createFile(session, pathArg, (String)args.get("content")); result = "File created."; break;
          case "deleteFile": AITools.deleteFile(session, pathArg); result = "File deleted."; break;
          case "mkdir": AITools.mkdir(session, pathArg); result = "Directory created."; break;
          case "renameFile": AITools.renameFile(session, pathArg, (String)args.get("new_name")); result = "Renamed to " + args.get("new_name") + "."; break;
          case "moveFile": AITools.moveFile(session, pathArg, (String)args.get("destination")); result = "Moved to " + args.get("destination") + "."; break;
          case "copyFile": AITools.copyFile(session, pathArg, (String)args.get("destination")); result = "Copied to " + args.get("destination") + "."; break;
          case "listFiles": result = json.toJSON(AITools.listFiles(session, pathArg)); break;
          case "grepSearch": result = json.toJSON(AITools.grepSearch(session, (String)session.getAttribute("workingDirectory"), (String)args.get("pattern"))); break;
          case "getProjectStructure": result = json.toJSON(AITools.listAllFiles(session, (String)session.getAttribute("workingDirectory"))); break;
          case "webSearch": result = AITools.webSearch((String)args.get("query")); break;
          case "todoWrite": result = todoWrite(session, args); break;
          case "runCommand": result = AITools.runCommand(session, (String)args.get("command"), asInt(args.get("timeout_seconds"))); break;
          case "subTask": result = runSubAgent(session, httpSessionId, model, thinkingMode, args); break;
          case "delegate": result = runSubAgent(session, httpSessionId, model, thinkingMode, delegateArgs(args)); break;
          case "proposePlan": result = proposePlan(session, httpSessionId, args); break;
          case "finish": result = StopGates.finish(session, httpSessionId, args); break;
          case "generateImage": case "editImage": result = runImageTool(session, httpSessionId, name, args); break;
          case "generateVideo": case "generate3d": result = runMediaTool(session, httpSessionId, name, args); break;
        }
      }
      catch (InterruptedException ie) { throw ie; } // user stop: propagate so the whole turn (incl. sub-agents) ends
      catch (Exception e)
      {
        // A closed stream after a user stop must not resume the model as a tool error
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("stopped");
        result = "Error: " + e.getMessage(); toolError = true;
      }

      // Reality audit: record what actually ran (sub-agent work included) so completion claims can be
      // checked against actions; failed calls stay visible in the ledger but don't validate a claim
      if (!"todoWrite".equals(name))
      {
        turnToolLedger.computeIfAbsent(httpSessionId, k -> Collections.synchronizedList(new ArrayList<>())).add(name + (toolError ? "(failed)" : ""));
        if (!toolError) actionsSinceTodoWrite.merge(httpSessionId, 1, Integer::sum);
      }

      AgentRun toolRun = RunManager.get(httpSessionId);
      if (toolRun != null)
      {
        // What the run changed, for the reviewer and the Changes view
        if (!toolError && pathArg != null && RunPolicy.isMutating(name) && !"runCommand".equals(name)) toolRun.changedFiles.add(AITools.resolveFullPath(session, pathArg));
        if (!toolError && args.get("destination") instanceof String) toolRun.changedFiles.add(AITools.resolveFullPath(session, (String) args.get("destination")));
        // Stuck detection: the same failing call three times means retrying will not help
        if (toolError)
        {
          String key = name + "|" + String.valueOf(args.get("path")) + "|" + String.valueOf(args.get("command")) + "|" + (result.length() > 120 ? result.substring(0, 120) : result);
          int n = toolRun.errorCounts.merge(key, 1, Integer::sum);
          if (n >= 3) result += "\n\nSTUCK: this exact call has now failed " + n + " times. Do not retry it again. Call askUser and explain in one sentence why you are stuck and what you need.";
        }
      }

      Map<String, Object> toolResultMsg = new HashMap<>();
      toolResultMsg.put("type", "tool_result");
      toolResultMsg.put("tool", name);
      if (depth > 0) toolResultMsg.put("sub", true);
      if (pathArg != null) toolResultMsg.put("path", AITools.resolveFullPath(session, pathArg));

      String summary;
      if (toolError)
      {
        // Keep the full result for the LLM/history, but don't flood the chat display with long payloads
        String display = result.length() > 300 ? result.substring(0, 300) + "…" : result;
        summary = "❌ " + display;
        toolResultMsg.put("error", display);
      }
      else
      {
        summary = "✅ Tool responded";
        if (result != null) {
            if (name.equals("askUser")) {
                summary = "✅ Answers sent to the AI";
            } else if (name.equals("todoWrite") || name.equals("generateImage") || name.equals("editImage") || name.equals("generateVideo") || name.equals("generate3d")) {
                summary = "✅ " + result;
            } else if (name.equals("runCommand")) {
                String firstLine = result.contains("\n") ? result.substring(0, result.indexOf('\n')) : result;
                summary = "✅ " + firstLine + " (" + result.length() + " chars)";
            } else if (name.equals("listFiles") || name.equals("getProjectStructure")) {
                try {
                    Object[] items = (Object[]) json.parse(new JSON.ReaderSource(new java.io.StringReader(result)));
                    int dirs = 0;
                    int files = 0;
                    for (Object o : items) {
                        Map<String, Object> m = (Map<String, Object>) o;
                        if (Integer.valueOf(1).equals(m.get("type"))) dirs++; else files++;
                    }
                    summary = "✅ Returned " + dirs + " directories and " + files + " files.";
                } catch (Exception e) { summary = "✅ Returned " + result.length() + " bytes."; }
            } else {
                summary = "✅ Returned " + (result.length() > 1024 ? (result.length()/1024) + "kb" : result.length() + " bytes.");
            }
        }
      }

      toolResultMsg.put("result", summary);
      AetherWebSocket.sendToSession(httpSessionId, toolResultMsg);

      Map<String, Object> toolLog = new HashMap<>();
      toolLog.put("role", "tool");
      toolLog.put("tool", name);
      toolLog.put("tool_call_id", callId);
      toolLog.put("content", result);
      toolLog.put("time", System.currentTimeMillis());
      toolLog.put("log_action", logAction);
      toolLog.put("log_summary", summary);
      if (toolError) toolLog.put("error", true);
      history.add(toolLog);
      if (useHistory) saveChatSession(session, history);
  }

  // todoWrite tool: replace the session's task list, persist it on the chat session, and push it to the UI.
  // Completion claims are audited first: an item newly marked done with NO tool executed since the last
  // accepted update is a fabrication (weak models do this constantly) and the whole update is rejected.
  @SuppressWarnings("unchecked")
  private static String todoWrite(HttpSession session, Map<String, Object> args) throws Exception
  {
    List<Map<String, Object>> todos = new ArrayList<>();
    Object todosObj = args.get("todos");
    Iterable<?> iter = null;
    if (todosObj instanceof Object[]) iter = Arrays.asList((Object[]) todosObj);
    else if (todosObj instanceof Iterable) iter = (Iterable<?>) todosObj;
    if (iter != null) for (Object o : iter) if (o instanceof Map) todos.add((Map<String, Object>) o);

    String httpSessionId = session.getId();
    List<Map<String, Object>> previous = (List<Map<String, Object>>) session.getAttribute("todoList");
    List<String> newlyDone = new ArrayList<>();
    for (Map<String, Object> t : todos)
    {
      if (!"done".equals(t.get("status"))) continue;
      String key = todoKey(t);
      boolean wasDone = false;
      if (previous != null) for (Map<String, Object> p : previous) if (todoKey(p).equals(key)) { wasDone = "done".equals(p.get("status")); break; }
      if (!wasDone) newlyDone.add(String.valueOf(t.get("content")));
    }

    if (!newlyDone.isEmpty() && actionsSinceTodoWrite.getOrDefault(httpSessionId, 0) == 0)
    {
      todoRejectedTurn.add(httpSessionId);
      List<String> ledger = turnToolLedger.get(httpSessionId);
      throw new Exception("Todo update REJECTED — claims must match actions. You marked " + newlyDone
        + " as done, but no tool has executed since the last todo update"
        + (ledger == null || ledger.isEmpty() ? " (no tools at all this turn)" : " (tools this turn: " + ledger + ")")
        + ". First DO the work with real tool calls (patchFile/writeFile/createFile/runCommand), verify it (readFile), THEN mark the item done.");
    }

    actionsSinceTodoWrite.put(httpSessionId, 0);
    todoRejectedTurn.remove(httpSessionId);

    session.setAttribute("todoList", todos);
    saveTodos(session, todos);
    broadcastTodos(session);
    int done = 0;
    for (Map<String, Object> t : todos) if ("done".equals(t.get("status"))) done++;
    return "Todo list updated: " + todos.size() + " items, " + done + " done. Re-check the remaining items and verify each one (readFile/listFiles/runCommand) before marking it done.";
  }

  // Stable identity for matching todo items across updates: prefer the id, fall back to the text
  private static String todoKey(Map<String, Object> t)
  {
    Object id = t.get("id");
    if (id != null && !String.valueOf(id).trim().isEmpty()) return "id:" + String.valueOf(id).trim();
    return "c:" + String.valueOf(t.get("content")).trim();
  }

  // End-of-turn reality check: returns a corrective instruction when unfinished items remain (bounded
  // by MAX_TODO_NUDGES per turn), or null when the turn may end normally
  @SuppressWarnings("unchecked")
  private static String pendingTodoNudge(HttpSession session, String httpSessionId)
  {
    if (todoNudges.getOrDefault(httpSessionId, 0) >= MAX_TODO_NUDGES) return null;
    List<Map<String, Object>> todos = (List<Map<String, Object>>) session.getAttribute("todoList");
    if (todos == null || todos.isEmpty()) return null;

    // A rejected completion claim widens the check to pending items: the model wanted them done but did nothing
    boolean rejected = todoRejectedTurn.contains(httpSessionId);
    List<String> unfinished = new ArrayList<>();
    for (Map<String, Object> t : todos)
    {
      String status = String.valueOf(t.get("status"));
      if ("in_progress".equals(status) || "verify".equals(status) || (rejected && "pending".equals(status))) unfinished.add("- " + t.get("content") + " [" + status + "]");
    }
    if (unfinished.isEmpty()) return null;

    todoNudges.merge(httpSessionId, 1, Integer::sum);
    List<String> ledger = turnToolLedger.get(httpSessionId);
    return "AUTO-CHECK (server audit, not the user): your turn ended but the todo list still has unfinished items:\n"
      + String.join("\n", unfinished)
      + "\nTools that actually executed this turn: " + (ledger == null || ledger.isEmpty() ? "NONE" : String.valueOf(ledger))
      + ".\nDo not summarize or claim completion. CONTINUE NOW: take the first unfinished item, execute it with real tool calls (patchFile/writeFile/createFile/runCommand), verify the result by reading it back, then call todoWrite with the honest statuses. If an item truly cannot be done, set it back to pending and state the blocker in one sentence.";
  }

  // Persist the todo list on the current chat session map in aether.ing.json
  @SuppressWarnings("unchecked")
  private static void saveTodos(HttpSession session, List<Map<String, Object>> todos)
  {
    String workingDir = (String) session.getAttribute("workingDirectory");
    String sessionId = (String) session.getAttribute("currentChatSessionId");
    if (workingDir == null || sessionId == null) return;
    try
    {
      atomicConfigWrite(session, workingDir + "/aether.ing.json", (s, config) -> {
        Object[] sessionsArr = (Object[]) config.get("sessions");
        if (sessionsArr == null) return;
        for (Object sess : sessionsArr)
        {
          Map<String, Object> m = (Map<String, Object>) sess;
          if (sessionId.equals(m.get("id"))) { m.put("todos", todos); break; }
        }
      });
    } catch (Exception e) { e.printStackTrace(); }
  }

  public static void broadcastTodos(HttpSession session)
  {
    Object todos = session.getAttribute("todoList");
    Map<String, Object> msg = new HashMap<>();
    msg.put("type", "todo_update");
    msg.put("todos", todos != null ? todos : new ArrayList<>());
    AetherWebSocket.sendToSession(session.getId(), msg);
  }

  private static Integer asInt(Object o)
  {
    return o instanceof Number ? ((Number) o).intValue() : null;
  }

  // subTask tool: nested agent loop on the CURRENT thread so stop/interrupt semantics stay truthful.
  // Depth is hard-capped at 1: the nested tool list never contains subTask.
  private static final int SUB_MAX_ROUNDS = 12;

  @SuppressWarnings("unchecked")
  private static String runSubAgent(HttpSession session, String httpSessionId, String model, String thinkingMode, Map<String, Object> args) throws Exception
  {
    String prompt = args.get("prompt") instanceof String ? (String) args.get("prompt") : "";
    if (prompt.trim().isEmpty()) return "Error: subTask needs a fully standalone prompt.";

    // Optional "agentId" or "agentId:commandId" target; default is the delegating turn's agent
    String agentRef = args.get("agent") instanceof String ? ((String) args.get("agent")).trim() : "";
    String subAgentId = null, subCommandId = null;
    if (!agentRef.isEmpty())
    {
      String[] parts = agentRef.split(":", 2);
      subAgentId = parts[0];
      if (parts.length > 1) subCommandId = parts[1];
    }

    Agent target;
    if (subAgentId != null)
    {
      target = agents.get(subAgentId);
      if (target == null) return "Error: unknown agent '" + subAgentId + "' for subTask.";
    }
    else
    {
      String currentId = (String) session.getAttribute("currentAgentId");
      target = currentId != null ? agents.get(currentId) : null;
      if (target == null) target = agents.get("general");
      if (target == null) return "Error: no agent available for subTask.";
    }

    String systemPrompt = target.getPrompt() + "\n\nYou are a sub-agent executing ONE delegated task. Work step by step, verify your results, then reply with a concise final report of what you did, found, and changed — your final message is returned to the delegating agent.";
    String userPrompt = prompt;
    if (subCommandId != null && target.getCommands() != null)
    {
      for (AgentCommand c : target.getCommands())
      {
        if (subCommandId.equals(c.getId()))
        {
          Map<String, String> ctx = buildContextMap(session, null, null);
          ctx.put("USER_INPUT", prompt);
          userPrompt = expandPrompt(c.getPrompt(), ctx);
          break;
        }
      }
    }

    List<String> subTools = target.getTools() != null ? new ArrayList<>(target.getTools()) : new ArrayList<>();
    subTools.remove("subTask"); // hard depth limit: a sub-agent cannot delegate further
    subTools.remove("delegate");
    subTools.remove("finish");
    subTools.remove("proposePlan");
    if (subTools.contains("runCommand") && !AITools.isSshWorkingDir(session)) subTools.remove("runCommand");

    List<Map<String, Object>> subHistory = new ArrayList<>();
    Map<String, Object> userMsg = new HashMap<>();
    userMsg.put("role", "user");
    userMsg.put("content", userPrompt);
    subHistory.add(userMsg);

    for (int round = 0; round < SUB_MAX_ROUNDS; round++)
    {
      // The sub-agent draws from the same per-turn round budget as the main loop
      int total = activeAiRounds.merge(httpSessionId, 1, Integer::sum);
      if (total > MAX_ROUNDS) return "Sub-task aborted: the turn's total round limit (" + MAX_ROUNDS + ") was reached.";

      RunManager.forwardSteersToSub(httpSessionId, subHistory);
      Object[] toolCalls = subTurn(httpSessionId, model, thinkingMode, systemPrompt, subTools, subHistory);
      if (toolCalls.length == 0)
      {
        for (int i = subHistory.size() - 1; i >= 0; i--)
        {
          Map<String, Object> m = subHistory.get(i);
          if ("assistant".equals(m.get("role")))
          {
            String report = String.valueOf(m.get("content"));
            return report.length() > 8192 ? report.substring(0, 8192) + "\n[... report truncated ...]" : report;
          }
        }
        return "(The sub-task finished without a report.)";
      }
      for (Object tcObj : toolCalls)
      {
        Map<String, Object> tc = (Map<String, Object>) tcObj;
        Map<String, Object> fn = (Map<String, Object>) tc.get("function");
        executeSingleTool(session, httpSessionId, subHistory, (String) fn.get("name"), (Map<String, Object>) fn.get("arguments"), (String) tc.get("id"), model, thinkingMode, false, 1);
      }
    }
    return "Sub-task stopped after " + SUB_MAX_ROUNDS + " rounds without finishing. Partial work may exist; re-check the todo list and files.";
  }

  // One synchronous LLM turn for a sub-agent: same chat-completions streaming, but content is mirrored
  // to the user through the tool-progress card instead of chat_delta. Appends the assistant message to
  // subHistory and returns the assembled tool calls (empty when the turn is final).
  @SuppressWarnings("unchecked")
  private static Object[] subTurn(String httpSessionId, String model, String thinkingMode, String systemPrompt, List<String> toolNames, List<Map<String, Object>> subHistory) throws Exception
  {
    LlmProviders.Target target = LlmProviders.resolve(model);
    if (target == null) throw new Exception("No LLM provider found for model '" + model + "'");
    Map<String, Object> request = new HashMap<>();
    request.put("model", target.model);
    request.put("stream", true);
    if (thinkingMode != null) request.put("reasoning_effort", "off".equals(thinkingMode) ? "none" : thinkingMode);
    List<Map<String, Object>> tools = getToolsDefinition(toolNames);
    if (!tools.isEmpty()) request.put("tools", tools);

    List<Map<String, Object>> messages = new ArrayList<>();
    messages.add(Map.of("role", "system", "content", systemPrompt));
    for (Map<String, Object> m : subHistory)
    {
      String role = String.valueOf(m.get("role"));
      Map<String, Object> msg = new HashMap<>();
      msg.put("role", role);
      msg.put("content", m.get("content") != null ? m.get("content") : "");
      if (m.containsKey("tool_calls")) msg.put("tool_calls", toOpenAiToolCalls(m.get("tool_calls")));
      if ("tool".equals(role) && m.get("tool_call_id") != null) msg.put("tool_call_id", m.get("tool_call_id"));
      messages.add(msg);
    }
    request.put("messages", messages);

    HttpRequest.Builder subReqBuilder = HttpRequest.newBuilder().uri(URI.create(target.url + "/v1/chat/completions")).timeout(java.time.Duration.ofMinutes(5)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.toJSON(request)));
    if (!target.apiKey.isEmpty()) subReqBuilder.header("Authorization", "Bearer " + target.apiKey);
    HttpRequest httpRequest = subReqBuilder.build();
    HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() != 200)
    {
      String errBody = "";
      try { errBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8); } catch (Exception ignore) {}
      if (errBody.length() > 200) errBody = errBody.substring(0, 200);
      throw new Exception("Sub-agent HTTP " + response.statusCode() + (errBody.isEmpty() ? "" : ": " + errBody));
    }

    InputStream bodyStream = response.body();
    activeAiStreams.put(httpSessionId, bodyStream);
    StringBuilder content = new StringBuilder();
    LinkedHashMap<String, Map<String, Object>> toolAccum = new LinkedHashMap<>();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(bodyStream, StandardCharsets.UTF_8)))
    {
      String line;
      while ((line = reader.readLine()) != null)
      {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("stopped");
        line = line.trim();
        if (!line.startsWith("data:")) continue;
        String payload = line.substring(5).trim();
        if ("[DONE]".equals(payload)) break;
        Map<String, Object> part = (Map<String, Object>) json.parse(new JSON.ReaderSource(new java.io.StringReader(payload)));
        Object[] choices = part.get("choices") instanceof Object[] ? (Object[]) part.get("choices") : null;
        if (choices == null || choices.length == 0) continue;
        Map<String, Object> delta = (Map<String, Object>) ((Map<String, Object>) choices[0]).get("delta");
        if (delta == null) continue;

        if (delta.get("tool_calls") instanceof Object[])
        {
          for (Object o : (Object[]) delta.get("tool_calls"))
          {
            Map<String, Object> tc = (Map<String, Object>) o;
            String key = String.valueOf(asLong(tc.get("index")));
            Map<String, Object> acc = toolAccum.computeIfAbsent(key, k -> new HashMap<>());
            if (tc.get("id") instanceof String && !((String) tc.get("id")).isEmpty()) acc.put("id", tc.get("id"));
            Map<String, Object> fn = (Map<String, Object>) tc.get("function");
            if (fn != null)
            {
              if (fn.get("name") instanceof String) acc.put("name", (String) acc.getOrDefault("name", "") + fn.get("name"));
              if (fn.get("arguments") instanceof String) acc.put("args", (String) acc.getOrDefault("args", "") + fn.get("arguments"));
              else if (fn.get("arguments") instanceof Map && !((Map<?, ?>) fn.get("arguments")).isEmpty()) acc.put("argsMap", fn.get("arguments"));
            }
          }
        }

        String chunk = delta.get("content") instanceof String ? (String) delta.get("content") : "";
        if (!chunk.isEmpty())
        {
          content.append(chunk);
          // The user watches the sub-agent work live through the existing streaming progress card
          Map<String, Object> progress = new HashMap<>();
          progress.put("type", "tool_progress");
          progress.put("tool", "subTask");
          progress.put("chars", content.length());
          progress.put("delta", chunk);
          AetherWebSocket.sendToSession(httpSessionId, progress, false);
        }
      }
    }
    finally { activeAiStreams.remove(httpSessionId, bodyStream); }

    List<Map<String, Object>> calls = new ArrayList<>();
    int i = 0;
    for (Map<String, Object> acc : toolAccum.values())
    {
      Map<String, Object> argsMap = new HashMap<>();
      if (acc.get("argsMap") instanceof Map) argsMap = (Map<String, Object>) acc.get("argsMap");
      else if (acc.get("args") instanceof String)
      {
        argsMap = parseToolArgs((String) acc.get("args"), "sub-agent " + acc.get("name"));
      }
      Map<String, Object> fn = new HashMap<>();
      fn.put("name", acc.getOrDefault("name", ""));
      fn.put("arguments", argsMap);
      Map<String, Object> call = new HashMap<>();
      call.put("id", acc.get("id") != null ? acc.get("id") : "sub_call_" + i);
      call.put("function", fn);
      calls.add(call);
      i++;
    }

    Map<String, Object> aiMsg = new HashMap<>();
    aiMsg.put("role", "assistant");
    aiMsg.put("content", content.toString());
    if (!calls.isEmpty()) aiMsg.put("tool_calls", calls.toArray());
    subHistory.add(aiMsg);
    return calls.toArray();
  }

  // askUser answers from the client ([{question, answer}, ...]) formatted as a tool result the model can read
  @SuppressWarnings("unchecked")
  private static String formatQuestionAnswers(Object answersObj)
  {
    StringBuilder sb = new StringBuilder("The user answered:\n");
    Iterable<?> iter = null;
    if (answersObj instanceof Object[]) iter = Arrays.asList((Object[]) answersObj);
    else if (answersObj instanceof Iterable) iter = (Iterable<?>) answersObj;

    boolean any = false;
    if (iter != null) for (Object o : iter)
    {
      if (!(o instanceof Map)) continue;
      Map<String, Object> a = (Map<String, Object>) o;
      String question = a.get("question") != null ? a.get("question").toString() : "";
      String answer = a.get("answer") != null ? a.get("answer").toString().trim() : "";
      sb.append("Q: ").append(question).append("\nA: ").append(answer.isEmpty() ? "(no answer given)" : answer).append("\n");
      any = true;
    }
    if (!any) sb.append("(The user gave no answers. Proceed with your best judgment.)");
    return sb.toString();
  }

  // patchFile arguments: prefer the edits array, fall back to legacy single old_text/new_text
  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> extractEdits(Map<String, Object> args)
  {
    List<Map<String, Object>> edits = new ArrayList<>();
    Object editsObj = args.get("edits");
    Iterable<?> iter = null;
    if (editsObj instanceof Object[]) iter = Arrays.asList((Object[]) editsObj);
    else if (editsObj instanceof Iterable) iter = (Iterable<?>) editsObj;
    if (iter != null) for (Object o : iter) if (o instanceof Map) edits.add((Map<String, Object>) o);

    if (edits.isEmpty() && args.get("old_text") instanceof String)
    {
      Map<String, Object> edit = new HashMap<>();
      edit.put("old_text", args.get("old_text"));
      edit.put("new_text", args.get("new_text") != null ? args.get("new_text") : "");
      edits.add(edit);
    }
    return edits;
  }

  private static void generateTitleIfNeeded(HttpSession session, List<Map<String, Object>> history)
  {
    String sessionId = (String) session.getAttribute("currentChatSessionId");
    String workingDir = (String) session.getAttribute("workingDirectory");
    if (sessionId == null || workingDir == null) return;

    boolean needsTitle = true;
    String configPath = workingDir + "/aether.ing.json";
    configLock(configPath).lock();
    try
    {
      Map<String, Object> config = (Map<String, Object>) json.parse(new JSON.ReaderSource(new java.io.StringReader(AITools.readFile(session, configPath))));
      Object[] sessArr = (Object[]) config.get("sessions");
      if (sessArr != null) for (Object o : sessArr)
      {
        Map<String, Object> s = (Map<String, Object>) o;
        if (sessionId.equals(s.get("id")) && s.get("title") != null) { needsTitle = false; break; }
      }
    } catch (Exception e) {
      // Config unreadable (missing file, no permission): a generated title could never be saved anyway,
      // so don't burn an LLM call on every turn
      needsTitle = false;
    } finally {
      configLock(configPath).unlock();
    }

    if (needsTitle) generateTitle(session, history);
  }

  private static void runPsychologist(HttpSession session, String lastUserMsg)
  {
    Agent psychAgent = agents.get("internal-psychologist");
    if (psychAgent == null || lastUserMsg == null || lastUserMsg.trim().isEmpty()) return;

    // The psychologist prompt only consumes USER_PERSONALITY and USER_INPUT, so a full buildContextMap
    // re-read AGENTS.md, the open file and a whole recursive listing per turn just to discard them
    Map<String, String> context = new HashMap<>();
    String workingDir = (String) session.getAttribute("workingDirectory");
    if (workingDir != null) try
    {
      String configContent = AITools.readFile(session, workingDir + "/aether.ing.json");
      Map<String, Object> config = (Map<String, Object>) json.parse(new JSON.ReaderSource(new java.io.StringReader(configContent)));
      if (config.containsKey("personality")) context.put("USER_PERSONALITY", config.get("personality").toString());
    } catch (Exception e) {}
    context.put("USER_INPUT", lastUserMsg);
    String psychPrompt = expandPrompt(psychAgent.getPrompt(), context);

    final String httpSessionId = session.getId();
    List<Map<String, String>> messages = new ArrayList<>();
    messages.add(Map.of("role", "system", "content", psychPrompt));
    messages.add(Map.of("role", "user", "content", "Analyze interaction and update profile."));
    internalQuery(httpSessionId, "personality analysis", messages, personality ->
    {
      HttpSession liveSession = SessionTracker.getSessionById(httpSessionId);
      if (liveSession != null) updatePersonality(liveSession, personality);
    });
  }

  private static void updatePersonality(HttpSession session, String personality)
  {
    String workingDir = (String) session.getAttribute("workingDirectory");
    if (workingDir == null) return;
    try
    {
      String configPath = workingDir + "/aether.ing.json";
      atomicConfigWrite(session, configPath, (s, config) -> {
        config.put("personality", personality);
      });
    } catch (Exception e) { e.printStackTrace(); }
  }

  private static String shortText(Object o, int max)
  {
    String t = o != null ? String.valueOf(o) : "";
    return t.length() > max ? t.substring(0, max) + "…" : t;
  }

  // delegate{agent, task} is subTask{agent, prompt, description} with a clearer contract for managers
  private static Map<String, Object> delegateArgs(Map<String, Object> args)
  {
    Map<String, Object> sub = new HashMap<>();
    sub.put("prompt", args.get("task"));
    sub.put("description", shortText(args.get("task"), 60));
    if (args.get("agent") instanceof String) sub.put("agent", args.get("agent"));
    return sub;
  }

  // proposePlan tool: records the Definition of Done and the todo list; with the run's plan gate on it
  // waits until the user approves, edits or rejects the plan on any device
  @SuppressWarnings("unchecked")
  private static String proposePlan(HttpSession session, String httpSessionId, Map<String, Object> args) throws Exception
  {
    List<String> dod = stringList(args.get("dod"));
    List<String> items = stringList(args.get("todos"));
    AgentRun run = RunManager.get(httpSessionId);
    if (run != null && run.planGate)
    {
      Map<String, Object> req = new HashMap<>();
      req.put("type", "plan_request");
      req.put("dod", dod);
      req.put("todos", items);
      req.put("summary", args.get("summary") != null ? args.get("summary") : "");
      Map<String, Object> answer = RunManager.awaitDecision(run, req);
      String decision = String.valueOf(answer.get("decision"));
      if ("reject".equals(decision)) return "The user REJECTED the plan" + (answer.get("note") != null ? ": " + answer.get("note") : ".") + " Revise it and call proposePlan again.";
      if (answer.get("dod") != null) dod = stringList(answer.get("dod"));
      if (answer.get("todos") != null) items = stringList(answer.get("todos"));
      if (answer.get("note") instanceof String && !((String) answer.get("note")).trim().isEmpty()) dod.add("User note: " + answer.get("note"));
    }
    if (run != null) run.definitionOfDone = dod;
    List<Map<String, Object>> todos = new ArrayList<>();
    for (int i = 0; i < items.size(); i++)
    {
      Map<String, Object> t = new HashMap<>();
      t.put("id", "t" + (i + 1));
      t.put("content", items.get(i));
      t.put("status", i == 0 ? "in_progress" : "pending");
      todos.add(t);
    }
    session.setAttribute("todoList", todos);
    saveTodos(session, todos);
    broadcastTodos(session);
    actionsSinceTodoWrite.put(httpSessionId, 0);
    return "Plan " + (run != null && run.planGate ? "APPROVED by the user" : "recorded") + ".\nDefinition of Done:\n- " + String.join("\n- ", dod) + "\nTodo list (ids t1..t" + todos.size() + ", first item now in_progress):\n- " + String.join("\n- ", items) + "\nExecute it item by item; keep the todo list current with todoWrite; call finish when everything is done and verified.";
  }

  private static List<String> stringList(Object o)
  {
    List<String> out = new ArrayList<>();
    Iterable<?> iter = o instanceof Object[] ? Arrays.asList((Object[]) o) : o instanceof Iterable ? (Iterable<?>) o : null;
    if (iter != null) for (Object x : iter) if (x != null && !String.valueOf(x).trim().isEmpty()) out.add(x instanceof Map ? String.valueOf(((Map<?, ?>) x).get("content")) : String.valueOf(x).trim());
    return out;
  }
}
