package vn.hayi.shop.platform.security;

import java.util.UUID;

/**
 * Answers whether a session is still live, checked on every authenticated request (Decision G1). Implemented by the
 * identity module, which owns sessions; platform only depends on this interface.
 */
public interface SessionStatusChecker {

	boolean isActive(UUID sessionId);

}
