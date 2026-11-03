package vn.hayi.shop.branch.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.id.UuidV7;
import vn.hayi.shop.shared.time.BranchTime;

/**
 * A physical store (Branch §4.1–4.3): identity, location, contact and lifecycle. Starts {@code DRAFT}; the
 * {@code branchCode} is unique, upper-case and never reused.
 */
@Entity
@Table(name = "brn_branch")
public class Branch {

	@Id
	private UUID id;

	@Column(updatable = false)
	private String branchCode;

	private String name;

	@Enumerated(EnumType.STRING)
	private BranchStatus status;

	private String phone;

	private String email;

	private String addressLine;

	private String wardName;

	private String districtName;

	private String provinceName;

	private BigDecimal latitude;

	private BigDecimal longitude;

	private String timezone;

	private Instant activatedAt;

	private Instant closedAt;

	@Column(updatable = false)
	private Instant createdAt;

	private Instant updatedAt;

	@Version
	private long version;

	/** Part of the aggregate (AGGREGATE-MAP): changing it bumps {@code version} like any other edit (Decision Q1). */
	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
	@JoinColumn(name = "branch_id", nullable = false, updatable = false)
	private List<BranchBusinessHour> weeklyHours = new ArrayList<>();

	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
	@JoinColumn(name = "branch_id", nullable = false, updatable = false)
	private List<BranchHourOverride> hourOverrides = new ArrayList<>();

	protected Branch() {
	}

	public static Branch create(String branchCode, String name, BranchAddress address, GeoPoint location,
			String phone, String email, Instant now) {
		Branch branch = new Branch();
		branch.id = UuidV7.generate();
		branch.branchCode = normalizeCode(branchCode);
		branch.name = name;
		branch.status = BranchStatus.DRAFT;
		branch.phone = phone;
		branch.email = email;
		branch.addressLine = address.line();
		branch.wardName = address.ward();
		branch.districtName = address.district();
		branch.provinceName = address.province();
		branch.latitude = (location != null) ? location.lat() : null;
		branch.longitude = (location != null) ? location.lng() : null;
		branch.timezone = BranchTime.DEFAULT_ZONE.getId();
		branch.createdAt = now;
		branch.updatedAt = now;
		return branch;
	}

	/**
	 * Applies {@code edit} to the Branch's information (API 09 §1.2) and returns what changed; an edit that changes
	 * nothing leaves the Branch untouched, so its version does not move (Decision O3).
	 * @throws DomainException {@code BRANCH_INVALID_TRANSITION} once the Branch is {@code CLOSED} (Decision O2)
	 */
	public BranchDiff edit(BranchEdit edit, Instant now) {
		requireNotClosed();
		String newName = requireText(edit.name().applyTo(this.name), "name");
		String newLine = requireText(edit.addressLine().applyTo(this.addressLine), "address.line");
		String newProvince = requireText(edit.province().applyTo(this.provinceName), "address.province");
		BranchDiff diff = new BranchDiff();
		this.name = changed(diff, "name", this.name, newName);
		this.addressLine = changed(diff, "address.line", this.addressLine, newLine);
		this.wardName = changed(diff, "address.ward", this.wardName, edit.ward().applyTo(this.wardName));
		this.districtName = changed(diff, "address.district", this.districtName,
				edit.district().applyTo(this.districtName));
		this.provinceName = changed(diff, "address.province", this.provinceName, newProvince);
		GeoPoint location = getLocation();
		GeoPoint newLocation = edit.location().applyTo(location);
		if (!sameLocation(location, newLocation)) {
			diff.put("location", location, newLocation);
			this.latitude = (newLocation != null) ? newLocation.lat() : null;
			this.longitude = (newLocation != null) ? newLocation.lng() : null;
		}
		this.phone = changed(diff, "phone", this.phone, edit.phone().applyTo(this.phone));
		this.email = changed(diff, "email", this.email, edit.email().applyTo(this.email));
		if (!diff.isEmpty()) {
			this.updatedAt = now;
		}
		return diff;
	}

