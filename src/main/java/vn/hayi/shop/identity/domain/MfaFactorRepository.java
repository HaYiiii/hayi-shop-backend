package vn.hayi.shop.identity.domain;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface MfaFactorRepository extends Repository<MfaFactor, UUID> {

	MfaFactor save(MfaFactor factor);

	boolean existsByUserIdAndStatus(UUID userId, MfaFactorStatus status);

	/**
	 * The user's non-disabled factors, locked: enrollment, verification and step-up of one user run one after the
	 * other, so a TOTP step is never accepted twice.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select f from MfaFactor f where f.userId = :userId and f.status <> vn.hayi.shop.identity.domain.MfaFactorStatus.DISABLED")
	List<MfaFactor> findUsableByUserIdForUpdate(UUID userId);

}
