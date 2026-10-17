package vn.hayi.shop.identity.infrastructure;

import static vn.hayi.shop.platform.persistence.UuidBinary.toBytes;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;
import vn.hayi.shop.platform.observability.TraceIds;
import vn.hayi.shop.platform.security.SecurityEventSink;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.shared.id.UuidV7;

/**
 * Append-only Security Event Log (Identity §2.9, ERD 01 {@code iam_security_event}), separate from business Audit.
 * Never receives passwords, OTPs, tokens or keys: {@code metadata} is already redacted by the caller.
 */
@Component
public class SecurityEventLog implements SecurityEventSink {

	private final JdbcClient jdbc;

	private final JsonMapper json;

	private final Clock clock;

	SecurityEventLog(JdbcClient jdbc, JsonMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.json = json;
		this.clock = clock;
	}

	public void record(SecurityEventType type, UUID userId, UUID sessionId, ClientInfo client) {
		record(type, userId, sessionId, null, client, null);
	}

	/** @param identifierMasked the identifier typed at login, masked, when the user is unknown */
	public void record(SecurityEventType type, UUID userId, UUID sessionId, String identifierMasked,
			ClientInfo client, Map<String, ?> metadata) {
		this.jdbc.sql("""
				INSERT INTO iam_security_event (id, event_type, user_id, session_id, identifier_masked, ip_address,
				  user_agent, metadata, trace_id, occurred_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""")
			.params(toBytes(UuidV7.generate()), type.name(), toBytes(userId), toBytes(sessionId), identifierMasked,
					client.ipAddress(), client.userAgent(),
					(metadata != null) ? this.json.writeValueAsString(metadata) : null, TraceIds.current(),
					Instant.now(this.clock))
			.update();
	}

	/** When the user's latest failed login happened, to tell a streak of failures from scattered ones. */
	public Optional<Instant> lastLoginFailure(UUID userId) {
		return this.jdbc.sql("""
				SELECT MAX(occurred_at) FROM iam_security_event WHERE user_id = ? AND event_type = 'LOGIN_FAILED'
				""")
			.param(toBytes(userId))
			.query((rs, n) -> Optional.ofNullable(rs.getTimestamp(1)).map(Timestamp::toInstant))
			.single();
	}

	@Override
	public void csrfValidationFailed(UUID userId, UUID sessionId, ClientInfo client) {
		record(SecurityEventType.CSRF_VALIDATION_FAILED, userId, sessionId, client);
	}

}
