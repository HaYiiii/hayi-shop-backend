package vn.hayi.shop.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import vn.hayi.shop.shared.error.DomainException;

/** Invariants of an option group (Catalog §5.4, Decision AB3). */
class OptionGroupTest {

	static final Instant T0 = Instant.parse("2026-11-06T02:00:00Z");

	static final Instant T1 = Instant.parse("2026-11-06T03:00:00Z");

	@Test
	void minMaxRules() {
		assertCode(() -> group(SelectionType.MULTIPLE, -1, 2, List.of()), CatalogErrorCode.OPTION_GROUP_MINMAX_INVALID);
		assertCode(() -> group(SelectionType.MULTIPLE, 2, 1, List.of()), CatalogErrorCode.OPTION_GROUP_MINMAX_INVALID);
		assertCode(() -> group(SelectionType.MULTIPLE, 0, 0, List.of()), CatalogErrorCode.OPTION_GROUP_MINMAX_INVALID);
		assertCode(() -> group(SelectionType.SINGLE, 0, 2, List.of()), CatalogErrorCode.OPTION_GROUP_MINMAX_INVALID);
		assertThat(group(SelectionType.SINGLE, 1, 1, List.of()).getStatus()).isEqualTo(OptionStatus.ACTIVE);
	}

	@Test
	void noMoreActiveDefaultsThanMax() {
		NewOptionItem a = new NewOptionItem("a", "A", 0, true);
		NewOptionItem b = new NewOptionItem("b", "B", 0, true);
		assertCode(() -> group(SelectionType.SINGLE, 0, 1, List.of(a, b)),
				CatalogErrorCode.OPTION_GROUP_MINMAX_INVALID);

		OptionGroup group = group(SelectionType.MULTIPLE, 0, 2, List.of(a, b));
		assertCode(() -> group.edit("G", SelectionType.MULTIPLE, 0, 1, T1),
				CatalogErrorCode.OPTION_GROUP_MINMAX_INVALID);
		group.archiveItem(group.getItems().getFirst().getId(), T1);
		assertThat(group.edit("G", SelectionType.MULTIPLE, 0, 1, T1).isEmpty()).isFalse();
	}

	@Test
	void itemCodesAreUniqueWithinTheGroupAndPricesNotNegative() {
		OptionGroup group = group(SelectionType.MULTIPLE, 0, 3, List.of(new NewOptionItem("tc", "Trân châu", 5000, false)));

		assertCode(() -> group.addItem(new NewOptionItem("TC", "Trân châu 2", 0, false), T1),
				CatalogErrorCode.CATALOG_CODE_TAKEN);
		assertCode(() -> group.addItem(new NewOptionItem("x", "X", -1, false), T1),
				CatalogErrorCode.CATALOG_PRICE_NEGATIVE);
		assertThat(group.addItem(new NewOptionItem("pudding", "Pudding", 7000, false), T1).getSortOrder())
			.isEqualTo(1);
	}

	@Test
	void archivedGroupAndItemAreFrozen() {
		OptionGroup group = group(SelectionType.MULTIPLE, 0, 3, List.of(new NewOptionItem("a", "A", 0, false)));
		var itemId = group.getItems().getFirst().getId();
		group.archiveItem(itemId, T1);
		assertCode(() -> group.editItem(itemId, "B", 0, false, T1), CatalogErrorCode.CATALOG_OPTION_ARCHIVED);

		group.archive(T1);
		assertCode(() -> group.edit("X", SelectionType.MULTIPLE, 0, 3, T1), CatalogErrorCode.CATALOG_OPTION_ARCHIVED);
		assertCode(() -> group.archive(T1), CatalogErrorCode.CATALOG_OPTION_ARCHIVED);
	}

	private static OptionGroup group(SelectionType type, int min, int max, List<NewOptionItem> items) {
		return OptionGroup.create("sua", "Sữa", type, min, max, items, T0);
	}

	private static void assertCode(Runnable action, CatalogErrorCode code) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class,
				ex -> assertThat(ex.code()).isEqualTo(code));
	}

}
