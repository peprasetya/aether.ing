package ing.aether.tools.ai.run;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.util.ajax.JSON;

import ing.aether.data.Agent;
import ing.aether.socket.AetherWebSocket;
import ing.aether.tools.ai.AITools;
import ing.aether.tools.ai.AgentWorker;
import ing.aether.tools.ai.LlmProviders;
import jakarta.servlet.http.HttpSession;
import jakarta.websocket.Session;

// The orchestrator's completion gate. "Done" is decided outside the model, cheapest check first:
// 1. todo ledger (every item done), 2. the project's verify command from AGENTS.md (build/tests over
// SSH), 3. a reviewer model's JSON verdict against the Definition of Done and the actual changes,
// 4. optionally the user. A failed gate's reason goes back to the model and the loop continues.
// Also owns the per-run git base ref (never touching the user's HEAD or index) behind the Changes view.
public class StopGates
{
  private static final JSON json = new JSON();
  private static final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
  private static final Pattern VERIFY_LINE = Pattern.compile("(?im)^\\s*verify\\s*:\\s*`?([^`\\n]+?)`?\\s*$");
  private static final Pattern VERIFY_BLOCK = Pattern.compile("(?s)```verify\\s*\\n(.*?)```");
  private static final int REVIEW_CONTEXT_CHARS = 60000;

  @SuppressWarnings("unchecked")
  public static String finish(HttpSession session, String runId, Map<String, Object> args) throws InterruptedException
  {
    AgentRun run = RunManager.get(runId);
    String summary = args.get("summary") != null ? String.valueOf(args.get("summary")) : "";
    if (run == null) return "Finished. Give the user your final summary.";
    Agent agent = AgentWorker.findAgent((String) session.getAttribute("currentAgentId"));
    List<String> failures = new ArrayList<>();

    // 1. Todo ledger
    if (agent == null || agent.stopGate("todos", true))
    {
      List<String> open = new ArrayList<>();
      Object todos = session.getAttribute("todoList");
      if (todos instanceof List) for (Map<String, Object> t : (List<Map<String, Object>>) todos) if (!"done".equals(t.get("status"))) open.add(t.get("content") + " [" + t.get("status") + "]");
      gate(run, "todos", open.isEmpty(), open.isEmpty() ? "All todo items are done." : "Unfinished: " + String.join("; ", open));
      if (!open.isEmpty()) failures.add("TODOS: these items are not done: " + String.join("; ", open));
    }

    // 2. Verify command (build/tests) from AGENTS.md
    String verifyOutput = null;
    if (failures.isEmpty() && (agent == null || agent.stopGate("verify", true)))
    {
      String cmd = verifyCommand(session);
      if (cmd != null && AITools.isSshWorkingDir(session))
      {
        RunManager.emit(runId, Map.of("type", "chat_status", "stage", "tool", "text", "Stop gate: running " + cmd), false);
        try { verifyOutput = AITools.runCommand(session, cmd, 300); }
        catch (Exception e) { verifyOutput = "Error: " + e.getMessage(); }
        boolean ok = verifyOutput != null && verifyOutput.startsWith("Exit code: 0");
        gate(run, "verify", ok, cmd + " → " + firstLine(verifyOutput));
        if (!ok) failures.add("VERIFY: `" + cmd + "` failed:\n" + cap(verifyOutput, 6000));
      }
      else gate(run, "verify", true, cmd == null ? "No verify command in AGENTS.md (add a line 'Verify: <command>'), skipped." : "Project is not on SSH, verify command skipped.");
    }

    // 3. Reviewer model
    if (failures.isEmpty() && (agent == null || agent.stopGate("review", true)) && run.reviewerModel != null && !run.reviewerModel.isEmpty())
    {
      Map<String, Object> verdict = review(session, run, summary, verifyOutput);
      String v = String.valueOf(verdict.get("verdict"));
      boolean ok = "approve".equals(v);
      if (!ok) failures.add("REVIEW (" + run.reviewerModel + ") verdict " + v + ": " + formatIssues(verdict));
    }

    // 4. Human
    if (failures.isEmpty() && agent != null && agent.stopGate("human", false))
    {
      Map<String, Object> req = new HashMap<>();
      req.put("type", "human_gate");
      req.put("message", summary);
      req.put("changes", new ArrayList<>(run.changedFiles));
      Map<String, Object> answer = RunManager.awaitDecision(run, req);
      boolean ok = "accept".equals(answer.get("decision"));
      gate(run, "human", ok, ok ? "Accepted by the user." : "The user asked for changes: " + answer.get("note"));
      if (!ok) failures.add("USER: requested changes: " + answer.get("note"));
    }

    if (failures.isEmpty())
    {
      run.finished = true;
      return "ALL STOP GATES PASSED. The task is complete: now reply with a concise final summary for the user (what changed, file by file, and anything they should check). Do not call more tools.";
    }
    run.continuations++;
    String head = "STOP GATE FAILED — the task is NOT done. Fix these problems with real tool calls, then call finish again:\n- " + String.join("\n- ", failures);
    if (run.continuations >= run.maxContinuations) head += "\n\nYou have used all allowed finish attempts. Stop working now: explain to the user what is still failing and why, and ask how to proceed.";
    return head;
  }

