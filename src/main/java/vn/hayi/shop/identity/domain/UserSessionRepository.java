package vn.hayi.shop.identity.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import vn.hayi.shop.shared.actor.Persona;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface UserSessionRepository extends Repository<UserSession, UUID> {

	UserSession save(UserSession session);

	Optional<UserSession> findById(UUID id);

	/** Serialises rotations of one refresh-token family (API 01-security §5). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from UserSession s where s.id = :id")
	Optional<UserSession> findByIdForUpdate(UUID id);

	/** The user's sessions of one persona that are not revoked yet (offboarding, Decision AR4). */
	List<UserSession> findByUserIdAndPersonaAndRevokedAtIsNull(UUID userId, Persona persona);

	/** Every session of the user not revoked yet, both personas (password reset, Decision AS2). */
	List<UserSession> findByUserIdAndRevokedAtIsNull(UUID userId);

}
