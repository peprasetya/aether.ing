package ing.aether.tools.ai;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.Pattern;

import org.eclipse.jetty.util.ajax.JSON;

import ing.aether.Portal;

/**
 * Registry of OpenAI-compatible LLM providers (Ollama, lemonade, OpenAI, OpenRouter, ...).
 * Providers are stored in aether.json as an array of {Name, Url, ApiKey} maps; when the
 * property is absent, a single provider named "Ollama" is derived from the legacy
 * OllamaUrl/ApiKey settings so existing deployments keep working without any migration.
 *
 * Models are addressed by a qualified id "Provider::model". "::" because bare model names
 * legally contain both ":" (ollama tags) and "/" (hf.co paths). Unqualified names resolve
 * against the first provider, which keeps legacy per-project configs valid.
 */
public class LlmProviders
{
  public static final String SEPARATOR="::";
  public static final String KeyName="Name";
  public static final String KeyUrl="Url";
  public static final String KeyApiKey="ApiKey";

  private static final JSON json = new JSON();
  private static final long CACHE_TTL_MS=60000L;

  // Model-list cache: page loads must not pay N live HTTP fetches per request
  private static volatile List<String> cachedModels=null;
  private static volatile long cacheExpires=0;

  // Everything an HTTP request build site needs, resolved from a qualified model id
  public static class Target
  {
    public final String provider;
    public final String url;
    public final String apiKey;
    public final String model;

    Target(String provider,String url,String apiKey,String model)
    {
      this.provider=provider;
      this.url=url;
      this.apiKey=apiKey;
      this.model=model;
    }
  }

  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> getProviders()
  {
    List<Map<String, Object>> result=new ArrayList<>();
    Object[] arr=Portal.getProperties(Portal.PropLlmProviders);
    if (arr!=null) for (Object o:arr)
    {
      if (o instanceof Map) result.add((Map<String, Object>)o);
    }
    if (!result.isEmpty()) return result;

    // Legacy derivation: a deployment configured before multi-provider support behaves as
    // one provider named "Ollama"; nothing is written back until the admin edits providers
    Object[] urlArr=Portal.getProperties(Portal.PropOllamaUrl);
    if (urlArr!=null && urlArr.length>0)
    {
      Map<String, Object> legacy=new HashMap<>();
      legacy.put(KeyName,"Ollama");
      legacy.put(KeyUrl,urlArr[0]);
      Object[] keyArr=Portal.getProperties(Portal.PropApiKey);
      legacy.put(KeyApiKey,(keyArr!=null && keyArr.length>0)?keyArr[0]:"");
      result.add(legacy);
    }
    return result;
  }

  public static void invalidateCache()
  {
    cachedModels=null;
    cacheExpires=0;
  }

  // Qualified id -> concrete endpoint. Returns null when no provider is configured or the
  // named provider no longer exists - callers must surface that instead of misrouting.
  public static Target resolve(String qualifiedModel)
  {
    List<Map<String, Object>> providers=getProviders();
    if (providers.isEmpty()) return null;
    String providerName=null;
    String bare=qualifiedModel==null?"":qualifiedModel;
    int idx=bare.indexOf(SEPARATOR);
    if (idx>0)
    {
      providerName=bare.substring(0,idx);
      bare=bare.substring(idx+SEPARATOR.length());
    }
    if (providerName==null) return toTarget(providers.get(0),bare);
    for (Map<String, Object> p:providers)
      if (providerName.equals(str(p.get(KeyName)))) return toTarget(p,bare);
    return null;
  }

  // Bare legacy model ids gain their provider prefix so saved configs keep matching the
  // dropdown options, which are always qualified
  public static String qualify(String model)
  {
    if (model==null || model.isEmpty() || model.contains(SEPARATOR)) return model;
    List<Map<String, Object>> providers=getProviders();
    if (providers.isEmpty()) return model;
    return str(providers.get(0).get(KeyName))+SEPARATOR+model;
  }

  // The model used for internal background queries (chat titles, personality analysis)
  public static String internalModel()
  {
    Object[] arr=Portal.getProperties(Portal.PropInternalModel);
    if (arr!=null && arr.length>0 && arr[0]!=null && !arr[0].toString().isEmpty()) return arr[0].toString();
    return qualify("gemma4:e4b-it-bf16");
  }

  // Admins see everything; allowed users see only models passing the configured filters
  public static List<String> listModels(boolean isAdmin)
  {
    return listModels(isAdmin?null:Portal.getProperties(Portal.PropModelFilters));
  }

  // All models of all providers as qualified ids, cached for CACHE_TTL_MS. filters=null
  // returns everything (admin); otherwise a model passes when any regex matches its bare
  // OR qualified name, so existing filters keep working and "^Ollama::" targets a provider.
  public static List<String> listModels(Object[] filters)
  {
    List<String> all=cachedModels;
    if (all==null || System.currentTimeMillis()>cacheExpires)
    {
      all=fetchAllModels();
      cachedModels=all;
      cacheExpires=System.currentTimeMillis()+CACHE_TTL_MS;
    }
    if (filters==null || filters.length==0) return new ArrayList<>(all);

    List<Pattern> patterns=new ArrayList<>();
    for (Object f:filters)
    {
      try { patterns.add(Pattern.compile(f.toString())); } catch (Exception e) {}
    }
    List<String> result=new ArrayList<>();
    for (String qualified:all)
    {
      int idx=qualified.indexOf(SEPARATOR);
      String bare=idx>0?qualified.substring(idx+SEPARATOR.length()):qualified;
      for (Pattern p:patterns)
      {
        if (p.matcher(bare).find() || p.matcher(qualified).find())
        {
          result.add(qualified);
          break;
        }
      }
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static List<String> fetchAllModels()
  {
    List<String> result=new ArrayList<>();
    for (Map<String, Object> provider:getProviders())
    {
      String name=str(provider.get(KeyName));
      String baseUrl=str(provider.get(KeyUrl));
      if (name.isEmpty() || baseUrl.isEmpty()) continue;
      try
      {
        HttpURLConnection conn=(HttpURLConnection)new URI(baseUrl+"/v1/models").toURL().openConnection();
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(5000);
        String apiKey=str(provider.get(KeyApiKey));
        if (!apiKey.isEmpty()) conn.setRequestProperty("Authorization","Bearer "+apiKey);
        try (InputStream is=conn.getInputStream())
        {
          Map<String, Object> resp=(Map<String, Object>)json.parse(new JSON.ReaderSource(new StringReader(new String(is.readAllBytes(),"UTF-8"))));
          Object[] data=resp.get("data") instanceof Object[]?(Object[])resp.get("data"):null;
          List<String> models=new ArrayList<>();
          if (data!=null) for (Object o:data)
          {
            Object id=((Map<String, Object>)o).get("id");
            if (id!=null) models.add(id.toString());
          }
          Collections.sort(models);
          for (String m:models) result.add(name+SEPARATOR+m);
        }
      } catch (Exception e)
      {
        // A provider being down must not empty the whole dropdown; its models just drop out
        System.out.println("WARN: model listing failed for provider '"+name+"': "+e.getMessage());
      }
    }
    return result;
  }

  private static Target toTarget(Map<String, Object> provider,String bareModel)
  {
    return new Target(str(provider.get(KeyName)),str(provider.get(KeyUrl)),str(provider.get(KeyApiKey)),bareModel);
  }

  private static String str(Object o)
  {
    return o==null?"":o.toString();
  }
}
