package ing.aether;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CommandContainer
{
  CommandRegister[] value();
}
