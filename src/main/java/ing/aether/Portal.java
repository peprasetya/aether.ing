package ing.aether;

import java.beans.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

import org.eclipse.jetty.util.ajax.JSON;

import ing.aether.beans.AuthOpenIDBean;
import ing.aether.beans.BeanObject;
import ing.aether.beans.SetupBean;
import ing.aether.tools.FileUploadParser;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.*;



@WebFilter(urlPatterns = "/*") 
public class Portal extends HttpServlet implements Filter
{
  /**
   * 
   */
  private static final long serialVersionUID=6970029988797653197L;
  private static final String jsonFile="aether.json";
  private static final String version="0.2";
  
  public static final String SessionEmail="LoginEmail";
  public static final String SessionAccountID="LoginID";
  // Which OpenID provider this session signed in with; only 'G' implies a Drive grant
  public static final String SessionLoginProvider="LoginProvider";
  public static final String SessionData="LoginData";
  public static final String SessionAdmin="Admin";
  public static final String SessionMonitor="HardwareMonitoring";
  public static final String MediaList="media";
  public static final String ID="id";
  public static final String Progress="progress";
  public static final String Update="last";
  public static final String PropAdmin="Administrators";
  public static final String PropAuth="Authentication";
  public static final String PropOllamaUrl="OllamaUrl";
  public static final String PropApiKey="ApiKey";
  public static final String PropLlmProviders="LlmProviders";
  public static final String PropInternalModel="InternalModel";
  public static final String PropAllowedUsers="AllowedUsers";
  public static final String PropModelFilters="ModelFilters";
  public static final String ROOTFOLDER="root";
  public static final String MULTIPART_FORMDATA_TYPE = "multipart/form-data";
  public static final String DEFAULT_TYPE = "text/html; charset=utf-8";

  private FilterConfig filterConfig;
  private static Map<String, Object> data=new HashMap<>();
  private static final JSON json = new JSON();


  
  public static Object[] getProperties(String key)
  {
    return (Object[])data.get(key);
  }
  
