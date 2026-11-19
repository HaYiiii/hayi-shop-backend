package vn.hayi.shop.catalog.web.backoffice;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import vn.hayi.shop.catalog.application.CatalogClient;
import vn.hayi.shop.catalog.application.CollectionChanges;
import vn.hayi.shop.catalog.application.CollectionService;
import vn.hayi.shop.catalog.application.CollectionView;
import vn.hayi.shop.catalog.application.TagService;
import vn.hayi.shop.catalog.application.TagView;
import vn.hayi.shop.platform.security.AuthenticatedSession;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.platform.security.Permission;
import vn.hayi.shop.platform.security.RequiresPermission;
import vn.hayi.shop.platform.web.InvalidFieldsException;
import vn.hayi.shop.platform.web.MergePatch;
import vn.hayi.shop.platform.web.VersionTags;
import vn.hayi.shop.platform.web.spec.backoffice.api.CollectionsApi;
import vn.hayi.shop.platform.web.spec.backoffice.api.TagsApi;
import vn.hayi.shop.platform.web.spec.backoffice.model.CollectionCreateRequestDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.CollectionDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.CollectionListDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.CollectionProductsRequestDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.CollectionStatusDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.TagCreateRequestDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.TagDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.TagListDto;
import vn.hayi.shop.shared.actor.ActorContext;
import vn.hayi.shop.shared.patch.Change;

/** Backoffice Collection and Tag endpoints (API 08 §1.10, Decision AL); brand-level (Decision M1). */
@RestController
class BackofficeCollectionController implements CollectionsApi, TagsApi {

	private static final String COLLECTIONS = "/api/backoffice/v1/collections/";

	private static final String TAGS = "/api/backoffice/v1/tags/";

	/** Same as the {@code CategorySlug} / {@code TagSlug} schemas. */
	private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

	private final CollectionService collections;

	private final TagService tags;

	private final HttpServletRequest request;

	BackofficeCollectionController(CollectionService collections, TagService tags, HttpServletRequest request) {
		this.collections = collections;
		this.tags = tags;
		this.request = request;
	}

	// --- Collections (AL1) -------------------------------------------------------------------------------------

	@Override
	@RequiresPermission(Permission.CATALOG_VIEW)
	public ResponseEntity<CollectionListDto> listCollections(Boolean includeArchived) {
		return ResponseEntity.ok(new CollectionListDto(this.collections.list(Boolean.TRUE.equals(includeArchived))
			.stream()
			.map(BackofficeCollectionController::toDto)
			.toList()));
	}

	@Override
	@RequiresPermission(Permission.CATALOG_VIEW)
	public ResponseEntity<CollectionDto> getCollection(UUID collectionId) {
		return withETag(this.collections.get(collectionId));
	}

	@Override
	@RequiresPermission(Permission.CATALOG_MANAGE)
	public ResponseEntity<CollectionDto> createCollection(CollectionCreateRequestDto body, String xCsrfToken) {
		CollectionView collection = this.collections.create(body.getSlug(), body.getName(), body.getDescription(),
				client());
		return ResponseEntity.created(URI.create(COLLECTIONS + collection.collectionId()))
			.eTag(VersionTags.of(collection.version()))
			.body(toDto(collection));
	}

	@Override
	@RequiresPermission(Permission.CATALOG_MANAGE)
	public ResponseEntity<CollectionDto> updateCollection(UUID collectionId, JsonNode body, String ifMatch,
			String xCsrfToken) {
		var expected = VersionTags.require(ifMatch);
		MergePatch patch = MergePatch.of(body);
		Change<String> name = patch.requiredText("name", 128);
		Change<String> slug = patch.requiredText("slug", 128, SLUG);
		Change<String> description = patch.optionalText("description", 500);
		Change<Integer> sortOrder = patch.requiredInt("sortOrder");
		Change<String> status = patch.requiredEnum("status", Set.of("ACTIVE", "INACTIVE"));
		patch.validate();
		if (sortOrder.applyTo(0) < 0) {
			throw new InvalidFieldsException(List.of(new InvalidFieldsException.Violation("sortOrder", "MIN")));
		}
		return withETag(this.collections.update(collectionId,
				new CollectionChanges(name, slug, description, sortOrder, status), expected, client()));
	}

	@Override
	@RequiresPermission(Permission.CATALOG_MANAGE)
	public ResponseEntity<CollectionDto> replaceCollectionProducts(UUID collectionId, CollectionProductsRequestDto body,
			String ifMatch, String xCsrfToken) {
		var expected = VersionTags.require(ifMatch);
		List<UUID> ids = body.getProductIds();
		if (new HashSet<>(ids).size() != ids.size()) {
			throw new InvalidFieldsException(List.of(new InvalidFieldsException.Violation("productIds", "DUPLICATE")));
		}
		return withETag(this.collections.replaceProducts(collectionId, ids, expected, client()));
	}

	@Override
	@RequiresPermission(Permission.CATALOG_MANAGE)
	public ResponseEntity<CollectionDto> archiveCollection(UUID collectionId, String ifMatch, String xCsrfToken) {
		return withETag(this.collections.archive(collectionId, VersionTags.optional(ifMatch), client()));
	}

	// --- Tags (AL2) --------------------------------------------------------------------------------------------

	@Override
	@RequiresPermission(Permission.CATALOG_VIEW)
	public ResponseEntity<TagListDto> listTags() {
		return ResponseEntity
			.ok(new TagListDto(this.tags.list().stream().map(BackofficeCollectionController::toDto).toList()));
	}

	@Override
	@RequiresPermission(Permission.CATALOG_MANAGE)
	public ResponseEntity<TagDto> createTag(TagCreateRequestDto body, String xCsrfToken) {
		TagView tag = this.tags.create(body.getSlug(), body.getName(), client());
		return ResponseEntity.created(URI.create(TAGS + tag.tagId())).body(toDto(tag));
	}

	@Override
	@RequiresPermission(Permission.CATALOG_MANAGE)
	public ResponseEntity<TagDto> updateTag(UUID tagId, JsonNode body, String xCsrfToken) {
		MergePatch patch = MergePatch.of(body);
		Change<String> name = patch.requiredText("name", 64);
		Change<String> slug = patch.requiredText("slug", 64, SLUG);
		patch.validate();
		return ResponseEntity.ok(toDto(this.tags.update(tagId, name, slug, client())));
	}

	private CatalogClient client() {
		ClientInfo client = ClientInfo.from(this.request);
		return new CatalogClient(ActorContext.staff(AuthenticatedSession.require().userId(), null),
				client.ipAddress(), client.userAgent());
	}

	private static ResponseEntity<CollectionDto> withETag(CollectionView collection) {
		return ResponseEntity.ok().eTag(VersionTags.of(collection.version())).body(toDto(collection));
	}

	private static CollectionDto toDto(CollectionView collection) {
		return new CollectionDto(collection.collectionId(), collection.slug(), collection.name(),
				collection.description(), CollectionStatusDto.valueOf(collection.status()), collection.sortOrder(),
				collection.productIds(), collection.version(), utc(collection.createdAt()),
				utc(collection.updatedAt()));
	}

	private static TagDto toDto(TagView tag) {
		return new TagDto(tag.tagId(), tag.slug(), tag.name(), utc(tag.createdAt()));
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
