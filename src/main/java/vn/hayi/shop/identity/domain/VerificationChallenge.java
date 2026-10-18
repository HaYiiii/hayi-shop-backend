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

import vn.hayi.shop.shared.id.UuidV7;

/**
 * A short-lived challenge with a limited number of attempts (Identity §2.3, ERD 01 {@code iam_verification_challenge}).
 * For {@link VerificationPurpose#MFA_LOGIN} the code is a TOTP or recovery code checked against the user's factor,
 * so no code hash is stored (Decision L1).
 */
@Entity
@Table(name = "iam_verification_challenge")
public class VerificationChallenge {

	@Id
	private UUID id;

	@Enumerated(EnumType.STRING)
	private VerificationPurpose purpose;

	private String target;

	private UUID userId;

	@Enumerated(EnumType.STRING)
	private VerificationChannel channel;

	private String codeHash;

	@Enumerated(EnumType.STRING)
	private VerificationStatus status;

	private int attemptCount;

	private int maxAttempts;

	private Instant expiresAt;

	private Instant verifiedAt;

	private Instant consumedAt;

	@Column(updatable = false)
	private Instant createdAt;

	@Version
	private long version;

	protected VerificationChallenge() {
	}

	/** The MFA step of a Backoffice login that passed the password check. */
	public static VerificationChallenge mfaLogin(UUID userId, String email, Instant now, Duration ttl, int maxAttempts) {
		VerificationChallenge challenge = new VerificationChallenge();
		challenge.id = UuidV7.generate();
		challenge.purpose = VerificationPurpose.MFA_LOGIN;
		challenge.target = email;
		challenge.userId = userId;
		challenge.channel = VerificationChannel.TOTP;
		challenge.status = VerificationStatus.PENDING;
		challenge.maxAttempts = maxAttempts;
		challenge.expiresAt = now.plus(ttl);
		challenge.createdAt = now;
		return challenge;
	}

	/** Pending and not expired; an expired challenge is marked {@code EXPIRED} on the way. */
	public boolean isOpen(Instant now) {
		if (this.status == VerificationStatus.PENDING && !now.isBefore(this.expiresAt)) {
			this.status = VerificationStatus.EXPIRED;
		}
		return this.status == VerificationStatus.PENDING;
	}

	public boolean isFailed() {
		return this.status == VerificationStatus.FAILED;
	}

	/**
	 * Counts a wrong code.
	 * @return {@code true} if this attempt used up the challenge
	 */
	public boolean registerWrongCode() {
		this.attemptCount++;
		if (this.attemptCount >= this.maxAttempts) {
			this.status = VerificationStatus.FAILED;
		}
		return isFailed();
	}

	/** Verified and used in one go: the login it belongs to completes now. */
	public void consume(Instant now) {
		if (this.status != VerificationStatus.PENDING) {
			throw new IllegalStateException("Challenge is " + this.status);
		}
		this.status = VerificationStatus.CONSUMED;
		this.verifiedAt = now;
		this.consumedAt = now;
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getUserId() {
		return this.userId;
	}

	public VerificationPurpose getPurpose() {
		return this.purpose;
	}

}
