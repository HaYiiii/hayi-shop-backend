package vn.hayi.shop.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.audit.api.AuditEntry;
import vn.hayi.shop.audit.api.AuditRecorder;
import vn.hayi.shop.audit.api.AuditSource;
import vn.hayi.shop.catalog.domain.CatalogErrorCode;
import vn.hayi.shop.catalog.domain.Category;
import vn.hayi.shop.catalog.domain.CategoryRepository;
import vn.hayi.shop.catalog.domain.CategoryStatus;
import vn.hayi.shop.catalog.domain.FieldDiff;
import vn.hayi.shop.shared.actor.ActorContext;
import vn.hayi.shop.shared.concurrency.ExpectedVersion;
import vn.hayi.shop.shared.error.DomainException;

/**
 * Creates, edits, archives, orders and reads the Category tree (Catalog §5.3, API 08 §1.2, Decision Y); every change
 * is audited in the same transaction (Decision W8).
 */
@Service
public class CategoryService {

	private static final String SLUG_UNIQUE_KEY = "uk_cat_category__slug";

	/** Audit entity of a reorder: the parent group; the roots have no parent, so they use the nil UUID. */
	private static final UUID ROOTS = new UUID(0, 0);

	private final CategoryRepository categories;

	private final AuditRecorder audit;

	private final Clock clock;

