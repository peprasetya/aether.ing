package ing.aether;

import java.util.HashSet;
import java.util.Set;

import ing.aether.beans.*;
import jakarta.servlet.*;
import jakarta.servlet.annotation.HandlesTypes;

@HandlesTypes(BeanObject.class)
public class CommandInitializer implements ServletContainerInitializer
{

  @Override
  public void onStartup(Set<Class<?>> discoveredClasses,ServletContext ctx) throws ServletException
  {
    scan(discoveredClasses);
  }

  public static void scan(ServletContext ctx)
  {
    System.out.println("CommandInitializer: Starting manual programmatic scan...");
    Set<Class<?>> discoveredClasses=new HashSet<>();
    String path="/WEB-INF/classes/ing/aether/beans/";
    Set<String> resources=ctx.getResourcePaths(path);
    if (resources!=null)
    {
      for (String resource:resources)
      {
        if (resource.endsWith(".class"))
        {
          String className=resource.substring(resource.indexOf("/classes/")+9,resource.lastIndexOf(".class")).replace('/','.');
          try
          {
            Class<?> clazz=Class.forName(className);
            if (BeanObject.class.isAssignableFrom(clazz)) discoveredClasses.add(clazz);
          }catch (ClassNotFoundException e)
          {
            System.err.println("Error loading class during scan: "+className);
          }
        }
      }
    }
    scan(discoveredClasses);
  }

  private static void scan(Set<Class<?>> discoveredClasses)
  {
    System.out.println("CommandInitializer: Registering discovered command beans...");
    if (discoveredClasses==null||discoveredClasses.isEmpty())
    {
      System.out.println("CommandInitializer: No command beans found.");
      return;
    }
    
    discoveredClasses.add(BeanObject.class);

    for (Class<?> beanClass:discoveredClasses)
    {
      //System.out.println("Inspecting: "+beanClass.toString());
      if (beanClass.isAnnotationPresent(CommandRegister.class) || beanClass.isAnnotationPresent(CommandContainer.class))
      {
        CommandRegister[] annotations=beanClass.getAnnotationsByType(CommandRegister.class);
        for (CommandRegister annotation: annotations)
          Command.register(annotation.value(),beanClass,annotation.accessType(),annotation.createSession(),annotation.preventCache());
      }
    }
    System.out.println("CommandInitializer: Scan complete.");
  }
}
