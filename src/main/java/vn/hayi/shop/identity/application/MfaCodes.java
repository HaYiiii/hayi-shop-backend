package vn.hayi.shop.identity.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.hayi.shop.identity.domain.MfaFactor;
import vn.hayi.shop.identity.domain.MfaFactorRepository;
import vn.hayi.shop.identity.domain.MfaFactorStatus;
import vn.hayi.shop.identity.domain.MfaRecoveryCode;
import vn.hayi.shop.identity.domain.MfaRecoveryCodeRepository;
import vn.hayi.shop.identity.domain.Totp;
import vn.hayi.shop.identity.infrastructure.MfaSecretCipher;

/**
 * Checks TOTP and recovery codes against a user's factor (Identity §2.4, Decision L5). Callers run in a transaction;
 * the factor row is locked, so one TOTP step is accepted once even under concurrent requests.
 */
@Component
class MfaCodes {

	private final MfaFactorRepository factors;

	private final MfaRecoveryCodeRepository recoveryCodes;

	private final MfaSecretCipher cipher;

	MfaCodes(MfaFactorRepository factors, MfaRecoveryCodeRepository recoveryCodes, MfaSecretCipher cipher) {
		this.factors = factors;
		this.recoveryCodes = recoveryCodes;
		this.cipher = cipher;
	}

	boolean hasActiveFactor(UUID userId) {
		return this.factors.existsByUserIdAndStatus(userId, MfaFactorStatus.ACTIVE);
	}

	/** The user's usable factors, locked for the rest of the transaction. */
	List<MfaFactor> lockFactors(UUID userId) {
		return this.factors.findUsableByUserIdForUpdate(userId);
	}

	/** A TOTP code of the active factor; the step it matched cannot be used again. */
	boolean acceptTotp(UUID userId, String code, Instant now) {
		Optional<MfaFactor> factor = lockFactors(userId).stream()
			.filter(candidate -> candidate.getStatus() == MfaFactorStatus.ACTIVE)
			.findFirst();
		if (factor.isEmpty()) {
			return false;
		}
		OptionalLong step = Totp.match(secretOf(factor.get()), code, now, factor.get().getLastUsedAt());
		step.ifPresent(accepted -> factor.get().markUsed(Totp.stepStart(accepted), now));
		return step.isPresent();
	}

	/** A 6-digit TOTP code, or else one of the user's unused recovery codes. */
	boolean acceptTotpOrRecoveryCode(UUID userId, String code, Instant now) {
		if (code.length() == 6 && code.chars().allMatch(Character::isDigit)) {
			return acceptTotp(userId, code, now);
		}
		Optional<MfaRecoveryCode> recoveryCode = this.recoveryCodes
			.findByUserIdAndCodeHashAndUsedAtIsNull(userId, MfaRecoveryCode.hash(code));
		recoveryCode.ifPresent(found -> found.use(now));
		return recoveryCode.isPresent();
	}

	/** The pending factor's step a first code matched, if it did. */
	OptionalLong matchPending(MfaFactor pending, String code, Instant now) {
		return Totp.match(secretOf(pending), code, now, null);
	}

	/** A new set of recovery codes; the old set stops working (Decision L5). */
	List<String> issueRecoveryCodes(UUID userId, Instant now) {
		this.recoveryCodes.deleteByUserId(userId);
		List<String> codes = new ArrayList<>(MfaRecoveryCode.SET_SIZE);
		while (codes.size() < MfaRecoveryCode.SET_SIZE) {
			String code = MfaRecoveryCode.newCode();
			if (!codes.contains(code)) {
				codes.add(code);
				this.recoveryCodes.save(MfaRecoveryCode.of(userId, code, now));
			}
		}
		return codes;
	}

	byte[] encrypt(UUID userId, byte[] secret) {
		return this.cipher.encrypt(userId, secret);
	}

	MfaFactor save(MfaFactor factor) {
		return this.factors.save(factor);
	}

	private byte[] secretOf(MfaFactor factor) {
		return this.cipher.decrypt(factor.getUserId(), factor.getSecretEncrypted());
	}

}
