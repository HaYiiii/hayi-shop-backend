package vn.hayi.shop.fulfillment.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntFunction;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

import org.springframework.data.domain.Persistable;

import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.id.UuidV7;
import vn.hayi.shop.shared.scope.BranchOwned;

/**
 * How one Branch takes one fulfillment type (Fulfillment §8.1–8.4, AGGREGATE-MAP {@code BranchFulfillmentConfig}).
 * The Branch is a foreign id only (no cross-module relation). A saved config starts at version 1: version 0 is the
 * unsaved default a client creates it from (Decision R2).
 */
@Entity
@Table(name = "ful_branch_fulfillment_config")
public class BranchFulfillmentConfig implements Persistable<UUID>, BranchOwned {

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

	/** Part of the aggregate (AGGREGATE-MAP): changing it bumps {@code version} like any other edit (Decision S1). */
	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
	@JoinColumn(name = "config_id", nullable = false, updatable = false)
	private List<WeeklyScheduleEntry> weeklySchedule = new ArrayList<>();

	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
	@JoinColumn(name = "config_id", nullable = false, updatable = false)
	private List<ScheduleOverride> overrides = new ArrayList<>();

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

	/** The weekly windows, by day then start. */
	public List<WeeklyWindow> getWeeklySchedule() {
		return sorted(this.weeklySchedule.stream().map(WeeklyScheduleEntry::toWindow).toList());
	}

	/** Overrides of {@code from} and later, by date then start (a closed day first). */
	public List<ScheduleOverride> getOverridesFrom(LocalDate from) {
		return this.overrides.stream()
			.filter(override -> !override.getDate().isBefore(from))
			.sorted(Comparator.comparing(ScheduleOverride::getDate)
				.thenComparing(override -> override.getWindow(),
						Comparator.nullsFirst(Comparator.comparing(TimeWindow::start))))
			.toList();
	}

	/**
	 * Replaces the whole week after the checks of Decision S5–S6, {@code branchHours} giving the Branch's weekly open
	 * ranges of an ISO day. The same windows in any order change nothing: returns {@code false}, version unchanged.
	 */
	public boolean replaceWeeklySchedule(List<WeeklyWindow> week, IntFunction<List<TimeWindow>> branchHours,
			Instant now) {
		ScheduleRules.checkWeekly(week, branchHours);
		if (sorted(week).equals(getWeeklySchedule())) {
			return false;
		}
		this.weeklySchedule.clear();
		week.forEach(window -> this.weeklySchedule.add(new WeeklyScheduleEntry(window)));
		this.updatedAt = now;
		return true;
	}

	/**
	 * The pickup windows in force on {@code date} with their capacity (Decision N4): the date's overrides if any (none
	 * when closed), otherwise the weekly windows of that day; a capacity left {@code null} there falls back to the
	 * config default. Empty means no pickup that day.
	 */
	public List<CapacityWindow> windowsOn(LocalDate date) {
		List<ScheduleOverride> overrides = getOverridesOn(date);
		if (!overrides.isEmpty()) {
			return overrides.stream()
				.filter(override -> !override.isClosed())
				.map(override -> new CapacityWindow(override.getWindow(),
						orDefault(override.getMaxOrders(), this.defaultSlotMaxOrders),
						orDefault(override.getMaxItems(), this.defaultSlotMaxItems)))
				.toList();
		}
		return getWeeklySchedule().stream()
			.filter(window -> window.dayOfWeek() == date.getDayOfWeek().getValue())
			.map(window -> new CapacityWindow(window.window(), orDefault(window.maxOrders(), this.defaultSlotMaxOrders),
					orDefault(window.maxItems(), this.defaultSlotMaxItems)))
			.toList();
	}

	private static Integer orDefault(Integer value, Integer fallback) {
		return (value != null) ? value : fallback;
	}

	/** The overrides of {@code date}, by start (a closed day is one). */
	public List<ScheduleOverride> getOverridesOn(LocalDate date) {
		return getOverridesFrom(date).stream().filter(override -> override.getDate().equals(date)).toList();
	}

	/**
	 * Sets one date's overrides, replacing the date's previous ones (Decision S3); {@code branchHours} are the
	 * Branch's actual open ranges on that date. The same hours in any window order change nothing: returns
	 * {@code false}, version and override ids unchanged.
	 */
	public boolean putOverride(DateOverride override, LocalDate today, List<TimeWindow> branchHours, Instant now) {
		ScheduleRules.checkOverride(override, today, branchHours);
		List<ScheduleOverride> previous = getOverridesOn(override.date());
		if (!previous.isEmpty() && asDateOverride(previous).equals(normalized(override))) {
			return false;
		}
		this.overrides.removeAll(previous);
		this.overrides.addAll(ScheduleOverride.of(override, now));
		this.updatedAt = now;
		return true;
	}

	private static DateOverride asDateOverride(List<ScheduleOverride> rows) {
		ScheduleOverride first = rows.getFirst();
		return normalized(new DateOverride(first.getDate(), first.isClosed(),
				rows.stream().map(ScheduleOverride::getWindow).filter(Objects::nonNull).toList(), first.getMaxOrders(),
				first.getMaxItems(), first.getReason()));
	}

	private static DateOverride normalized(DateOverride override) {
		return new DateOverride(override.date(), override.closed(),
				override.windows().stream().sorted(Comparator.comparing(TimeWindow::start)).toList(),
				override.maxOrders(), override.maxItems(), override.reason());
	}

	/**
	 * Removes one override of this config and returns it. An id of another config, so of another Branch, is simply not
	 * here: the same answer as an unknown id (Decision S7).
	 * @throws DomainException {@code FULFILLMENT_OVERRIDE_NOT_FOUND}
	 */
	public ScheduleOverride removeOverride(UUID overrideId, Instant now) {
		ScheduleOverride removed = this.overrides.stream()
			.filter(override -> override.getId().equals(overrideId))
			.findFirst()
			.orElseThrow(() -> new DomainException(FulfillmentErrorCode.FULFILLMENT_OVERRIDE_NOT_FOUND,
					"No override " + overrideId + " in config " + this.id));
		this.overrides.remove(removed);
		this.updatedAt = now;
		return removed;
	}

	private static List<WeeklyWindow> sorted(List<WeeklyWindow> week) {
		return week.stream()
			.sorted(Comparator.comparingInt(WeeklyWindow::dayOfWeek)
				.thenComparing(window -> window.window().start())
				.thenComparing(window -> window.window().end()))
			.toList();
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

	@Override
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
