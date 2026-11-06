package vn.hayi.shop.catalog.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.OptimisticLock;

import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.error.RuleViolationsException;
import vn.hayi.shop.shared.id.UuidV7;

/**
 * A reusable brand-level option group (Size, Đường, Topping…) and its items (Catalog §5.4). The aggregate of its
 * items: every item change moves the group's version (Decision AB1). Rules (AB3): {@code min ≥ 0}, {@code max ≥ 1},
 * {@code max ≥ min}, {@code SINGLE} means {@code max = 1}, and no more {@code ACTIVE} default items than {@code max}.
 */
@Entity
@Table(name = "cat_option_group")
public class OptionGroup {

	@Id
	private UUID id;

	@Column(updatable = false)
	private String code;

	private String name;

	@Enumerated(EnumType.STRING)
	private SelectionType selectionType;

	private int minSelect;

	private int maxSelect;

	@Enumerated(EnumType.STRING)
	private OptionStatus status;

	@Column(updatable = false)
	private Instant createdAt;

	private Instant updatedAt;

	@Version
	private long version;

	/**
	 * Kept out of Hibernate's automatic version bump (as for Product Variants); every item change touches
	 * {@code updatedAt}, which moves the version.
	 */
	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
	@OptimisticLock(excluded = true)
	@JoinColumn(name = "option_group_id", nullable = false, updatable = false)
	@OrderBy("sortOrder")
	@BatchSize(size = 100)
	private List<OptionItem> items = new ArrayList<>();

	protected OptionGroup() {
	}

	/**
	 * @throws DomainException {@code OPTION_GROUP_MINMAX_INVALID}, or {@code CATALOG_CODE_TAKEN} for an item code
	 * twice
	 * @throws RuleViolationsException {@code CATALOG_PRICE_NEGATIVE} on {@code items[i].defaultExtraPrice}
	 */
	public static OptionGroup create(String code, String name, SelectionType selectionType, int minSelect,
			int maxSelect, List<NewOptionItem> items, Instant now) {
		List<RuleViolationsException.Violation> negative = new ArrayList<>();
		for (int i = 0; i < items.size(); i++) {
			if (items.get(i).defaultExtraPrice() < 0) {
				negative.add(new RuleViolationsException.Violation("items[" + i + "].defaultExtraPrice", "MIN"));
			}
		}
		if (!negative.isEmpty()) {
			throw new RuleViolationsException(CatalogErrorCode.CATALOG_PRICE_NEGATIVE, negative);
		}
		OptionGroup group = new OptionGroup();
		group.id = UuidV7.generate();
		group.code = Product.normalizeCode(code);
		group.name = name;
		group.selectionType = selectionType;
		group.minSelect = minSelect;
		group.maxSelect = maxSelect;
		group.status = OptionStatus.ACTIVE;
		group.createdAt = now;
		group.updatedAt = now;
		Set<String> codes = new HashSet<>();
		for (NewOptionItem item : items) {
			if (!codes.add(Product.normalizeCode(item.code()))) {
				throw codeTaken(item.code());
			}
			group.items.add(OptionItem.create(item, group.items.size(), now));
		}
		requireValidSelection(selectionType, minSelect, maxSelect, group.activeDefaults(null, false));
		return group;
	}

	/** Changes name, selection type and min/max; returns what changed (nothing → version stays). */
	public FieldDiff edit(String newName, SelectionType newType, int newMin, int newMax, Instant now) {
		requireActive();
		requireValidSelection(newType, newMin, newMax, activeDefaults(null, false));
		FieldDiff diff = new FieldDiff();
		this.name = diff.track("name", this.name, newName);
		this.selectionType = diff.track("selectionType", this.selectionType, newType);
		this.minSelect = diff.track("minSelect", this.minSelect, newMin);
		this.maxSelect = diff.track("maxSelect", this.maxSelect, newMax);
		touchIf(diff, now);
		return diff;
	}

	/** Adds an item last in the order (Decision AB2). */
	public OptionItem addItem(NewOptionItem item, Instant now) {
		requireActive();
		requireNotNegative(item.defaultExtraPrice());
		String normalized = Product.normalizeCode(item.code());
		if (this.items.stream().anyMatch(existing -> existing.getCode().equals(normalized))) {
			throw codeTaken(normalized);
		}
		requireValidSelection(this.selectionType, this.minSelect, this.maxSelect,
				activeDefaults(null, false) + (item.isDefaultSelected() ? 1 : 0));
		int position = this.items.stream().mapToInt(OptionItem::getSortOrder).max().orElse(-1) + 1;
		OptionItem added = OptionItem.create(item, position, now);
		this.items.add(added);
		this.updatedAt = now;
		return added;
	}

