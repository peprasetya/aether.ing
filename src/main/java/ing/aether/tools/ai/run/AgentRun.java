package ing.aether.tools.ai.run;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

// One agent run: owned by a user (issSubId), bound to one chat session of one working directory, and
// independent of any browser session. Chat turns are short interactive runs; "Start task" agents are
// autonomous runs that loop until their stop gates pass.
public class AgentRun
{
  public static final String WORKING = "WORKING";
  public static final String WAITING_INPUT = "WAITING_INPUT";
  public static final String REVIEWING = "REVIEWING";
  public static final String PARKED = "PARKED";
  public static final String COMPLETED = "COMPLETED";
  public static final String FAILED = "FAILED";
  public static final String CANCELLED = "CANCELLED";

  public static class Steer
  {
    public final String text;
    public final String mode;
    public final String clientId;
    public final long time = System.currentTimeMillis();
    public volatile boolean forwardedToSub = false;

    public Steer(String text, String mode, String clientId)
    {
      this.text = text;
      this.mode = mode;
      this.clientId = clientId;
    }
  }

  // Chat history that knows which log sequence it reflects: a device attaching mid-run renders this
  // list and then replays only the events after historySeq, so nothing is shown twice. Static and
  // with a transient run reference: browser sessions hold this list, and Jetty saves sessions to disk
  public static class RunHistory extends CopyOnWriteArrayList<Map<String, Object>>
  {
    private static final long serialVersionUID = 1L;
    private transient AgentRun run;

    public RunHistory(AgentRun run, Collection<Map<String, Object>> initial)
    {
      super(initial);
      this.run = run;
    }

    private void touched()
    {
      AgentRun r = run;
      if (r != null) r.historySeq = r.log != null ? r.log.headSeq() : 0;
    }

    @Override public boolean add(Map<String, Object> e) { boolean r = super.add(e); touched(); return r; }
    @Override public void add(int index, Map<String, Object> e) { super.add(index, e); touched(); }
    @Override public Map<String, Object> set(int index, Map<String, Object> e) { Map<String, Object> r = super.set(index, e); touched(); return r; }
    @Override public boolean remove(Object o) { boolean r = super.remove(o); touched(); return r; }
    @Override public Map<String, Object> remove(int index) { Map<String, Object> r = super.remove(index); touched(); return r; }
  }

  public final String runId;
  public final String issSubId;
  public final String email;
  public final String workingDir;
  public final long created;

  public volatile String chatSessionId;
  public volatile String agentId;
  public volatile String model;
  public volatile String thinkingMode;
  public volatile String behavior = "interactive";
  public volatile String objective = "";
  public volatile String originClientId;
  public volatile boolean includeHistory = true;
  public volatile boolean planGate = false;
  public volatile String reviewerModel;

  public volatile String state = WORKING;
  public volatile String stopReason;
  public volatile long updated;
  public volatile long historySeq = 0;
  public volatile Map<String, Object> pendingRequest;

  public volatile RunSession session;
  public volatile RunEventLog log;

  public final ConcurrentLinkedQueue<Steer> steers = new ConcurrentLinkedQueue<>();
  public volatile boolean interruptRequested = false;
  public volatile boolean pauseRequested = false;

  // Budgets (phase 2): model calls per run, extended by the user when exhausted
  public volatile int roundLimit;
  public volatile int continuations = 0;
  public volatile int maxContinuations = 6;

  // Orchestrator state (phase 3)
  public volatile boolean finished = false;
  public volatile List<String> definitionOfDone = new ArrayList<>();
  public final Set<String> changedFiles = Collections.synchronizedSet(new LinkedHashSet<>());
  public final Map<String, Integer> errorCounts = new ConcurrentHashMap<>();
  public final Set<String> alwaysAllow = ConcurrentHashMap.newKeySet();
  public volatile String gitBase;
  public volatile int todoDone = 0;
  public volatile int todoTotal = 0;

  public AgentRun(String runId, String issSubId, String email, String workingDir, long created)
  {
    this.runId = runId;
    this.issSubId = issSubId;
    this.email = email;
    this.workingDir = workingDir;
    this.created = created;
    this.updated = created;
  }

  public boolean isTerminal()
  {
    return COMPLETED.equals(state) || FAILED.equals(state) || CANCELLED.equals(state);
  }

  public boolean isActive()
  {
    return !isTerminal() && !PARKED.equals(state);
  }

  public boolean isAutonomous()
  {
    return "autonomous".equals(behavior);
  }

  public Map<String, Object> summary()
  {
    Map<String, Object> m = new HashMap<>();
    m.put("runId", runId);
    m.put("chatSessionId", chatSessionId);
    m.put("workingDir", workingDir);
    m.put("agentId", agentId);
    m.put("model", model);
    m.put("behavior", behavior);
    m.put("objective", objective);
    m.put("state", state);
    if (stopReason != null) m.put("stopReason", stopReason);
    m.put("created", created);
    m.put("updated", updated);
    m.put("todoDone", todoDone);
    m.put("todoTotal", todoTotal);
    m.put("headSeq", log != null ? log.headSeq() : 0);
    m.put("needsInput", WAITING_INPUT.equals(state) || PARKED.equals(state));
    if (pendingRequest != null) m.put("pendingType", pendingRequest.get("type"));
    return m;
  }

  // Registry entry: enough to show and resume the run after a server restart
  public Map<String, Object> registryEntry()
  {
    Map<String, Object> m = summary();
    m.put("issSubId", issSubId);
    m.put("email", email);
    m.put("thinkingMode", thinkingMode);
    m.put("planGate", planGate);
    if (reviewerModel != null) m.put("reviewerModel", reviewerModel);
    return m;
  }
}
