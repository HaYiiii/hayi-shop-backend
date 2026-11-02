package vn.hayi.shop.branch.application;

import java.math.BigDecimal;

/**
 * What an Owner/Admin enters to create a Branch (API 09 §1.1). Required: code, name, address line and province;
 * {@code lat}/{@code lng} come together or not at all.
 */
public record NewBranch(String branchCode, String name, String addressLine, String ward, String district,
		String province, BigDecimal lat, BigDecimal lng, String phone, String email) {
}
