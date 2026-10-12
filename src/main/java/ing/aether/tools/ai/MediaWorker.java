package ing.aether.tools.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpSession;

import org.eclipse.jetty.util.ajax.JSON;

import ing.aether.socket.AetherWebSocket;

/**
 * Video and 3D generation. Both are asynchronous jobs with one lifecycle on the model server (hemera):
 * start, poll, download, or delete to cancel. The request fields come from the model's own parameter
 * list in /v1/models, so a new model needs no change here. Results are saved into the project through
 * the file provider, because the model server forgets finished jobs.
 */
public class MediaWorker
{
  public static final String VIDEO = LlmProviders.KindVideo;
  public static final String MODEL3D = LlmProviders.Kind3d;

  private static final Set<String> VIDEO_EXT = Set.of("mp4", "m4v", "webm", "mov");
  private static final Set<String> MODEL_EXT = Set.of("glb");
  private static final long POLL_MS = 2000;
  private static final long MAX_JOB_MS = 4L * 3600 * 1000;
  private static final int MAX_POLL_FAILURES = 15;
  // Fields every video endpoint takes even when the model publishes no parameter list
  private static final Set<String> VIDEO_COMMON = Set.of("prompt", "negative_prompt", "size", "seconds", "frames", "fps", "seed", "steps", "cfg_scale");

  private static final JSON json = new JSON();
  private static final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  // httpSessionId -> {target, endpoint, jobId} of the job that session waits for
  private static final Map<String, Object[]> activeJobs = new ConcurrentHashMap<>();

  public static boolean isVideoPath(String path)
  {
    return path != null && VIDEO_EXT.contains(ImageWorker.ext(path));
  }

  public static boolean is3dPath(String path)
  {
    return path != null && MODEL_EXT.contains(ImageWorker.ext(path));
  }

  public static boolean isMediaPath(String path)
  {
    return isVideoPath(path) || is3dPath(path);
  }

  public static String mimeFor(String path)
  {
    switch (ImageWorker.ext(path))
    {
      case "mp4": case "m4v": return "video/mp4";
      case "webm": return "video/webm";
      case "mov": return "video/quicktime";
      case "glb": return "model/gltf-binary";
      default: return "application/octet-stream";
    }
  }

  private static String defaultEndpoint(String kind)
  {
    return VIDEO.equals(kind) ? "/v1/videos" : "/v1/3d/generations";
  }

  // The model's own parameter list ({name -> {type, min, max, values, ...}}), or null when it publishes none
  @SuppressWarnings("unchecked")
  public static Map<String, Object> parametersOf(String model)
  {
    Map<String, Object> info = LlmProviders.modelInfo(model);
    return info != null && info.get("parameters") instanceof Map ? (Map<String, Object>) info.get("parameters") : null;
  }

