package vn.hayi.shop.catalog.application;

import java.text.Collator;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.audit.api.AuditEntry;
import vn.hayi.shop.audit.api.AuditRecorder;
import vn.hayi.shop.audit.api.AuditSource;
import vn.hayi.shop.catalog.domain.CatalogErrorCode;
import vn.hayi.shop.catalog.domain.Collection;
import vn.hayi.shop.catalog.domain.CollectionRepository;
import vn.hayi.shop.catalog.domain.CollectionStatus;
import vn.hayi.shop.catalog.domain.FieldDiff;
import vn.hayi.shop.catalog.domain.Product;
import vn.hayi.shop.catalog.domain.ProductRepository;
import vn.hayi.shop.catalog.domain.ProductStatus;
import vn.hayi.shop.shared.concurrency.ExpectedVersion;
import vn.hayi.shop.shared.error.DomainException;

/**
 * Creates, edits, archives and reads Collections and sets their Products (Catalog §5.3, API 08 §1.10, Decision AL1);
 * every change is audited in the same transaction (Decision W8).
 */
@Service
public class CollectionService {

	private static final String SLUG_UNIQUE_KEY = "uk_cat_collection__slug";

	private static final Collator VIETNAMESE = Collator.getInstance(Locale.forLanguageTag("vi"));

	/** {@code sortOrder}, then name in Vietnamese order (AL1, AL3). */
	static final Comparator<Collection> ORDER = Comparator.comparingInt(Collection::getSortOrder)
		.thenComparing(Collection::getName, VIETNAMESE)
		.thenComparing(Collection::getId);

	private final CollectionRepository collections;

	private final ProductRepository products;

	private final AuditRecorder audit;

	private final Clock clock;

	CollectionService(CollectionRepository collections, ProductRepository products, AuditRecorder audit,
			Clock clock) {
		this.collections = collections;
		this.products = products;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional
	public CollectionView create(String slug, String name, String description, CatalogClient client) {
		if (this.collections.existsBySlug(slug)) {
			throw slugTaken(slug);
		}
		Collection collection = save(Collection.create(slug, name, description,
				this.collections.nextSortOrder(CollectionStatus.ARCHIVED), this.clock.instant()));
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("slug", slug);
		values.put("name", name);
		values.put("description", description);
		record(client, "COLLECTION_CREATE", collection, null, values);
		return CollectionView.of(collection);
	}

	/** Merge Patch at {@code expected} version; no change keeps the version and is not audited (AL1). */
	@Transactional
	public CollectionView update(UUID collectionId, CollectionChanges changes, ExpectedVersion expected,
			CatalogClient client) {
		Collection collection = find(collectionId);
		expected.verify(collection.getVersion());
		collection.requireNotArchived();
		String newSlug = changes.slug().applyTo(collection.getSlug());
		if (!newSlug.equals(collection.getSlug()) && this.collections.existsBySlugAndIdNot(newSlug, collectionId)) {
			throw slugTaken(newSlug);
		}
		FieldDiff diff = collection.edit(changes.name().applyTo(collection.getName()), newSlug,
				changes.description().applyTo(collection.getDescription()),
				changes.sortOrder().applyTo(collection.getSortOrder()),
				changes.status().map(CollectionStatus::valueOf).applyTo(collection.getStatus()),
				this.clock.instant());
		if (!diff.isEmpty()) {
			save(collection);
			record(client, "COLLECTION_UPDATE", collection, diff.oldValues(), diff.newValues());
		}
		return CollectionView.of(collection);
	}

	/** {@code expected} is {@code null} when no {@code If-Match} was sent (optional on commands). */
	@Transactional
	public CollectionView archive(UUID collectionId, ExpectedVersion expected, CatalogClient client) {
		Collection collection = find(collectionId);
		if (expected != null) {
			expected.verify(collection.getVersion());
		}
		CollectionStatus previous = collection.getStatus();
		collection.archive(this.clock.instant());
		save(collection);
		record(client, "COLLECTION_ARCHIVE", collection, Map.of("status", previous.name()),
				Map.of("status", collection.getStatus().name()));
		return CollectionView.of(collection);
	}

	/**
	 * Puts exactly these Products in this order (AL1).
	 * @throws DomainException {@code CATALOG_PRODUCT_NOT_FOUND}, {@code CATALOG_PRODUCT_ARCHIVED}
	 */
	@Transactional
	public CollectionView replaceProducts(UUID collectionId, List<UUID> productIds, ExpectedVersion expected,
			CatalogClient client) {
		Collection collection = find(collectionId);
		expected.verify(collection.getVersion());
		collection.requireNotArchived();
		Map<UUID, Product> found = productIds.isEmpty() ? Map.of()
				: this.products.findByIdIn(productIds)
					.stream()
					.collect(Collectors.toMap(Product::getId, Function.identity()));
		for (UUID productId : productIds) {
			Product product = found.get(productId);
			if (product == null) {
				throw new DomainException(CatalogErrorCode.CATALOG_PRODUCT_NOT_FOUND, "No Product " + productId);
			}
			if (product.getStatus() == ProductStatus.ARCHIVED) {
				throw new DomainException(CatalogErrorCode.CATALOG_PRODUCT_ARCHIVED,
						"Product " + productId + " is archived");
			}
		}
		List<UUID> before = collection.getProductIds();
		if (collection.replaceProducts(productIds, this.clock.instant())) {
			save(collection);
			record(client, "COLLECTION_PRODUCTS_REPLACE", collection, Map.of("productIds", before),
					Map.of("productIds", productIds));
		}
		return CollectionView.of(collection);
	}

	@Transactional(readOnly = true)
	public CollectionView get(UUID collectionId) {
		return CollectionView.of(find(collectionId));
	}

	/** By {@code sortOrder} then name; without {@code ARCHIVED} unless {@code includeArchived}. */
	@Transactional(readOnly = true)
	public List<CollectionView> list(boolean includeArchived) {
		List<Collection> found = includeArchived ? this.collections.findAll()
				: this.collections.findByStatusNot(CollectionStatus.ARCHIVED);
		return found.stream().sorted(ORDER).map(CollectionView::of).toList();
	}

	private Collection find(UUID collectionId) {
		return this.collections.findById(collectionId)
			.orElseThrow(() -> new DomainException(CatalogErrorCode.CATALOG_COLLECTION_NOT_FOUND,
					"No Collection " + collectionId));
	}

	private Collection save(Collection collection) {
		try {
			return this.collections.saveAndFlush(collection);
		}
		catch (DataIntegrityViolationException ex) {
			// A concurrent create or edit took the slug after the check; any other violation is a bug.
			String message = NestedExceptionUtils.getMostSpecificCause(ex).getMessage();
			if (message != null && message.contains(SLUG_UNIQUE_KEY)) {
				throw slugTaken(collection.getSlug());
			}
			throw ex;
		}
	}

	private void record(CatalogClient client, String action, Collection collection, Map<String, ?> oldValue,
			Map<String, ?> newValue) {
		this.audit.record(AuditEntry.of(client.actor(), AuditSource.BACKOFFICE_WEB, action, "COLLECTION",
				collection.getId())
			.withOldValue(oldValue)
			.withNewValue(newValue)
			.withClient(client.ipAddress(), client.userAgent()));
	}

	private static DomainException slugTaken(String slug) {
		return new DomainException(CatalogErrorCode.CATALOG_SLUG_TAKEN, "Slug taken: " + slug, slug);
	}

}
