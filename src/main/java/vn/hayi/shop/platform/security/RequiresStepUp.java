package vn.hayi.shop.platform.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint as action <b>S</b> of the permission matrix (API 01-security §7–8, Decision L7): the caller's
 * session must have stepped up within {@code hayi.security.step-up-freshness}, otherwise {@code 403 STEP_UP_REQUIRED}.
 * Checked after authentication and before the handler runs, so a refused request has no effect.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresStepUp {

}
