/**
 * Technical infrastructure (web, security, idempotency, outbox, jobs, realtime, storage client, persistence, observability).
 * Contains no business rules. See SOURCE-STRUCTURE.md §2.4.
 */
@ApplicationModule(displayName = "Platform", type = ApplicationModule.Type.OPEN)
package vn.hayi.shop.platform;

import org.springframework.modulith.ApplicationModule;
