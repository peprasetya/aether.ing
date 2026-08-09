package ing.aether.beans;

import java.util.*;
import java.util.regex.Pattern;

import ing.aether.CommandRegister;
import ing.aether.Portal;
import ing.aether.data.UserSettings;
import ing.aether.tools.ai.LlmProviders;

@CommandRegister(value=SettingBean.COMMAND,accessType=1,createSession=true,preventCache=true)
@CommandRegister(value=SettingBean.CMDProvider,accessType=1,createSession=true,preventCache=true)
public class SettingBean extends BeanObject
{
  public static final String COMMAND="setting";
  public static final String CMDProvider="provider";

  public static final String DefaultOllamaUrl="http://localhost:11434";
  public static final String DefaultModelFilter="^[^/]+$";

  private String searchkey;
  private String searchprovider;
  private String internalmodel;
  private String action;
  private String value;
  private String[] admins;
  private String[] allowedUsers;
  private String[] modelFilters;

  // LLM provider add-form (global, admin-only, stored in aether.json)
  private String llmname;
  private String llmurl;
  private String llmkey;
  private List<Map<String, Object>> llmProviders = new ArrayList<>();
  private List<String> allModels = new ArrayList<>();

  // Storage provider form (per-user, stored in the user's aether.ing.setting.json)
  private String providertype;
  private String providername;
  private String sshhost;
  private String sshport;
  private String sshuser;
  private String sshpassword;
  private String sshroot;
  private List<Map<String, Object>> providers = new ArrayList<>();
  private String publicKey;
  private boolean providersChanged = false;

  public SettingBean()
  {
  }

  public String getSearchkey(){return searchkey;}
  public void setSearchkey(String searchkey){this.searchkey=searchkey;}

  public String getSearchprovider(){return searchprovider;}
  public void setSearchprovider(String searchprovider){this.searchprovider=searchprovider;}

  public String getInternalmodel(){return internalmodel;}
  public void setInternalmodel(String internalmodel){this.internalmodel=internalmodel;}

  public void setLlmname(String llmname){this.llmname=llmname;}
  public void setLlmurl(String llmurl){this.llmurl=llmurl;}
  public void setLlmkey(String llmkey){this.llmkey=llmkey;}
  public List<Map<String, Object>> getLlmProviders(){return llmProviders;}
  public List<String> getAllModels(){return allModels;}

  public String getAction(){return action;}
  public void setAction(String action){this.action=action;}

  public String getValue(){return value;}
  public void setValue(String value){this.value=value;}

  public String[] getAdmins(){return admins;}
  public String[] getAllowedUsers(){return allowedUsers;}
  public String[] getModelFilters(){return modelFilters;}

  public String getProvidertype(){return providertype;}
  public void setProvidertype(String providertype){this.providertype=providertype;}

  public String getProvidername(){return providername;}
  public void setProvidername(String providername){this.providername=providername;}

  public String getSshhost(){return sshhost;}
  public void setSshhost(String sshhost){this.sshhost=sshhost;}

  public String getSshport(){return sshport;}
  public void setSshport(String sshport){this.sshport=sshport;}

  public String getSshuser(){return sshuser;}
  public void setSshuser(String sshuser){this.sshuser=sshuser;}

  public void setSshpassword(String sshpassword){this.sshpassword=sshpassword;}

  public String getSshroot(){return sshroot;}
  public void setSshroot(String sshroot){this.sshroot=sshroot;}

  public List<Map<String, Object>> getProviders(){return providers;}
  public String getPublicKey(){return publicKey;}
  public boolean isProvidersChanged(){return providersChanged;}

