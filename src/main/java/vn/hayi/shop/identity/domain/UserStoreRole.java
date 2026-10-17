package vn.hayi.shop.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import vn.hayi.shop.shared.id.UuidV7;

/** A role granted in the implicit system-wide STORE scope (Identity §2.7, Decision D2). */
@Entity
@Table(name = "iam_user_store_role")
public class UserStoreRole {

	@Id
	private UUID id;

	private UUID userId;

	private UUID roleId;

	private Instant grantedAt;

	private UUID grantedByUserId;

	protected UserStoreRole() {
	}

	/** @param grantedByUserId {@code null} when granted by the system (bootstrap) */
	public static UserStoreRole grant(UUID userId, UUID roleId, UUID grantedByUserId, Instant now) {
		UserStoreRole role = new UserStoreRole();
		role.id = UuidV7.generate();
		role.userId = userId;
		role.roleId = roleId;
		role.grantedByUserId = grantedByUserId;
		role.grantedAt = now;
		return role;
	}

}
