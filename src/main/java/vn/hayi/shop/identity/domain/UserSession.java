package vn.hayi.shop.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import vn.hayi.shop.shared.actor.Persona;
import vn.hayi.shop.shared.id.UuidV7;

/**
 * One login = one session = one refresh-token family (Identity §2.5–2.6). Revoking the session ends every token of
 * the family; it also ends at its absolute lifetime ({@code expiresAt}).
 */
@Entity
@Table(name = "iam_user_session")
public class UserSession {

	@Id
	private UUID id;

	private UUID userId;

	@Enumerated(EnumType.STRING)
	private Persona persona;

	@Enumerated(EnumType.STRING)
	private SessionChannel channel;

	private String deviceLabel;

	private String userAgent;

	private String ipAddress;

	private Instant stepUpVerifiedAt;

	@Column(updatable = false)
	private Instant createdAt;

	private Instant lastActiveAt;

	private Instant expiresAt;

	private Instant revokedAt;

	@Enumerated(EnumType.STRING)
	private RevokeReason revokeReason;

	@Version
	private long version;

	protected UserSession() {
	}

	public static UserSession open(UUID userId, Persona persona, SessionChannel channel, String ipAddress,
			String userAgent, Instant now, Duration absoluteLifetime) {
		UserSession session = new UserSession();
		session.id = UuidV7.generate();
		session.userId = userId;
		session.persona = persona;
		session.channel = channel;
		session.ipAddress = ipAddress;
		session.userAgent = userAgent;
		session.createdAt = now;
		session.lastActiveAt = now;
		session.expiresAt = now.plus(absoluteLifetime);
		return session;
	}

	public boolean isActive(Instant now) {
		return this.revokedAt == null && now.isBefore(this.expiresAt);
	}

	/** Idempotent: the first reason wins. */
	public boolean revoke(RevokeReason reason, Instant now) {
		if (this.revokedAt != null) {
			return false;
		}
		this.revokedAt = now;
		this.revokeReason = reason;
		return true;
	}

	public void touch(Instant now) {
		this.lastActiveAt = now;
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getUserId() {
		return this.userId;
	}

	public Instant getExpiresAt() {
		return this.expiresAt;
	}

}
