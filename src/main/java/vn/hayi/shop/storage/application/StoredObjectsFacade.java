package vn.hayi.shop.storage.application;

import java.time.Clock;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.storage.api.StoredObjectInfo;
import vn.hayi.shop.storage.api.StoredObjects;
import vn.hayi.shop.storage.domain.StoredObject;
import vn.hayi.shop.storage.domain.StoredObjectRepository;
import vn.hayi.shop.storage.domain.StoredObjectStatus;
import vn.hayi.shop.storage.domain.Visibility;

/** {@code storage.api} for business contexts (Decision AF3); joins the caller's transaction. */
@Service
class StoredObjectsFacade implements StoredObjects {

	private final StoredObjectRepository objects;

	private final ObjectStore store;

	private final Clock clock;

	StoredObjectsFacade(StoredObjectRepository objects, ObjectStore store, Clock clock) {
		this.objects = objects;
		this.store = store;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<StoredObjectInfo> find(UUID storedObjectId) {
		return this.objects.findById(storedObjectId).map(this::info);
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, StoredObjectInfo> findAll(Collection<UUID> storedObjectIds) {
		if (storedObjectIds.isEmpty()) {
			return Map.of();
		}
		return this.objects.findByIdIn(storedObjectIds)
			.stream()
			.collect(Collectors.toMap(StoredObject::getId, this::info));
	}

	@Override
	@Transactional
	public void archive(UUID storedObjectId) {
		StoredObject object = this.objects.findById(storedObjectId)
			.orElseThrow(() -> new IllegalStateException("No stored object " + storedObjectId));
		if (object.archive(this.clock.instant())) {
			this.objects.saveAndFlush(object);
		}
	}

	private StoredObjectInfo info(StoredObject object) {
		String publicUrl = (object.getStatus() == StoredObjectStatus.ACTIVE
				&& object.getVisibility() == Visibility.PUBLIC)
						? this.store.publicUrl(object.getBucket(), object.getObjectKey()) : null;
		return new StoredObjectInfo(object.getId(), StoredObjectInfo.Status.valueOf(object.getStatus().name()),
				StoredObjectInfo.Visibility.valueOf(object.getVisibility().name()),
				StoredObjectInfo.Purpose.valueOf(object.getPurpose().name()), publicUrl);
	}

}
