package vn.hayi.shop.platform.security;

import java.util.Optional;
import java.util.UUID;

/**
 * Decides whether a user may use a permission, at a Branch when the endpoint names one (Decision M1). Implemented by
 * the identity module, which owns roles and the effective-permission rules (Identity §2.7, Decision A); platform only
 * maps the decision to an error code, like {@link SessionStatusChecker} and {@link StepUpChecker} (Decision M6).
 */
public interface PermissionEvaluator {

	AccessDecision evaluate(UUID userId, Permission permission, Optional<UUID> branchId);

}
