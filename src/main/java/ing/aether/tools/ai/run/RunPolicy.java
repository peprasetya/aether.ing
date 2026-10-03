package ing.aether.tools.ai.run;

import java.util.*;
import java.util.regex.Pattern;

import ing.aether.data.Agent;
import ing.aether.tools.ai.AITools;
import ing.aether.tools.ai.AgentWorker;
import jakarta.servlet.http.HttpSession;

// Declarative tool permissions evaluated before the approval prompt. An agent's "policy" array holds
// rules {tool, path, action: allow|deny|ask}; the most specific matching rule wins (a longer literal path
// prefix beats a shorter one, then an exact tool beats "*") and at equal specificity deny > ask > allow.
// Without rules the default reproduces the original behaviour exactly: every mutating tool asks.
// A write outside the working directory always asks unless a rule denies it.
public class RunPolicy
{
  public static final String ALLOW = "allow";
  public static final String DENY = "deny";
  public static final String ASK = "ask";

  private static final Set<String> MUTATING = Set.of("writeFile", "patchFile", "createFile", "deleteFile", "mkdir", "runCommand", "renameFile", "moveFile", "copyFile");

  public static boolean isMutating(String tool)
  {
    return MUTATING.contains(tool);
  }

  public static String decide(HttpSession session, String tool, String pathArg)
  {
    AgentRun run = RunManager.get(session.getId());
    boolean outside = pathArg != null && isOutsideWd(session, pathArg);

    Agent agent = AgentWorker.findAgent((String) session.getAttribute("currentAgentId"));
    String ruled = agent != null ? evaluate(agent.getPolicy(), tool, relative(session, pathArg)) : null;
    if (DENY.equals(ruled)) return DENY;
    if (outside && MUTATING.contains(tool)) return ASK;
    if (run != null && run.alwaysAllow.contains(tool)) return ALLOW;
    if (ruled != null) return ruled;
    return MUTATING.contains(tool) ? ASK : ALLOW;
  }

  private static String relative(HttpSession session, String pathArg)
  {
    if (pathArg == null) return null;
    try { return AITools.resolveRelativePath(session, AITools.resolveFullPath(session, pathArg)); }
    catch (Exception e) { return pathArg; }
  }

  private static boolean isOutsideWd(HttpSession session, String pathArg)
  {
    String wd = (String) session.getAttribute("workingDirectory");
    if (wd == null) return false;
    String full = AITools.resolveFullPath(session, pathArg);
    return full != null && !(full.equals(wd) || full.startsWith(wd + "/"));
  }

  // Returns the action of the most specific matching rule, or null when no rule matches
  public static String evaluate(List<Map<String, Object>> rules, String tool, String relPath)
  {
    if (rules == null || rules.isEmpty()) return null;
    String best = null;
    int bestScore = -1;
    for (Map<String, Object> r : rules)
    {
      String ruleTool = r.get("tool") != null ? String.valueOf(r.get("tool")) : "*";
      String action = r.get("action") != null ? String.valueOf(r.get("action")).toLowerCase() : null;
      if (!ALLOW.equals(action) && !DENY.equals(action) && !ASK.equals(action)) continue;
      if (!"*".equals(ruleTool) && !ruleTool.equals(tool)) continue;
      String glob = r.get("path") != null ? String.valueOf(r.get("path")) : null;
      // Path specificity dominates (a narrower deny always holds); an exact tool name breaks ties
      int score = "*".equals(ruleTool) ? 0 : 5;
      if (glob != null)
      {
        if (relPath == null || !globToRegex(glob).matcher(relPath).matches()) continue;
        score += (literalPrefix(glob).length() + 1) * 10;
      }
      if (score > bestScore || (score == bestScore && rank(action) > rank(best)))
      {
        best = action;
        bestScore = score;
      }
    }
    return best;
  }

  private static int rank(String action)
  {
    if (DENY.equals(action)) return 3;
    if (ASK.equals(action)) return 2;
    if (ALLOW.equals(action)) return 1;
    return 0;
  }

  private static String literalPrefix(String glob)
  {
    int i = 0;
    while (i < glob.length() && "*?[".indexOf(glob.charAt(i)) < 0) i++;
    return glob.substring(0, i);
  }

  // "**" spans directories, "*" and "?" stay inside one path segment
  static Pattern globToRegex(String glob)
  {
    StringBuilder sb = new StringBuilder("^");
    for (int i = 0; i < glob.length(); i++)
    {
      char c = glob.charAt(i);
      if (c == '*')
      {
        boolean dbl = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
        if (dbl)
        {
          sb.append(".*");
          i++;
          if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') i++;
        }
        else sb.append("[^/]*");
      }
      else if (c == '?') sb.append("[^/]");
      else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) sb.append('\\').append(c);
      else sb.append(c);
    }
    return Pattern.compile(sb.append('$').toString());
  }
}
