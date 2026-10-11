package ing.aether.tools.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpSession;

import org.eclipse.jetty.util.ajax.JSON;

import ing.aether.SessionTracker;
import ing.aether.socket.AetherWebSocket;

/**
 * The cast of a story project. AGENTS.md stays the only place the user writes characters; the internal
 * model reads it and the resulting names are cached in the project's aether.ing.json under "characters",
 * keyed by a hash of AGENTS.md so an unchanged file never costs a model call. The chat pane offers the
 * names in a dropdown and the selected one fills the [[CHARACTER]] placeholder of a command template.
 */
public class CharacterWorker
{
  private static final JSON json = new JSON();
  private static final int MAX_CHARACTERS = 40;
  private static final long RUN_GUARD_MS = 6L * 60 * 1000;
  private static final Map<String, Long> running = new ConcurrentHashMap<>();

  private static final String EXTRACT_PROMPT = "You extract the cast of a story from a project document. Reply with ONLY a JSON object, no markdown and no explanation: "
    + "{\"characters\":[{\"name\":\"Julian\",\"aliases\":[\"Jul\"]}]}. "
    + "Read the whole document line by line and return an entry for EVERY named person or creature that acts or speaks in the story, not only the first ones; main characters first. Use each name exactly as the document writes it; "
    + "aliases are other names or nicknames the document uses for the same character. "
    + "Do not include places, organisations, objects, the narrator, the author or the reader. At most " + MAX_CHARACTERS + " characters. "
    + "If the document names no characters, return {\"characters\":[]}.";

  // Pushes the stored list at once, then re-extracts it when AGENTS.md changed since the last extraction or force is set.
  // Runs in the background; the browser receives a characters_update for each stage
  public static void sync(HttpSession session, boolean force)
  {
    final String workingDir = (String) session.getAttribute("workingDirectory");
    if (workingDir == null) return;
    final String sid = session.getId();
    CompletableFuture.runAsync(() ->
    {
      try
      {
        HttpSession live = SessionTracker.getSessionById(sid);
        if (live == null) live = session;
        String md;
        try { md = AITools.readFile(live, workingDir + "/AGENTS.md"); }
        catch (Exception e) { push(sid, workingDir, new ArrayList<>()); return; }
        if (md == null) md = "";
        String hash = sha256(md);

        Map<String, Object> stored = readStored(live, workingDir);
        push(sid, workingDir, storedList(stored));
        if (!force && stored != null && hash.equals(stored.get("hash"))) return;
        if (md.trim().isEmpty()) return;

        Long since = running.get(workingDir);
        if (!force && since != null && System.currentTimeMillis() - since < RUN_GUARD_MS) return;
        running.put(workingDir, System.currentTimeMillis());

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", EXTRACT_PROMPT));
        messages.add(Map.of("role", "user", "content", md));
        final String mdText = md;
        AgentWorker.internalQuery(sid, "character list", messages, text ->
        {
          try
          {
            List<Map<String, Object>> list = parse(text, mdText);
            if (list == null)
            {
              AetherWebSocket.notify(sid, "Could not read a character list from the model's answer. Try Resync again.");
              return;
            }
            HttpSession s = SessionTracker.getSessionById(sid);
            if (s == null) s = session;
            save(s, workingDir, hash, list);
            push(sid, workingDir, list);
          }
          catch (Exception e) { System.out.println("WARN: saving the character list failed: " + e.getMessage()); }
          finally { running.remove(workingDir); }
        });
      }
      catch (Exception e) { System.out.println("WARN: character sync failed: " + e.getMessage()); }
    });
  }

