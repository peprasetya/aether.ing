package ing.aether;

import java.io.*;
import java.nio.charset.StandardCharsets;

import ing.aether.beans.BeanObject;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.annotation.WebFilter;

@WebFilter(urlPatterns = "/dynamic/*", dispatcherTypes = {DispatcherType.INCLUDE})
public class AjaxModifier implements Filter
{
  public AjaxModifier()
  {
  }

  public void destroy()
  {
  }

  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
  {
    PrintWriter out = response.getWriter();
    BeanObject bean = (BeanObject) request.getAttribute("bean");
    AjaxWrapper wrapper = new AjaxWrapper((HttpServletResponse) response);
    String data = "";

    try
    {
      chain.doFilter(request, wrapper);
      data = wrapper.getProcessedData();
    }
    catch (ServletException e)
    {
      if (e.getMessage() != null && e.getMessage().startsWith("JSP file ") && e.getMessage().endsWith(" not found"))
      {
        data = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?><ajax></ajax>";
      }
      else
      {
        throw e;
      }
    }
    catch (FileNotFoundException e)
    {
      data = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?><ajax></ajax>";
    }

    if (bean != null && bean.getMessage() != null && !bean.getMessage().isEmpty())
    {
      String message = "<message>" + bean.getMessage() + "</message>";
      int ajaxPos = data.indexOf("<ajax>");
      if (ajaxPos != -1)
      {
        data = data.substring(0, ajaxPos + 6) + message + data.substring(ajaxPos + 6);
      }
      else
      {
        System.out.println("AjaxModifier: Missing <ajax> tag in response");
      }
    }

    out.write(data);
    out.close();
  }

  public void init(FilterConfig fConfig) throws ServletException
  {
  }
}
