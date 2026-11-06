package vn.hayi.shop.catalog.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.audit.api.AuditEntry;
import vn.hayi.shop.audit.api.AuditRecorder;
import vn.hayi.shop.audit.api.AuditSource;
import vn.hayi.shop.catalog.domain.CatalogErrorCode;
import vn.hayi.shop.catalog.domain.FieldDiff;
import vn.hayi.shop.catalog.domain.NewOptionItem;
import vn.hayi.shop.catalog.domain.OptionGroup;
import vn.hayi.shop.catalog.domain.OptionGroupRepository;
import vn.hayi.shop.catalog.domain.OptionItem;
import vn.hayi.shop.catalog.domain.OptionStatus;
import vn.hayi.shop.catalog.domain.Product;
import vn.hayi.shop.catalog.domain.SelectionType;
import vn.hayi.shop.shared.concurrency.ExpectedVersion;
import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.patch.Change;

/**
 * The brand-level option library (Catalog §5.4, API 08 §1.5, Decision AB); every change is audited in the same
 * transaction (Decision W8, AB5).
 */
@Service
public class OptionGroupService {

	private static final List<String> UNIQUE_KEYS = List.of("uk_cat_option_group__code",
			"uk_cat_option_item__group_code");

	private static final Pattern DUPLICATE_ENTRY = Pattern.compile("Duplicate entry '([^']*)'");

	private final OptionGroupRepository groups;

	private final AuditRecorder audit;

	private final Clock clock;

	OptionGroupService(OptionGroupRepository groups, AuditRecorder audit, Clock clock) {
		this.groups = groups;
		this.audit = audit;
		this.clock = clock;
	}

	/** The library price of an item to create, for the caller's price-permission check (AB4). */
	public record NewItem(String code, String name, long defaultExtraPrice, boolean isDefaultSelected) {

		NewOptionItem toDomain() {
			return new NewOptionItem(this.code, this.name, this.defaultExtraPrice, this.isDefaultSelected);
		}

	}

	@Transactional
	public OptionGroupView create(String code, String name, String selectionType, int minSelect, int maxSelect,
			List<NewItem> items, CatalogClient client) {
		OptionGroup group = OptionGroup.create(code, name, SelectionType.valueOf(selectionType), minSelect, maxSelect,
				items.stream().map(NewItem::toDomain).toList(), this.clock.instant());
		if (this.groups.existsByCode(group.getCode())) {
			throw codeTaken(group.getCode());
		}
		save(group);
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("code", group.getCode());
		values.put("name", group.getName());
		values.put("selectionType", group.getSelectionType().name());
		values.put("minSelect", group.getMinSelect());
		values.put("maxSelect", group.getMaxSelect());
		values.put("items", group.getItems().stream()
			.map(item -> Map.of("code", item.getCode(), "defaultExtraPrice", item.getDefaultExtraPrice(),
					"isDefaultSelected", item.isDefaultSelected()))
			.toList());
		record(client, "OPTION_GROUP_CREATE", group.getId(), null, values);
		return OptionGroupView.of(group);
	}

	/** Merge Patch of the group itself, at {@code expected} version (AB2). */
	@Transactional
	public OptionGroupView update(UUID groupId, Change<String> name, Change<String> selectionType,
			Change<Integer> minSelect, Change<Integer> maxSelect, ExpectedVersion expected, CatalogClient client) {
		OptionGroup group = find(groupId, expected);
		FieldDiff diff = group.edit(name.applyTo(group.getName()),
				SelectionType.valueOf(selectionType.applyTo(group.getSelectionType().name())),
				minSelect.applyTo(group.getMinSelect()), maxSelect.applyTo(group.getMaxSelect()), this.clock.instant());
		return saved(group, diff, "OPTION_GROUP_UPDATE", null, client);
	}

	@Transactional
	public OptionGroupView addItem(UUID groupId, NewItem item, ExpectedVersion expected, CatalogClient client) {
		OptionGroup group = find(groupId, expected);
		OptionItem added = group.addItem(item.toDomain(), this.clock.instant());
		save(group);
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("optionItemId", added.getId());
		values.put("code", added.getCode());
		values.put("name", added.getName());
		values.put("defaultExtraPrice", added.getDefaultExtraPrice());
		values.put("isDefaultSelected", added.isDefaultSelected());
		record(client, "OPTION_ITEM_CREATE", groupId, null, values);
		return OptionGroupView.of(group);
	}

