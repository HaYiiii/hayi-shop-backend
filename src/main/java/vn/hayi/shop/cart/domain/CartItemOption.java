package vn.hayi.shop.cart.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** One chosen option item of a Cart Item, with the group it belongs to (ERD 06 {@code crt_cart_item_option}). */
@Embeddable
public record CartItemOption(@Column(name = "option_item_id") UUID optionItemId,
		@Column(name = "option_group_id") UUID optionGroupId) {
}
