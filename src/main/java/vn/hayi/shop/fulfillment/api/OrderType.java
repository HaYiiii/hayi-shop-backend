package vn.hayi.shop.fulfillment.api;

/** Who orders: one customer, or a Group Order, which uses the longer Group lead time (Decision N2). */
public enum OrderType {

	INDIVIDUAL,
	GROUP

}
