package vn.hayi.shop.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vn.hayi.shop.shared.error.DomainException;

/** Invariants of a new Product (Catalog §5.2, Decision B, Z). */
class ProductTest {

	static final Instant T0 = Instant.parse("2026-11-05T02:00:00Z");

	static final NewVariant M = new NewVariant("ml-m", "M", 45000, true);

	static final NewVariant L = new NewVariant("ml-l", "L", 55000, false);

	@Test
	void startsDraftWithUpperCaseCodesAndActiveVariants() {
		Product product = create(ProductType.STANDARD, null, List.of(M, L));

		assertThat(product.getStatus()).isEqualTo(ProductStatus.DRAFT);
		assertThat(product.getProductCode()).isEqualTo("MATCHA-LATTE");
		assertThat(product.getVariants()).extracting(ProductVariant::getSku).containsExactly("ML-M", "ML-L");
		assertThat(product.getVariants()).extracting(ProductVariant::getStatus).containsOnly(VariantStatus.ACTIVE);
		assertThat(product.getVariants()).extracting(ProductVariant::getSortOrder).containsExactly(0, 1);
		assertThat(product.fromPrice()).hasValue(45000);
	}

	@Test
	void comboIsNotSupported() {
		assertThatThrownBy(() -> create(ProductType.COMBO, null, List.of(M)))
			.isInstanceOfSatisfying(DomainException.class,
					ex -> assertThat(ex.code()).isEqualTo(CatalogErrorCode.PRODUCT_TYPE_NOT_SUPPORTED));
	}

	@Test
	void variantsNeedExactlyOneDefaultUniqueSkusAndNoNegativePrice() {
		assertThatThrownBy(() -> create(ProductType.STANDARD, null, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> create(ProductType.STANDARD, null, List.of(L)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> create(ProductType.STANDARD, null, List.of(M, new NewVariant("ML-M", "M2", 1, false))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> create(ProductType.STANDARD, null, List.of(new NewVariant("x", "X", -1, true))))
			.isInstanceOfSatisfying(DomainException.class,
					ex -> assertThat(ex.code()).isEqualTo(CatalogErrorCode.CATALOG_PRICE_NEGATIVE));
	}

	@Test
	void primaryCategoryMustBeActive() {
		Category active = Category.create("matcha", "Matcha", null, null, 0, T0);
		assertThat(create(ProductType.STANDARD, active, List.of(M)).getPrimaryCategoryId()).isEqualTo(active.getId());

		Category archived = Category.create("cu", "Cũ", null, null, 0, T0);
		archived.archive(false, T0);
		assertThatThrownBy(() -> create(ProductType.STANDARD, archived, List.of(M)))
			.isInstanceOfSatisfying(DomainException.class,
					ex -> assertThat(ex.code()).isEqualTo(CatalogErrorCode.CATALOG_CATEGORY_ARCHIVED));
	}

	@Test
	void lifecycleFollowsDecisionAA() {
		Category category = Category.create("matcha", "Matcha", null, null, 0, T0);
		Product product = create(ProductType.STANDARD, category, List.of(M));

		assertThat(product.activate(category, Map.of(), T0)).isEqualTo(ProductStatus.DRAFT);
		assertThat(product.deactivate(T0)).isEqualTo(ProductStatus.ACTIVE);
		assertThat(product.activate(category, Map.of(), T0)).isEqualTo(ProductStatus.INACTIVE);
		assertThat(product.archive(T0)).isEqualTo(ProductStatus.ACTIVE);
		assertThat(product.getArchivedAt()).isEqualTo(T0);
		assertThatThrownBy(() -> product.archive(T0)).isInstanceOfSatisfying(DomainException.class,
				ex -> assertThat(ex.code()).isEqualTo(CatalogErrorCode.CATALOG_PRODUCT_INVALID_TRANSITION));
	}

	@Test
	void activationReportsEveryMissingCondition() {
		Product product = create(ProductType.STANDARD, null, List.of(M));

		assertThatThrownBy(() -> product.activate(null, Map.of(), T0))
			.isInstanceOfSatisfying(ProductActivationInvalidException.class,
					ex -> assertThat(ex.issues()).extracting(ProductActivationInvalidException.Issue::code)
						.containsExactly(ActivationIssue.PRIMARY_CATEGORY_MISSING));
		assertThat(product.getStatus()).isEqualTo(ProductStatus.DRAFT);
	}

	@Test
	void optionConfigFollowsW4() {
		Category category = Category.create("matcha", "Matcha", null, null, 0, T0);
		Product product = create(ProductType.STANDARD, category, List.of(M));
		OptionGroup milk = OptionGroup.create("sua", "Sữa", SelectionType.SINGLE, 1, 1,
				List.of(new NewOptionItem("tuoi", "Sữa tươi", 0, true), new NewOptionItem("yen", "Yến mạch", 10000, false)),
				T0);
		Map<UUID, OptionGroup> groups = Map.of(milk.getId(), milk);

		assertThat(product.replaceOptionGroups(List.of(new OptionGroupChoice(milk.getId(), null, null)), groups, T0))
			.isTrue();
		assertThat(product.replaceOptionGroups(List.of(new OptionGroupChoice(milk.getId(), null, null)), groups, T0))
			.as("same choice")
			.isFalse();
		List<UUID> items = milk.getItems().stream().map(OptionItem::getId).toList();
		product.replaceItemOverrides(List.of(new ProductOptionItemOverride(items.get(0), null, true),
				new ProductOptionItemOverride(items.get(1), null, true)), groups, T0);

		assertThatThrownBy(() -> product.activate(category, groups, T0))
			.isInstanceOfSatisfying(ProductActivationInvalidException.class,
					ex -> assertThat(ex.issues()).containsExactly(
							new ProductActivationInvalidException.Issue(ActivationIssue.OPTION_CONFIG_INVALID,
									milk.getId())));
		assertThat(product.changesOverridePrices(List.of(new ProductOptionItemOverride(items.get(0), 5000L, true))))
			.isTrue();
		assertThat(product.changesOverridePrices(List.of(new ProductOptionItemOverride(items.get(0), null, false))))
			.as("hiding only")
			.isFalse();
	}

	private static Product create(ProductType type, Category category, List<NewVariant> variants) {
		return Product.create("matcha-latte", "matcha-latte", "Matcha Latte", type, category, null, null, variants, T0);
	}

}
