package vn.hayi.shop.storage.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * How business contexts use stored files they link (Storage §18.1, §18.3, Decision AF3): look one up before linking
 * it, archive it when the link goes. Implemented by the Storage module.
 */
public interface StoredObjects {

	Optional<StoredObjectInfo> find(UUID storedObjectId);

	/** The known ones among {@code storedObjectIds}, by id, for a page of images. */
	Map<UUID, StoredObjectInfo> findAll(Collection<UUID> storedObjectIds);

	/**
	 * {@code ACTIVE → ARCHIVED} once no business link uses it (Decision W7); the job that deletes the bytes comes in
	 * S7. Archiving an archived object again is a no-op.
	 * @throws IllegalStateException when the object is unknown or neither {@code ACTIVE} nor {@code ARCHIVED}
	 */
	void archive(UUID storedObjectId);

}
