package vn.hayi.shop.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The user's one password credential (Identity §2.12, Decision D1). Only the hash is stored. */
@Entity
@Table(name = "iam_user_credential")
public class UserCredential {

	@Id
	private UUID userId;

	private String passwordHash;

	private boolean mustChangePassword;

	private Instant passwordChangedAt;

	private Instant updatedAt;

	protected UserCredential() {
	}

	/** A temporary password set by someone else: it must be replaced at first login (Identity §2.11). */
	public static UserCredential temporary(UUID userId, String passwordHash, Instant now) {
		UserCredential credential = new UserCredential();
		credential.userId = userId;
		credential.passwordHash = passwordHash;
		credential.mustChangePassword = true;
		credential.passwordChangedAt = now;
		credential.updatedAt = now;
		return credential;
	}

	public void changePassword(String newPasswordHash, Instant now) {
		this.passwordHash = newPasswordHash;
		this.mustChangePassword = false;
		this.passwordChangedAt = now;
		this.updatedAt = now;
	}

	public UUID getUserId() {
		return this.userId;
	}

	public String getPasswordHash() {
		return this.passwordHash;
	}

	public boolean isMustChangePassword() {
		return this.mustChangePassword;
	}

}
