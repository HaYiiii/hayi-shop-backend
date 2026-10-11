/**
 * Identity & Access bounded context. Other modules may only use the {@code api} package.
 * Layout: api / web / application / domain / infrastructure (SOURCE-STRUCTURE.md §2.2).
 */
@ApplicationModule(displayName = "Identity & Access")
package vn.hayi.shop.identity;

import org.springframework.modulith.ApplicationModule;
