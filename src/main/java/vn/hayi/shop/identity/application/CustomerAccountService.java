package vn.hayi.shop.identity.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.customer.api.CustomerProfiles;
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
