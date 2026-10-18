package vn.hayi.shop.identity.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

public interface LoginIdentifierRepository extends Repository<LoginIdentifier, UUID> {

	LoginIdentifier save(LoginIdentifier identifier);

	Optional<LoginIdentifier> findByTypeAndNormalizedValue(IdentifierType type, String normalizedValue);

	Optional<LoginIdentifier> findByUserIdAndType(UUID userId, IdentifierType type);

}
