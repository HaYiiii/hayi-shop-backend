package vn.hayi.shop.cart.domain;

/** ERD 06 {@code crt_cart.status}: a Cart leaves {@code ACTIVE} only on {@code OrderCreated} or when discarded. */
public enum CartStatus {

	ACTIVE, CONVERTED, DISCARDED

}