  public static void setProperties(String key, Object[] value)
  {
    data.put(key,value);
    saveData();
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> getProperty(String key)
  {
    return (Map<String, Object>)data.get(key);
  }
  
  public static void setProperty(String key, Map<String, Object> value)
  {
    data.put(key,value);
    saveData();
  }

  @SuppressWarnings("unchecked")
  public static void reloadProperty()
  {
    try
    {
      File file=new File(jsonFile);
      if (file.exists())
      {
        try (Reader reader=new FileReader(file))
        {
          data=(Map<String, Object>)json.parse(new JSON.ReaderSource(reader));
          ing.aether.data.ProviderManager.initialize();
        }
      }
    }catch (IOException e)
    {
      e.printStackTrace(System.out);
    }
  }

  private static void saveData()
  {
    try
    {
      try (FileWriter file=new FileWriter(jsonFile))
      {
        file.write(json.toJSON(data));
        file.flush();
        ing.aether.data.ProviderManager.initialize();
      }
    }catch (IOException e)
    {
      System.out.println(e.getMessage());
    }
  }
  
  
  @Override
  public void init(FilterConfig filterConfig) throws ServletException
  {
    this.filterConfig = filterConfig;
    filterConfig.getServletContext().setSessionTimeout(480);
    reloadProperty();
    CommandInitializer.scan(filterConfig.getServletContext());
    ing.aether.tools.ai.AgentWorker.loadAgents(filterConfig.getServletContext().getRealPath("/WEB-INF"));
    System.out.println("Aether Initialize, version: "+version);
  }

  private void filter(ServletRequest request,ServletResponse response,FilterChain filterChain)
  {
    try
    {
      filterChain.doFilter(request,response);
    } catch (ServletException sx)
    {
      System.out.println("Filter-ServletExcetpion");
      filterConfig.getServletContext().log(sx.getMessage());
    } catch (IOException iox)
    {
      System.out.println("Filter-IOExcetpion");
      filterConfig.getServletContext().log(iox.getMessage());
    }
  }

  @Override
  public void doFilter(ServletRequest request,ServletResponse response,FilterChain filterChain) throws IOException,ServletException
  {
    try
    {
      HttpServletRequest req=(HttpServletRequest)request;
      String path=req.getRequestURI().substring(req.getContextPath().length());
      boolean filter=false;
      if (path.equals("/"))
      {
        
      } else if (path.endsWith(".css"))filter=true;
      else if (path.endsWith(".js"))filter=true;
      else if (path.endsWith(".txt"))filter=true;
      else if (path.endsWith(".ico"))filter=true;
      else if (path.startsWith("/images/"))filter=true;
      else if (path.startsWith("/resources/"))filter=true;
      else if (path.startsWith("/.well-known/"))filter=true;
      else if (path.equals("/auth"))filter=true;
      else if (path.startsWith("/socket/"))
      {
        HttpSession session = req.getSession(true);
        if (session != null) SessionTracker.sessionCheck(session);
        filter=true;
      }
      if (filter)
      {
        filter(request,response,filterChain);
      } else 
      {
        String host=req.getHeader("Host");
        if (host!=null)
        {
          if (host.contains(":"))host=host.substring(0,host.indexOf(":"));
          doRequest(host,req,(HttpServletResponse)response,path);
        } else filter(request,response,filterChain); // no Host header: never leave the request unanswered
      }
    } catch (ClassCastException e)
    {
      System.out.println("Filter-ClassCastException");
      System.out.println(e.getMessage());
      filter(request,response,filterChain);
    }
  }

  public static void setProperties(Object bean, ServletRequest request)
  {
    Enumeration<?> e = request.getParameterNames();
    while (e.hasMoreElements())
    {
      String name=(String)e.nextElement();
      try
      {
        BeanInfo info=Introspector.getBeanInfo(bean.getClass());
        if (info==null)continue;
        Method method = null;
        Class<?> type = null;
        PropertyDescriptor pd[]=info.getPropertyDescriptors();
        for (int i=0;(i<pd.length)&&method==null;i++)
        {
          if (!pd[i].getName().equals(name)) continue;
          method = pd[i].getWriteMethod();
          type = pd[i].getPropertyType();
        }
        if (method==null)continue;
        if (type.isArray())
        {
          Class<?> t = type.getComponentType();
          String[] values=request.getParameterValues(name);
          if ((values!=null) && (t.equals(String.class))) method.invoke(bean, new Object[] {values});
        } else
        {
          String value=request.getParameter(name);
          if (value != null) if ((!value.equals("")) && type.equals(String.class))method.invoke(bean, new Object[] {value});
        }
      } catch (Exception ex) {}
    }
  }

  private void setProperties(Object bean,Map<String,List<FileUploadParser.MultipartItem>> multipartData)
  {
    for (Map.Entry<String,List<FileUploadParser.MultipartItem>> entry:multipartData.entrySet())
    {
      String name=entry.getKey();
      List<FileUploadParser.MultipartItem> items=entry.getValue();
      if (items.isEmpty()) continue;
      try
      {
        if (items.get(0).isFormField())
        {
          // This logic handles regular form fields from the multipart request
          BeanInfo info=Introspector.getBeanInfo(bean.getClass());
          PropertyDescriptor[] pd=info.getPropertyDescriptors();
          for (int i=0;i<pd.length;i++)
          {
            if (pd[i].getName().equals(name))
            {
              Method method=pd[i].getWriteMethod();
              if (method!=null)
              {
                if (pd[i].getPropertyType().isArray())
                {
                  String[] values=items.stream().map(FileUploadParser.MultipartItem::getValue).toArray(String[]::new);
                  method.invoke(bean,new Object[]{values});
                }else method.invoke(bean,items.get(0).getValue());
              }
              break;
            }
          }
        }else
        {
          // This logic handles the file uploads
          Method method=bean.getClass().getMethod("addMultipartItem",FileUploadParser.MultipartItem.class);
          for (FileUploadParser.MultipartItem fileItem:items)method.invoke(bean,fileItem);
        }
      }catch (Exception ex){/* Log or ignore exceptions for missing properties, etc.*/}
    }
  }

  //@SuppressWarnings("rawtypes")
  // Restricted to Google logins: a Microsoft/Yahoo account never has a Drive grant to check,
  // and an unrestricted test would bounce every request on such a deployment to welcome
  // forever. Session reads throw once a concurrent logout invalidates the session.
  private static boolean needsDriveAuth(HttpSession session,String account)
  {
    if (session==null || account==null) return false;
    try
    {
      if (!"G".equals(session.getAttribute(SessionLoginProvider))) return false;
      return !SessionTracker.hasGoogleDrive((String)session.getAttribute(SessionAccountID),account);
    }catch (IllegalStateException e) {return false;}
  }

  private void doRequest(String host,HttpServletRequest request,HttpServletResponse response,String path)
  {
    /*
    String ipAddress=request.getRemoteAddr();
    String addIp=request.getHeader("X-Forwarded-For");
    if (addIp!=null && addIp.length()>0)
    {
      if (addIp.contains(ipAddress))
        ipAddress=addIp;
      else
        ipAddress+=","+addIp;
    }
    */
    /*
    Enumeration<String> uhm=request.getHeaderNames();
    while (uhm.hasMoreElements())
    {
      String key=uhm.nextElement();
      System.out.println("header |"+key+"|"+request.getHeader(key)+"|");
    }
    */
    HttpSession session=null;
    String account=null;
    String command=BeanObject.CMDMenu;
    if (path.length()>1)
    {
      command=path.substring(1);
      int sp=command.indexOf('/');
      if (sp>0)command=command.substring(0,sp);
    }
    //System.out.println("Request:"+path);
    Command cmd=null;
    BeanObject bean=null;
    //String message=null;
    do
    {
      if (cmd==null)cmd=Command.getCommand(command);
      if (cmd!=null) 
      {
        command=cmd.getCommand();
        session=request.getSession(cmd.isCreateSession());
        if (session!=null)
        {
          SessionTracker.sessionCheck(session);
          account=(String)session.getAttribute(SessionEmail);
        }
        if (cmd.getAccessType()>0 && account==null)
        {
          command=BeanObject.CMDWelcome;
          cmd=Command.getCommand(command);
        }
        // Signed in through Google but with no working Drive grant: send deep links back to
        // welcome, which renders the Drive authorization step. Welcome and logout are
        // accessType 0, so this can neither loop nor strand the user. Restricted to Google
        // logins because a Microsoft/Yahoo account never has a Drive grant to check.
        else if (cmd.getAccessType()>0 && needsDriveAuth(session,account))
        {
          command=BeanObject.CMDWelcome;
          cmd=Command.getCommand(command);
        }
        try
        {
          Class<?>[] parameterType = null;
          bean=(BeanObject)cmd.getBean().getDeclaredConstructor(parameterType).newInstance();
        }catch (InstantiationException|IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException e)
        {
          bean=new BeanObject();
        }
      }
      if (bean==null) bean=new BeanObject();
      bean.setBeanObjects(request,cmd,session,account);
      
      String ct=request.getContentType();
      if (ct!=null&&ct.toLowerCase().startsWith("multipart/form-data"))try
        {
          // Parse multipart data using our new tool
          Map<String,List<FileUploadParser.MultipartItem>> multipartData=FileUploadParser.parse(request);
          // Set properties on the bean from the parsed data
          setProperties(bean,multipartData);
        }catch (IOException e)
        {
          System.out.println("Error parsing multipart request");
          e.printStackTrace(System.out);
        }
      else
      {
        // This is your existing logic for regular form posts
        setProperties(bean,request);
      }
      
      try
      {
        bean.processRequest();
      }catch (IllegalStateException e)
      {
        // Only swallow this when the session really was invalidated underneath us by a
        // concurrent logout - any other IllegalStateException is a genuine bug worth seeing
        boolean invalidated=false;
        try {if (session!=null) session.getCreationTime();} catch (IllegalStateException ise) {invalidated=true;}
        if (!invalidated) throw e;
        System.out.println("INFO: Request '"+command+"' abandoned, session invalidated by a concurrent logout");
        return;
      }
      cmd=bean.getCommand();
      //System.out.println("Post Command:"+cmd.getCommand());
      account=bean.getAccount();
      //rights=bean.getRights();
    } while(!cmd.getCommand().equals(command));

    response.setContentType(bean.getContentType());
    if (bean.isPartial())response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
    if (bean.getContentRange()!=null)response.setHeader("Content-Range",bean.getContentRange());
    if (bean.getContentLength()>=0)response.setHeader("Content-Length",String.valueOf(bean.getContentLength()));
    if (cmd.isPreventCache())
    {
      response.setHeader("Cache-Control","private, no-cache, no-store, must-revalidate, max-age = 0");
      response.setHeader("Pragma","no-cache");
    } else
    {
      response.setHeader("Cache-Control","private, max-age = 14400");
    }
    
    if (bean.getRedirect()!=null)try
    {
      response.sendRedirect(bean.getRedirect());
      return;
    }catch (IOException e){}
    
    request.setAttribute("bean",bean);
    String page="/maintemplate.jsp";  
    boolean ignoreExcept=false;
    if (bean.isAjaxCall())
    {
      page="/dynamic/"+command+".jsp";
      response.setContentType("text/xml; charset=UTF-8");
      response.setCharacterEncoding("UTF-8");
    } else if (!DEFAULT_TYPE.equals(bean.getContentType()))
    {
      if (bean.getContentDisposition()!=null)
        response.setHeader("Content-Disposition","attachment; filename=\""+bean.getContentDisposition()+"\"");
      page="/content/"+command+".jsp";
      ignoreExcept=true;
    }
    RequestDispatcher rd=request.getRequestDispatcher(page);
    try
    {
      rd.include(request,response);
    }catch (EOFException e)
    {
      System.out.println("EOFException Test *************************************************************");
    }catch (ServletException|IOException e)
    {
      if (e instanceof IOException && ((IOException)e).getCause() instanceof java.util.concurrent.TimeoutException)ignoreExcept=true;
      if (ignoreExcept)
      {
        //System.out.println(e.getMessage());
        return;
      }
      System.out.println("Portal NetTools trapped Exception");
      System.out.println("IP Address: "+request.getRemoteAddr());
      e.printStackTrace(System.out);
    } catch (Exception e)
    {
      System.out.println("Portal Catch Exception.");
      e.printStackTrace(System.out);
    }

  }

}
