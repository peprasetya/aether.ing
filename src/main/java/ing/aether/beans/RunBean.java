package ing.aether.beans;

import java.util.Map;

import ing.aether.CommandRegister;
import ing.aether.Portal;
import ing.aether.tools.ai.run.AgentRun;
import ing.aether.tools.ai.run.RunManager;

// Deep link /run/<runId> (push notifications, another device): resolves the run the user owns to its
// project and chat, which run.jsp then opens. Unknown or foreign run ids resolve to nothing.
@CommandRegister(value=RunBean.COMMAND, accessType=1, createSession=true, preventCache=true)
public class RunBean extends BeanObject
{
  public static final String COMMAND = "run";

  private String runId = null;
  private String workingDir = null;
  private String chatSessionId = null;

  @Override
  protected void processData()
  {
    if (session == null) return;
    String issSubId = (String) session.getAttribute(Portal.SessionAccountID);
    String id = path.getLibrary();
    if (issSubId == null || id == null) return;
    AgentRun run = RunManager.getOwned(id, issSubId);
    if (run != null)
    {
      runId = run.runId;
      workingDir = run.workingDir;
      chatSessionId = run.chatSessionId;
      return;
    }
    // Finished runs evicted from memory are still listed in the user's encrypted run index
    for (Map<String, Object> entry : RunManager.listFor(issSubId))
    {
      if (!id.equals(entry.get("runId"))) continue;
      runId = id;
      workingDir = (String) entry.get("workingDir");
      chatSessionId = (String) entry.get("chatSessionId");
      return;
    }
  }

  public String getRunId() { return runId; }
  public String getWorkingDir() { return workingDir; }
  public String getChatSessionId() { return chatSessionId; }
}
