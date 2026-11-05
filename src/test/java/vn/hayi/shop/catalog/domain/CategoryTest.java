package vn.hayi.shop.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import vn.hayi.shop.shared.error.DomainException;

/** Invariants of the Category tree (Catalog §5.3, Decision W5, Y2–Y4). */
class CategoryTest {

	static final Instant T0 = Instant.parse("2026-11-05T02:00:00Z");

	static final Instant T1 = Instant.parse("2026-11-05T03:00:00Z");

	@Test
	void childOfARootIsAllowedButNotAThirdLevel() {
		Category root = Category.create("tra", "Trà", null, null, 0, T0);
		Category child = Category.create("tra-sua", "Trà sữa", null, root, 0, T0);

		assertThat(child.getParentId()).isEqualTo(root.getId());
		assertCode(() -> Category.create("chan-chau", "Trân châu", null, child, 0, T0),
				CatalogErrorCode.CATALOG_CATEGORY_DEPTH_EXCEEDED);
	}

	@Test
	void archivedCategoryCanNeitherParentNorBeEdited() {
		Category root = Category.create("cu", "Cũ", null, null, 0, T0);
		root.archive(false, T1);

		assertThat(root.getStatus()).isEqualTo(CategoryStatus.ARCHIVED);
		assertCode(() -> Category.create("con", "Con", null, root, 0, T0), CatalogErrorCode.CATALOG_CATEGORY_ARCHIVED);
		assertCode(() -> root.edit("Mới", "cu", null, T1), CatalogErrorCode.CATALOG_CATEGORY_ARCHIVED);
		assertCode(() -> root.archive(false, T1), CatalogErrorCode.CATALOG_CATEGORY_ARCHIVED);
	}

	@Test
	void archiveIsRefusedWhileInUse() {
		Category root = Category.create("goc", "Gốc", null, null, 0, T0);

		assertCode(() -> root.archive(true, T1), CatalogErrorCode.CATALOG_CATEGORY_IN_USE);
		assertThat(root.getStatus()).isEqualTo(CategoryStatus.ACTIVE);
	}

	@Test
	void editReportsOnlyChangedFields() {
		Category category = Category.create("ca-phe", "Cà phê", "Đậm", null, 0, T0);

		FieldDiff unchanged = category.edit("Cà phê", "ca-phe", "Đậm", T1);
		assertThat(unchanged.isEmpty()).isTrue();
		assertThat(category.getUpdatedAt()).isEqualTo(T0);

		FieldDiff diff = category.edit("Coffee", "ca-phe", null, T1);
		assertThat(diff.oldValues()).containsOnlyKeys("name", "description");
		assertThat(diff.newValues()).containsEntry("name", "Coffee").containsEntry("description", null);
		assertThat(category.getUpdatedAt()).isEqualTo(T1);
	}

	@Test
	void categoryWithChildrenOrItselfCannotBecomeAChild() {
		Category parent = Category.create("cha", "Cha", null, null, 0, T0);
		Category other = Category.create("khac", "Khác", null, null, 1, T0);

		assertCode(() -> parent.moveTo(other, true, 0, new FieldDiff(), T1),
				CatalogErrorCode.CATALOG_CATEGORY_DEPTH_EXCEEDED);
		assertCode(() -> other.moveTo(other, false, 0, new FieldDiff(), T1),
				CatalogErrorCode.CATALOG_CATEGORY_DEPTH_EXCEEDED);
	}

	@Test
	void moveGoesLastUnderTheNewParentAndIsRecorded() {
		Category target = Category.create("den", "Đến", null, null, 0, T0);
		Category moved = Category.create("chuyen", "Chuyển", null, null, 1, T0);
		FieldDiff diff = new FieldDiff();

		moved.moveTo(target, false, 4, diff, T1);

		assertThat(moved.getParentId()).isEqualTo(target.getId());
		assertThat(moved.getSortOrder()).isEqualTo(4);
		assertThat(diff.oldValues()).containsEntry("parentId", null);
		assertThat(diff.newValues()).containsEntry("parentId", target.getId());
	}

	@Test
	void placingAtTheSamePositionIsNotAChange() {
		Category category = Category.create("a", "A", null, null, 2, T0);

		assertThat(category.placeAt(2, T1)).isFalse();
		assertThat(category.placeAt(0, T1)).isTrue();
		assertThat(category.getSortOrder()).isZero();
	}

	private static void assertCode(Runnable action, CatalogErrorCode code) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class,
				ex -> assertThat(ex.code()).isEqualTo(code));
	}

}
