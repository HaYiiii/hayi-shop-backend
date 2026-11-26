package vn.hayi.shop.cart.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.id.UuidV7;

/** Cart Item equivalence and limits (Cart & Pricing §6.3, Decision AV2). */
class CartTest {

	private static final Instant NOW = Instant.parse("2026-11-26T02:00:00Z");

	private final UUID product = UuidV7.generate();

	private final UUID variant = UuidV7.generate();

	private final CartItemOption milk = new CartItemOption(UuidV7.generate(), UuidV7.generate());

	private final CartItemOption sugar = new CartItemOption(UuidV7.generate(), UuidV7.generate());

	@Test
	void anEquivalentItemAddsToTheQuantityAndKeepsTheLatestPriceSeen() {
		Cart cart = Cart.open(UuidV7.generate(), UuidV7.generate(), NOW);

		CartItem first = cart.add(selection(List.of(this.milk, this.sugar), "ít đá"), 1, 50000L, NOW);
		CartItem again = cart.add(selection(List.of(this.sugar, this.milk), "ít đá"), 2, 55000L, NOW.plusSeconds(1));

		assertThat(again).isSameAs(first);
		assertThat(cart.getItems()).singleElement().satisfies(item -> {
			assertThat(item.getQuantity()).isEqualTo(3);
			assertThat(item.getLastSeenUnitPrice()).isEqualTo(55000L);
		});
		assertThat(cart.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
	}

	@Test
	void aDifferentNoteOrOptionIsAnotherItem() {
		Cart cart = Cart.open(UuidV7.generate(), UuidV7.generate(), NOW);

		cart.add(selection(List.of(this.milk), "ít đá"), 1, 50000L, NOW);
		cart.add(selection(List.of(this.milk), null), 1, 50000L, NOW);
		cart.add(selection(List.of(this.sugar), "ít đá"), 1, 50000L, NOW);

		assertThat(cart.getItems()).hasSize(3);
	}

	@Test
	void theNoteIsStrippedAndItsSpacesCollapsedAndBlankIsNone() {
		assertThat(CartSelection.normalizeNote("  ít \t  đá\n ")).isEqualTo("ít đá");
		assertThat(CartSelection.normalizeNote("   ")).isNull();
		assertThat(CartSelection.normalizeNote(null)).isNull();
		assertThat(selection(List.of(), "ít đá").equivalenceKey())
			.isEqualTo(selection(List.of(), CartSelection.normalizeNote(" ít  đá ")).equivalenceKey())
			.hasSize(64);
	}

	@Test
	void anItemHoldsAtMostNinetyNine() {
		Cart cart = Cart.open(UuidV7.generate(), UuidV7.generate(), NOW);
		cart.add(selection(List.of(), null), 60, 50000L, NOW);

		assertThatThrownBy(() -> cart.add(selection(List.of(), null), 40, 50000L, NOW))
			.isInstanceOfSatisfying(DomainException.class,
					ex -> assertThat(ex.code()).isEqualTo(CartErrorCode.CART_ITEM_QUANTITY_LIMIT));
		assertThat(cart.getItems().getFirst().getQuantity()).isEqualTo(60);
		cart.add(selection(List.of(), null), 39, 50000L, NOW);
		assertThat(cart.getItems().getFirst().getQuantity()).isEqualTo(99);
	}

	@Test
	void aCartHoldsAtMostFiftyItemsButAnEquivalentOneStillAdds() {
		Cart cart = Cart.open(UuidV7.generate(), UuidV7.generate(), NOW);
		for (int i = 0; i < Cart.MAX_ITEMS; i++) {
			cart.add(selection(List.of(), "món " + i), 1, 50000L, NOW);
		}

		assertThatThrownBy(() -> cart.add(selection(List.of(), "món mới"), 1, 50000L, NOW))
			.isInstanceOfSatisfying(DomainException.class,
					ex -> assertThat(ex.code()).isEqualTo(CartErrorCode.CART_ITEM_LIMIT));
		cart.add(selection(List.of(), "món 0"), 1, 50000L, NOW);
		assertThat(cart.getItems()).hasSize(Cart.MAX_ITEMS);
		assertThat(cart.getItems().getFirst().getQuantity()).isEqualTo(2);
	}

	private CartSelection selection(List<CartItemOption> options, String note) {
		return new CartSelection(this.product, this.variant, options, note);
	}

}
