package ing.aether.tools;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import ing.aether.socket.AetherWebSocket;
import jakarta.servlet.http.HttpSession;

public class MonitorWorker
{
  private static final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();
  private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
  private static final Map<String, long[]> prevCpuTicks = new ConcurrentHashMap<>();

  public static synchronized void start(HttpSession session)
  {
    String sessionId = session.getId();
    if (tasks.containsKey(sessionId)) return;
    System.out.println("MonitorWorker: Starting monitoring for session " + sessionId);

    ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
      if (!tasks.containsKey(sessionId)) return;
      try
      {
        Map<String, Object> cpu = getCpuData(sessionId);
        Map<String, Object> gpu = getGpuData();
        
        // Final check before sending to avoid race with stop()
        if (tasks.containsKey(sessionId))
        {
          Map<String, Object> data = new HashMap<>();
          data.put("type", "monitor");
          data.put("cpu", cpu);
          data.put("gpu", gpu);
          AetherWebSocket.sendToSession(sessionId, data, false);
        }
      } catch (Exception e) 
      { 
        if (tasks.containsKey(sessionId))
        {
          System.err.println("MonitorWorker Error: " + e.getMessage());
          e.printStackTrace();
        }
      }
    }, 0, 1, TimeUnit.SECONDS);

    tasks.put(sessionId, task);
  }

  public static synchronized void stop(HttpSession session)
  {
    String sessionId = session.getId();
    System.out.println("MonitorWorker: Stopping monitoring for session " + sessionId);
    
    ScheduledFuture<?> task = tasks.remove(sessionId);
    if (task != null) task.cancel(false);
    
    // Send clear message AFTER removing from tasks to ensure it's the absolute last message
    Map<String, Object> data = new HashMap<>();
    data.put("type", "monitor");
    AetherWebSocket.sendToSession(sessionId, data, false);
    
    prevCpuTicks.remove(sessionId);
  }

  private static Map<String, Object> getCpuData(String sessionId)
  {
    Map<String, Object> cpu = new HashMap<>();
    List<Double> usages = new ArrayList<>();
    File statFile = new File("/proc/stat");
    if (!statFile.exists()) return null;

    try (BufferedReader reader = new BufferedReader(new FileReader(statFile)))
    {
      String line;
      int coreIdx = 0;
      long[] prevTicks = prevCpuTicks.get(sessionId);
      long[] currentTicks = new long[64 * 2]; // Max 64 cores
      
      while ((line = reader.readLine()) != null && line.startsWith("cpu"))
      {
        if (line.startsWith("cpu ")) continue; 
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 5) continue;

        long user = Long.parseLong(parts[1]);
        long nice = Long.parseLong(parts[2]);
        long system = Long.parseLong(parts[3]);
        long idle = Long.parseLong(parts[4]);
        long iowait = Long.parseLong(parts[5]);
        long irq = Long.parseLong(parts[6]);
        long softirq = Long.parseLong(parts[7]);

        long total = user + nice + system + idle + iowait + irq + softirq;
        
        if (prevTicks != null && coreIdx * 2 + 1 < prevTicks.length)
        {
          long deltaTotal = total - prevTicks[coreIdx * 2];
          long deltaIdle = idle - prevTicks[coreIdx * 2 + 1];
          double usage = deltaTotal > 0 ? 100.0 * (1.0 - (double) deltaIdle / deltaTotal) : 0.0;
          usages.add(Math.max(0.0, Math.min(100.0, usage)));
        } else usages.add(0.0);

        if (coreIdx * 2 + 1 < currentTicks.length)
        {
          currentTicks[coreIdx * 2] = total;
          currentTicks[coreIdx * 2 + 1] = idle;
        }
        coreIdx++;
      }
      prevCpuTicks.put(sessionId, currentTicks);
    } catch (IOException e) { e.printStackTrace(); }
    
    if (usages.isEmpty()) return null;

    cpu.put("usage", usages);
    cpu.put("temp", getHwmonValue("coretemp", "temp1_input", 1000.0));
    if (cpu.get("temp") == null) cpu.put("temp", getHwmonValue("k10temp", "temp1_input", 1000.0));
    
    return cpu;
  }

  private static Map<String, Object> getGpuData()
  {
    Map<String, Object> gpu = new HashMap<>();
    // Try AMD (radeontop)
    String radeon = runCommand("radeontop -d - -l 1");
    if (radeon != null && !radeon.isEmpty() && radeon.contains("gpu"))
    {
      gpu.put("vendor", "AMD");
      Map<String, Double> usage = new HashMap<>();
      Pattern p = Pattern.compile("(\\w+) (\\d+\\.\\d+)%");
      Matcher m = p.matcher(radeon);
      while (m.find())
      {
        usage.put(m.group(1), Double.parseDouble(m.group(2)));
      }
      gpu.put("usage", usage);
      gpu.put("temp", getHwmonValue("amdgpu", "temp1_input", 1000.0));
      gpu.put("power", getHwmonValue("amdgpu", "power1_average", 1000000.0));
      return gpu;
    }

    // Try NVIDIA
    String nvidia = runCommand("nvidia-smi --query-gpu=utilization.gpu,temperature.gpu,power.draw --format=csv,noheader,nounits");
    if (nvidia != null && !nvidia.isEmpty() && !nvidia.startsWith("Error"))
    {
      gpu.put("vendor", "NVIDIA");
      String[] parts = nvidia.split(", ");
      if (parts.length >= 3)
      {
        Map<String, Double> usage = new HashMap<>();
        usage.put("gpu", Double.parseDouble(parts[0]));
        gpu.put("usage", usage);
        gpu.put("temp", Double.parseDouble(parts[1]));
        gpu.put("power", Double.parseDouble(parts[2]));
      }
      return gpu;
    }

    return null;
  }

  private static Double getHwmonValue(String name, String file, double divisor)
  {
    File hwmonDir = new File("/sys/class/hwmon");
    if (!hwmonDir.exists()) return null;
    File[] dirs = hwmonDir.listFiles();
    if (dirs != null)
    {
      for (File dir : dirs)
      {
        try (BufferedReader reader = new BufferedReader(new FileReader(new File(dir, "name"))))
        {
          String hwmonName = reader.readLine().trim();
          if (name.equals(hwmonName))
          {
            File valueFile = new File(dir, file);
            if (valueFile.exists())
            {
              try (BufferedReader valReader = new BufferedReader(new FileReader(valueFile)))
              {
                return Double.parseDouble(valReader.readLine().trim()) / divisor;
              }
            }
          }
        } catch (Exception e) {}
      }
    }
    return null;
  }

  private static String runCommand(String cmd)
  {
    Process p = null;
    try
    {
      p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
      BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) sb.append(line).append("\n");
      boolean finished = p.waitFor(2, TimeUnit.SECONDS);
      if (!finished) 
      {
        p.destroyForcibly();
      }
      return sb.toString().trim();
    } catch (InterruptedException e) 
    {
      if (p != null) p.destroyForcibly();
      Thread.currentThread().interrupt();
      return null;
    } catch (Exception e) 
    { 
      // Silence errors if no tasks are active (shutdown or deactivation)
      if (tasks.isEmpty()) return null;
      System.err.println("MonitorWorker: Command execution failed: " + cmd + " - " + e.getMessage());
      return null; 
    }
  }
}
