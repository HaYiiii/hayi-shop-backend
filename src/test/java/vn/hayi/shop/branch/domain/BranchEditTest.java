package vn.hayi.shop.branch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.patch.Change;

/** Editing a Branch's information (API 09 §1.2, Decision O). */
class BranchEditTest {

	static final Instant CREATED = Instant.parse("2026-11-01T00:00:00Z");

	static final Instant LATER = Instant.parse("2026-11-03T00:00:00Z");

	@Test
	void editReportsOnlyChangedFields() {
		Branch branch = branch();

		BranchDiff diff = branch.edit(new BranchEdit(Change.set("Mới"), Change.keep(), Change.set(null),
				Change.keep(), Change.set("TP.HCM"), Change.keep(), Change.keep(), Change.keep()), LATER);

		assertThat(diff.oldValues()).containsOnlyKeys("name", "address.ward")
			.containsEntry("name", "HaYi")
			.containsEntry("address.ward", "Bến Nghé");
		assertThat(diff.newValues()).containsEntry("name", "Mới").containsEntry("address.ward", null);
		assertThat(branch.getAddress().ward()).isNull();
	}

	@Test
	void editThatChangesNothingIsEmpty() {
		Branch branch = branch();

		BranchDiff diff = branch.edit(new BranchEdit(Change.set("HaYi"), Change.keep(), Change.keep(), Change.keep(),
				Change.keep(), Change.set(new GeoPoint(new BigDecimal("10.775700"), new BigDecimal("106.7019"))),
				Change.keep(), Change.keep()), LATER);

		assertThat(diff.isEmpty()).isTrue();
	}

	@Test
	void requiredFieldsCannotBecomeBlank() {
		Branch branch = branch();

		assertThatIllegalArgumentException().isThrownBy(() -> branch.edit(new BranchEdit(Change.set(" "),
				Change.keep(), Change.keep(), Change.keep(), Change.keep(), Change.keep(), Change.keep(),
				Change.keep()), LATER));
		assertThatIllegalArgumentException().isThrownBy(() -> branch.edit(new BranchEdit(Change.keep(),
				Change.set(null), Change.keep(), Change.keep(), Change.keep(), Change.keep(), Change.keep(),
				Change.keep()), LATER));
		assertThat(branch.getName()).isEqualTo("HaYi");
	}

	@Test
	void closedBranchCannotBeEdited() {
		Branch branch = branch();
		ReflectionTestUtils.setField(branch, "status", BranchStatus.CLOSED);

		assertThatThrownBy(() -> branch.edit(BranchEdit.none(), LATER)).isInstanceOfSatisfying(DomainException.class,
				ex -> assertThat(ex.code()).isEqualTo(BranchErrorCode.BRANCH_INVALID_TRANSITION));
	}

	private static Branch branch() {
		return Branch.create("Q1-A", "HaYi", new BranchAddress("12 Nguyễn Huệ", "Bến Nghé", "Quận 1", "TP.HCM"),
				new GeoPoint(new BigDecimal("10.7757"), new BigDecimal("106.7019")), null, null, CREATED);
	}

}
