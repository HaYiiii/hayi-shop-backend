package vn.hayi.shop.branch.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

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
