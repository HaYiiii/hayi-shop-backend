package vn.hayi.shop.branch.application;

import java.math.BigDecimal;

import vn.hayi.shop.shared.patch.Change;

/**
 * What an Owner/Admin changes on a Branch (API 09 §1.2): each field is kept or set, {@code null} clearing the
 * optional ones. The web layer has already refused blank required fields and read-only keys.
 */
public record BranchChanges(Change<String> name, Change<String> addressLine, Change<String> ward,
		Change<String> district, Change<String> province, Change<Location> location, Change<String> phone,
		Change<String> email) {

	/** Both coordinates together (Decision O1). */
	public record Location(BigDecimal lat, BigDecimal lng) {
	}

}
