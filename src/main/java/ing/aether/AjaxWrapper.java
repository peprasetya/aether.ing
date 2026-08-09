package ing.aether;

import java.io.*;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.*;

public class AjaxWrapper extends HttpServletResponseWrapper
{
  private CharArrayWriter output;

  public AjaxWrapper(HttpServletResponse response)
  {
    super(response);
    output = new CharArrayWriter();
  }

  public String getProcessedData()
  {
    String data = output.toString();
    if (data == null || data.isEmpty())
    {
      return "";
    }

    StringBuilder sb = new StringBuilder(data.length() + 256);
    int start = 0;
    int cursor = 0;
    String[] tags = {"replacehtml", "script"};

    while (cursor < data.length())
    {
      int begin = -1;
      String utag = null;

      for (String tag : tags)
      {
        int pos = data.indexOf("<" + tag, cursor);
        if (pos != -1 && (begin == -1 || pos < begin))
        {
          int next = pos + tag.length() + 1;
          if (next < data.length())
          {
            char c = data.charAt(next);
            if (c == ' ' || c == '>')
            {
              begin = pos;
              utag = tag;
            }
          }
        }
      }

      if (begin == -1)
      {
        break;
      }

      sb.append(data, start, begin);

      int end = -1;
      boolean inQuote = false;
      char quoteChar = 0;
      for (int i = begin; i < data.length(); i++)
      {
        char ch = data.charAt(i);
        if ((ch == '"' || ch == '\'') && (i == begin || data.charAt(i - 1) != '\\'))
        {
          if (!inQuote)
          {
            inQuote = true;
            quoteChar = ch;
          }
          else if (ch == quoteChar) inQuote = false;
        }
        if (ch == '>' && !inQuote)
        {
          end = i;
          break;
        }
      }

      if (end == -1)
      {
        System.out.println("AjaxWrapper Error: Malformed tag at index " + begin + ". Missing '>'");
        start = begin;
        break;
      }
      sb.append(data, begin, end + 1);

      String closeTag = "</" + utag + ">";
      int closePos = data.indexOf(closeTag, end);
      if (closePos == -1)
      {
        System.out.println("AjaxWrapper Error: Missing closing tag " + closeTag + " for tag at index " + begin);
        start = end + 1;
        cursor = start;
        continue;
      }

      sb.append("<![CDATA[");
      sb.append(data.substring(end + 1, closePos).replace("]]>", "]]]]><![CDATA[>"));
      sb.append("]]>");

      start = closePos;
      cursor = closePos;
    }

    sb.append(data, start, data.length());
    return sb.toString();
  }

  public byte[] getData()
  {
    return getProcessedData().getBytes(StandardCharsets.UTF_8);
  }

  public PrintWriter getWriter()
  {
    return new PrintWriter(output);
  }
}
