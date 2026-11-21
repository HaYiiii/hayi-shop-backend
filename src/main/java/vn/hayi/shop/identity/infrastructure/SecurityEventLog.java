package vn.hayi.shop.identity.infrastructure;

import static vn.hayi.shop.platform.persistence.UuidBinary.toBytes;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

	/** {@code LOGIN_FAILED} reason for a correct password on an account without Backoffice access. */
	public static final String NO_BACKOFFICE_ACCESS = "NO_BACKOFFICE_ACCESS";

	/** {@code LOGIN_FAILED} reason for a correct password on an account without a CustomerProfile (Decision AO1). */
	public static final String NO_CUSTOMER_PROFILE = "NO_CUSTOMER_PROFILE";

	/** {@code MFA_FAILED} stage of a wrong code answering a login challenge, the only one counted for the lock. */
	public static final String LOGIN_STAGE = "LOGIN";

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

	/**
	 * Wrong-password logins of the user after {@code since}. A correct password without Backoffice access is a
	 * {@code LOGIN_FAILED} too, but does not count (Decision K2); nor does one without a CustomerProfile (AO1).
	 */
	public int countLoginFailures(UUID userId, Instant since) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM iam_security_event
				WHERE user_id = ? AND event_type = 'LOGIN_FAILED' AND occurred_at > ?
				  AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(metadata, '$.reason')), '') NOT IN (?, ?)
				""")
			.params(toBytes(userId), since, NO_BACKOFFICE_ACCESS, NO_CUSTOMER_PROFILE)
			.query(Integer.class)
			.single();
	}

	/** When events of {@code type} came from {@code ipAddress} since {@code since}, oldest first (Decision AN5). */
	public List<Instant> occurredFromIpSince(SecurityEventType type, String ipAddress, Instant since) {
		return this.jdbc.sql("""
				SELECT occurred_at FROM iam_security_event
				WHERE event_type = ? AND ip_address = ? AND occurred_at > ? ORDER BY occurred_at
				""")
			.params(type.name(), ipAddress, since)
			.query((rs, n) -> rs.getTimestamp(1).toInstant())
			.list();
	}

	@Override
	public void rateLimitExceeded(ClientInfo client, Map<String, ?> metadata) {
		record(SecurityEventType.RATE_LIMIT_EXCEEDED, null, null, null, client, metadata);
	}

	@Override
	public void csrfValidationFailed(UUID userId, UUID sessionId, ClientInfo client) {
		record(SecurityEventType.CSRF_VALIDATION_FAILED, userId, sessionId, client);
	}

	/** Wrong MFA codes at login after {@code since}, across challenges (Decision L1); enrollment and step-up excluded. */
	public int countLoginMfaFailures(UUID userId, Instant since) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM iam_security_event
				WHERE user_id = ? AND event_type = 'MFA_FAILED' AND occurred_at > ?
				  AND JSON_UNQUOTE(JSON_EXTRACT(metadata, '$.stage')) = ?
				""")
			.params(toBytes(userId), since, LOGIN_STAGE)
			.query(Integer.class)
			.single();
	}

	/** Failed step-ups of the user's session after {@code since} (Decision L7); the user narrows it to the index. */
	public int countStepUpFailures(UUID userId, UUID sessionId, Instant since) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM iam_security_event
				WHERE user_id = ? AND occurred_at > ? AND session_id = ? AND event_type = 'MFA_FAILED'
				""")
			.params(toBytes(userId), since, toBytes(sessionId))
			.query(Integer.class)
			.single();
	}

}
