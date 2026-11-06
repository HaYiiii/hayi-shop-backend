package vn.hayi.shop.catalog.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import vn.hayi.shop.shared.id.UuidV7;

/**
 * One choice of an option group (Sữa tươi, Trân châu…) with its library price (Catalog §5.4). Part of the
 * {@link OptionGroup} aggregate: changed only through it (Decision AB1).
 */
@Entity
@Table(name = "cat_option_item")
public class OptionItem {

	@Id
	private UUID id;

	@Column(updatable = false)
	private String code;

	private String name;

	private long defaultExtraPrice;

	private boolean isDefaultSelected;

	@Enumerated(EnumType.STRING)
	private OptionStatus status;

	private int sortOrder;

	@Column(updatable = false)
	private Instant createdAt;

	private Instant updatedAt;

	protected OptionItem() {
	}

	static OptionItem create(NewOptionItem item, int sortOrder, Instant now) {
		OptionItem created = new OptionItem();
		created.id = UuidV7.generate();
		created.code = Product.normalizeCode(item.code());
		created.name = item.name();
		created.defaultExtraPrice = item.defaultExtraPrice();
		created.isDefaultSelected = item.isDefaultSelected();
		created.status = OptionStatus.ACTIVE;
		created.sortOrder = sortOrder;
		created.createdAt = now;
		created.updatedAt = now;
		return created;
	}

	void edit(String name, long defaultExtraPrice, boolean isDefaultSelected, FieldDiff diff, Instant now) {
		this.name = diff.track("name", this.name, name);
		this.defaultExtraPrice = diff.track("defaultExtraPrice", this.defaultExtraPrice, defaultExtraPrice);
		this.isDefaultSelected = diff.track("isDefaultSelected", this.isDefaultSelected, isDefaultSelected);
		if (!diff.isEmpty()) {
			this.updatedAt = now;
		}
	}

	void archive(Instant now) {
		this.status = OptionStatus.ARCHIVED;
		this.updatedAt = now;
	}

	boolean isActive() {
		return this.status == OptionStatus.ACTIVE;
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

	public long getDefaultExtraPrice() {
		return this.defaultExtraPrice;
	}

	public boolean isDefaultSelected() {
		return this.isDefaultSelected;
	}

	public OptionStatus getStatus() {
		return this.status;
	}

	public int getSortOrder() {
		return this.sortOrder;
	}

}
