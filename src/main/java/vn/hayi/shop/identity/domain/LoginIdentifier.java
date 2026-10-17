package vn.hayi.shop.identity.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import vn.hayi.shop.shared.id.UuidV7;

/**
 * The single source of truth for a login email/phone/employee code (Identity §2.2): unique per
 * {@code (type, normalizedValue)} across the system.
 */
@Entity
@Table(name = "iam_login_identifier")
public class LoginIdentifier {

	@Id
	private UUID id;

	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "identifier_type")
	private IdentifierType type;

	private String normalizedValue;

	private Instant verifiedAt;

	@Column(updatable = false)
	private Instant createdAt;

	private Instant updatedAt;

	protected LoginIdentifier() {
	}

	/** An email the provisioning actor vouches for (Owner bootstrap, staff provisioning). */
	public static LoginIdentifier verifiedEmail(UUID userId, String email, Instant now) {
		LoginIdentifier identifier = new LoginIdentifier();
		identifier.id = UuidV7.generate();
		identifier.userId = userId;
		identifier.type = IdentifierType.EMAIL;
		identifier.normalizedValue = normalizeEmail(email);
		identifier.verifiedAt = now;
		identifier.createdAt = now;
		identifier.updatedAt = now;
		return identifier;
	}

	/** Emails are stored lower-case (ERD 01). */
	public static String normalizeEmail(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}

	public UUID getUserId() {
		return this.userId;
	}

}
