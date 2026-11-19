package vn.hayi.shop.customer.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import vn.hayi.shop.shared.id.UuidV7;

/**
 * The Customer persona of a {@code User} (Customer §3.2): {@code customerId} is separate from {@code userId}, which
 * is kept as a foreign id. No login phone or email here; those belong to Identity (§3.1).
 */
@Entity
@Table(name = "cus_customer_profile")
public class CustomerProfile {

	public static final int MAX_DISPLAY_NAME = 128;

	@Id
	private UUID id;

	@Column(updatable = false)
	private UUID userId;

	private String displayName;

	@Column(updatable = false)
	private Instant createdAt;

	private Instant updatedAt;

	@Version
	private long version;

	protected CustomerProfile() {
	}

	public static CustomerProfile create(UUID userId, String displayName, Instant now) {
		CustomerProfile profile = new CustomerProfile();
		profile.id = UuidV7.generate();
		profile.userId = userId;
		profile.displayName = displayName;
		profile.createdAt = now;
		profile.updatedAt = now;
		return profile;
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getUserId() {
		return this.userId;
	}

	public String getDisplayName() {
		return this.displayName;
	}

}