	/** Changes an item; returns what changed. The price change is the caller's to authorize (AB4). */
	public FieldDiff editItem(UUID itemId, String newName, long newPrice, boolean newDefault, Instant now) {
		requireActive();
		OptionItem item = item(itemId);
		requireItemActive(item);
		requireNotNegative(newPrice);
		requireValidSelection(this.selectionType, this.minSelect, this.maxSelect, activeDefaults(item, newDefault));
		FieldDiff diff = new FieldDiff();
		item.edit(newName, newPrice, newDefault, diff, now);
		touchIf(diff, now);
		return diff;
	}

	/** {@code ACTIVE → ARCHIVED} for good (Decision AB3); Products using it are flagged at activation (W4). */
	public void archive(Instant now) {
		requireActive();
		this.status = OptionStatus.ARCHIVED;
		this.updatedAt = now;
	}

	/** Archives one item for good; it no longer counts as a default (AB3). */
	public OptionItem archiveItem(UUID itemId, Instant now) {
		requireActive();
		OptionItem item = item(itemId);
		requireItemActive(item);
		item.archive(now);
		this.updatedAt = now;
		return item;
	}

	public Optional<OptionItem> findItem(UUID itemId) {
		return this.items.stream().filter(item -> item.getId().equals(itemId)).findFirst();
	}

	private OptionItem item(UUID itemId) {
		return findItem(itemId).orElseThrow(() -> new DomainException(CatalogErrorCode.CATALOG_OPTION_ITEM_NOT_FOUND,
				"No item " + itemId + " in option group " + this.id));
	}

	/** Active default items, counting {@code edited} as {@code editedDefault} when it is given. */
	private long activeDefaults(OptionItem edited, boolean editedDefault) {
		return this.items.stream()
			.filter(OptionItem::isActive)
			.filter(item -> (item == edited) ? editedDefault : item.isDefaultSelected())
			.count();
	}

	/**
	 * The limits of AB3 without the default-item rule, for a Product's effective min/max (Decision AC2).
	 * @throws DomainException {@code OPTION_GROUP_MINMAX_INVALID}
	 */
	public static void requireValidLimits(SelectionType type, int min, int max) {
		requireValidSelection(type, min, max, 0);
	}

	/** {@code ACTIVE} items not in {@code hidden}, for W4 (Decision AC4). */
	public long visibleActiveItems(Set<UUID> hidden) {
		return this.items.stream().filter(OptionItem::isActive).filter(item -> !hidden.contains(item.getId())).count();
	}

	/** Checked before anything changes, so a refused change leaves the group as it was (AB3). */
	private static void requireValidSelection(SelectionType type, int min, int max, long defaults) {
		boolean valid = min >= 0 && max >= 1 && max >= min && (type != SelectionType.SINGLE || max == 1)
				&& defaults <= max;
		if (!valid) {
			throw new DomainException(CatalogErrorCode.OPTION_GROUP_MINMAX_INVALID,
					type + " min " + min + " max " + max + " with " + defaults + " default item(s)");
		}
	}

	private void requireActive() {
		if (this.status != OptionStatus.ACTIVE) {
			throw archived("Option group " + this.id);
		}
	}

	private static void requireItemActive(OptionItem item) {
		if (!item.isActive()) {
			throw archived("Option item " + item.getId());
		}
	}

	private static void requireNotNegative(long price) {
		if (price < 0) {
			throw new RuleViolationsException(CatalogErrorCode.CATALOG_PRICE_NEGATIVE,
					List.of(new RuleViolationsException.Violation("defaultExtraPrice", "MIN")));
		}
	}

	private void touchIf(FieldDiff diff, Instant now) {
		if (!diff.isEmpty()) {
			this.updatedAt = now;
		}
	}

	private static DomainException archived(String what) {
		return new DomainException(CatalogErrorCode.CATALOG_OPTION_ARCHIVED, what + " is archived");
	}

	private static DomainException codeTaken(String code) {
		return new DomainException(CatalogErrorCode.CATALOG_CODE_TAKEN, "Item code twice: " + code, code);
	}

	public UUID getId() {
		return this.id;
	}

	public String getCode() {
		return this.code;
	}

	public String getName() {
		return this.name;
	}

	public SelectionType getSelectionType() {
		return this.selectionType;
	}

	public int getMinSelect() {
		return this.minSelect;
	}

	public int getMaxSelect() {
		return this.maxSelect;
	}

	public OptionStatus getStatus() {
		return this.status;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

	public long getVersion() {
		return this.version;
	}

	public List<OptionItem> getItems() {
		return List.copyOf(this.items);
	}

}
