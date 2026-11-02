package vn.hayi.shop.branch.domain;

import java.util.Objects;

/** Postal address of a Branch; {@code ward} and {@code district} are optional (ERD 03). */
public record BranchAddress(String line, String ward, String district, String province) {

	public BranchAddress {
		Objects.requireNonNull(line, "line");
		Objects.requireNonNull(province, "province");
	}

}
