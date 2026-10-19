package vn.hayi.shop.identity.infrastructure;

import static vn.hayi.shop.platform.persistence.UuidBinary.toBytes;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.hayi.shop.platform.security.StepUpChecker;

/**
 * {@code iam_user_session.step_up_verified_at}, read live for every action marked S: unlike the session-status cache,
 * a step-up must be seen at once (API 01-security §8).
 */
@Component
class SessionStepUps implements StepUpChecker {

	private final JdbcClient jdbc;

	SessionStepUps(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<Instant> stepUpVerifiedAt(UUID sessionId) {
		return this.jdbc.sql("SELECT step_up_verified_at FROM iam_user_session WHERE id = ? AND revoked_at IS NULL")
			.param(toBytes(sessionId))
			.query((rs, n) -> Optional.ofNullable(rs.getTimestamp(1)).map(Timestamp::toInstant))
			.optional()
			.flatMap(verifiedAt -> verifiedAt);
	}

}