  // One job from start to saved file. Blocks for minutes (video, vertex-colour meshes) up to tens of minutes (PBR meshes)
  @SuppressWarnings("unchecked")
  public static Map<String, Object> run(HttpSession session, String httpSessionId, String kind, String model, Map<String, Object> params) throws Exception
  {
    boolean video = VIDEO.equals(kind);
    LlmProviders.Target target = LlmProviders.resolve(model);
    if (model == null || model.isEmpty() || target == null || !kind.equals(LlmProviders.modelKind(model)))
      throw new Exception(video ? "No video model selected. Choose one in the 🎬 video model selector." : "No 3D model selected. Choose one in the 🧊 3D model selector.");

    Map<String, Object> info = LlmProviders.modelInfo(model);
    String endpoint = info != null && info.get("endpoint") instanceof String ? (String) info.get("endpoint") : defaultEndpoint(kind);
    Map<String, Object> allowed = parametersOf(model);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", target.model);
    for (Map.Entry<String, Object> e : params.entrySet())
    {
      String key = e.getKey();
      Object value = e.getValue();
      if (value == null || value.toString().trim().isEmpty() || "image".equals(key) || "end_image".equals(key) || "model".equals(key)) continue;
      Map<String, Object> spec = allowed != null && allowed.get(key) instanceof Map ? (Map<String, Object>) allowed.get(key) : null;
      // seconds and size are endpoint fields: models list frames, width and height instead
      boolean endpointField = video && VIDEO_COMMON.contains(key);
      if (spec == null && !endpointField) continue;
      Object coerced = coerce(value, spec != null ? String.valueOf(spec.get("type")) : null);
      if (coerced != null) body.put(key, coerced);
    }

    String prompt = body.get("prompt") == null ? "" : body.get("prompt").toString().trim();
    if (video && prompt.isEmpty()) throw new Exception("The video prompt is empty: describe what happens in the clip, sound included.");

    // Input pictures. For 3D the object picture is required; for video a first (and last) frame is optional
    List<String> used = new ArrayList<>();
    String imagePath = pictureFor(session, params.get("image"), !video);
    if (!video && imagePath == null) throw new Exception("3D needs a picture of the object. Open or tick an image, or generate one first.");
    if (imagePath != null)
    {
      byte[] png = ImageWorker.inputPng(session, imagePath);
      body.put("image", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
      used.add(ImageWorker.nameOf(imagePath));
      // A clip in another aspect ratio than its first frame stretches or crops the picture
      String sizeFrom = params.get("size_from") == null ? "" : params.get("size_from").toString();
      if (video && ("aspect".equals(sizeFrom) || "image".equals(sizeFrom)))
      {
        int[] fitted = sizeFromImage(png, "image".equals(sizeFrom), body, allowed);
        if (fitted != null)
        {
          body.remove("size");
          if (allowed != null && allowed.containsKey("width") && allowed.containsKey("height"))
          {
            body.put("width", (long) fitted[0]);
            body.put("height", (long) fitted[1]);
          }
          else body.put("size", fitted[0] + "x" + fitted[1]);
          used.add(fitted[0] + "×" + fitted[1]);
        }
      }
    }
    if (video && allowed != null && allowed.containsKey("end_image"))
    {
      String endPath = pictureFor(session, params.get("end_image"), false);
      if (endPath != null)
      {
        body.put("end_image", dataUrl(session, endPath));
        used.add(ImageWorker.nameOf(endPath));
      }
    }

    String what = video ? "video" : "3D model";
    AgentWorker.sendStatus(httpSessionId, "media", "Starting " + what + " on " + target.model + (used.isEmpty() ? "" : " · from " + String.join(" → ", used)));

    long start = System.currentTimeMillis();
    Map<String, Object> job = call(target, "POST", endpoint, json.toJSON(body));
    String jobId = job.get("id") == null ? null : job.get("id").toString();
    if (jobId == null) throw new Exception("The model server did not return a job id.");
    Object[] active = new Object[] { target, endpoint, jobId };
    activeJobs.put(httpSessionId, active);

    try
    {
      int failures = 0;
      String status = String.valueOf(job.get("status"));
      while ("queued".equals(status) || "in_progress".equals(status))
      {
        try { Thread.sleep(POLL_MS); }
        catch (InterruptedException ie)
        {
          cancelJob(target, endpoint, jobId);
          throw ie;
        }
        if (Thread.currentThread().isInterrupted() || activeJobs.get(httpSessionId) != active)
        {
          cancelJob(target, endpoint, jobId);
          throw new InterruptedException(what + " cancelled");
        }
        if (System.currentTimeMillis() - start > MAX_JOB_MS)
        {
          cancelJob(target, endpoint, jobId);
          throw new Exception("The " + what + " did not finish within " + (MAX_JOB_MS / 3600000) + " hours and was cancelled.");
        }
        try
        {
          job = call(target, "GET", endpoint + "/" + jobId, null);
          failures = 0;
        }
        catch (InterruptedException ie)
        {
          cancelJob(target, endpoint, jobId);
          throw ie;
        }
        catch (JobGoneException e)
        {
          // Not a hiccup: the server answered and does not have the job. Waiting longer cannot bring it back
          throw new Exception("The model server no longer knows this " + what + " job. It was probably restarted while the job was running: please start it again.");
        }
        catch (Exception e)
        {
          // The server is busy with the GPU for minutes at a time: one missed poll is not a failed job
          if (++failures >= MAX_POLL_FAILURES) throw new Exception("Lost contact with the model server while waiting for the " + what + ": " + e.getMessage());
          continue;
        }
        status = String.valueOf(job.get("status"));
        long progress = Math.round(ImageWorker.asDouble(job.get("progress"), 0));
        Map<String, Object> extra = new HashMap<>();
        extra.put("progress", progress);
        String label = "queued".equals(status) ? "Waiting for the GPU" : (video ? "Generating video" : "Building 3D model");
        AgentWorker.sendStatus(httpSessionId, "media", label + (("in_progress".equals(status)) ? " · " + progress + "%" : "") + " · " + target.model, extra);
      }

      if (!"completed".equals(status))
      {
        if ("cancelled".equals(status)) throw new InterruptedException(what + " cancelled");
        Object err = job.get("error");
        String message = err instanceof Map ? String.valueOf(((Map<String, Object>) err).get("message")) : (err != null ? err.toString() : status);
        throw new Exception("The " + what + " failed: " + message);
      }

      AgentWorker.sendStatus(httpSessionId, "saving", "Downloading the " + what + "…");
      byte[] data = download(target, endpoint + "/" + jobId + "/content");
      String contentType = job.get("content_type") != null ? job.get("content_type").toString() : "";
      String ext = video ? (contentType.contains("webm") ? "webm" : "mp4") : "glb";

      AgentWorker.sendStatus(httpSessionId, "saving", "Saving " + (data.length / (1024 * 1024)) + " MB…");
      String hint = ImageWorker.slug(params.get("name") != null ? params.get("name").toString() : (prompt.isEmpty() ? (imagePath != null ? ImageWorker.nameOf(imagePath).replaceAll("\\.[^.]*$", "") : what) : prompt));
      String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
      String dir = imagePath != null ? ImageWorker.dirOf(imagePath) : (String) session.getAttribute("workingDirectory");
      if (dir == null) throw new Exception("Set a working directory first: the result needs a folder to be saved in.");
      String resultPath = dir + "/ai-" + hint + "-" + ts + "." + ext;
      ImageWorker.writeBytes(session, resultPath, data);

      Object seed = job.get("seed");
      Object usedParams = job.get("parameters");
      if (seed == null && usedParams instanceof Map) seed = ((Map<String, Object>) usedParams).get("seed");

      Map<String, Object> out = new HashMap<>();
      out.put("kind", kind);
      out.put("path", resultPath);
      out.put("bytes", data.length);
      out.put("seconds", Math.round((System.currentTimeMillis() - start) / 1000.0));
      out.put("model", model);
      if (seed != null) out.put("seed", seed);
      if (usedParams instanceof Map) out.put("parameters", usedParams);
      if (!prompt.isEmpty()) out.put("prompt", prompt);

      // Buffered on purpose: a client that was asleep replays it on reconnect and opens the result
      Map<String, Object> done = new HashMap<>(out);
      done.put("type", "media_done");
      AetherWebSocket.sendToSession(httpSessionId, done);
      // The server keeps finished jobs in memory; the file is ours now
      cancelJob(target, endpoint, jobId);
      return out;
    }
    finally
    {
      activeJobs.remove(httpSessionId, active);
    }
  }

  // Stop for this session's job, if one is running or queued. Fire-and-forget: the caller is the WebSocket thread
  public static void cancel(String httpSessionId)
  {
    Object[] active = activeJobs.remove(httpSessionId);
    if (active == null) return;
    CompletableFuture.runAsync(() -> cancelJob((LlmProviders.Target) active[0], (String) active[1], (String) active[2]));
  }

  private static void cancelJob(LlmProviders.Target target, String endpoint, String jobId)
  {
    try
    {
      HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(target.url + endpoint + "/" + jobId)).timeout(Duration.ofSeconds(20)).DELETE();
      if (!target.apiKey.isEmpty()) b.header("Authorization", "Bearer " + target.apiKey);
      int code = httpClient.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
      if (code >= 400 && code != 404) System.out.println("WARN: deleting job " + jobId + " on " + target.provider + " returned HTTP " + code);
    }
    catch (Exception e)
    {
      System.out.println("WARN: deleting job " + jobId + " on " + target.provider + " failed: " + e.getMessage());
    }
  }

