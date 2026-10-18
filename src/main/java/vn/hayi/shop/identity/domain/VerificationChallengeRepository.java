package vn.hayi.shop.identity.domain;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface VerificationChallengeRepository extends Repository<VerificationChallenge, UUID> {

	VerificationChallenge save(VerificationChallenge challenge);

	/** Serialises attempts on one challenge, so {@code maxAttempts} is exact. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from VerificationChallenge c where c.id = :id")
	Optional<VerificationChallenge> findByIdForUpdate(UUID id);

}