  private static void gate(AgentRun run, String gate, boolean pass, String detail)
  {
    Map<String, Object> m = new HashMap<>();
    m.put("type", "gate_result");
    m.put("gate", gate);
    m.put("pass", pass);
    m.put("detail", detail);
    RunManager.emit(run.runId, m, true);
  }

  public static String verifyCommand(HttpSession session)
  {
    String wd = (String) session.getAttribute("workingDirectory");
    if (wd == null) return null;
    try
    {
      String md = AITools.readFile(session, wd + "/AGENTS.md");
      Matcher b = VERIFY_BLOCK.matcher(md);
      if (b.find()) return b.group(1).trim().replace("\n", " && ");
      Matcher l = VERIFY_LINE.matcher(md);
      if (l.find()) return l.group(1).trim();
    }
    catch (Exception e) {}
    return null;
  }

  // ===== reviewer =====

  @SuppressWarnings("unchecked")
  private static Map<String, Object> review(HttpSession session, AgentRun run, String summary, String verifyOutput)
  {
    RunManager.emit(run.runId, Map.of("type", "review_start", "model", run.reviewerModel), true);
    Map<String, Object> verdict;
    try
    {
      Agent reviewer = AgentWorker.findAgent("internal-reviewer");
      String system = reviewer != null && reviewer.getPrompt() != null ? reviewer.getPrompt() : "You are a strict code reviewer. Judge whether the work satisfies the objective and the Definition of Done. Reply ONLY with JSON: {\"verdict\":\"approve|revise|blocked\",\"issues\":[{\"file\":\"\",\"problem\":\"\",\"severity\":\"high|medium|low\"}],\"dodChecks\":[{\"item\":\"\",\"pass\":true}]}";
      String context = reviewContext(session, run, summary, verifyOutput);
      String raw = complete(run.reviewerModel, system, context, true);
      if (raw == null) raw = complete(run.reviewerModel, system, context, false);
      verdict = raw != null ? (Map<String, Object>) json.parse(new JSON.ReaderSource(new java.io.StringReader(AgentWorker.repairJson(raw)))) : null;
      if (verdict == null || !(verdict.get("verdict") instanceof String)) throw new Exception("reviewer returned no verdict");
    }
    catch (Exception e)
    {
      verdict = new HashMap<>();
      verdict.put("verdict", "blocked");
      verdict.put("issues", new Object[] { Map.of("file", "", "problem", "Reviewer unavailable: " + e.getMessage() + ". Ask the user to review the changes (askUser).", "severity", "high") });
    }
    Map<String, Object> evt = new HashMap<>(verdict);
    evt.put("type", "review_verdict");
    evt.put("model", run.reviewerModel);
    RunManager.emit(run.runId, evt, true);
    return verdict;
  }