  // A picture named by the caller ("Image 2", a file name), or the first input when it is required and unnamed
  private static String pictureFor(HttpSession session, Object named, boolean required) throws Exception
  {
    String name = named == null ? null : named.toString().trim();
    if (name != null && (name.isEmpty() || "none".equalsIgnoreCase(name) || "false".equalsIgnoreCase(name))) name = null;
    if (name == null) return required ? ImageWorker.resolveInput(session, null) : null;
    if ("true".equalsIgnoreCase(name)) return ImageWorker.resolveInput(session, null);
    String path = ImageWorker.resolveInput(session, name);
    if (path == null) throw new Exception("No picture named \"" + name + "\" is open or ticked. Pictures available: " + ImageWorker.inputImages(session).size() + " (Image 1, Image 2, … in explorer order).");
    return path;
  }

  // Width and height in the picture's aspect ratio, inside what the model accepts. exact keeps the picture's own
  // pixel count; otherwise the pixel count of the size that would have been used (the user's or the model's default)
  // is kept, so matching the shape of a large photo does not also multiply the generation time
  @SuppressWarnings("unchecked")
  private static int[] sizeFromImage(byte[] png, boolean exact, Map<String, Object> body, Map<String, Object> allowed)
  {
    java.awt.image.BufferedImage img;
    try { img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png)); } catch (Exception e) { return null; }
    if (img == null || img.getWidth() < 1 || img.getHeight() < 1) return null;
    Map<String, Object> ws = allowed != null && allowed.get("width") instanceof Map ? (Map<String, Object>) allowed.get("width") : new HashMap<>();
    Map<String, Object> hs = allowed != null && allowed.get("height") instanceof Map ? (Map<String, Object>) allowed.get("height") : new HashMap<>();

    double baseW = ImageWorker.asDouble(body.get("width"), ImageWorker.asDouble(ws.get("default"), 768));
    double baseH = ImageWorker.asDouble(body.get("height"), ImageWorker.asDouble(hs.get("default"), 448));
    if (body.get("size") != null && body.get("size").toString().matches("\\d+x\\d+"))
    {
      String[] wh = body.get("size").toString().split("x");
      baseW = Double.parseDouble(wh[0]);
      baseH = Double.parseDouble(wh[1]);
    }
    double ratio = (double) img.getWidth() / img.getHeight();
    double w = exact ? img.getWidth() : Math.sqrt(baseW * baseH * ratio);
    double h = w / ratio;

    double minW = ImageWorker.asDouble(ws.get("min"), 64), maxW = ImageWorker.asDouble(ws.get("max"), 4096);
    double minH = ImageWorker.asDouble(hs.get("min"), 64), maxH = ImageWorker.asDouble(hs.get("max"), 4096);
    // Shrink into the largest allowed frame first, then grow to the smallest: the ratio wins over the pixel count
    double f = Math.min(1.0, Math.min(maxW / w, maxH / h));
    w *= f;
    h *= f;
    f = Math.max(1.0, Math.max(minW / w, minH / h));
    w *= f;
    h *= f;
    int stepW = Math.max(1, ImageWorker.asInt(ws.get("step"), 32)), stepH = Math.max(1, ImageWorker.asInt(hs.get("step"), 32));
    int rw = (int) Math.max(minW, Math.min(maxW, Math.round(w / stepW) * stepW));
    int rh = (int) Math.max(minH, Math.min(maxH, Math.round(h / stepH) * stepH));
    return new int[] { rw, rh };
  }

  private static String dataUrl(HttpSession session, String path) throws Exception
  {
    return "data:image/png;base64," + Base64.getEncoder().encodeToString(ImageWorker.inputPng(session, path));
  }

  // The form sends everything as text: turn a value into what the model's parameter type expects
  private static Object coerce(Object value, String type)
  {
    String s = value.toString().trim();
    try
    {
      if ("int".equals(type)) return (long) Math.round(Double.parseDouble(s));
      if ("number".equals(type)) return Double.parseDouble(s);
      if ("bool".equals(type)) return value instanceof Boolean ? value : Boolean.valueOf("true".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s) || "1".equals(s));
    }
    catch (NumberFormatException e) { return null; }
    if (type == null && value instanceof Number) return value;
    if (type == null && s.matches("-?\\d+")) return Long.parseLong(s);
    if (type == null && s.matches("-?\\d*\\.\\d+")) return Double.parseDouble(s);
    return s;
  }

  // A status poll answered with 404: the server has no such job (it forgets them all when it restarts)
  private static class JobGoneException extends Exception
  {
    JobGoneException(String message)
    {
      super(message);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> call(LlmProviders.Target target, String method, String path, String jsonBody) throws Exception
  {
    HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(target.url + path)).timeout(Duration.ofMinutes(jsonBody != null ? 5 : 1));
    if (!target.apiKey.isEmpty()) b.header("Authorization", "Bearer " + target.apiKey);
    if (jsonBody != null) b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
    else b.method(method, HttpRequest.BodyPublishers.noBody());
    HttpResponse<String> response = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    Map<String, Object> parsed = null;
    try { parsed = (Map<String, Object>) json.parse(new JSON.ReaderSource(new java.io.StringReader(response.body()))); } catch (Exception ignore) {}
    if (response.statusCode() >= 400)
    {
      Object err = parsed != null ? parsed.get("error") : null;
      String message = err instanceof Map ? String.valueOf(((Map<String, Object>) err).get("message")) : (err != null ? err.toString() : "HTTP " + response.statusCode());
      if (response.statusCode() == 404 && "GET".equals(method)) throw new JobGoneException(message);
      throw new Exception(message);
    }
    if (parsed == null) throw new Exception("Unreadable answer from " + target.provider + " (HTTP " + response.statusCode() + ")");
    return parsed;
  }

  private static byte[] download(LlmProviders.Target target, String path) throws Exception
  {
    HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(target.url + path)).timeout(Duration.ofMinutes(30)).GET();
    if (!target.apiKey.isEmpty()) b.header("Authorization", "Bearer " + target.apiKey);
    HttpResponse<byte[]> response = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() != 200) throw new Exception("Download failed: HTTP " + response.statusCode());
    return response.body();
  }
}
