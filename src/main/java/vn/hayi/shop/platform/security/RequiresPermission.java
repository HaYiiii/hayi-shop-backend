package vn.hayi.shop.platform.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The permission a Backoffice endpoint needs (API 01-security §7, Decision M1), checked after authentication and
 * before the handler runs, so a refused request has no effect:
 * <ol>
 * <li>the caller holds the permission in no scope → {@code 403 PERMISSION_DENIED};</li>
 * <li>a {@code {branchId}} path variable outside the caller's scope → {@code 403 BRANCH_SCOPE_DENIED}
 * (API README §3.1);</li>
 * <li>the caller lacks the permission at that Branch → {@code 403 PERMISSION_DENIED}.</li>
 * </ol>
 * Without {@code {branchId}} (a cross-Branch view) holding the permission somewhere is enough; the handler then
 * intersects its data with the caller's {@link EffectiveAccess}. Step-up ({@link RequiresStepUp}) is checked last.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresPermission {

	Permission value();

}
