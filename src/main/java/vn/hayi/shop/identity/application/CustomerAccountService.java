package vn.hayi.shop.identity.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.customer.api.CustomerErrorCode;
import vn.hayi.shop.customer.api.CustomerProfiles;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.identity.domain.IdentifierType;
import vn.hayi.shop.identity.domain.LoginIdentifier;
import vn.hayi.shop.identity.domain.LoginIdentifierRepository;
import vn.hayi.shop.identity.domain.PhoneNumbers;

/** {@code GET /api/customer/v1/auth/me} (Decision AO2): the user and a summary of its Customer profile. */
@Service
public class CustomerAccountService {

	private final LoginIdentifierRepository identifiers;

	private final CustomerProfiles profiles;

	CustomerAccountService(LoginIdentifierRepository identifiers, CustomerProfiles profiles) {
		this.identifiers = identifiers;
		this.profiles = profiles;
	}

	/** Every field may be {@code null} except {@code userId}; the API keeps every key. */
	public record CustomerAccount(UUID userId, UUID customerId, String displayName, String phoneMasked) {
	}

	/** {@code GET /me/profile} (API 07 §1): {@code loyalty} stays {@code null} until S16. */
	public record CustomerProfileView(UUID customerId, String displayName, String phoneMasked, long version) {
	}

	/** @throws DomainException {@code CUSTOMER_PROFILE_NOT_FOUND} for a session without profile */
	@Transactional(readOnly = true)
	public CustomerProfileView profile(UUID userId) {
		CustomerProfiles.Summary summary = this.profiles.findByUser(userId)
			.orElseThrow(() -> new DomainException(CustomerErrorCode.CUSTOMER_PROFILE_NOT_FOUND, "No profile"));
		return new CustomerProfileView(summary.customerId(), summary.displayName(), maskedPhone(userId),
				summary.version());
	}

	/** Renames the caller's own profile at {@code expectedVersion} (Decision O). */
	@Transactional
	public CustomerProfileView rename(UUID userId, String displayName, long expectedVersion) {
		CustomerProfiles.Summary summary = this.profiles.rename(userId, displayName, expectedVersion);
		return new CustomerProfileView(summary.customerId(), summary.displayName(), maskedPhone(userId),
				summary.version());
	}

	private String maskedPhone(UUID userId) {
		return this.identifiers.findByUserIdAndType(userId, IdentifierType.PHONE)
			.map(LoginIdentifier::getNormalizedValue)
			.map(PhoneNumbers::mask)
			.orElse(null);
	}

	@Transactional(readOnly = true)
	public CustomerAccount account(UUID userId) {
		String phone = this.identifiers.findByUserIdAndType(userId, IdentifierType.PHONE)
			.map(LoginIdentifier::getNormalizedValue)
			.map(PhoneNumbers::mask)
			.orElse(null);
		return this.profiles.findByUser(userId)
			.map(profile -> new CustomerAccount(userId, profile.customerId(), profile.displayName(), phone))
			.orElse(new CustomerAccount(userId, null, null, phone));
	}

}
