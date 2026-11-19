package vn.hayi.shop.storage.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

public interface StoredObjectRepository extends Repository<StoredObject, UUID> {

	StoredObject saveAndFlush(StoredObject object);

	Optional<StoredObject> findById(UUID id);

}
