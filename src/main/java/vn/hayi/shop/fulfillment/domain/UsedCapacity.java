package vn.hayi.shop.fulfillment.domain;

/** Orders and items already claimed in one slot; no counter row yet means {@link #NONE} (Decision T5). */
public record UsedCapacity(int orders, int items) {

	public static final UsedCapacity NONE = new UsedCapacity(0, 0);

}
