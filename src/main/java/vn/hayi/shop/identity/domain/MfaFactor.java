package vn.hayi.shop.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import vn.hayi.shop.shared.id.UuidV7;

/**
 * A user's TOTP authenticator (Identity §2.4, ERD 01 {@code iam_mfa_factor}). The secret is stored encrypted
 * (Decision L3); {@code lastUsedAt} is the start of the last accepted TOTP step, so a code is never accepted twice
 * (Decision L5). The table has no version column: changes go through {@link MfaFactorRepository} row locks.
 */
@Entity
@Table(name = "iam_mfa_factor")
public class MfaFactor {

	@Id
	private UUID id;

	private UUID userId;

	@Enumerated(EnumType.STRING)
	private MfaFactorType factorType;

	private byte[] secretEncrypted;

	@Enumerated(EnumType.STRING)
	private MfaFactorStatus status;

	private Instant activatedAt;

	private Instant lastUsedAt;

	@Column(updatable = false)
	private Instant createdAt;

	private Instant updatedAt;

	protected MfaFactor() {
	}

	/** A factor waiting for its first code (Decision L4). */
	public static MfaFactor pendingTotp(UUID userId, byte[] secretEncrypted, Instant now) {
		MfaFactor factor = new MfaFactor();
		factor.id = UuidV7.generate();
		factor.userId = userId;
		factor.factorType = MfaFactorType.TOTP;
		factor.secretEncrypted = secretEncrypted;
		factor.status = MfaFactorStatus.PENDING;
		factor.createdAt = now;
		factor.updatedAt = now;
		return factor;
	}

	/** Restarting enrollment replaces the secret of the pending factor (Decision L4). */
	public void replacePendingSecret(byte[] secretEncrypted, Instant now) {
		requireStatus(MfaFactorStatus.PENDING);
		this.secretEncrypted = secretEncrypted;
		this.updatedAt = now;
	}

	/** The first code confirmed the secret: the factor protects every later login. */
	public void activate(Instant acceptedStep, Instant now) {
		requireStatus(MfaFactorStatus.PENDING);
		this.status = MfaFactorStatus.ACTIVE;
		this.activatedAt = now;
		markUsed(acceptedStep, now);
	}

	/** Records the TOTP step just accepted; earlier and equal steps are refused from now on. */
	public void markUsed(Instant acceptedStep, Instant now) {
		this.lastUsedAt = acceptedStep;
		this.updatedAt = now;
	}

	private void requireStatus(MfaFactorStatus expected) {
		if (this.status != expected) {
			throw new IllegalStateException("MFA factor is " + this.status + ", expected " + expected);
		}
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getUserId() {
		return this.userId;
	}

	public byte[] getSecretEncrypted() {
		return this.secretEncrypted;
	}

	public MfaFactorStatus getStatus() {
		return this.status;
	}

	public Instant getLastUsedAt() {
		return this.lastUsedAt;
	}

}