	/**
	 * Merge Patch of one item. A real change of its price needs {@code CATALOG_PRICE_MANAGE}, which the caller
	 * reports as {@code mayChangePrice} (AB4); sending the current price is not a change.
	 */
	@Transactional
	public OptionGroupView updateItem(UUID groupId, UUID itemId, Change<String> name, Change<Long> defaultExtraPrice,
			Change<Boolean> isDefaultSelected, boolean mayChangePrice, ExpectedVersion expected, CatalogClient client) {
		OptionGroup group = find(groupId, expected);
		OptionItem item = group.findItem(itemId)
			.orElseThrow(() -> new DomainException(CatalogErrorCode.CATALOG_OPTION_ITEM_NOT_FOUND,
					"No item " + itemId + " in option group " + groupId));
		long newPrice = defaultExtraPrice.applyTo(item.getDefaultExtraPrice());
		if (newPrice != item.getDefaultExtraPrice() && !mayChangePrice) {
			throw new DomainException(CommonErrorCode.PERMISSION_DENIED, "Missing permission CATALOG_PRICE_MANAGE");
		}
		FieldDiff diff = group.editItem(itemId, name.applyTo(item.getName()), newPrice,
				isDefaultSelected.applyTo(item.isDefaultSelected()), this.clock.instant());
		return saved(group, diff, "OPTION_ITEM_UPDATE", itemId, client);
	}

	@Transactional
	public OptionGroupView archive(UUID groupId, ExpectedVersion expected, CatalogClient client) {
		OptionGroup group = find(groupId, expected);
		group.archive(this.clock.instant());
		save(group);
		record(client, "OPTION_GROUP_ARCHIVE", groupId, Map.of("status", OptionStatus.ACTIVE.name()),
				Map.of("status", group.getStatus().name()));
		return OptionGroupView.of(group);
	}

	@Transactional
	public OptionGroupView archiveItem(UUID groupId, UUID itemId, ExpectedVersion expected, CatalogClient client) {
		OptionGroup group = find(groupId, expected);
		OptionItem item = group.archiveItem(itemId, this.clock.instant());
		save(group);
		record(client, "OPTION_ITEM_ARCHIVE", groupId,
				Map.of("optionItemId", itemId, "status", OptionStatus.ACTIVE.name()),
				Map.of("optionItemId", itemId, "status", item.getStatus().name()));
		return OptionGroupView.of(group);
	}

	@Transactional(readOnly = true)
	public OptionGroupView get(UUID groupId) {
		return OptionGroupView.of(find(groupId, null));
	}

	@Transactional(readOnly = true)
	public List<OptionGroupView> list(boolean includeArchived) {
		List<OptionGroup> found = includeArchived ? this.groups.findAllByOrderByCodeAsc()
				: this.groups.findByStatusOrderByCodeAsc(OptionStatus.ACTIVE);
		return found.stream().map(OptionGroupView::of).toList();
	}

	/** Codes as stored: trimmed and upper-cased (Decision Z2, AB1). */
	public static String normalizeCode(String code) {
		return Product.normalizeCode(code);
	}

	private OptionGroupView saved(OptionGroup group, FieldDiff diff, String action, UUID itemId,
			CatalogClient client) {
		if (diff.isEmpty()) {
			return OptionGroupView.of(group);
		}
		save(group);
		Map<String, Object> oldValue = new LinkedHashMap<>();
		Map<String, Object> newValue = new LinkedHashMap<>();
		if (itemId != null) {
			oldValue.put("optionItemId", itemId);
			newValue.put("optionItemId", itemId);
		}
		oldValue.putAll(diff.oldValues());
		newValue.putAll(diff.newValues());
		record(client, action, group.getId(), oldValue, newValue);
		return OptionGroupView.of(group);
	}

	private OptionGroup find(UUID groupId, ExpectedVersion expected) {
		OptionGroup group = this.groups.findById(groupId)
			.orElseThrow(() -> new DomainException(CatalogErrorCode.CATALOG_OPTION_GROUP_NOT_FOUND,
					"No option group " + groupId));
		if (expected != null) {
			expected.verify(group.getVersion());
		}
		return group;
	}

	private void save(OptionGroup group) {
		try {
			this.groups.saveAndFlush(group);
		}
		catch (DataIntegrityViolationException ex) {
			// A concurrent create took the code after the check; any other violation is a bug.
			String message = NestedExceptionUtils.getMostSpecificCause(ex).getMessage();
			if (message != null && UNIQUE_KEYS.stream().anyMatch(message::contains)) {
				Matcher matcher = DUPLICATE_ENTRY.matcher(message);
				throw codeTaken(matcher.find() ? matcher.group(1) : group.getCode());
			}
			throw ex;
		}
	}

	private void record(CatalogClient client, String action, UUID groupId, Map<String, ?> oldValue,
			Map<String, ?> newValue) {
		this.audit.record(AuditEntry.of(client.actor(), AuditSource.BACKOFFICE_WEB, action, "OPTION_GROUP", groupId)
			.withOldValue(oldValue)
			.withNewValue(newValue)
			.withClient(client.ipAddress(), client.userAgent()));
	}

	private static DomainException codeTaken(String code) {
		return new DomainException(CatalogErrorCode.CATALOG_CODE_TAKEN, "Code taken: " + code, code);
	}

}
