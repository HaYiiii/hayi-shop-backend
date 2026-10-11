/**
 * Catalog bounded context. Other modules may only use the {@code api} package.
 * Layout: api / web / application / domain / infrastructure (SOURCE-STRUCTURE.md §2.2).
 */
@ApplicationModule(displayName = "Catalog")
package vn.hayi.shop.catalog;

import org.springframework.modulith.ApplicationModule;
