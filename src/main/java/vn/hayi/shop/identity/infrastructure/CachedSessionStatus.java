package vn.hayi.shop.identity.infrastructure;

import static vn.hayi.shop.platform.persistence.UuidBinary.toBytes;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import vn.hayi.shop.platform.security.SessionStatusChecker;

/**
 * Per-request session check with an in-process cache (API 01-security §4: TTL ≤ 30s). A revoke in this instance
 * evicts the entry right after commit, so the revoked session is refused on the very next request. Several
 * instances need cross-instance invalidation later (not required in the first phase).
 */
@Component
public class CachedSessionStatus implements SessionStatusChecker {

	static final Duration TTL = Duration.ofSeconds(30);

	private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();

	private final JdbcClient jdbc;

	private final Clock clock;

	CachedSessionStatus(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	public boolean isActive(UUID sessionId) {
		Instant now = Instant.now(this.clock);
		Entry entry = this.cache.get(sessionId);
		if (entry == null || !now.isBefore(entry.checkedAt().plus(TTL))) {
			entry = new Entry(load(sessionId), now);
			this.cache.put(sessionId, entry);
		}
		return entry.validUntil() != null && now.isBefore(entry.validUntil());
	}

	/** Drops the cached state once the current transaction commits (or now, outside a transaction). */
	public void evictAfterCommit(UUID sessionId) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					CachedSessionStatus.this.cache.remove(sessionId);
				}
			});
		}
		else {
			this.cache.remove(sessionId);
		}
	}

	/** End of validity of a live session; {@code null} when revoked or unknown. */
	private Instant load(UUID sessionId) {
		return this.jdbc.sql("SELECT expires_at FROM iam_user_session WHERE id = ? AND revoked_at IS NULL")
			.param(toBytes(sessionId))
			.query((rs, n) -> rs.getTimestamp(1))
			.optional()
			.map(Timestamp::toInstant)
			.orElse(null);
	}

	private record Entry(Instant validUntil, Instant checkedAt) {
	}

}