  @SuppressWarnings("unchecked")
  private static String reviewContext(HttpSession session, AgentRun run, String summary, String verifyOutput)
  {
    StringBuilder sb = new StringBuilder();
    sb.append("# Objective\n").append(run.objective).append("\n\n");
    if (!run.definitionOfDone.isEmpty()) sb.append("# Definition of Done\n- ").append(String.join("\n- ", run.definitionOfDone)).append("\n\n");
    Object todos = session.getAttribute("todoList");
    if (todos instanceof List)
    {
      sb.append("# Todo list\n");
      for (Map<String, Object> t : (List<Map<String, Object>>) todos) sb.append("- [").append(t.get("status")).append("] ").append(t.get("content")).append("\n");
      sb.append("\n");
    }
    sb.append("# Agent's summary\n").append(summary).append("\n\n");
    if (verifyOutput != null) sb.append("# Verify command output\n").append(cap(verifyOutput, 4000)).append("\n\n");
    String diff = gitDiff(session, run);
    if (diff != null && !diff.trim().isEmpty()) sb.append("# Diff against the state before the run\n```diff\n").append(cap(diff, 30000)).append("\n```\n\n");
    else
    {
      sb.append("# Changed files (current content)\n");
      for (String f : new ArrayList<>(run.changedFiles))
      {
        if (sb.length() > REVIEW_CONTEXT_CHARS) break;
        try { sb.append("\n--- ").append(AITools.resolveRelativePath(session, f)).append(" ---\n").append(cap(AITools.readFile(session, f), 12000)).append("\n"); }
        catch (Exception e) { sb.append("\n--- ").append(f).append(" --- (unreadable or deleted)\n"); }
      }
    }
    return cap(sb.toString(), REVIEW_CONTEXT_CHARS + 20000);
  }

