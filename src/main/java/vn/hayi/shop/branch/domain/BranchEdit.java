package vn.hayi.shop.branch.domain;

import vn.hayi.shop.shared.patch.Change;

/**
 * An edit of a Branch's information (API 09 §1.2, Decision O1); {@code branchCode}, status and timezone are not
 * editable. Setting {@code name}, {@code addressLine} or {@code province} to {@code null} or blank is refused.
 */
public record BranchEdit(Change<String> name, Change<String> addressLine, Change<String> ward,
		Change<String> district, Change<String> province, Change<GeoPoint> location, Change<String> phone,
		Change<String> email) {

	/** An edit that keeps every field. */
	public static BranchEdit none() {
		return new BranchEdit(Change.keep(), Change.keep(), Change.keep(), Change.keep(), Change.keep(), Change.keep(),
				Change.keep(), Change.keep());
	}

}