  @Override
  protected void processData()
  {
    // Storage providers are per-user settings, kept in the user's own Drive; no admin needed
    String issSubId=(String)session.getAttribute(Portal.SessionAccountID);
    String email=(String)session.getAttribute(Portal.SessionEmail);
    UserSettings.ensureLoaded(issSubId, email);

    if ("addProvider".equals(action))
    {
      try
      {
        if (sshhost==null || sshhost.trim().isEmpty() || sshuser==null || sshuser.trim().isEmpty()) throw new Exception("Host and user are required");
        Map<String,Object> cfg=new HashMap<>();
        cfg.put("type", (providertype==null || providertype.isEmpty()) ? "ssh" : providertype);
        cfg.put("name", (providername==null || providername.trim().isEmpty()) ? sshhost.trim() : providername.trim());
        cfg.put("host", sshhost.trim());
        int port=22;
        try { if (sshport!=null && !sshport.trim().isEmpty()) port=Integer.parseInt(sshport.trim()); } catch (NumberFormatException e) {}
        cfg.put("port", port);
        cfg.put("user", sshuser.trim());
        cfg.put("root", sshroot==null ? "" : sshroot.trim());
        UserSettings.addProvider(issSubId, email, cfg, sshpassword);
        providersChanged=true;
        success=true;
        message=(sshpassword!=null && !sshpassword.isEmpty())
          ? "Provider added; public key installed on the server"
          : "Provider added. Install the Aether public key on the server to connect.";
      } catch (Exception e) { success=false; message="Error adding provider: "+e.getMessage(); }
      sshpassword=null; // one-time use only, never kept
    }
    else if ("removeProvider".equals(action) && value!=null)
    {
      try
      {
        UserSettings.removeProvider(issSubId, value);
        providersChanged=true;
        success=true;
        message="Provider removed";
      } catch (Exception e) { success=false; message="Error removing provider: "+e.getMessage(); }
    }
    providers=UserSettings.getProviders(issSubId);
    publicKey=UserSettings.getPublicKey(issSubId);

    if (!isAdmin()) return;

    // Do NOT loadProperties() here: it would overwrite the just-submitted form fields
    // (searchkey/searchprovider/internalmodel) with the still-persisted old values
    // before saveConfig below gets a chance to read them.
    if ("saveConfig".equals(action))
    {
      if (searchkey!=null) Portal.setProperties("SearchKey",new Object[]{searchkey});
      if (searchprovider!=null && !searchprovider.isEmpty()) Portal.setProperties("SearchProvider",new Object[]{searchprovider});
      if (internalmodel!=null && !internalmodel.isEmpty()) Portal.setProperties(Portal.PropInternalModel,new Object[]{internalmodel});
      success=true;
      message="Configuration saved";
    }
    else if ("addLlmProvider".equals(action))
    {
      if (llmname==null || llmname.trim().isEmpty() || llmurl==null || llmurl.trim().isEmpty()) message="Provider name and URL are required";
      else if (llmname.contains(LlmProviders.SEPARATOR)) message="Provider name must not contain '"+LlmProviders.SEPARATOR+"'";
      else
      {
        String name=llmname.trim();
        String urlValue=llmurl.trim();
        while (urlValue.endsWith("/")) urlValue=urlValue.substring(0,urlValue.length()-1);
        List<Map<String, Object>> current=LlmProviders.getProviders();
        boolean exists=false;
        for (Map<String, Object> p:current) if (name.equals(String.valueOf(p.get(LlmProviders.KeyName)))) exists=true;
        if (exists) message="A provider named '"+name+"' already exists";
        else
        {
          // Materialize the (possibly legacy-derived) list on first write, then append
          Map<String, Object> entry=new HashMap<>();
          entry.put(LlmProviders.KeyName,name);
          entry.put(LlmProviders.KeyUrl,urlValue);
          entry.put(LlmProviders.KeyApiKey,llmkey==null?"":llmkey.trim());
          current.add(entry);
          Portal.setProperties(Portal.PropLlmProviders,current.toArray());
          LlmProviders.invalidateCache();
          success=true;
          message="LLM provider added";
        }
      }
    }
    else if ("removeLlmProvider".equals(action) && value!=null)
    {
      List<Map<String, Object>> current=LlmProviders.getProviders();
      if (current.size()<=1) message="Cannot remove the last LLM provider";
      else
      {
        boolean removed=current.removeIf(p -> value.equals(String.valueOf(p.get(LlmProviders.KeyName))));
        if (removed)
        {
          Portal.setProperties(Portal.PropLlmProviders,current.toArray());
          LlmProviders.invalidateCache();
          success=true;
          message="LLM provider removed";
        }
        else message="Provider not found";
      }
    }
    else if ("toggleMonitor".equals(action))
    {
      Boolean isMonitoring = (Boolean) session.getAttribute(Portal.SessionMonitor);
      if (isMonitoring == null) isMonitoring = false;

      success=true;
      if (!isMonitoring)
      {
        ing.aether.tools.MonitorWorker.start(session);
        session.setAttribute(Portal.SessionMonitor, true);
        message = "Monitoring started";
      }
      else
      {
        ing.aether.tools.MonitorWorker.stop(session);
        session.setAttribute(Portal.SessionMonitor, false);
        message = "Monitoring stopped";
      }
    }
    else if ("addAdmin".equals(action) && value!=null && !value.isEmpty())
    {
      addProperty(Portal.PropAdmin,value);
      success=true;
      message="Admin added";
    }
    else if ("removeAdmin".equals(action) && value!=null)
    {
      removeProperty(Portal.PropAdmin,value);
      success=true;
      message="Admin removed";
    }
    else if ("addAllowed".equals(action) && value!=null && !value.isEmpty())
    {
      addProperty(Portal.PropAllowedUsers,value);
      success=true;
      message="User added to allowed list";
    }
    else if ("removeAllowed".equals(action) && value!=null)
    {
      removeProperty(Portal.PropAllowedUsers,value);
      success=true;
      message="User removed from allowed list";
    }
    else if ("addFilter".equals(action) && value!=null && !value.isEmpty())
    {
      if (isValidRegex(value))
      {
        addProperty(Portal.PropModelFilters,value);
        success=true;
        message="Model filter added";
      }
      else message="Invalid regex pattern";
    }
    else if ("removeFilter".equals(action) && value!=null)
    {
      removeProperty(Portal.PropModelFilters,value);
      success=true;
      message="Model filter removed";
    }

    loadProperties();
  }

