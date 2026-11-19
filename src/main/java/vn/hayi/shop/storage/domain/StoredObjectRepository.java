package vn.hayi.shop.storage.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

public interface StoredObjectRepository extends Repository<StoredObject, UUID> {

	StoredObject saveAndFlush(StoredObject object);

	Optional<StoredObject> findById(UUID id);

	List<StoredObject> findByIdIn(Collection<UUID> ids);

}
