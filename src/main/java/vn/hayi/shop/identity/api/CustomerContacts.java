package vn.hayi.shop.identity.api;

import java.util.Optional;
import java.util.UUID;

/**
 * A User's login phone and the phone form used everywhere (Decision AN2), for other modules: the default recipient of a
 * checkout (Decision AZ4). Implemented by the Identity & Access module.
 */
public interface CustomerContacts {

	/** The E.164 phone the User logs in with, if any. */
	Optional<String> loginPhone(UUID userId);

	/** The E.164 form of a Vietnamese phone, or empty when it is not one (Decision AN2). */
	Optional<String> normalizePhone(String input);

}
