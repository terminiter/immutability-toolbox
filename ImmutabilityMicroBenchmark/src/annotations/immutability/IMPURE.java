package annotations.immutability;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// this annotation is valid for methods
@Target({ ElementType.METHOD })

// annotation only exists at the source level and is not included in compiled code
@Retention(RetentionPolicy.SOURCE)

public @interface IMPURE {

}