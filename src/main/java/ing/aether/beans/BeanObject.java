package ing.aether.beans;

import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.jetty.util.ajax.JSON;

import ing.aether.Command;
import ing.aether.CommandRegister;
import ing.aether.Portal;
import ing.aether.data.*;
import ing.aether.tools.FileUploadParser;
import ing.aether.tools.ai.AgentWorker;
import jakarta.servlet.http.*;

public class BeanObject
{
  public static final String CMDWelcome="welcome";
  public static final String CMDMenu="menu";
  public static final String CMDHome="home";
  
  private static final JSON json = new JSON();
  protected HttpServletRequest request=null;
  protected Command command=null;
  protected String redirect=null;
  protected String contentDisposition=null;
  protected String contentType=Portal.DEFAULT_TYPE;
  protected boolean html=true;
  private String order=null;
  private boolean ajaxCall=false;
  protected boolean success=false;
  protected HttpSession session=null;
  protected String account=null;
  protected boolean partial=false;
  protected String contentRange=null;
  protected long contentLength=-1;
  protected PathMap path=null;
  
  protected List<String> models = new ArrayList<>();
  protected List<Map<String, Object>> agentList = new ArrayList<>();
  protected List<Map<String, Object>> chatHistory = new ArrayList<>();
  protected Map<String, String> aiConfig = new HashMap<>();

  public List<String> getModels(){return models;}
  public List<Map<String, Object>> getAgentList(){return agentList;}
  public List<Map<String, Object>> getChatHistory(){return chatHistory;}
  public Map<String, String> getAiConfig(){return aiConfig;}

  @SuppressWarnings("unchecked")
  public void loadAIData()
  {
    if (session == null) return;
    
    agentList = AgentWorker.getAgentMaps().stream()
        .filter(a -> !((String)a.get("id")).toLowerCase().startsWith("internal"))
        .collect(Collectors.toList());
    
    // Qualified "Provider::model" ids across all configured providers, cached in LlmProviders
    models = ing.aether.tools.ai.LlmProviders.listModels(isAdmin());

    List<Map<String, Object>> history = (List<Map<String, Object>>) session.getAttribute("chatHistory");
    String wd = (String) session.getAttribute("workingDirectory");
    
    if (history == null && wd != null)
    {
       history = AgentWorker.loadLatestSession(session);
       if (history != null) session.setAttribute("chatHistory", history);
    }
    if (history != null) chatHistory = history;

    // Load persisted AI config
    AgentWorker.loadAiConfig(session);
    aiConfig = AgentWorker.getAiConfig(session);
  }
  
  public Command getCommand(){return command;}
  public String getRedirect(){return redirect;}
  public String getContentDisposition(){return contentDisposition;}
  public String getContentType(){return contentType;}
  public boolean isPartial(){return partial;}
  public String getContentRange(){return contentRange;}
  public long getContentLength(){return contentLength;}
  public boolean isAjaxCall(){return ajaxCall;}
  public boolean isSuccess(){return success;}
  public boolean isHtml(){return html;}
  public String getAccount(){return account;}
  
  public String getRealPath()
  {
    return path != null ? path.getRealPath() : null;
  }
  
  protected String message=null;

  protected ArrayList<FileUploadParser.MultipartItem> uploads=null;

  public void setBeanObjects(HttpServletRequest request,Command command,HttpSession session,String account)
  {
    this.request=request;
    this.command=command;
    this.session=session;
    this.account=account;
  }
  
  public void setAjaxcall(String newValue)
  {
    ajaxCall="1".equals(newValue);
    if (isAjaxCall()) contentType="text/xml";
  }
  
  public void setOrder(String newValue)
  {
    order=newValue;
  }
  
  public String getOrder() {return order;}

  protected void processData()
  {
  }

  public void processRequest()
  {
    path=new PathMap(URLDecoder.decode(request.getRequestURI().substring(request.getContextPath().length()),StandardCharsets.UTF_8),session);
    try
    {
      processData();
    } catch (Exception e)
    {
      e.printStackTrace(System.out);
    }
  }

  public String getMessage()
  {
    return message;
  }
  
  protected void logActivity(boolean success,String message)
  {
    this.success=success;
  }
  
  public boolean isAdmin()
  {
    if (session==null)return false;
    Object admin=session.getAttribute(Portal.SessionAdmin);
    return admin instanceof Boolean?(boolean)admin:false;
  }
  
  public String getSessionId()
  {
    return session.getId();
  }
  

  

  public void addMultipartItem(FileUploadParser.MultipartItem upload)
  {
    if (uploads==null)uploads=new ArrayList<FileUploadParser.MultipartItem>();
    uploads.add(upload);
  }

}
