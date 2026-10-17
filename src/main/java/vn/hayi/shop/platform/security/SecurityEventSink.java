package vn.hayi.shop.platform.security;

import java.util.UUID;

/**
 * Receives security events detected by the platform filters (Identity §2.9, API 01-security §9). Implemented by the
 * identity module, which owns the Security Event Log.
 */
public interface SecurityEventSink {

	void csrfValidationFailed(UUID userId, UUID sessionId, ClientInfo client);

}
