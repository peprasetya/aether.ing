package ing.aether.beans;

import ing.aether.Command;
import ing.aether.CommandRegister;
import ing.aether.Portal;
import ing.aether.SessionTracker;

@CommandRegister(value=WelcomeBean.CMDWelcome,accessType=0,createSession=true,preventCache=true)
public class WelcomeBean extends BeanObject
{
  public static final String CMDWelcome="welcome";

  private boolean driveAuthNeeded=false;

  public boolean isDriveAuthNeeded(){return driveAuthNeeded;}

  public WelcomeBean() {
    super();
  }

  @Override
  protected void processData()
  {
    // Check if setup is needed - if Authentication or Administrators missing
    Object[] admins=Portal.getProperties(Portal.PropAdmin);
    Object[] auths=Portal.getProperties(Portal.PropAuth);

    if ((admins==null || admins.length==0) || (auths==null || auths.length==0))
    {
      // Redirect to setup when either is missing
      command=Command.getCommand(SetupBean.COMMAND);
      return;
    }

    // Signed in but without a working Drive grant: the identity-only login step leaves
    // this state for new accounts and for revoked tokens, so ask for Drive before the app
    if (account!=null && "G".equals(session.getAttribute(Portal.SessionLoginProvider)))
      driveAuthNeeded=!SessionTracker.hasGoogleDrive((String)session.getAttribute(Portal.SessionAccountID),account);
  }

}