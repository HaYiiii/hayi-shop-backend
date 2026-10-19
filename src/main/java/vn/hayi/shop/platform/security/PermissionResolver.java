package vn.hayi.shop.platform.security;

import java.util.UUID;

/**
 * Resolves a user's {@link EffectiveAccess} from roles and staff assignments; implemented by the identity module,
 * which owns roles. Read live on every call (Decision M3), so a role or assignment change applies to the next request.
 */
public interface PermissionResolver {

	EffectiveAccess resolve(UUID userId);

}
