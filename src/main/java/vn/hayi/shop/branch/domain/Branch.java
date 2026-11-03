package vn.hayi.shop.branch.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
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
		if (this.status == BranchStatus.CLOSED) {
			throw new DomainException(BranchErrorCode.BRANCH_INVALID_TRANSITION, "Branch " + this.id + " is CLOSED",
					this.status.name());
		}
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
