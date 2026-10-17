package vn.hayi.shop.identity.infrastructure;

import static vn.hayi.shop.platform.persistence.UuidBinary.toBytes;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import vn.hayi.shop.platform.security.SessionStatusChecker;

/**
 * Per-request session check with an in-process cache (API 01-security §4: TTL ≤ 30s). A revoke in this instance
 * evicts the entry right after commit, so the revoked session is refused on the very next request. Several
 * instances need cross-instance invalidation later (not required in the first phase). Entries expire after the TTL
 * and the cache is bounded, so sessions seen once do not stay in memory.
 */
@Component
public class CachedSessionStatus implements SessionStatusChecker {

	static final Duration TTL = Duration.ofSeconds(30);

	static final long MAXIMUM_SIZE = 100_000;

	private final Cache<UUID, Instant> cache;

	private final JdbcClient jdbc;

	private final Clock clock;

	/**
	 * @param ticker measures the TTL; the monotonic system ticker unless a test supplies one that follows its clock
	 * (a wall-clock ticker would keep entries past the TTL when the system clock steps back)
	 */
	CachedSessionStatus(JdbcClient jdbc, Clock clock, ObjectProvider<Ticker> ticker) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.cache = Caffeine.newBuilder()
			.expireAfterWrite(TTL)
			.maximumSize(MAXIMUM_SIZE)
			.ticker(ticker.getIfAvailable(Ticker::systemTicker))
			.build();
	}

	@Override
	public boolean isActive(UUID sessionId) {
		Instant validUntil = this.cache.get(sessionId, this::load);
		return Instant.now(this.clock).isBefore(validUntil);
	}

	/** Drops the cached state once the current transaction commits (or now, outside a transaction). */
	public void evictAfterCommit(UUID sessionId) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					CachedSessionStatus.this.cache.invalidate(sessionId);
				}
			});
		}
		else {
			this.cache.invalidate(sessionId);
		}
	}

	/** End of validity of a live session; {@link Instant#MIN} when revoked or unknown (Caffeine caches no null). */
	private Instant load(UUID sessionId) {
		return this.jdbc.sql("SELECT expires_at FROM iam_user_session WHERE id = ? AND revoked_at IS NULL")
			.param(toBytes(sessionId))
			.query((rs, n) -> rs.getTimestamp(1))
			.optional()
			.map(Timestamp::toInstant)
			.orElse(Instant.MIN);
	}

}
