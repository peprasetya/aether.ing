package ing.aether.data;

import java.util.Set;
import java.util.Collections;
import jakarta.servlet.http.HttpSession;
import ing.aether.Portal;
import ing.aether.SessionTracker;

/**
 * Registry proxy for managing FileProvider instances, isolated by user session.
 */
public class FileProviderRegistry 
{
  /**
   * Registers a provider for the current session's user.
   */
  public static void register(HttpSession session, String name, FileProvider provider) 
  {
    String issSubId = (String) session.getAttribute(Portal.SessionAccountID);
    if (issSubId != null) SessionTracker.registerProvider(issSubId, name, provider);
  }

  /**
   * Unregisters a provider for the current session's user.
   */
  public static void unregister(HttpSession session, String name) 
  {
    // Not explicitly implemented in SessionTracker yet, but we could add if needed
  }

  /**
   * Retrieves a provider by name for the current session's user.
   */
  public static FileProvider getProvider(HttpSession session, String name) 
  {
    String issSubId = (String) session.getAttribute(Portal.SessionAccountID);
    return (issSubId != null) ? (FileProvider) SessionTracker.getProvider(issSubId, name) : null;
  }
  
  /**
   * Retrieves a provider by name for a specific user ID.
   */
  public static FileProvider getProvider(String issSubId, String name) 
  {
    return (issSubId != null) ? (FileProvider) SessionTracker.getProvider(issSubId, name) : null;
  }

  /**
   * Returns all registered provider names for the current session's user.
   */
  public static Set<String> getProviderNames(HttpSession session) 
  {
    String issSubId = (String) session.getAttribute(Portal.SessionAccountID);
    return (issSubId != null) ? SessionTracker.getProviderNames(issSubId) : Collections.emptySet();
  }
  
  /**
   * Clears all providers for the current session's user.
   */
  public static void clear(HttpSession session)
  {
    String issSubId = (String) session.getAttribute(Portal.SessionAccountID);
    if (issSubId != null) SessionTracker.clearProviders(issSubId);
  }
}
