package ing.aether.data;

public class AgentCommand
{
  private String id;
  private String label;
  private String prompt;
  private boolean chatlog;
  private String response;
  private String target;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getLabel() { return label; }
  public void setLabel(String label) { this.label = label; }
  public String getPrompt() { return prompt; }
  public void setPrompt(String prompt) { this.prompt = prompt; }
  public boolean isChatlog() { return chatlog; }
  public void setChatlog(boolean chatlog) { this.chatlog = chatlog; }
  public String getResponse() { return response; }
  public void setResponse(String response) { this.response = response; }
  public String getTarget() { return target; }
  public void setTarget(String target) { this.target = target; }
}
