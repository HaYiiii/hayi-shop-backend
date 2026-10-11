/**
 * Shared kernel: concepts that are genuinely common and rarely change (IDs, Money, time, actor, error codes).
 * No bounded-context business rules belong here. See SOURCE-STRUCTURE.md §2.3.
 */
@ApplicationModule(displayName = "Shared Kernel", type = ApplicationModule.Type.OPEN)
package vn.hayi.shop.shared;

import org.springframework.modulith.ApplicationModule;
