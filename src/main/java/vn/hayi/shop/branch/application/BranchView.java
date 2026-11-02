package vn.hayi.shop.branch.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import vn.hayi.shop.branch.domain.Branch;
import vn.hayi.shop.branch.domain.BranchAddress;
import vn.hayi.shop.branch.domain.GeoPoint;

/** A Branch as the Backoffice reads it (API 09 §1.1); {@code lat}/{@code lng} are {@code null} without coordinates. */
public record BranchView(UUID branchId, String branchCode, String name, String status, String addressLine,
		String ward, String district, String province, BigDecimal lat, BigDecimal lng, String phone, String email,
		String timezone, Instant activatedAt, Instant closedAt, Instant createdAt, long version) {

	static BranchView of(Branch branch) {
		BranchAddress address = branch.getAddress();
		GeoPoint location = branch.getLocation();
		return new BranchView(branch.getId(), branch.getBranchCode(), branch.getName(), branch.getStatus().name(),
				address.line(), address.ward(), address.district(), address.province(),
				(location != null) ? location.lat() : null, (location != null) ? location.lng() : null,
				branch.getPhone(), branch.getEmail(), branch.getTimezone(), branch.getActivatedAt(),
				branch.getClosedAt(), branch.getCreatedAt(), branch.getVersion());
	}

}
