package vn.hayi.shop.order.application;

import vn.hayi.shop.catalog.domain.Product;

// TEMPORARY: order reaches into catalog internals to prove CI goes red (#3) — will be reverted.
class BoundaryViolation {

	Product product;

}
