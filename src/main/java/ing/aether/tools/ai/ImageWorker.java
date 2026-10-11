package ing.aether.tools.ai;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import javax.imageio.ImageIO;

import org.eclipse.jetty.util.ajax.JSON;

import ing.aether.data.FileProvider;
import ing.aether.data.PathMap;
import ing.aether.socket.AetherWebSocket;
import jakarta.servlet.http.HttpSession;

/**
 * AI image generation and editing against an OpenAI-compatible images endpoint (hemera / sd.cpp),
 * plus the layered-image sidecar "<image>.layers.json" shared by the viewer and the AI.
 *
 * Layers are composed here with Java2D rather than in the browser, so a tool call in the middle of a
 * turn never has to ask the client for pixels: a run of several minutes finishes and is saved even
 * when the user's device is asleep. The transform is the one scriptlib.js draws with - translate to
 * the layer centre, rotate, scale, draw centred - so the canvas and the composite agree.
 */
public class ImageWorker
{
  public static final String SIDECAR_SUFFIX = ".layers.json";
  private static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "avif", "ico");
  // What ImageIO decodes; the viewer converts every other format to PNG before a layer refers to it
  private static final Set<String> READABLE_EXT = Set.of("png", "jpg", "jpeg", "gif", "bmp");
  private static final JSON json = new JSON();
  private static final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  // sd.cpp on the 8060S iGPU runs 1024x1024 at 20 steps for roughly nine minutes
  private static final Duration IMAGE_TIMEOUT = Duration.ofMinutes(30);
  private static final int VISION_MAX_SIDE = 768;

  // Seconds per (step x megapixel), per image model: seeded from a measured hemera run (52s for
  // 512x512 at 8 steps) and refined after every run, so estimates track the real hardware
  private static final double DEFAULT_RATE = 25.0;
  private static final Map<String, Double> secondsPerStepMp = new ConcurrentHashMap<>();

  // The viewer autosaves the sidecar while a generation may be appending its result layer
  private static final Map<String, ReentrantLock> sidecarLocks = new ConcurrentHashMap<>();

  // The image request in flight per HTTP session: {target, request id}. Stop hands the id to hemera's
  // cancel route, which kills the engine and releases the GPU; merely dropping our side of the
  // connection would leave the image rendering for minutes
  private static final Map<String, Object[]> activeRequests = new ConcurrentHashMap<>();
  private static final String CANCEL_PATH = "/hemera/api/images/cancel";

  public static boolean isImagePath(String path)
  {
    return path != null && IMAGE_EXT.contains(ext(path));
  }

  public static String ext(String path)
  {
    if (path == null) return "";
    String name = nameOf(path);
    int dot = name.lastIndexOf('.');
    return dot == -1 ? "" : name.substring(dot + 1).toLowerCase();
  }

  public static String mimeFor(String path)
  {
    switch (ext(path))
    {
      case "png": return "image/png";
      case "jpg": case "jpeg": return "image/jpeg";
      case "gif": return "image/gif";
      case "webp": return "image/webp";
      case "bmp": return "image/bmp";
      case "svg": return "image/svg+xml";
      case "avif": return "image/avif";
      case "ico": return "image/x-icon";
      case "json": return "application/json";
      default: return "application/octet-stream";
    }
  }

  public static String nameOf(String path)
  {
    int slash = path.lastIndexOf('/');
    return slash == -1 ? path : path.substring(slash + 1);
  }

  public static String dirOf(String path)
  {
    int slash = path.lastIndexOf('/');
    return slash == -1 ? "" : path.substring(0, slash);
  }

  public static ReentrantLock sidecarLock(String sidecarPath)
  {
    return sidecarLocks.computeIfAbsent(sidecarPath, k -> new ReentrantLock());
  }

  // ---------- byte I/O through PathMap, which enforces provider ownership ----------

  public static byte[] readBytes(HttpSession session, String fullPath) throws Exception
  {
    PathMap pm = new PathMap("/read/" + fullPath, session);
    FileProvider provider = pm.getProvider();
    if (provider == null) throw new Exception("Provider not found for path: " + fullPath);
    try (InputStream is = provider.getInputStream(pm.getSuffix()))
    {
      return is.readAllBytes();
    }
  }

  public static void writeBytes(HttpSession session, String fullPath, byte[] data) throws Exception
  {
    PathMap pm = new PathMap("/save/" + fullPath, session);
    FileProvider provider = pm.getProvider();
    if (provider == null) throw new Exception("Provider not found for path: " + fullPath);
    try (OutputStream os = provider.getOutputStream(pm.getSuffix()))
    {
      os.write(data);
    }
  }

  // ---------- sidecar ----------

  @SuppressWarnings("unchecked")
  public static Map<String, Object> loadSidecar(HttpSession session, String imagePath)
  {
    String sidecarPath = imagePath + SIDECAR_SUFFIX;
    if (!AITools.exists(session, sidecarPath)) return null;
    try
    {
      String content = new String(readBytes(session, sidecarPath), StandardCharsets.UTF_8);
      return (Map<String, Object>) json.parse(new JSON.ReaderSource(new StringReader(content)));
    } catch (Exception e)
    {
      System.out.println("WARN: unreadable layer file " + sidecarPath + ": " + e.getMessage());
      return null;
    }
  }

  // The implicit one-layer document of an image that was never layered; null when ImageIO cannot read it
  public static Map<String, Object> defaultSidecar(HttpSession session, String imagePath)
  {
    try
    {
      BufferedImage base = readImage(session, imagePath);
      Map<String, Object> layer = new HashMap<>();
      layer.put("id", "base");
      layer.put("name", nameOf(imagePath));
      layer.put("src", nameOf(imagePath));
      layer.put("x", base.getWidth() / 2.0);
      layer.put("y", base.getHeight() / 2.0);
      layer.put("scale", 1.0);
      layer.put("rotation", 0.0);
      layer.put("opacity", 1.0);
      layer.put("visible", true);
      Map<String, Object> doc = new HashMap<>();
      doc.put("version", 1);
      doc.put("width", base.getWidth());
      doc.put("height", base.getHeight());
      doc.put("layers", new Object[] { layer });
      return doc;
    } catch (Exception e) { return null; }
  }

  public static Map<String, Object> sidecarOrDefault(HttpSession session, String imagePath)
  {
    Map<String, Object> doc = loadSidecar(session, imagePath);
    return doc != null ? doc : defaultSidecar(session, imagePath);
  }

  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> layers(Map<String, Object> doc)
  {
    List<Map<String, Object>> result = new ArrayList<>();
    Object arr = doc == null ? null : doc.get("layers");
    if (arr instanceof Object[]) for (Object o : (Object[]) arr) result.add((Map<String, Object>) o);
    else if (arr instanceof List) for (Object o : (List<Object>) arr) result.add((Map<String, Object>) o);
    return result;
  }

  // A layer names its file relative to the image's folder ("src") or by full provider path ("url")
  public static String layerPath(String imagePath, Map<String, Object> layer)
  {
    Object url = layer.get("url");
    if (url instanceof String && !((String) url).isEmpty()) return (String) url;
    String dir = dirOf(imagePath);
    return (dir.isEmpty() ? "" : dir + "/") + layer.get("src");
  }

  // ---------- composing ----------

  public static BufferedImage readImage(HttpSession session, String fullPath) throws Exception
  {
    if (!READABLE_EXT.contains(ext(fullPath))) throw new Exception(nameOf(fullPath) + " is " + ext(fullPath).toUpperCase() + ", which the server cannot decode; open it in the viewer once so it is converted to PNG.");
    BufferedImage img = ImageIO.read(new ByteArrayInputStream(readBytes(session, fullPath)));
    if (img == null) throw new Exception("Cannot decode image " + nameOf(fullPath));
    return img;
  }

  // Visible layers, bottom to top, onto a transparent canvas of the document size
  public static BufferedImage compose(HttpSession session, String imagePath, Map<String, Object> doc) throws Exception
  {
    int w = asInt(doc.get("width"), 0);
    int h = asInt(doc.get("height"), 0);
    if (w <= 0 || h <= 0) throw new Exception("Layer document has no canvas size");
    BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = out.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    for (Map<String, Object> layer : layers(doc))
    {
      if (Boolean.FALSE.equals(layer.get("visible"))) continue;
      BufferedImage img = readImage(session, layerPath(imagePath, layer));
      AffineTransform t = new AffineTransform();
      t.translate(asDouble(layer.get("x"), w / 2.0), asDouble(layer.get("y"), h / 2.0));
      t.rotate(Math.toRadians(asDouble(layer.get("rotation"), 0)));
      double s = asDouble(layer.get("scale"), 1);
      t.scale(s, s);
      t.translate(-img.getWidth() / 2.0, -img.getHeight() / 2.0);
      float opacity = (float) Math.max(0, Math.min(1, asDouble(layer.get("opacity"), 1)));
      g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
      g.drawImage(img, t, null);
    }
    g.dispose();
    return out;
  }

  public static byte[] toPng(BufferedImage img) throws Exception
  {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    ImageIO.write(img, "png", bos);
    return bos.toByteArray();
  }

  // Small flattened JPEG of the open canvas for a vision-capable chat model; null when there is nothing to show
  public static String canvasDataUrl(HttpSession session, String imagePath)
  {
    try
    {
      Map<String, Object> doc = sidecarOrDefault(session, imagePath);
      if (doc == null) return null;
      return previewDataUrl(compose(session, imagePath, doc));
    } catch (Exception e)
    {
      System.out.println("WARN: canvas preview for the chat model failed: " + e.getMessage());
      return null;
    }
  }

  // Images the user ticked in the explorer, in order, plus the open image (first) when it is not ticked.
  // The open image stands for its flattened canvas. Chat vision, edit inputs and the context text all use this order
  public static List<String> inputImages(HttpSession session)
  {
    List<String> out = new ArrayList<>();
    String open = (String) session.getAttribute("lastFilePath");
    Object cf = session.getAttribute("turnContextFiles");
    if (cf instanceof List) for (Object o : (List<?>) cf)
    {
      String p = String.valueOf(o);
      if (isImagePath(p) && !out.contains(p)) out.add(p);
    }
    if (isImagePath(open) && !out.contains(open)) out.add(0, open);
    return out;
  }

  // "@portrait.jpg" in a prompt becomes "image N", the number the model is given (see inputImages). Longest names
  // first so "@a.png" cannot eat the start of "@a.png.bak". An unknown @name is left untouched
  public static String resolveMentions(HttpSession session, String text)
  {
    if (text == null || text.indexOf('@') < 0) return text;
    List<String> inputs = inputImages(session);
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < inputs.size(); i++) order.add(i);
    order.sort((a, b) -> nameOf(inputs.get(b)).length() - nameOf(inputs.get(a)).length());
    for (int i : order) text = text.replace("@" + nameOf(inputs.get(i)), "image " + (i + 1));
    return text;
  }

  // One input picture as PNG bytes: the flattened canvas for the open image, the file itself otherwise
  public static byte[] inputPng(HttpSession session, String path) throws Exception
  {
    if (path.equals(session.getAttribute("lastFilePath")))
    {
      Map<String, Object> doc = sidecarOrDefault(session, path);
      if (doc != null) return toPng(compose(session, path, doc));
    }
    return toPng(readImage(session, path));
  }

  // "Image 2", a file name or a full path, resolved against inputImages; a null or empty name is the first input.
  // Returns null when there is no such picture
  public static String resolveInput(HttpSession session, String name)
  {
    List<String> inputs = inputImages(session);
    if (name == null || name.trim().isEmpty()) return inputs.isEmpty() ? null : inputs.get(0);
    String n = name.trim();
    for (int i = 0; i < inputs.size(); i++) if (n.equals(inputs.get(i)) || n.equals(nameOf(inputs.get(i))) || n.equalsIgnoreCase("image " + (i + 1))) return inputs.get(i);
    return null;
  }

  // Downscaled JPEG data URL of one input image: the canvas for the open image, the file otherwise
  public static String inputDataUrl(HttpSession session, String path)
  {
    if (path.equals(session.getAttribute("lastFilePath"))) return canvasDataUrl(session, path);
    try
    {
      return previewDataUrl(readImage(session, path));
    } catch (Exception e)
    {
      System.out.println("WARN: preview of " + nameOf(path) + " for the chat model failed: " + e.getMessage());
      return null;
    }
  }

  private static String previewDataUrl(BufferedImage full)
  {
    try
    {
      double f = Math.min(1.0, (double) VISION_MAX_SIDE / Math.max(full.getWidth(), full.getHeight()));
      int w = Math.max(1, (int) Math.round(full.getWidth() * f));
      int h = Math.max(1, (int) Math.round(full.getHeight() * f));
      // JPEG has no alpha: transparency shows as white, which is what a person would see on paper
      BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
      Graphics2D g = small.createGraphics();
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, w, h);
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.drawImage(full, 0, 0, w, h, null);
      g.dispose();
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      ImageIO.write(small, "jpg", bos);
      return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bos.toByteArray());
    } catch (Exception e)
    {
      System.out.println("WARN: image preview for the chat model failed: " + e.getMessage());
      return null;
    }
  }

  // Plain-text view of the open image for the model's context turn: it cannot read pixels as text
  public static String describe(HttpSession session, String imagePath, String relativePath)
  {
    Map<String, Object> doc = sidecarOrDefault(session, imagePath);
    StringBuilder sb = new StringBuilder();
    sb.append("An image is open in the viewer: ").append(relativePath);
    if (doc == null) return sb.append(" (format not readable by the server).").toString();
    sb.append(" (canvas ").append(asInt(doc.get("width"), 0)).append("x").append(asInt(doc.get("height"), 0)).append(" px)\n");
    sb.append("Layers, bottom to top:\n");
    int i = 1;
    for (Map<String, Object> layer : layers(doc))
    {
      sb.append(i++).append(". \"").append(layer.get("name")).append("\" — file ").append(layer.get("url") != null ? layer.get("url") : layer.get("src"));
      sb.append(Boolean.FALSE.equals(layer.get("visible")) ? ", hidden" : ", visible");
      sb.append(", centre (").append(Math.round(asDouble(layer.get("x"), 0))).append(",").append(Math.round(asDouble(layer.get("y"), 0))).append(")");
      sb.append(", scale ").append(String.format(Locale.ROOT, "%.2f", asDouble(layer.get("scale"), 1)));
      sb.append(", rotation ").append(Math.round(asDouble(layer.get("rotation"), 0))).append("°\n");
    }
    return sb.toString();
  }

  // ---------- generation ----------

  public static long estimateSeconds(String model, int w, int h, int steps)
  {
    double rate = secondsPerStepMp.getOrDefault(model, DEFAULT_RATE);
    return Math.round(rate * steps * (w * (double) h / 1_000_000.0));
  }

  private static void recordRate(String model, double seconds, int w, int h, int steps)
  {
    double work = steps * (w * (double) h / 1_000_000.0);
    if (work <= 0) return;
    double measured = seconds / work;
    Double old = secondsPerStepMp.get(model);
    secondsPerStepMp.put(model, old == null ? measured : old * 0.7 + measured * 0.3);
  }

  public static String formatDuration(long seconds)
  {
    if (seconds < 60) return seconds + "s";
    return (seconds / 60) + "m" + String.format("%02d", seconds % 60) + "s";
  }

  // Sizes must be multiples of 32 for sd.cpp; "auto" follows the canvas aspect with a 1024 long side
  private static int[] resolveSize(Object sizeArg, Map<String, Object> doc)
  {
    String size = sizeArg == null ? "auto" : sizeArg.toString().trim().toLowerCase();
    int w = 768, h = 768;
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)\\s*[x×*]\\s*(\\d+)$").matcher(size);
    if (m.matches())
    {
      w = Integer.parseInt(m.group(1));
      h = Integer.parseInt(m.group(2));
    }
    else if (doc != null)
    {
      int cw = asInt(doc.get("width"), 768), ch = asInt(doc.get("height"), 768);
      double f = Math.min(1024.0, Math.max(cw, ch)) / Math.max(cw, ch);
      w = (int) Math.round(cw * f);
      h = (int) Math.round(ch * f);
    }
    return new int[] { round32(w), round32(h) };
  }

  private static int round32(int v)
  {
    return Math.max(256, Math.min(2048, Math.round(v / 32.0f) * 32));
  }

  /**
   * One generation or edit. params: mode ("generate"/"edit"), prompt, size, steps, transparent,
   * source ("canvas"/"selected"/"layers"), selected (layer id), layers (layer names), name (file hint).
   * The result is saved next to the open image and appended to its layers, or into the working
   * directory when no image is open. Blocks for the whole run; a user stop (cancel) ends it with
   * InterruptedException.
   */
  public static Map<String, Object> run(HttpSession session, String httpSessionId, String imageModel, Map<String, Object> params) throws Exception
  {
    LlmProviders.Target target = LlmProviders.resolve(imageModel);
    if (imageModel == null || imageModel.isEmpty() || target == null || !LlmProviders.KindImage.equals(LlmProviders.modelKind(imageModel)))
      throw new Exception("No image model selected. Choose one in the 🖼️ image model selector.");

    String prompt = params.get("prompt") == null ? "" : params.get("prompt").toString().trim();
    if (prompt.isEmpty()) throw new Exception("The image prompt is empty.");
    String mode = "edit".equals(params.get("mode")) ? "edit" : "generate";

    String lastFile = (String) session.getAttribute("lastFilePath");
    String imagePath = isImagePath(lastFile) ? lastFile : null;
    Map<String, Object> doc = imagePath != null ? sidecarOrDefault(session, imagePath) : null;

    int[] size = resolveSize(params.get("size"), doc);
    int steps = Math.max(1, Math.min(100, asInt(params.get("steps"), 14)));
    boolean transparent = Boolean.TRUE.equals(params.get("transparent")) || "true".equals(String.valueOf(params.get("transparent")));

    Map<String, Object> body = new HashMap<>();
    body.put("model", target.model);
    body.put("prompt", prompt);
    body.put("size", size[0] + "x" + size[1]);
    body.put("steps", steps);
    if (transparent) body.put("background", "transparent");
    // Without a seed the engine reuses its default one, so the same prompt gave the same picture every time
    long seed = params.get("seed") != null ? (long) asDouble(params.get("seed"), 0) : (long) (Math.random() * 2147483647L);
    body.put("seed", seed);

    if ("edit".equals(mode))
    {
      if (doc == null && inputImages(session).isEmpty()) throw new Exception("Editing needs an open or ticked image. Open one in the viewer, tick files in the explorer, or ask for a new image instead.");
      List<byte[]> inputs = editInputs(session, imagePath, doc, params);
      List<String> encoded = new ArrayList<>();
      long bytes = 0;
      for (byte[] b : inputs)
      {
        encoded.add(Base64.getEncoder().encodeToString(b));
        bytes += b.length;
      }
      body.put("images", encoded);
      List<String> order = new ArrayList<>();
      int n = 1;
      for (String p : inputImages(session)) order.add("Image " + n++ + " = " + nameOf(p));
      AgentWorker.sendStatus(httpSessionId, "image", "Sending " + inputs.size() + (inputs.size() == 1 ? " image" : " images") + " (" + (bytes / 1024) + " KB) to " + target.model + (inputs.size() > 1 && source(params).equals("canvas") ? " · " + String.join(", ", order) : ""));
    }

    long est = estimateSeconds(imageModel, size[0], size[1], steps);
    Map<String, Object> startStatus = new HashMap<>();
    startStatus.put("estimateSec", est);
    AgentWorker.sendStatus(httpSessionId, "image", ("edit".equals(mode) ? "Editing" : "Generating") + " " + size[0] + "×" + size[1] + " · " + steps + " steps · est. ~" + formatDuration(est), startStatus);

    long start = System.nanoTime();
    String requestId = "aether-" + UUID.randomUUID();
    Object[] active = new Object[] { target, requestId };
    activeRequests.put(httpSessionId, active);
    byte[] png;
    try
    {
      png = callImages(target, "edit".equals(mode) ? "edits" : "generations", body, requestId);
    } finally
    {
      activeRequests.remove(httpSessionId, active);
    }
    double seconds = (System.nanoTime() - start) / 1e9;
    recordRate(imageModel, seconds, size[0], size[1], steps);

    AgentWorker.sendStatus(httpSessionId, "saving", "Saving image…");
    String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
    String hint = slug(params.get("name") != null ? params.get("name").toString() : prompt);
    String resultPath;
    if (imagePath != null)
    {
      String base = nameOf(imagePath);
      int dot = base.lastIndexOf('.');
      resultPath = dirOf(imagePath) + "/" + (dot > 0 ? base.substring(0, dot) : base) + ".ai-" + hint + "-" + ts + ".png";
    }
    else
    {
      String workingDir = (String) session.getAttribute("workingDirectory");
      if (workingDir == null) throw new Exception("Set a working directory first: the image needs a folder to be saved in.");
      resultPath = workingDir + "/ai-" + hint + "-" + ts + ".png";
    }
    writeBytes(session, resultPath, png);

    BufferedImage result = ImageIO.read(new ByteArrayInputStream(png));
    int rw = result != null ? result.getWidth() : size[0];
    int rh = result != null ? result.getHeight() : size[1];

    Map<String, Object> layer = null;
    if (imagePath != null && doc != null) layer = appendLayer(session, imagePath, nameOf(resultPath), hint, rw, rh);

    Map<String, Object> out = new HashMap<>();
    out.put("path", resultPath);
    out.put("width", rw);
    out.put("height", rh);
    out.put("seconds", Math.round(seconds));
    out.put("prompt", prompt);
    out.put("model", imageModel);
    out.put("seed", seed);
    // imagePath tells the viewer to add the layer; without a layer the client opens the new image instead
    if (layer != null)
    {
      out.put("imagePath", imagePath);
      out.put("layer", layer);
    }

    // Buffered on purpose: a client that was asleep replays it on reconnect and picks up the layer
    Map<String, Object> done = new HashMap<>(out);
    done.put("type", "image_done");
    AetherWebSocket.sendToSession(httpSessionId, done);
    return out;
  }

  private static List<byte[]> editInputs(HttpSession session, String imagePath, Map<String, Object> doc, Map<String, Object> params) throws Exception
  {
    String source = params.get("source") == null ? "canvas" : params.get("source").toString();
    List<byte[]> inputs = new ArrayList<>();
    List<Map<String, Object>> all = doc != null ? layers(doc) : new ArrayList<>();
    List<String> checked = inputImages(session);

    // Explicit layer names (from the AI tool) win over the viewer's source setting
    Set<String> names = new LinkedHashSet<>();
    Object named = params.get("layers");
    if (named instanceof Object[]) for (Object o : (Object[]) named) names.add(String.valueOf(o));
    else if (named instanceof List) for (Object o : (List<?>) named) names.add(String.valueOf(o));

    if (!names.isEmpty())
    {
      for (String n : names)
      {
        Map<String, Object> hit = null;
        for (Map<String, Object> l : all) if (n.equals(l.get("name")) || n.equals(l.get("id")) || n.equals(l.get("src"))) { hit = l; break; }
        if (hit != null) { inputs.add(toPng(readImage(session, layerPath(imagePath, hit)))); continue; }
        // Not a layer: a ticked file may be named by its file name or path, or as "Image N"
        String file = null;
        for (int i = 0; i < checked.size(); i++) if (n.equals(checked.get(i)) || n.equals(nameOf(checked.get(i))) || n.equalsIgnoreCase("image " + (i + 1))) { file = checked.get(i); break; }
        if (file == null) throw new Exception("No layer or ticked image named \"" + n + "\". Layers: " + layerNames(all) + ". Ticked images: " + checked.size());
        inputs.add(toPng(file.equals(imagePath) ? compose(session, imagePath, doc) : readImage(session, file)));
      }
      return inputs;
    }

    if ("selected".equals(source))
    {
      Object sel = params.get("selected");
      for (Map<String, Object> l : all) if (sel != null && sel.equals(l.get("id"))) inputs.add(toPng(readImage(session, layerPath(imagePath, l))));
      if (inputs.isEmpty()) throw new Exception("No layer is selected in the viewer.");
      for (String p : checked) if (!p.equals(imagePath)) inputs.add(toPng(readImage(session, p)));
      return inputs;
    }
    if ("layers".equals(source))
    {
      for (Map<String, Object> l : all) if (!Boolean.FALSE.equals(l.get("visible"))) inputs.add(toPng(readImage(session, layerPath(imagePath, l))));
      for (String p : checked) if (!p.equals(imagePath)) inputs.add(toPng(readImage(session, p)));
      return inputs;
    }
    for (String p : checked) inputs.add(toPng(p.equals(imagePath) ? compose(session, imagePath, doc) : readImage(session, p)));
    if (inputs.isEmpty() && doc != null) inputs.add(toPng(compose(session, imagePath, doc)));
    return inputs;
  }

  private static String source(Map<String, Object> params)
  {
    return params.get("source") == null ? "canvas" : params.get("source").toString();
  }

  private static String layerNames(List<Map<String, Object>> all)
  {
    List<String> n = new ArrayList<>();
    for (Map<String, Object> l : all) n.add(String.valueOf(l.get("name")));
    return n.toString();
  }

  // Stop for this session's image request, if one is running or queued. Fire-and-forget: the caller is
  // the WebSocket thread, and the waiting worker learns the outcome from its own 409 response
  public static void cancel(String httpSessionId)
  {
    Object[] active = activeRequests.remove(httpSessionId);
    if (active == null) return;
    LlmProviders.Target target = (LlmProviders.Target) active[0];
    String requestId = (String) active[1];
    HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(target.url + CANCEL_PATH)).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.toJSON(Map.of("id", requestId))));
    if (!target.apiKey.isEmpty()) b.header("Authorization", "Bearer " + target.apiKey);
    httpClient.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) ->
    {
      // 404 means it had already finished; anything else leaves the GPU busy and is worth knowing
      if (err != null) System.out.println("WARN: image cancel failed for " + requestId + ": " + err.getMessage());
      else if (resp.statusCode() != 200 && resp.statusCode() != 404) System.out.println("WARN: image cancel for " + requestId + " answered HTTP " + resp.statusCode() + ": " + resp.body());
    });
  }

  @SuppressWarnings("unchecked")
  private static byte[] callImages(LlmProviders.Target target, String endpoint, Map<String, Object> body, String requestId) throws Exception
  {
    HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(target.url + "/v1/images/" + endpoint)).timeout(IMAGE_TIMEOUT).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.toJSON(body)));
    if (!target.apiKey.isEmpty()) b.header("Authorization", "Bearer " + target.apiKey);
    // Hemera keys its cancel route on this id; other OpenAI-compatible servers ignore the header
    b.header("X-Hemera-Request-Id", requestId);
    HttpResponse<String> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
    String text = resp.body() == null ? "" : resp.body();
    // Our own cancel: surface it as a stop, never as an error card
    if (resp.statusCode() == 409 && text.contains("was cancelled")) throw new InterruptedException("image request cancelled");
    if (resp.statusCode() != 200) throw new Exception("Image model HTTP " + resp.statusCode() + (text.isEmpty() ? "" : ": " + (text.length() > 300 ? text.substring(0, 300) : text)));
    Map<String, Object> parsed = (Map<String, Object>) json.parse(new JSON.ReaderSource(new StringReader(text)));
    Object[] data = parsed.get("data") instanceof Object[] ? (Object[]) parsed.get("data") : null;
    if (data == null || data.length == 0) throw new Exception("Image model returned no image" + (parsed.get("error") != null ? ": " + parsed.get("error") : "."));
    Object b64 = ((Map<String, Object>) data[0]).get("b64_json");
    if (!(b64 instanceof String)) throw new Exception("Image model returned no b64_json (URL results are not supported).");
    return Base64.getDecoder().decode((String) b64);
  }

  // New result layer on top, centred and shrunk to fit the canvas; re-read under the lock so a
  // concurrent viewer autosave is extended rather than overwritten
  private static Map<String, Object> appendLayer(HttpSession session, String imagePath, String fileName, String name, int w, int h) throws Exception
  {
    String sidecarPath = imagePath + SIDECAR_SUFFIX;
    ReentrantLock lock = sidecarLock(sidecarPath);
    lock.lock();
    try
    {
      Map<String, Object> doc = sidecarOrDefault(session, imagePath);
      if (doc == null) return null;
      int cw = asInt(doc.get("width"), w), ch = asInt(doc.get("height"), h);
      Map<String, Object> layer = new HashMap<>();
      layer.put("id", "ai" + System.currentTimeMillis());
      layer.put("name", name);
      layer.put("src", fileName);
      layer.put("x", cw / 2.0);
      layer.put("y", ch / 2.0);
      layer.put("scale", Math.min(1.0, Math.min((double) cw / w, (double) ch / h)));
      layer.put("rotation", 0.0);
      layer.put("opacity", 1.0);
      layer.put("visible", true);
      List<Object> list = new ArrayList<>(layers(doc));
      list.add(layer);
      doc.put("layers", list.toArray());
      writeBytes(session, sidecarPath, json.toJSON(doc).getBytes(StandardCharsets.UTF_8));
      return layer;
    } finally
    {
      lock.unlock();
    }
  }

  static String slug(String text)
  {
    String s = text == null ? "" : text.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    String[] words = s.isEmpty() ? new String[0] : s.split(" ");
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < words.length && i < 4; i++) sb.append(sb.length() > 0 ? "-" : "").append(words[i]);
    String r = sb.length() > 32 ? sb.substring(0, 32) : sb.toString();
    return r.isEmpty() ? "image" : r;
  }

  public static int asInt(Object o, int def)
  {
    if (o instanceof Number) return ((Number) o).intValue();
    if (o instanceof String) try { return (int) Math.round(Double.parseDouble(((String) o).trim())); } catch (Exception e) {}
    return def;
  }

  public static double asDouble(Object o, double def)
  {
    if (o instanceof Number) return ((Number) o).doubleValue();
    if (o instanceof String) try { return Double.parseDouble(((String) o).trim()); } catch (Exception e) {}
    return def;
  }
}