	/** The weekly schedule, by day then opening time; a day without ranges is closed. */
	public List<WeeklyHours> getWeeklyHours() {
		return sorted(this.weeklyHours.stream().map(BranchBusinessHour::toWeeklyHours).toList());
	}

	/** Overrides of {@code from} and later, by date. */
	public List<DayOverride> getOverridesFrom(LocalDate from) {
		Map<LocalDate, List<BranchHourOverride>> byDate = new TreeMap<>();
		this.hourOverrides.stream()
			.filter(row -> !row.date().isBefore(from))
			.forEach(row -> byDate.computeIfAbsent(row.date(), date -> new ArrayList<>()).add(row));
		return byDate.values().stream().map(Branch::toDayOverride).toList();
	}

	/**
	 * The actual opening ranges on {@code date} (Branch §4.4, Decision Q5): its override if any, otherwise the weekly
	 * schedule of that day of week; empty means closed.
	 */
	public List<TimeRange> openingHoursOn(LocalDate date) {
		return overrideOn(date).map(DayOverride::ranges)
			.orElseGet(() -> getWeeklyHours().stream()
				.filter(hours -> hours.dayOfWeek() == date.getDayOfWeek().getValue())
				.map(WeeklyHours::range)
				.toList());
	}

	/**
	 * Replaces the whole week (Decision Q1–Q2). The same ranges in any order are the same week: then nothing changes,
	 * so the version does not move, and this returns {@code false}.
	 */
	public boolean replaceWeeklyHours(List<WeeklyHours> week, Instant now) {
		requireNotClosed();
		BusinessHoursRules.checkWeekly(week);
		if (sorted(week).equals(getWeeklyHours())) {
			return false;
		}
		this.weeklyHours.clear();
		week.forEach(hours -> this.weeklyHours.add(new BranchBusinessHour(hours)));
		this.updatedAt = now;
		return true;
	}

	private static List<WeeklyHours> sorted(List<WeeklyHours> week) {
		return week.stream()
			.sorted(Comparator.comparingInt(WeeklyHours::dayOfWeek).thenComparing(hours -> hours.range().open()))
			.toList();
	}

	/** Sets the hours of one date, replacing its override if any, which is returned (Decision Q3). */
	public Optional<DayOverride> putOverride(DayOverride override, LocalDate today, Instant now) {
		requireNotClosed();
		BusinessHoursRules.checkOverride(override, today);
		Optional<DayOverride> previous = overrideOn(override.date());
		this.hourOverrides.removeIf(row -> row.date().equals(override.date()));
		this.hourOverrides.addAll(BranchHourOverride.rowsOf(override, now));
		this.updatedAt = now;
		return previous;
	}

	/**
	 * Removes the override of {@code date} and returns it.
	 * @throws DomainException {@code BRANCH_HOURS_OVERRIDE_NOT_FOUND} when that date has none
	 */
	public DayOverride removeOverride(LocalDate date, Instant now) {
		requireNotClosed();
		DayOverride removed = overrideOn(date).orElseThrow(() -> new DomainException(
				BranchErrorCode.BRANCH_HOURS_OVERRIDE_NOT_FOUND, "No override on " + date, date.toString()));
		this.hourOverrides.removeIf(row -> row.date().equals(date));
		this.updatedAt = now;
		return removed;
	}

	private Optional<DayOverride> overrideOn(LocalDate date) {
		List<BranchHourOverride> rows = this.hourOverrides.stream().filter(row -> row.date().equals(date)).toList();
		return rows.isEmpty() ? Optional.empty() : Optional.of(toDayOverride(rows));
	}

	private static DayOverride toDayOverride(List<BranchHourOverride> rows) {
		BranchHourOverride first = rows.getFirst();
		List<TimeRange> ranges = rows.stream()
			.map(BranchHourOverride::range)
			.filter(Objects::nonNull)
			.sorted(Comparator.comparing(TimeRange::open))
			.toList();
		return new DayOverride(first.date(), first.closed(), ranges, first.reason());
	}

