package vn.hayi.shop.identity.domain;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface UserCredentialRepository extends Repository<UserCredential, UUID> {

	UserCredential save(UserCredential credential);

	Optional<UserCredential> findById(UUID userId);

	/** Serialises password changes, so a single-use change token cannot be spent twice concurrently. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from UserCredential c where c.userId = :userId")
	Optional<UserCredential> findByIdForUpdate(UUID userId);

}
