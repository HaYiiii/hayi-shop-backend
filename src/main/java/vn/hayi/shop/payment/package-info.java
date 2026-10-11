/**
 * Payment bounded context. Other modules may only use the {@code api} package.
 * Layout: api / web / application / domain / infrastructure (SOURCE-STRUCTURE.md §2.2).
 */
@ApplicationModule(displayName = "Payment")
package vn.hayi.shop.payment;

import org.springframework.modulith.ApplicationModule;
