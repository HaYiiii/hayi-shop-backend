package vn.hayi.shop.identity.domain;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface MfaRecoveryCodeRepository extends Repository<MfaRecoveryCode, UUID> {

	MfaRecoveryCode save(MfaRecoveryCode code);

	/** Locked, so a code is used once even when two requests send it together. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<MfaRecoveryCode> findByUserIdAndCodeHashAndUsedAtIsNull(UUID userId, String codeHash);

	/** A new set replaces the old one (Decision L5). */
	@Modifying
	@Query("delete from MfaRecoveryCode c where c.userId = :userId")
	void deleteByUserId(UUID userId);

}
