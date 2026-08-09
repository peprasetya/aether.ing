package ing.aether;

import java.util.HashMap;

import ing.aether.beans.*;


public class Command
{

  private static final HashMap<String,Command> commands=new HashMap<String,Command>();

  public static void register(String command,Class<?> beanClass,int accessType,boolean createSession,boolean preventCache)
  {
    if (command!=null&&!command.isEmpty()&&beanClass!=null)
    {
      commands.put(command, new Command(command, beanClass, accessType, createSession, preventCache));
      System.out.println("  -> Registered command '" + command + "' to bean " + beanClass.getSimpleName());
    }
  }

  public static Command getCommand(String name){return commands.getOrDefault(name,commands.get(BeanObject.CMDWelcome));}
  
  private String command;
  private Class<?> bean;
  private int accessType;
  private boolean createSession=true;
  private boolean preventCache=true;
  
  private Command(String command,Class<?> bean,int accessType,boolean createSession,boolean preventCache)
  {
    this.command=command;
    this.bean=bean;
    this.accessType=accessType;
    this.createSession=createSession;
    this.preventCache=preventCache;
  }
  
  public String getCommand(){return command;}
  public Class<?> getBean(){return bean;}
  public int getAccessType(){return accessType;}
  public boolean isCreateSession() {return createSession;}
  public boolean isPreventCache() {return preventCache;}
}
