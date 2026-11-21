package vn.hayi.shop.identity.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

public interface LoginIdentifierRepository extends Repository<LoginIdentifier, UUID> {

	LoginIdentifier save(LoginIdentifier identifier);

	/** Inserts now, so a phone taken by a concurrent registration fails on {@code uk_iam_login_identifier__type_value}. */
	LoginIdentifier saveAndFlush(LoginIdentifier identifier);

	Optional<LoginIdentifier> findByTypeAndNormalizedValue(IdentifierType type, String normalizedValue);

	Optional<LoginIdentifier> findByUserIdAndType(UUID userId, IdentifierType type);

}
