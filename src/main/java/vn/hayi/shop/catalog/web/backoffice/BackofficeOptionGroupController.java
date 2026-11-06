package vn.hayi.shop.catalog.web.backoffice;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import vn.hayi.shop.catalog.application.CatalogClient;
import vn.hayi.shop.catalog.application.OptionGroupService;
import vn.hayi.shop.catalog.application.OptionGroupView;
import vn.hayi.shop.platform.security.AccessDecision;
import vn.hayi.shop.platform.security.AuthenticatedSession;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.platform.security.Permission;
import vn.hayi.shop.platform.security.PermissionEvaluator;
import vn.hayi.shop.platform.security.RequiresPermission;
import vn.hayi.shop.platform.web.MergePatch;
import vn.hayi.shop.platform.web.VersionTags;
import vn.hayi.shop.platform.web.spec.backoffice.api.OptionGroupsApi;
import vn.hayi.shop.platform.web.spec.backoffice.model.OptionGroupCreateRequestDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.OptionGroupDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.OptionGroupListDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.OptionItemDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.OptionItemInputDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.OptionSelectionTypeDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.OptionStatusDto;
import vn.hayi.shop.shared.actor.ActorContext;
import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.patch.Change;

/**
 * Backoffice option library endpoints (API 08 §1.5, Decision AB). Brand-level, so a permission at any scope is
 * enough (M1). Prices need {@code CATALOG_PRICE_MANAGE} on top only when they are set or changed (AB4), so that check
 * happens here rather than in {@link RequiresPermission}.
 */
@RestController
class BackofficeOptionGroupController implements OptionGroupsApi {

	private static final String GROUPS = "/api/backoffice/v1/option-groups/";

	private static final Set<String> SELECTION_TYPES = Set.of("SINGLE", "MULTIPLE");

	private final OptionGroupService groups;

	private final PermissionEvaluator permissions;

	private final HttpServletRequest request;

	BackofficeOptionGroupController(OptionGroupService groups, PermissionEvaluator permissions,
			HttpServletRequest request) {
		this.groups = groups;
		this.permissions = permissions;
		this.request = request;
	}

	@Override
	@RequiresPermission(Permission.CATALOG_VIEW)
	public ResponseEntity<OptionGroupListDto> listOptionGroups(Boolean includeArchived) {
		return ResponseEntity.ok(new OptionGroupListDto(this.groups.list(Boolean.TRUE.equals(includeArchived))
			.stream()
			.map(BackofficeOptionGroupController::toDto)
			.toList()));
	}

	@Override
	@RequiresPermission(Permission.CATALOG_VIEW)
	public ResponseEntity<OptionGroupDto> getOptionGroup(UUID optionGroupId) {
		return withETag(this.groups.get(optionGroupId));
	}

	@Override
	@RequiresPermission(Permission.CATALOG_MANAGE)
	public ResponseEntity<OptionGroupDto> createOptionGroup(OptionGroupCreateRequestDto body, String xCsrfToken) {
		List<OptionGroupService.NewItem> items = (body.getItems() != null)
				? body.getItems().stream().map(BackofficeOptionGroupController::toNewItem).toList() : List.of();
		if (items.stream().anyMatch(item -> item.defaultExtraPrice() != 0)) {
			requirePricePermission();
		}
		OptionGroupView group = this.groups.create(body.getCode(), body.getName(), body.getSelectionType().getValue(),
				body.getMinSelect(), body.getMaxSelect(), items, client());
		return ResponseEntity.created(URI.create(GROUPS + group.optionGroupId()))
			.eTag(VersionTags.of(group.version()))
			.body(toDto(group));
	}

	@Override
	@RequiresPermission(Permission.CATALOG_MANAGE)
	public ResponseEntity<OptionGroupDto> updateOptionGroup(UUID optionGroupId, JsonNode body, String ifMatch,
			String xCsrfToken) {
		var expected = VersionTags.require(ifMatch);
		MergePatch patch = MergePatch.of(body);
		Change<String> name = patch.requiredText("name", 128);
		Change<String> selectionType = patch.requiredEnum("selectionType", SELECTION_TYPES);
		Change<Integer> minSelect = patch.requiredInt("minSelect");
		Change<Integer> maxSelect = patch.requiredInt("maxSelect");
		patch.validate();
		return withETag(this.groups.update(optionGroupId, name, selectionType, minSelect, maxSelect, expected,
				client()));
	}

