package vn.hayi.shop.fulfillment.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fulfillment settings. {@code nearlyFullRatio}: a slot is {@code NEARLY_FULL} when orders or items left are at most
 * this share of the maximum (API 02 §3, Decision T5; default 0.2).
 */
@ConfigurationProperties("hayi.fulfillment")
public record FulfillmentProperties(Double nearlyFullRatio) {

	public FulfillmentProperties {
		nearlyFullRatio = (nearlyFullRatio != null) ? nearlyFullRatio : 0.2;
		if (nearlyFullRatio < 0 || nearlyFullRatio > 1) {
			throw new IllegalArgumentException("hayi.fulfillment.nearly-full-ratio must be within [0, 1]");
		}
	}

}
