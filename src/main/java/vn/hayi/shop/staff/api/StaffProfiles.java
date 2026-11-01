package vn.hayi.shop.staff.api;

import java.util.Optional;
import java.util.UUID;

/** The staff identity of a user (Staff Operations §16.10); read by Identity for {@code GET /auth/me}. */
public interface StaffProfiles {

	/** The StaffProfile's full name, whatever its status; empty for a user without StaffProfile. */
	Optional<String> fullName(UUID userId);

}
