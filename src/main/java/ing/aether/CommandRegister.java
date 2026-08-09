package ing.aether;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(CommandContainer.class)
public @interface CommandRegister
{
  String value();
  int accessType() default 0;
  boolean createSession() default true;
  boolean preventCache() default true;
}
