package vn.hayi.shop.customer.application;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.customer.api.CustomerProfiles;
import vn.hayi.shop.customer.api.CustomerErrorCode;
import vn.hayi.shop.customer.domain.CustomerProfile;
import vn.hayi.shop.customer.domain.CustomerProfileRepository;
import vn.hayi.shop.shared.error.DomainException;

/** {@link CustomerProfiles}: runs inside the caller's registration transaction (Identity §2.11). */
@Service
class CustomerProfileService implements CustomerProfiles {

	private static final String USER_UNIQUE_KEY = "uk_cus_customer_profile__user_id";

	private final CustomerProfileRepository profiles;

	private final Clock clock;

	CustomerProfileService(CustomerProfileRepository profiles, Clock clock) {
		this.profiles = profiles;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
	public boolean existsForUser(UUID userId) {
		return this.profiles.existsByUserId(userId);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Summary> findByUser(UUID userId) {
		return this.profiles.findByUserId(userId)
			.map(profile -> new Summary(profile.getId(), profile.getDisplayName()));
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public UUID create(UUID userId, String displayName) {
		if (this.profiles.existsByUserId(userId)) {
			throw alreadyRegistered(userId);
		}
		try {
			return this.profiles.saveAndFlush(CustomerProfile.create(userId, displayName, this.clock.instant())).getId();
		}
		catch (DataIntegrityViolationException ex) {
			// A concurrent registration of the same user won; any other violation is a bug.
			String message = NestedExceptionUtils.getMostSpecificCause(ex).getMessage();
			if (message != null && message.contains(USER_UNIQUE_KEY)) {
				throw alreadyRegistered(userId);
			}
			throw ex;
		}
	}

	private static DomainException alreadyRegistered(UUID userId) {
		return new DomainException(CustomerErrorCode.CUSTOMER_ALREADY_REGISTERED,
				"User " + userId + " already has a Customer profile");
	}

}
