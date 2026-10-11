/**
 * Report bounded context. Other modules may only use the {@code api} package.
 * Layout: api / web / application / domain / infrastructure (SOURCE-STRUCTURE.md §2.2).
 */
@ApplicationModule(displayName = "Report")
package vn.hayi.shop.report;

import org.springframework.modulith.ApplicationModule;
