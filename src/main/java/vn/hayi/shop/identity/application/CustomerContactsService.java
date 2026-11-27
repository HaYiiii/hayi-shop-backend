package vn.hayi.shop.identity.application;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.identity.api.CustomerContacts;
import vn.hayi.shop.identity.domain.IdentifierType;
import vn.hayi.shop.identity.domain.LoginIdentifier;
import vn.hayi.shop.identity.domain.LoginIdentifierRepository;
import vn.hayi.shop.identity.domain.PhoneNumbers;

@Service
class CustomerContactsService implements CustomerContacts {

	private final LoginIdentifierRepository identifiers;

	CustomerContactsService(LoginIdentifierRepository identifiers) {
		this.identifiers = identifiers;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<String> loginPhone(UUID userId) {
		return this.identifiers.findByUserIdAndType(userId, IdentifierType.PHONE)
			.map(LoginIdentifier::getNormalizedValue);
	}

	@Override
	public Optional<String> normalizePhone(String input) {
		return PhoneNumbers.normalize(input);
	}

}
