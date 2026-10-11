/**
 * Audit bounded context. Other modules may only use the {@code api} package.
 * Layout: api / web / application / domain / infrastructure (SOURCE-STRUCTURE.md §2.2).
 */
@ApplicationModule(displayName = "Audit")
package vn.hayi.shop.audit;

import org.springframework.modulith.ApplicationModule;
