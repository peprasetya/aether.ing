package ing.aether.data;

import java.util.List;
import java.util.Map;

public class Agent
{
  private String id;
  private String name;
  private String prompt;
  private List<String> tools;
  private List<AgentCommand> commands;
  // Image agents also drive an image model: the UI shows the image model selector and controls
  private boolean image;
  // "interactive" (a chat turn, the default) or "autonomous" (loops until its stop gates pass)
  private String behavior;
  // Default reviewer model ("Provider::model") for the stop gates; the run may override it
  private String reviewerModel;
  // {modelCalls, continuations}: run budget, extendable by the user when exhausted
  private Map<String, Object> budget;
  // [{tool, path, action: allow|deny|ask}]: evaluated before the approval prompt
  private List<Map<String, Object>> policy;
  // {todos, verify, review, human}: which gates the finish tool must pass
  private Map<String, Object> stopGates;
  // Autonomous agents: whether the plan needs the user's approval before execution (default for the run toggle)
  private Boolean planGate;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getPrompt() { return prompt; }
  public void setPrompt(String prompt) { this.prompt = prompt; }
  public List<String> getTools() { return tools; }
  public void setTools(List<String> tools) { this.tools = tools; }
  public List<AgentCommand> getCommands() { return commands; }
  public void setCommands(List<AgentCommand> commands) { this.commands = commands; }
  public boolean isImage() { return image; }
  public void setImage(boolean image) { this.image = image; }
  public String getBehavior() { return behavior; }
  public void setBehavior(String behavior) { this.behavior = behavior; }
  public String getReviewerModel() { return reviewerModel; }
  public void setReviewerModel(String reviewerModel) { this.reviewerModel = reviewerModel; }
  public Map<String, Object> getBudget() { return budget; }
  public void setBudget(Map<String, Object> budget) { this.budget = budget; }
  public List<Map<String, Object>> getPolicy() { return policy; }
  public void setPolicy(List<Map<String, Object>> policy) { this.policy = policy; }
  public Map<String, Object> getStopGates() { return stopGates; }
  public void setStopGates(Map<String, Object> stopGates) { this.stopGates = stopGates; }
  public Boolean getPlanGate() { return planGate; }
  public void setPlanGate(Boolean planGate) { this.planGate = planGate; }

  public boolean isAutonomous()
  {
    return "autonomous".equals(behavior);
  }

  public boolean stopGate(String name, boolean fallback)
  {
    if (stopGates == null || !stopGates.containsKey(name)) return fallback;
    return Boolean.TRUE.equals(stopGates.get(name));
  }

  // Whether a command of this agent runs inside the chat log (only those can take steers)
  public boolean isChatlogCommand(String commandId)
  {
    if (commands == null || commandId == null) return true;
    for (AgentCommand c : commands) if (commandId.equals(c.getId())) return c.isChatlog() && !"image".equals(c.getResponse());
    return true;
  }
}