  // The instruction a command template receives as [[CHARACTER]]; empty for the narrator (nothing selected)
  public static String directive(String name)
  {
    if (name == null) return "";
    String clean = name.replaceAll("[\\p{Cntrl}]", " ").trim();
    if (clean.isEmpty()) return "";
    if (clean.length() > 60) clean = clean.substring(0, 60);
    return "Character focus: let this passage dwell on " + clean + " — their actions, gestures, facial expressions, spoken dialogue and reactions. "
      + "Other characters may still act and speak. The narration person, tense and voice stay exactly as the project rules define them.";
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> parse(String text, String md)
  {
    String t = text.trim();
    int a = t.indexOf('{'), b = t.lastIndexOf('}');
    if (a < 0 || b <= a) return null;
    Object parsed;
    try { parsed = json.parse(new JSON.ReaderSource(new java.io.StringReader(t.substring(a, b + 1)))); }
    catch (Exception e) { return null; }
    if (!(parsed instanceof Map)) return null;
    Object arr = ((Map<String, Object>) parsed).get("characters");
    Iterable<?> items = arr instanceof Object[] ? Arrays.asList((Object[]) arr) : arr instanceof Iterable ? (Iterable<?>) arr : null;
    if (items == null) return null;

    String lowerMd = md.toLowerCase();
    List<Map<String, Object>> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Object o : items)
    {
      String name = null;
      List<String> aliases = new ArrayList<>();
      if (o instanceof String) name = (String) o;
      else if (o instanceof Map)
      {
        Object n = ((Map<String, Object>) o).get("name");
        if (n != null) name = n.toString();
        Object al = ((Map<String, Object>) o).get("aliases");
        Iterable<?> ai = al instanceof Object[] ? Arrays.asList((Object[]) al) : al instanceof Iterable ? (Iterable<?>) al : null;
        if (ai != null) for (Object x : ai) if (x != null) aliases.add(x.toString().trim());
      }
      if (name == null) continue;
      name = name.replaceAll("[\\p{Cntrl}]", " ").trim();
      // A name the document never mentions is the model's invention
      if (name.isEmpty() || name.length() > 60 || !lowerMd.contains(name.toLowerCase())) continue;
      if (!seen.add(name.toLowerCase())) continue;
      List<String> keptAliases = new ArrayList<>();
      for (String al : aliases) if (!al.isEmpty() && al.length() <= 60 && lowerMd.contains(al.toLowerCase()) && !al.equalsIgnoreCase(name)) keptAliases.add(al);
      Map<String, Object> c = new LinkedHashMap<>();
      c.put("name", name);
      c.put("aliases", keptAliases.toArray());
      out.add(c);
      if (out.size() >= MAX_CHARACTERS) break;
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> readStored(HttpSession session, String workingDir)
  {
    try
    {
      Map<String, Object> config = (Map<String, Object>) json.parse(new JSON.ReaderSource(new java.io.StringReader(AITools.readFile(session, workingDir + "/aether.ing.json"))));
      Object c = config.get("characters");
      return c instanceof Map ? (Map<String, Object>) c : null;
    }
    catch (Exception e) { return null; }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> storedList(Map<String, Object> stored)
  {
    List<Map<String, Object>> list = new ArrayList<>();
    if (stored == null) return list;
    Object l = stored.get("list");
    Iterable<?> items = l instanceof Object[] ? Arrays.asList((Object[]) l) : l instanceof Iterable ? (Iterable<?>) l : null;
    if (items != null) for (Object o : items) if (o instanceof Map) list.add((Map<String, Object>) o);
    return list;
  }

  private static void save(HttpSession session, String workingDir, String hash, List<Map<String, Object>> list) throws Exception
  {
    AgentWorker.atomicConfigWrite(session, workingDir + "/aether.ing.json", (s, config) ->
    {
      Map<String, Object> c = new LinkedHashMap<>();
      c.put("hash", hash);
      c.put("updated", System.currentTimeMillis());
      c.put("list", list.toArray());
      config.put("characters", c);
    });
  }

  private static void push(String sid, String workingDir, List<Map<String, Object>> list)
  {
    Map<String, Object> msg = new HashMap<>();
    msg.put("type", "characters_update");
    msg.put("workingDir", workingDir);
    msg.put("characters", list.toArray());
    AetherWebSocket.sendToSession(sid, msg, false);
  }

  private static String sha256(String s) throws Exception
  {
    StringBuilder sb = new StringBuilder();
    for (byte b : MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
    return sb.toString();
  }
}
