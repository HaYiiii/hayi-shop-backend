package vn.hayi.shop.platform.security;

import java.util.Map;
import java.util.UUID;

/**
 * Receives security events detected by the platform filters (Identity §2.9, API 01-security §9). Implemented by the
 * identity module, which owns the Security Event Log.
 */
public interface SecurityEventSink {

	void csrfValidationFailed(UUID userId, UUID sessionId, ClientInfo client);

	/** An IP went over a public rate limit (Decision W9); {@code metadata} holds the bucket, limit and path. */
	void rateLimitExceeded(ClientInfo client, Map<String, ?> metadata);

}
