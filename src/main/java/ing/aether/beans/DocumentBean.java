package ing.aether.beans;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import ing.aether.CommandRegister;
import ing.aether.Portal;
import ing.aether.SessionTracker;
import ing.aether.data.FileProvider;
import ing.aether.tools.ai.ImageWorker;

@CommandRegister(value="read", accessType=1, createSession=true, preventCache=true)
public class DocumentBean extends BeanObject
{
  private String fileContent = "";
  private boolean image = false;

  public String getFileContent()
  {
    return fileContent;
  }

  // True when the opened file is an image: fileContent then holds its layer document, or is empty
  public boolean isImage()
  {
    return image;
  }

  public String getFileName()
  {
    try
    {
      FileProvider provider = path.getProvider();
      if (provider != null)
      {
        String subPath = path.getSuffix();
        if (!subPath.isEmpty())
        {
          return provider.getFileItem(subPath).getName();
        }
      }
    } catch (Exception e) {}

    if (path.getLength() > path.getLibrary().split("/").length)
    {
       String suffix = path.getSuffix();
       int lastSlash = suffix.lastIndexOf('/');
       return lastSlash == -1 ? suffix : suffix.substring(lastSlash + 1);
    }
    return "Untitled";
  }

  protected void processData()
  {
    loadAIData();
    FileProvider provider = path.getProvider();
    if (provider == null) return;

    try
    {
      String subPath = path.getSuffix();
      if (provider.exists(subPath) && !provider.isDirectory(subPath))
      {
        String bufferedPath = (String) session.getAttribute("bufferedPath");
        String currentPath = path.getLibrary() + "/" + path.getSuffix();
        
        session.setAttribute("lastFilePath", currentPath);
        
        // If no working directory is set, use the parent of this file
        if (session.getAttribute("workingDirectory") == null)
        {
          int lastSlash = currentPath.lastIndexOf('/');
          if (lastSlash != -1)
          {
            String wd = currentPath.substring(0, lastSlash);
            session.setAttribute("workingDirectory", wd);
            ing.aether.tools.ai.AITools.ensureProjectFiles(session, wd);
          }
        }
        
        // Images open in the viewer: the response carries the layer document, never the pixels, and the
        // text save buffer is left alone so no autosave baseline ever points at an image
        if (ImageWorker.isImagePath(subPath))
        {
          image = true;
          String sidecar = subPath + ImageWorker.SIDECAR_SUFFIX;
          if (provider.exists(sidecar)) try (InputStream is = provider.getInputStream(sidecar))
          {
            fileContent = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
          }
          if (isAjaxCall()) contentType = "text/plain; charset=utf-8";
          return;
        }

        if (currentPath.equals(bufferedPath) && session.getAttribute("bufferedContent") != null)
        {
          fileContent = (String) session.getAttribute("bufferedContent");
        } else
        {
          try (InputStream is = provider.getInputStream(subPath))
          {
            fileContent = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            // Update buffer with fresh content
            session.setAttribute("bufferedContent", fileContent);
            session.setAttribute("bufferedPath", currentPath);
          }
        }
        if (isAjaxCall()) contentType = "text/plain; charset=utf-8";
      }
    } catch (Exception e)
    {
      message = "Error reading file: " + e.getMessage();
    }
  }
}