  // One non-streaming completion on any configured provider; null when the provider rejects json mode
  @SuppressWarnings("unchecked")
  private static String complete(String model, String system, String user, boolean jsonMode) throws Exception
  {
    LlmProviders.Target target = LlmProviders.resolve(model);
    if (target == null) throw new Exception("no provider for reviewer model " + model);
    Map<String, Object> request = new HashMap<>();
    request.put("model", target.model);
    request.put("stream", false);
    request.put("messages", Arrays.asList(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
    if (jsonMode) request.put("response_format", Map.of("type", "json_object"));
    HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(target.url + "/v1/chat/completions")).timeout(java.time.Duration.ofMinutes(5)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.toJSON(request)));
    if (!target.apiKey.isEmpty()) b.header("Authorization", "Bearer " + target.apiKey);
    HttpResponse<String> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() == 400 && jsonMode) return null;
    if (resp.statusCode() != 200) throw new Exception("HTTP " + resp.statusCode() + " from " + target.provider);
    Map<String, Object> body = (Map<String, Object>) json.parse(new JSON.ReaderSource(new java.io.StringReader(resp.body())));
    Object[] choices = body.get("choices") instanceof Object[] ? (Object[]) body.get("choices") : null;
    if (choices == null || choices.length == 0) throw new Exception("empty reviewer response");
    Map<String, Object> msg = (Map<String, Object>) ((Map<String, Object>) choices[0]).get("message");
    String content = msg != null && msg.get("content") != null ? msg.get("content").toString() : "";
    return content.replaceAll("(?s)<think>.*?</think>", "").trim();
  }

  @SuppressWarnings("unchecked")
  private static String formatIssues(Map<String, Object> verdict)
  {
    StringBuilder sb = new StringBuilder();
    Object issues = verdict.get("issues");
    Iterable<?> iter = issues instanceof Object[] ? Arrays.asList((Object[]) issues) : issues instanceof Iterable ? (Iterable<?>) issues : null;
    if (iter != null) for (Object o : iter)
    {
      if (!(o instanceof Map)) continue;
      Map<String, Object> i = (Map<String, Object>) o;
      sb.append("\n  • [").append(i.getOrDefault("severity", "?")).append("] ");
      if (i.get("file") != null && !String.valueOf(i.get("file")).isEmpty()) sb.append(i.get("file")).append(": ");
      sb.append(i.get("problem"));
    }
    Object checks = verdict.get("dodChecks");
    Iterable<?> citer = checks instanceof Object[] ? Arrays.asList((Object[]) checks) : checks instanceof Iterable ? (Iterable<?>) checks : null;
    if (citer != null) for (Object o : citer)
    {
      if (o instanceof Map && Boolean.FALSE.equals(((Map<?, ?>) o).get("pass"))) sb.append("\n  • Definition of Done not met: ").append(((Map<?, ?>) o).get("item"));
    }
    return sb.length() == 0 ? "(no details given)" : sb.toString();
  }

  // ===== git checkpoints (SSH working directories only) =====

  // Records the pre-run state under a private ref; the user's HEAD, index and stash list stay untouched
  public static void captureGitBase(HttpSession session, AgentRun run)
  {
    if (run == null || run.gitBase != null || !AITools.isSshWorkingDir(session)) return;
    String cmd = "git rev-parse --is-inside-work-tree >/dev/null 2>&1 || exit 3; base=$(git stash create 2>/dev/null); [ -z \"$base\" ] && base=$(git rev-parse HEAD 2>/dev/null); [ -z \"$base\" ] && exit 4; git update-ref refs/aether/runs/" + run.runId + "/base $base && echo $base";
    try
    {
      String out = AITools.runCommand(session, cmd, 30);
      if (out == null || !out.startsWith("Exit code: 0")) return;
      String sha = out.substring(out.indexOf('\n') + 1).trim();
      if (sha.matches("[0-9a-f]{40}"))
      {
        run.gitBase = sha;
        RunManager.emit(run.runId, Map.of("type", "checkpoint", "kind", "base", "sha", sha), true);
      }
    }
    catch (Exception e) { System.out.println("StopGates: git base not captured: " + e.getMessage()); }
  }

  public static String gitDiff(HttpSession session, AgentRun run)
  {
    if (run.gitBase == null || session == null) return null;
    try
    {
      String out = AITools.runCommand(session, "git --no-pager diff " + run.gitBase + " --stat; echo; git --no-pager diff " + run.gitBase + "; git status --porcelain --untracked-files=all | grep '^??' | sed 's/^?? /new file: /'", 60);
      if (out == null || !out.startsWith("Exit code:")) return null;
      return out.substring(out.indexOf('\n') + 1);
    }
    catch (Exception e) { return null; }
  }

  // Changes tab: the run's changed files plus, on git projects, the real diff since the run started
  public static void sendChanges(Session socket, String runId, String issSubId)
  {
    AgentRun run = RunManager.getOwned(runId, issSubId);
    Map<String, Object> m = new HashMap<>();
    m.put("type", "run_changes");
    m.put("runId", runId);
    if (run == null)
    {
      m.put("files", new ArrayList<>());
      m.put("diff", "");
      AetherWebSocket.sendRaw(socket, json.toJSON(m));
      return;
    }
    List<String> files = new ArrayList<>();
    HttpSession s = run.session != null ? run.session.asHttpSession() : null;
    for (String f : new ArrayList<>(run.changedFiles)) files.add(s != null ? AITools.resolveRelativePath(s, f) : f);
    m.put("files", files);
    RunManager.AI_EXECUTOR.submit(() ->
    {
      String diff = gitDiff(s, run);
      m.put("diff", diff != null ? cap(diff, 200000) : "");
      m.put("git", run.gitBase != null);
      AetherWebSocket.sendRaw(socket, json.toJSON(m));
    });
  }

  private static String firstLine(String s)
  {
    if (s == null) return "";
    int nl = s.indexOf('\n');
    return nl < 0 ? s : s.substring(0, nl);
  }

  private static String cap(String s, int max)
  {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max) + "\n[... truncated ...]";
  }
}
