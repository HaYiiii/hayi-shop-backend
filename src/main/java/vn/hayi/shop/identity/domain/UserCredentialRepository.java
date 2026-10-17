package vn.hayi.shop.identity.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

public interface UserCredentialRepository extends Repository<UserCredential, UUID> {

	UserCredential save(UserCredential credential);

	Optional<UserCredential> findById(UUID userId);

}
