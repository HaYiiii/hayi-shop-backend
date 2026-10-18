package vn.hayi.shop.identity.domain;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface VerificationChallengeRepository extends Repository<VerificationChallenge, UUID> {

	VerificationChallenge save(VerificationChallenge challenge);

	/** Serialises attempts on one challenge, so {@code maxAttempts} is exact. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from VerificationChallenge c where c.id = :id")
	Optional<VerificationChallenge> findByIdForUpdate(UUID id);

	/** The owner of a challenge, read without loading it, so the user row can be locked before the challenge. */
	@Query("select c.userId from VerificationChallenge c where c.id = :id and c.purpose = :purpose")
	Optional<UUID> findUserIdByIdAndPurpose(UUID id, VerificationPurpose purpose);

	/** Ends the user's open challenges of {@code purpose}: a new login replaces them (Decision L1). */
	@Modifying
	@Query("""
			update VerificationChallenge c set c.status = :expired, c.version = c.version + 1
			where c.userId = :userId and c.purpose = :purpose and c.status = :pending
			""")
	int expireOpen(UUID userId, VerificationPurpose purpose, VerificationStatus pending, VerificationStatus expired);

}
