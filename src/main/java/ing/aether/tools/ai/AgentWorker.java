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
  private static final int MAX_ROUNDS = 40;

  // Reality audit for todo-capable agents: weak models claim work is done without executing anything.
  // The server keeps a per-turn ledger of tools that actually ran and checks completion claims against it.
  private static final Map<String, List<String>> turnToolLedger = new ConcurrentHashMap<>();
  private static final Map<String, Integer> actionsSinceTodoWrite = new ConcurrentHashMap<>();
  private static final Map<String, Integer> todoNudges = new ConcurrentHashMap<>();
  private static final Set<String> todoRejectedTurn = ConcurrentHashMap.newKeySet();
  private static final int MAX_TODO_NUDGES = 2;

  public static void handlePermission(String httpSessionId, boolean granted)
  {
    handlePermission(httpSessionId, granted, null);
  }

  public static void handlePermission(String httpSessionId, boolean granted, String reason)
  {
    SynchronousQueue<Object[]> queue = permissionQueues.get(httpSessionId);
    if (queue != null)
    {
      // Bounded offer: put() would block this WebSocket thread forever if the waiting AI thread is already gone
      try
      {
        if (!queue.offer(new Object[] { granted, reason }, 3, TimeUnit.SECONDS)) System.out.println("AgentWorker: Permission answer dropped, no waiting tool call for session " + httpSessionId);
      } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
  }

  public static void handleQuestionAnswers(String httpSessionId, Object answers)
  {
    SynchronousQueue<Object> queue = questionQueues.get(httpSessionId);
    if (queue != null)
    {
      // Bounded offer: put() would block this WebSocket thread forever if the waiting AI thread is already gone
      try
      {
        if (!queue.offer(answers != null ? answers : new Object[0], 3, TimeUnit.SECONDS)) System.out.println("AgentWorker: Question answers dropped, no waiting tool call for session " + httpSessionId);
      } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
  }

  public static boolean isProcessing(String httpSessionId)
  {
    return activeAiThreads.containsKey(httpSessionId);
  }

  public static void interruptProcessing(String httpSessionId)
  {
    Thread t = activeAiThreads.remove(httpSessionId);
    if (t != null)
    {
      System.out.println("AgentWorker: Interrupting AI task for session " + httpSessionId);
      t.interrupt();
    }
    // Close the response stream: a thread blocked in readLine() does not react to interrupt
    InputStream s = activeAiStreams.remove(httpSessionId);
    if (s != null) try { s.close(); } catch (Exception ignore) {}
    // Also release any waiting permissions with 'false'
    handlePermission(httpSessionId, false);
    // And any waiting askUser question with empty answers
    handleQuestionAnswers(httpSessionId, null);
  }

  private static java.util.concurrent.locks.ReentrantLock configLock(String configPath)
  {
    return configLocks.computeIfAbsent(configPath, k -> new java.util.concurrent.locks.ReentrantLock());
  }

  // Atomic read-modify-write: reads file once, applies fn, writes once
  @SuppressWarnings("unchecked")
  private static void atomicConfigWrite(HttpSession session, String configPath, java.util.function.BiConsumer<HttpSession, Map<String, Object>> fn) throws Exception
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

    // Prepare context
    Map<String, String> contextMap = buildContextMap(session, contextFiles, selectedText);
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

    // Rewritten by the psychologist on almost every turn, so it sits last, behind the open file
    String personality = contextMap.get("USER_PERSONALITY");
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
    runPsychologist(session, userMessage);

  }

  // Server-side cap for attached terminal output: head + tail up to 16KB total
  private static String capTerminal(String s)
  {
    int max = 16384;
    if (s.length() <= max) return s;
    return s.substring(0, 2048) + "\n[... " + (s.length() - max) + " chars truncated ...]\n" + s.substring(s.length() - (max - 2048));
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
    String httpSessionId = session.getId();
    if (isProcessing(httpSessionId))
    {
      AetherWebSocket.notify(httpSessionId, "Cannot switch chats while the AI is working.");
      return;
    }
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
    if (deletingCurrent && isProcessing(httpSessionId))
    {
      AetherWebSocket.notify(httpSessionId, "Cannot delete the active chat while the AI is working.");
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
    if (isProcessing(httpSessionId))
    {
      AetherWebSocket.notify(httpSessionId, "Cannot clear chats while the AI is working.");
      return;
    }
    String workingDir = (String) session.getAttribute("workingDirectory");
    if (workingDir == null) return;

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
  private static void internalQuery(String httpSessionId, String task, List<Map<String, String>> messages, java.util.function.Consumer<String> onResult)
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
        StringBuilder sb = new StringBuilder();
        for (String f : allFiles) sb.append("- ").append(AITools.resolveRelativePath(session, f)).append("\n");
        context.put("PROJECT_STRUCTURE", sb.toString());
      } catch (Exception e) {}
    }

    String activeFile = (String) session.getAttribute("lastFilePath");
    if (activeFile != null)
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
        // Runaway-loop guard: each LLM round consumes from the per-turn budget shared with sub-agents
        int rounds = activeAiRounds.merge(httpSessionId, 1, Integer::sum);
        if (rounds > MAX_ROUNDS)
        {
          AetherWebSocket.sendToSession(httpSessionId, Map.of("type", "chat_error", "error", "Round limit (" + MAX_ROUNDS + ") reached — stopping the tool loop to protect against a runaway turn."));
          AetherWebSocket.sendToSession(httpSessionId, Map.of("type", "chat_done", "responseType", responseType != null ? responseType : "chat"));
          return;
        }

        // The selected model carries its provider ("Provider::model"); a null target means
        // the provider was removed after selection, which must surface, not misroute
        LlmProviders.Target target = LlmProviders.resolve(model);
        if (target == null)
        {
          AetherWebSocket.sendToSession(httpSessionId, Map.of("type", "chat_error", "error", "No LLM provider found for model '" + model + "'. Pick another model, or ask the admin to check the provider list in Settings."));
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
          Map<String, Object> msg = new HashMap<>();
          msg.put("role", role);
          msg.put("content", m.get("content") != null ? m.get("content") : "");
          if (m.containsKey("tool_calls")) msg.put("tool_calls", toOpenAiToolCalls(m.get("tool_calls")));
          if ("tool".equals(role))
          {
            // Tool results need their call id; legacy entries without one are downgraded to user messages
            if (m.get("tool_call_id") != null) msg.put("tool_call_id", m.get("tool_call_id"));
            else { msg.put("role", "user"); msg.put("content", "Tool result: " + msg.get("content")); }
          }
          if ("user".equals(role)) lastUserIdx = messages.size();
          messages.add(msg);
        }
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
        request.put("messages", messages);

        String jsonRequest = json.toJSON(request);
        if (LOG_PROMPT)
        {
          StringBuilder index = new StringBuilder();
          for (Map<String, Object> m : messages) index.append("  [").append(m.get("role")).append("] ").append(String.valueOf(m.get("content")).length()).append(" chars\n");
          System.out.println("=== LLM REQUEST (" + model + ", " + jsonRequest.length() + " chars) ===\n" + index + prettyJson(jsonRequest) + "\n=== END LLM REQUEST ===");
        }
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder().uri(URI.create(target.url + "/v1/chat/completions")).timeout(java.time.Duration.ofMinutes(5)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonRequest));
        if (!target.apiKey.isEmpty()) reqBuilder.header("Authorization", "Bearer " + target.apiKey);
        HttpRequest httpRequest = reqBuilder.build();

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
          return;
        }

        bodyStream = response.body();
        activeAiStreams.put(httpSessionId, bodyStream);

        StringBuilder fullResponse = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        boolean isThinking = false;
        int thinkingSent = 0;

        // Incremental tool-call assembly: call index -> {id, name, args (JSON string fragments)}
        LinkedHashMap<String, Map<String, Object>> toolAccum = new LinkedHashMap<>();
        long startNanos = System.nanoTime();
        long firstTokenNanos = 0;
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
            if (firstTokenNanos == 0) firstTokenNanos = System.nanoTime();

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
              done.put("total_duration", endNanos - startNanos);
              done.put("prompt_eval_count", promptTokens);
              done.put("eval_count", completionTokens);
              done.put("eval_duration", firstTokenNanos > 0 ? endNanos - firstTokenNanos : 0);

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

              if (includeHistory) generateTitleIfNeeded(session, history);
              // Always close the turn so the client leaves the working state, even for an empty response.
              // Turn complete and delivered live: drop the replay buffer so reconnects don't re-feed stats/deltas
              if (AetherWebSocket.sendToSession(httpSessionId, done)) AetherWebSocket.clearBuffer(httpSessionId);
            }
        }
      } catch (Exception e)
      {
        // A user stop arrives as an interrupt or as the response stream being closed under us: end quietly
        boolean stopped = Thread.currentThread().isInterrupted() || e instanceof InterruptedException || e.getCause() instanceof InterruptedException;
        if (!stopped)
        {
          e.printStackTrace();
          if (AetherWebSocket.sendToSession(httpSessionId, Map.of("type", "chat_error", "error", "Error: " + e.getMessage()))) AetherWebSocket.clearBuffer(httpSessionId);
        }
      } finally {
        // Two-arg remove: a follow-up tool round may already have registered its own thread/stream under this key
        if (bodyStream != null) activeAiStreams.remove(httpSessionId, bodyStream);
        activeAiThreads.remove(httpSessionId, Thread.currentThread());
      }
    });
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
        case "renameFile": logAction = "✏️ Renaming: " + pathArg + " → " + args.get("new_name"); break;
        case "moveFile": logAction = "📦 Moving: " + pathArg + " → " + args.get("destination"); break;
        case "copyFile": logAction = "📄 Copying: " + pathArg + " → " + args.get("destination"); break;
      }

      Map<String, Object> toolCallMsg = new HashMap<>();
      toolCallMsg.put("type", "tool_call");
      toolCallMsg.put("tool", name);
      toolCallMsg.put("args", args);
      toolCallMsg.put("log", logAction);
      if (depth > 0) toolCallMsg.put("sub", true);
      AetherWebSocket.sendToSession(httpSessionId, toolCallMsg);

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

        if ("writeFile".equals(name) || "patchFile".equals(name) || "createFile".equals(name) || "deleteFile".equals(name) || "mkdir".equals(name) || "runCommand".equals(name) || "renameFile".equals(name) || "moveFile".equals(name) || "copyFile".equals(name))
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
          // Authoritative server-side path resolution so the client opens the right file for review
          if (pathArg != null) req.put("fullpath", AITools.resolveFullPath(session, pathArg));
          AetherWebSocket.sendToSession(httpSessionId, req);

          try {
            Object[] answer = queue.take(); // Blocks until UI responds
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
            } else if (name.equals("todoWrite")) {
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
}