	@Override
	@RequiresPermission(Permission.CATALOG_MANAGE)
	public ResponseEntity<OptionGroupDto> archiveOptionGroup(UUID optionGroupId, String ifMatch, String xCsrfToken) {
		return withETag(this.groups.archive(optionGroupId, VersionTags.optional(ifMatch), client()));
	}

	@Override
	@RequiresPermission(Permission.CATALOG_MANAGE)
	public ResponseEntity<OptionGroupDto> addOptionItem(UUID optionGroupId, OptionItemInputDto body, String ifMatch,
			String xCsrfToken) {
		OptionGroupService.NewItem item = toNewItem(body);
		if (item.defaultExtraPrice() != 0) {
			requirePricePermission();
		}
		OptionGroupView group = this.groups.addItem(optionGroupId, item, VersionTags.optional(ifMatch), client());
		return ResponseEntity.status(201).eTag(VersionTags.of(group.version())).body(toDto(group));
	}

	@Override
	@RequiresPermission(Permission.CATALOG_MANAGE)
	public ResponseEntity<OptionGroupDto> updateOptionItem(UUID optionGroupId, UUID optionItemId, JsonNode body,
			String ifMatch, String xCsrfToken) {
		var expected = VersionTags.require(ifMatch);
		MergePatch patch = MergePatch.of(body);
		Change<String> name = patch.requiredText("name", 128);
		Change<Long> price = patch.requiredLong("defaultExtraPrice");
		Change<Boolean> isDefault = patch.requiredBoolean("isDefaultSelected");
		patch.validate();
		return withETag(this.groups.updateItem(optionGroupId, optionItemId, name, price, isDefault,
				hasPricePermission(), expected, client()));
	}

	@Override
	@RequiresPermission(Permission.CATALOG_MANAGE)
	public ResponseEntity<OptionGroupDto> archiveOptionItem(UUID optionGroupId, UUID optionItemId, String ifMatch,
			String xCsrfToken) {
		return withETag(
				this.groups.archiveItem(optionGroupId, optionItemId, VersionTags.optional(ifMatch), client()));
	}

	private boolean hasPricePermission() {
		return this.permissions.evaluate(AuthenticatedSession.require().userId(), Permission.CATALOG_PRICE_MANAGE,
				Optional.empty()) == AccessDecision.GRANTED;
	}

	private void requirePricePermission() {
		if (!hasPricePermission()) {
			throw new DomainException(CommonErrorCode.PERMISSION_DENIED, "Missing permission CATALOG_PRICE_MANAGE");
		}
	}

	private static OptionGroupService.NewItem toNewItem(OptionItemInputDto input) {
		return new OptionGroupService.NewItem(input.getCode(), input.getName(),
				(input.getDefaultExtraPrice() != null) ? input.getDefaultExtraPrice() : 0,
				Boolean.TRUE.equals(input.getIsDefaultSelected()));
	}

	private CatalogClient client() {
		ClientInfo client = ClientInfo.from(this.request);
		return new CatalogClient(ActorContext.staff(AuthenticatedSession.require().userId(), null),
				client.ipAddress(), client.userAgent());
	}

	private static ResponseEntity<OptionGroupDto> withETag(OptionGroupView group) {
		return ResponseEntity.ok().eTag(VersionTags.of(group.version())).body(toDto(group));
	}

	private static OptionGroupDto toDto(OptionGroupView group) {
		return new OptionGroupDto(group.optionGroupId(), group.code(), group.name(),
				OptionSelectionTypeDto.valueOf(group.selectionType()), group.minSelect(), group.maxSelect(),
				OptionStatusDto.valueOf(group.status()), utc(group.createdAt()), utc(group.updatedAt()),
				group.version(),
				group.items().stream()
					.map(item -> new OptionItemDto(item.optionItemId(), item.code(), item.name(),
							item.defaultExtraPrice(), item.isDefaultSelected(), OptionStatusDto.valueOf(item.status()),
							item.sortOrder()))
					.toList());
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
