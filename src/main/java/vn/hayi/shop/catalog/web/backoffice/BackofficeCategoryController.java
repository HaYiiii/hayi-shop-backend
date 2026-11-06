package vn.hayi.shop.catalog.web.backoffice;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import vn.hayi.shop.catalog.application.CategoryChanges;
import vn.hayi.shop.catalog.application.CatalogClient;
import vn.hayi.shop.catalog.application.CategoryService;
import vn.hayi.shop.catalog.application.CategoryView;
import vn.hayi.shop.catalog.application.NewCategory;
import vn.hayi.shop.platform.security.AuthenticatedSession;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.platform.security.Permission;
import vn.hayi.shop.platform.security.RequiresPermission;
import vn.hayi.shop.platform.web.InvalidFieldsException;
import vn.hayi.shop.platform.web.MergePatch;
import vn.hayi.shop.platform.web.VersionTags;
import vn.hayi.shop.platform.web.spec.backoffice.api.CategoriesApi;
import vn.hayi.shop.platform.web.spec.backoffice.model.CategoryCreateRequestDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.CategoryDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.CategoryNodeDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.CategoryReorderRequestDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.CategoryStatusDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.CategoryTreeDto;
import vn.hayi.shop.shared.actor.ActorContext;
import vn.hayi.shop.shared.patch.Change;

/**
 * Backoffice Category endpoints (API 08 §1.2, Decision Y). Brand-level, so a permission at any scope is enough
 * (Decision M1).
 */
@RestController
class BackofficeCategoryController implements CategoriesApi {

	private static final String CATEGORIES = "/api/backoffice/v1/categories/";

	/** Same as the {@code CategorySlug} schema, which the generated create DTO already enforces. */
	private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

	private final CategoryService categories;

	private final HttpServletRequest request;

	BackofficeCategoryController(CategoryService categories, HttpServletRequest request) {
		this.categories = categories;
		this.request = request;
	}

	@Override
	@RequiresPermission(Permission.CATALOG_VIEW)
	public ResponseEntity<CategoryTreeDto> listCategories(Boolean includeArchived) {
		return ResponseEntity.ok(toTree(this.categories.tree(Boolean.TRUE.equals(includeArchived))));
	}

	@Override
	@RequiresPermission(Permission.CATALOG_VIEW)
	public ResponseEntity<CategoryDto> getCategory(UUID categoryId) {
		return withETag(this.categories.get(categoryId));
	}

	@Override
	@RequiresPermission(Permission.CATALOG_MANAGE)
	public ResponseEntity<CategoryDto> createCategory(CategoryCreateRequestDto body, String xCsrfToken) {
		CategoryView category = this.categories.create(
				new NewCategory(body.getSlug(), body.getName(), body.getDescription(), body.getParentId()), client());
		return ResponseEntity.created(URI.create(CATEGORIES + category.categoryId()))
			.eTag(VersionTags.of(category.version()))
			.body(toDto(category));
	}

	@Override
	@RequiresPermission(Permission.CATALOG_MANAGE)
	public ResponseEntity<CategoryDto> updateCategory(UUID categoryId, JsonNode body, String ifMatch,
			String xCsrfToken) {
		var expected = VersionTags.require(ifMatch);
		return withETag(this.categories.update(categoryId, toChanges(body), expected, client()));
	}

	@Override
	@RequiresPermission(Permission.CATALOG_MANAGE)
	public ResponseEntity<CategoryDto> archiveCategory(UUID categoryId, String ifMatch, String xCsrfToken) {
		return withETag(this.categories.archive(categoryId, VersionTags.optional(ifMatch), client()));
	}

	@Override
	@RequiresPermission(Permission.CATALOG_MANAGE)
	public ResponseEntity<CategoryTreeDto> reorderCategories(CategoryReorderRequestDto body, String xCsrfToken) {
		List<UUID> ids = body.getCategoryIds();
		if (new HashSet<>(ids).size() != ids.size()) {
			throw new InvalidFieldsException(List.of(new InvalidFieldsException.Violation("categoryIds", "DUPLICATE")));
		}
		return ResponseEntity.ok(toTree(this.categories.reorder(body.getParentId(), ids, client())));
	}

	/** Field limits follow ERD 05 and the {@code CategoryPatch} schema; anything else in the body is refused. */
	private static CategoryChanges toChanges(JsonNode body) {
		MergePatch patch = MergePatch.of(body);
		Change<String> name = patch.requiredText("name", 128);
		Change<String> slug = patch.requiredText("slug", 128, SLUG);
		Change<String> description = patch.optionalText("description", 500);
		Change<UUID> parentId = patch.optionalUuid("parentId");
		patch.validate();
		return new CategoryChanges(name, slug, description, parentId);
	}

	private CatalogClient client() {
		ClientInfo client = ClientInfo.from(this.request);
		return new CatalogClient(ActorContext.staff(AuthenticatedSession.require().userId(), null),
				client.ipAddress(), client.userAgent());
	}

	private static ResponseEntity<CategoryDto> withETag(CategoryView category) {
		return ResponseEntity.ok().eTag(VersionTags.of(category.version())).body(toDto(category));
	}

	private static CategoryTreeDto toTree(List<CategoryView> roots) {
		return new CategoryTreeDto(roots.stream()
			.map(root -> new CategoryNodeDto(root.categoryId(), root.slug(), root.name(), root.description(),
					root.parentId(), root.sortOrder(), CategoryStatusDto.valueOf(root.status()), root.version(),
					utc(root.createdAt()), utc(root.updatedAt()),
					root.children().stream().map(BackofficeCategoryController::toDto).toList()))
			.toList());
	}

	private static CategoryDto toDto(CategoryView category) {
		return new CategoryDto(category.categoryId(), category.slug(), category.name(), category.description(),
				category.parentId(), category.sortOrder(), CategoryStatusDto.valueOf(category.status()),
				category.version(), utc(category.createdAt()), utc(category.updatedAt()));
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
