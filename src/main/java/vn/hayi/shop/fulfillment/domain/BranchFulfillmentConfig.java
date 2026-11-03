package vn.hayi.shop.fulfillment.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

import org.springframework.data.domain.Persistable;

import vn.hayi.shop.shared.id.UuidV7;

/**
 * How one Branch takes one fulfillment type (Fulfillment §8.1–8.4, AGGREGATE-MAP {@code BranchFulfillmentConfig}).
 * The Branch is a foreign id only (no cross-module relation). A saved config starts at version 1: version 0 is the
 * unsaved default a client creates it from (Decision R2).
 */
@Entity
@Table(name = "ful_branch_fulfillment_config")
public class BranchFulfillmentConfig implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(updatable = false)
	private UUID branchId;

	@Enumerated(EnumType.STRING)
	@Column(updatable = false)
	private FulfillmentType fulfillmentType;

	@Column(name = "is_enabled")
	private boolean enabled;

	private boolean asapEnabled;

	private int maxAdvanceDays;

	private int slotDurationMinutes;

	private int sameDayCutoffMinutes;

	private int minLeadMinutes;

	private Integer groupOrderMinLeadMinutes;

	private Integer customerCancelCutoffMinutes;

	private Integer defaultSlotMaxOrders;

	private Integer defaultSlotMaxItems;

	private String pickupInstructions;

	@Column(updatable = false)
	private Instant createdAt;

	private Instant updatedAt;

	@Version
	private long version;

	@Transient
	private boolean isNew;

	protected BranchFulfillmentConfig() {
	}

	public static BranchFulfillmentConfig create(UUID branchId, FulfillmentType type, FulfillmentSettings settings,
			Instant now) {
		BranchFulfillmentConfig config = new BranchFulfillmentConfig();
		config.id = UuidV7.generate();
		config.branchId = branchId;
		config.fulfillmentType = type;
		config.apply(settings);
		config.createdAt = now;
		config.updatedAt = now;
		config.version = 1;
		config.isNew = true;
		return config;
	}

	/** Replaces the settings; returns {@code false} when nothing changes, so the version does not move. */
	public boolean replace(FulfillmentSettings settings, Instant now) {
		if (settings.equals(settings())) {
			return false;
		}
		apply(settings);
		this.updatedAt = now;
		return true;
	}

	public FulfillmentSettings settings() {
		return new FulfillmentSettings(this.enabled, this.asapEnabled, this.maxAdvanceDays, this.slotDurationMinutes,
				this.sameDayCutoffMinutes, this.minLeadMinutes, this.groupOrderMinLeadMinutes,
				this.customerCancelCutoffMinutes, this.defaultSlotMaxOrders, this.defaultSlotMaxItems,
				this.pickupInstructions);
	}

	private void apply(FulfillmentSettings settings) {
		this.enabled = settings.enabled();
		this.asapEnabled = settings.asapEnabled();
		this.maxAdvanceDays = settings.maxAdvanceDays();
		this.slotDurationMinutes = settings.slotDurationMinutes();
		this.sameDayCutoffMinutes = settings.sameDayCutoffMinutes();
		this.minLeadMinutes = settings.minLeadMinutes();
		this.groupOrderMinLeadMinutes = settings.groupOrderMinLeadMinutes();
		this.customerCancelCutoffMinutes = settings.customerCancelCutoffMinutes();
		this.defaultSlotMaxOrders = settings.defaultSlotMaxOrders();
		this.defaultSlotMaxItems = settings.defaultSlotMaxItems();
		this.pickupInstructions = settings.pickupInstructions();
	}

	@Override
	public UUID getId() {
		return this.id;
	}

	/** Lets Spring Data {@code persist} a new config with its preset version instead of merging it. */
	@Override
	public boolean isNew() {
		return this.isNew;
	}

	@PostPersist
	@PostLoad
	void markPersisted() {
		this.isNew = false;
	}

	public UUID getBranchId() {
		return this.branchId;
	}

	public FulfillmentType getFulfillmentType() {
		return this.fulfillmentType;
	}

	public long getVersion() {
		return this.version;
	}

}
