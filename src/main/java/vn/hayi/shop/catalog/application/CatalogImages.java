package vn.hayi.shop.catalog.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import vn.hayi.shop.catalog.domain.CatalogErrorCode;
import vn.hayi.shop.catalog.domain.CategoryRepository;
import vn.hayi.shop.catalog.domain.ProductRepository;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.storage.api.StoredObjectInfo;
import vn.hayi.shop.storage.api.StoredObjects;

/**
 * Catalog's side of stored images (Storage §18.3, Decision AG, W7): which files may be linked, their public URLs,
 * and archiving a file once its link goes.
 */
@Component
class CatalogImages {

	private final StoredObjects storedObjects;

	private final ProductRepository products;

	private final CategoryRepository categories;

	CatalogImages(StoredObjects storedObjects, ProductRepository products, CategoryRepository categories) {
		this.storedObjects = storedObjects;
		this.products = products;
		this.categories = categories;
	}

	/**
	 * A file may be linked when it is an {@code ACTIVE}, {@code PUBLIC} file of that purpose that nothing links yet
	 * (Decision AG1, AG4). Runs only queries, so the caller may still change its aggregate afterwards.
	 * @throws DomainException {@code CATALOG_IMAGE_INVALID}, {@code CATALOG_IMAGE_IN_USE}
	 */
	void requireLinkable(UUID storedObjectId, StoredObjectInfo.Purpose purpose) {
		StoredObjectInfo info = this.storedObjects.find(storedObjectId)
			.orElseThrow(() -> invalid(storedObjectId, "unknown"));
		if (!info.isActive() || info.visibility() != StoredObjectInfo.Visibility.PUBLIC || info.purpose() != purpose) {
			throw invalid(storedObjectId, info.status() + " " + info.visibility() + " " + info.purpose());
		}
		if (this.products.isImageInUse(storedObjectId) || this.categories.existsByImageObjectId(storedObjectId)) {
			throw new DomainException(CatalogErrorCode.CATALOG_IMAGE_IN_USE,
					"Stored object " + storedObjectId + " is already linked");
		}
	}

	/** The public URL of each linked file, by stored object id; a missing one has none. */
	Map<UUID, String> urls(Collection<UUID> storedObjectIds) {
		return this.storedObjects.findAll(storedObjectIds)
			.values()
			.stream()
			.filter(info -> info.publicUrl() != null)
			.collect(Collectors.toMap(StoredObjectInfo::storedObjectId, StoredObjectInfo::publicUrl));
	}

	String url(UUID storedObjectId) {
		return (storedObjectId != null) ? urls(List.of(storedObjectId)).get(storedObjectId) : null;
	}

	/** The link went: the file is archived in the same transaction (W7); its bytes go with the S7 cleanup job. */
	void unlinked(UUID storedObjectId) {
		this.storedObjects.archive(storedObjectId);
	}

	private static DomainException invalid(UUID storedObjectId, String why) {
		return new DomainException(CatalogErrorCode.CATALOG_IMAGE_INVALID,
				"Stored object " + storedObjectId + " cannot be linked: " + why);
	}

}
