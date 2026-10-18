package vn.hayi.shop.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One opaque refresh token of a session's family (Identity §2.6, ERD 01). Only its SHA-256 is stored. A token is
 * used once: rotation marks it {@code rotatedAt} and links the replacement; presenting it again is reuse.
 */
@Entity
@Table(name = "iam_refresh_token")
public class RefreshToken {

	@Id
	private UUID id;

	private UUID sessionId;

	@Column(columnDefinition = "char(64)")
	private String tokenHash;

	private Instant issuedAt;

	private Instant expiresAt;

	private Instant rotatedAt;

	private UUID replacedById;

	protected RefreshToken() {
	}

	/**
	 * @param id chosen by the caller, because the opaque value is derived from it
	 * @param expiresAt idle expiry, capped by the session's absolute lifetime
	 */
	public static RefreshToken issue(UUID id, UUID sessionId, String tokenHash, Instant now, Instant expiresAt) {
		RefreshToken token = new RefreshToken();
		token.id = id;
		token.sessionId = sessionId;
		token.tokenHash = tokenHash;
		token.issuedAt = now;
		token.expiresAt = expiresAt;
		return token;
	}

	public boolean isRotated() {
		return this.rotatedAt != null;
	}

	public boolean isExpired(Instant now) {
		return !now.isBefore(this.expiresAt);
	}

	/** Reuse soon enough after rotation is two tabs refreshing at once, not theft (API 01-security §5). */
	public boolean isWithinReuseGrace(Instant now, Duration grace) {
		return this.rotatedAt != null && !now.isAfter(this.rotatedAt.plus(grace));
	}

	public void rotate(UUID replacementId, Instant now) {
		this.rotatedAt = now;
		this.replacedById = replacementId;
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getSessionId() {
		return this.sessionId;
	}

	public Instant getExpiresAt() {
		return this.expiresAt;
	}

	public UUID getReplacedById() {
		return this.replacedById;
	}

}
