package vn.hayi.shop.identity.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.identity.domain.IdentifierType;
import vn.hayi.shop.identity.domain.LoginIdentifier;
import vn.hayi.shop.identity.domain.LoginIdentifierRepository;
import vn.hayi.shop.staff.api.StaffProfiles;

/** Reads the {@link BackofficeAccount} of {@code GET /auth/me}. */
@Service
@Transactional(readOnly = true)
public class BackofficeAccountService {

	private final LoginIdentifierRepository identifiers;

	private final StaffProfiles staffProfiles;

	private final MfaCodes mfaCodes;

	BackofficeAccountService(LoginIdentifierRepository identifiers, StaffProfiles staffProfiles, MfaCodes mfaCodes) {
		this.identifiers = identifiers;
		this.staffProfiles = staffProfiles;
		this.mfaCodes = mfaCodes;
	}

	public BackofficeAccount account(UUID userId) {
		String email = this.identifiers.findByUserIdAndType(userId, IdentifierType.EMAIL)
			.map(LoginIdentifier::getNormalizedValue)
			.orElse(null);
		// The same check step-up uses to pick TOTP or password (API 01-security §8), so the dialog never asks wrong.
		return new BackofficeAccount(email, this.staffProfiles.fullName(userId).orElse(null),
				this.mfaCodes.hasActiveFactor(userId));
	}

}
