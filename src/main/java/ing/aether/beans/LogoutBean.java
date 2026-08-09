package ing.aether.beans;

import ing.aether.Command;
import ing.aether.CommandRegister;

@CommandRegister(value=LogoutBean.COMMAND,accessType=0,createSession=true,preventCache=true)
public class LogoutBean extends BeanObject
{
  public static final String COMMAND="logout";
  
  protected void processData()
  {
    session.invalidate();
    account=null;
    command=Command.getCommand("welcome");
  }
}