	CategoryService(CategoryRepository categories, AuditRecorder audit, Clock clock) {
		this.categories = categories;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional
	public CategoryView create(NewCategory request, Client client) {
		Category parent = (request.parentId() != null) ? lock(request.parentId()) : null;
		if (this.categories.existsBySlug(request.slug())) {
			throw slugTaken(request.slug());
		}
		Category category = save(Category.create(request.slug(), request.name(), request.description(), parent,
				nextPosition(request.parentId()), this.clock.instant()));
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("slug", category.getSlug());
		values.put("name", category.getName());
		values.put("description", category.getDescription());
		values.put("parentId", category.getParentId());
		record(client, "CATEGORY_CREATE", "CATEGORY", category.getId(), null, values);
		return CategoryView.of(category);
	}

	/**
	 * Edits a Category still at {@code expected} version (Decision Y3, O4). A new parent puts it last there; an edit
	 * that changes nothing keeps the version and is not audited.
	 */
	@Transactional
	public CategoryView update(UUID categoryId, CategoryChanges changes, ExpectedVersion expected, Client client) {
		Category category = lock(categoryId);
		expected.verify(category.getVersion());
		category.requireNotArchived();
		String newSlug = changes.slug().applyTo(category.getSlug());
		if (!newSlug.equals(category.getSlug()) && this.categories.existsBySlugAndIdNot(newSlug, categoryId)) {
			throw slugTaken(newSlug);
		}
		Instant now = this.clock.instant();
		FieldDiff diff = category.edit(changes.name().applyTo(category.getName()), newSlug,
				changes.description().applyTo(category.getDescription()), now);
		UUID newParentId = changes.parentId().applyTo(category.getParentId());
		if (!Objects.equals(newParentId, category.getParentId())) {
			Category parent = (newParentId != null) ? lock(newParentId) : null;
			boolean hasChildren = this.categories.existsByParentIdAndStatusNot(categoryId, CategoryStatus.ARCHIVED);
			category.moveTo(parent, hasChildren, nextPosition(newParentId), diff, now);
		}
		if (diff.isEmpty()) {
			return CategoryView.of(category);
		}
		save(category);
		record(client, "CATEGORY_UPDATE", "CATEGORY", categoryId, diff.oldValues(), diff.newValues());
		return CategoryView.of(category);
	}

	/** {@code expected} is {@code null} when no {@code If-Match} was sent (optional on commands, Decision Y4). */
	@Transactional
	public CategoryView archive(UUID categoryId, ExpectedVersion expected, Client client) {
		Category category = lock(categoryId);
		if (expected != null) {
			expected.verify(category.getVersion());
		}
		category.archive(this.categories.existsByParentIdAndStatusNot(categoryId, CategoryStatus.ARCHIVED),
				this.clock.instant());
		this.categories.flush();
		record(client, "CATEGORY_ARCHIVE", "CATEGORY", categoryId, Map.of("status", CategoryStatus.ACTIVE.name()),
				Map.of("status", CategoryStatus.ARCHIVED.name()));
		return CategoryView.of(category);
	}

	/**
	 * Puts the non-archived siblings under {@code parentId} ({@code null} = the roots) in the order of
	 * {@code categoryIds}, which must hold exactly them (Decision Y6). Only moved Categories get a new version.
	 */
	@Transactional
	public List<CategoryView> reorder(UUID parentId, List<UUID> categoryIds, Client client) {
		if (parentId != null) {
			lock(parentId).requireNotArchived();
		}
		List<Category> siblings = this.categories.findSiblings(parentId, CategoryStatus.ARCHIVED);
		List<UUID> before = siblings.stream().map(Category::getId).toList();
		if (before.size() != categoryIds.size() || !new HashSet<>(before).containsAll(categoryIds)) {
			throw new DomainException(CatalogErrorCode.CATALOG_CATEGORY_ORDER_STALE,
					"Expected the siblings " + before + " but got " + categoryIds);
		}
		if (!before.equals(categoryIds)) {
			Map<UUID, Category> byId = new LinkedHashMap<>();
			siblings.forEach(sibling -> byId.put(sibling.getId(), sibling));
			Instant now = this.clock.instant();
			for (int position = 0; position < categoryIds.size(); position++) {
				byId.get(categoryIds.get(position)).placeAt(position, now);
			}
			this.categories.flush();
			record(client, "CATEGORY_REORDER", "CATEGORY_ORDER", (parentId != null) ? parentId : ROOTS,
					Map.of("categoryIds", before), Map.of("categoryIds", categoryIds));
		}
		return tree(false);
	}

	@Transactional(readOnly = true)
	public CategoryView get(UUID categoryId) {
		return CategoryView.of(find(categoryId));
	}

	/** Roots with their children, both by {@code sortOrder}; only {@code ACTIVE} unless {@code includeArchived}. */
	@Transactional(readOnly = true)
	public List<CategoryView> tree(boolean includeArchived) {
		List<Category> all = includeArchived ? this.categories.findAllByOrderBySortOrderAscIdAsc()
				: this.categories.findByStatusOrderBySortOrderAscIdAsc(CategoryStatus.ACTIVE);
		Map<UUID, List<CategoryView>> children = new LinkedHashMap<>();
		for (Category category : all) {
			if (category.getParentId() != null) {
				children.computeIfAbsent(category.getParentId(), id -> new ArrayList<>()).add(CategoryView.of(category));
			}
		}
		return all.stream()
			.filter(category -> category.getParentId() == null)
			.map(root -> CategoryView.of(root, children.getOrDefault(root.getId(), List.of())))
			.toList();
	}

	private int nextPosition(UUID parentId) {
		List<Category> siblings = this.categories.findSiblings(parentId, CategoryStatus.ARCHIVED);
		return siblings.isEmpty() ? 0 : siblings.getLast().getSortOrder() + 1;
	}

	private Category save(Category category) {
		try {
			return this.categories.saveAndFlush(category);
		}
		catch (DataIntegrityViolationException ex) {
			// A concurrent create or edit took the slug after the check; any other violation is a bug.
			String message = NestedExceptionUtils.getMostSpecificCause(ex).getMessage();
			if (message != null && message.contains(SLUG_UNIQUE_KEY)) {
				throw slugTaken(category.getSlug());
			}
			throw ex;
		}
	}

	private Category lock(UUID categoryId) {
		return this.categories.findByIdForUpdate(categoryId).orElseThrow(() -> notFound(categoryId));
	}

	private Category find(UUID categoryId) {
		return this.categories.findById(categoryId).orElseThrow(() -> notFound(categoryId));
	}

	private void record(Client client, String action, String entityType, UUID entityId, Map<String, ?> oldValue,
			Map<String, ?> newValue) {
		this.audit.record(AuditEntry.of(client.actor(), AuditSource.BACKOFFICE_WEB, action, entityType, entityId)
			.withOldValue(oldValue)
			.withNewValue(newValue)
			.withClient(client.ipAddress(), client.userAgent()));
	}

	private static DomainException notFound(UUID categoryId) {
		return new DomainException(CatalogErrorCode.CATALOG_CATEGORY_NOT_FOUND, "No Category " + categoryId);
	}

	private static DomainException slugTaken(String slug) {
		return new DomainException(CatalogErrorCode.CATALOG_SLUG_TAKEN, "Slug taken: " + slug, slug);
	}

	/** Who asked, for Audit. */
	public record Client(ActorContext actor, String ipAddress, String userAgent) {
	}

}
