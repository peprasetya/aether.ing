package ing.aether.data;

import java.util.List;

public class Agent
{
  private String id;
  private String name;
  private String prompt;
  private List<String> tools;
  private List<AgentCommand> commands;

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
}
