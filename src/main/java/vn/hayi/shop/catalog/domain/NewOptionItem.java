package vn.hayi.shop.catalog.domain;

/** An option item to create; {@code defaultExtraPrice} is the library price in VND (Catalog §5.4). */
public record NewOptionItem(String code, String name, long defaultExtraPrice, boolean isDefaultSelected) {
}