	/** A {@code CLOSED} Branch is frozen: information and hours alike (Decision O2, Q1). */
	private void requireNotClosed() {
		if (this.status == BranchStatus.CLOSED) {
			throw new DomainException(BranchErrorCode.BRANCH_INVALID_TRANSITION, "Branch " + this.id + " is CLOSED",
					this.status.name());
		}
	}

	/** {@code DRAFT/INACTIVE → ACTIVE}; {@code activatedAt} is the latest activation (Decision P2). */
	public BranchStatus activate(Instant now) {
		BranchStatus previous = moveTo(BranchStatus.ACTIVE, now, BranchStatus.DRAFT, BranchStatus.INACTIVE);
		this.activatedAt = now;
		return previous;
	}

	/** {@code ACTIVE → INACTIVE}. */
	public BranchStatus deactivate(Instant now) {
		return moveTo(BranchStatus.INACTIVE, now, BranchStatus.ACTIVE);
	}

	/** {@code DRAFT/ACTIVE/INACTIVE → CLOSED}, terminal. */
	public BranchStatus close(Instant now) {
		BranchStatus previous = moveTo(BranchStatus.CLOSED, now, BranchStatus.DRAFT, BranchStatus.ACTIVE,
				BranchStatus.INACTIVE);
		this.closedAt = now;
		return previous;
	}

	/**
	 * The lifecycle of Branch §4.3: only the listed {@code from} statuses may move to {@code target}; staying in the
	 * same status is not a transition either (Decision P1).
	 * @throws DomainException {@code BRANCH_INVALID_TRANSITION} otherwise
	 */
	private BranchStatus moveTo(BranchStatus target, Instant now, BranchStatus... from) {
		BranchStatus previous = this.status;
		if (!Arrays.asList(from).contains(previous)) {
			throw new DomainException(BranchErrorCode.BRANCH_INVALID_TRANSITION,
					"Branch " + this.id + " cannot go from " + previous + " to " + target, previous.name());
		}
		this.status = target;
		this.updatedAt = now;
		return previous;
	}

	private static String changed(BranchDiff diff, String field, String current, String next) {
		if (!Objects.equals(current, next)) {
			diff.put(field, current, next);
		}
		return next;
	}

	/** Coordinates are {@code DECIMAL(9,6)}: {@code 10.7757} and {@code 10.775700} are the same point. */
	private static boolean sameLocation(GeoPoint a, GeoPoint b) {
		if (a == null || b == null) {
			return a == b;
		}
		return a.lat().compareTo(b.lat()) == 0 && a.lng().compareTo(b.lng()) == 0;
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value;
	}

	/** Codes compare upper-case, so {@code q1-a} and {@code Q1-A} are the same code. */
	public static String normalizeCode(String branchCode) {
		return branchCode.toUpperCase(Locale.ROOT);
	}

	public UUID getId() {
		return this.id;
	}

	public String getBranchCode() {
		return this.branchCode;
	}

	public String getName() {
		return this.name;
	}

	public BranchStatus getStatus() {
		return this.status;
	}

	public String getPhone() {
		return this.phone;
	}

	public String getEmail() {
		return this.email;
	}

	public BranchAddress getAddress() {
		return new BranchAddress(this.addressLine, this.wardName, this.districtName, this.provinceName);
	}

	/** {@code null} while the Branch has no coordinates. */
	public GeoPoint getLocation() {
		return (this.latitude != null && this.longitude != null) ? new GeoPoint(this.latitude, this.longitude) : null;
	}

	public String getTimezone() {
		return this.timezone;
	}

	public Instant getActivatedAt() {
		return this.activatedAt;
	}

	public Instant getClosedAt() {
		return this.closedAt;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public long getVersion() {
		return this.version;
	}

}
