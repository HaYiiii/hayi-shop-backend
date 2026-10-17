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
 * Security identity (Identity §2.1, §2.12), separate from Customer/Staff profiles. Holds the account lifecycle and
 * the temporary login lock (§2.8).
 */
@Entity
@Table(name = "iam_user")
public class User {

	static final String LOCK_REASON_FAILED_LOGINS = "TOO_MANY_FAILED_LOGINS";

	@Id
	private UUID id;

	@Enumerated(EnumType.STRING)
	private UserStatus status;

	private Instant lockedUntil;

	private String lockReason;

	private int failedLoginCount;

	private Instant lastLoginAt;

	private Instant closedAt;

	@Column(updatable = false)
	private Instant createdAt;

	private Instant updatedAt;

	@Version
	private long version;

	protected User() {
	}

	public static User create(Instant now) {
		User user = new User();
		user.id = UuidV7.generate();
		user.status = UserStatus.ACTIVE;
		user.createdAt = now;
		user.updatedAt = now;
		return user;
	}

	public boolean isActive() {
		return this.status == UserStatus.ACTIVE;
	}

	public boolean isLocked(Instant now) {
		return this.lockedUntil != null && now.isBefore(this.lockedUntil);
	}

	/** Time left on the lock; zero when not locked. */
	public Duration lockRemaining(Instant now) {
		return isLocked(now) ? Duration.between(now, this.lockedUntil) : Duration.ZERO;
	}

	/**
	 * Counts a failed login and locks the account when the policy's limit is reached.
	 * @param previousFailureAt when the previous failed login happened, if any
	 * @return {@code true} if this failure locked the account
	 */
	public boolean registerFailedLogin(Instant now, Instant previousFailureAt, LoginLockPolicy policy) {
		boolean lockExpired = this.lockedUntil != null && !now.isBefore(this.lockedUntil);
		boolean streakBroken = previousFailureAt == null || previousFailureAt.isBefore(now.minus(policy.failureWindow()));
		if (lockExpired || streakBroken) {
			this.failedLoginCount = 0;
			this.lockedUntil = null;
			this.lockReason = null;
		}
		this.failedLoginCount++;
		this.updatedAt = now;
		if (this.failedLoginCount >= policy.maxFailures()) {
			this.lockedUntil = now.plus(policy.lockDuration());
			this.lockReason = LOCK_REASON_FAILED_LOGINS;
			return true;
		}
		return false;
	}

	/** Correct password: the failure streak and any expired lock are cleared. */
	public void registerSuccessfulLogin(Instant now) {
		this.failedLoginCount = 0;
		this.lockedUntil = null;
		this.lockReason = null;
		this.lastLoginAt = now;
		this.updatedAt = now;
	}

	public UUID getId() {
		return this.id;
	}

}
