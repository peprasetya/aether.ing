package ing.aether.data;

import java.util.HashMap;
import java.util.Map;
import ing.aether.Portal;

/**
 * Manages user accounts and their associated tokens.
 */
public class AccountManager 
{
  public static final String PropAccounts = "Accounts";
  public static final String KeyRefreshToken = "refreshToken";
  public static final String KeyProvider = "provider";
  public static final String KeyIssSubId = "issSubId";

  @SuppressWarnings("unchecked")
  public static void saveAccountMapping(String email, String provider, String issSubId) 
  {
    Map<String, Object> accounts = (Map<String, Object>) Portal.getProperty(PropAccounts);
    if (accounts == null) accounts = new HashMap<>();
    
    Map<String, Object> accountData = (Map<String, Object>) accounts.get(email);
    if (accountData == null) accountData = new HashMap<>();
    
    accountData.put(KeyProvider, provider);
    accountData.put(KeyIssSubId, issSubId);
    
    accounts.put(email, accountData);
    Portal.setProperty(PropAccounts, accounts);
  }

  @SuppressWarnings("unchecked")
  public static void saveRefreshToken(String email, String provider, String refreshToken) 
  {
    Map<String, Object> accounts = (Map<String, Object>) Portal.getProperty(PropAccounts);
    if (accounts == null) accounts = new HashMap<>();
    
    Map<String, Object> accountData = (Map<String, Object>) accounts.get(email);
    if (accountData == null) accountData = new HashMap<>();
    
    accountData.put(KeyProvider, provider);
    accountData.put(KeyRefreshToken, refreshToken);
    
    accounts.put(email, accountData);
    Portal.setProperty(PropAccounts, accounts);
  }

  @SuppressWarnings("unchecked")
  public static String getRefreshToken(String email) 
  {
    Map<String, Object> accounts = (Map<String, Object>) Portal.getProperty(PropAccounts);
    if (accounts == null) return null;
    
    Map<String, Object> accountData = (Map<String, Object>) accounts.get(email);
    if (accountData == null) return null;
    
    return (String) accountData.get(KeyRefreshToken);
  }
  
  @SuppressWarnings("unchecked")
  public static Map<String, Map<String, Object>> getAllAccounts()
  {
    return (Map<String, Map<String, Object>>) (Map) Portal.getProperty(PropAccounts);
  }
}