  private void loadProperties()
  {
    // Fresh install: seed the legacy OllamaUrl so LlmProviders derives a working default
    // provider ("Ollama" at localhost) before the admin configures anything
    Object[] llmArr=Portal.getProperties(Portal.PropLlmProviders);
    Object[] ollamaArr=Portal.getProperties(Portal.PropOllamaUrl);
    if ((llmArr==null || llmArr.length==0) && (ollamaArr==null || ollamaArr.length==0))
      Portal.setProperties(Portal.PropOllamaUrl, new Object[]{DefaultOllamaUrl});

    llmProviders=LlmProviders.getProviders();
    allModels=LlmProviders.listModels((Object[])null);

    Object[] internalArr=Portal.getProperties(Portal.PropInternalModel);
    internalmodel=(internalArr!=null && internalArr.length>0)?(String)internalArr[0]:LlmProviders.internalModel();

    Object[] searchArr=Portal.getProperties("SearchKey");
    searchkey=(searchArr!=null && searchArr.length>0)?(String)searchArr[0]:"";

    Object[] searchProvArr=Portal.getProperties("SearchProvider");
    searchprovider=(searchProvArr!=null && searchProvArr.length>0)?(String)searchProvArr[0]:"brave";

    Object[] adminArr=Portal.getProperties(Portal.PropAdmin);
    admins=Arrays.stream(adminArr!=null?adminArr:new Object[0]).map(Object::toString).toArray(String[]::new);

    Object[] allowedArr=Portal.getProperties(Portal.PropAllowedUsers);
    allowedUsers=Arrays.stream(allowedArr!=null?allowedArr:new Object[0]).map(Object::toString).toArray(String[]::new);

    Object[] filterArr=Portal.getProperties(Portal.PropModelFilters);
    if (filterArr==null || filterArr.length==0)
    {
      modelFilters=new String[]{DefaultModelFilter};
      Portal.setProperties(Portal.PropModelFilters, (Object[])modelFilters);
    }
    else
    {
      modelFilters=Arrays.stream(filterArr).map(Object::toString).toArray(String[]::new);
    }
  }

  private void addProperty(String key,String newValue)
  {
    Object[] current=Portal.getProperties(key);
    List<Object> list=new ArrayList<>(Arrays.asList(current!=null?current:new Object[0]));
    if (!list.contains(newValue))
    {
      list.add(newValue);
      Portal.setProperties(key,list.toArray());
    }
  }

  private void removeProperty(String key,String valueToRemove)
  {
    Object[] current=Portal.getProperties(key);
    if (current==null) return;
    List<Object> list=new ArrayList<>(Arrays.asList(current));
    if (list.remove(valueToRemove))
    {
      Portal.setProperties(key,list.toArray());
    }
  }

  private boolean isValidRegex(String regex)
  {
    try
    {
      Pattern.compile(regex);
      return true;
    }
    catch (Exception e)
    {
      return false;
    }
  }
}
