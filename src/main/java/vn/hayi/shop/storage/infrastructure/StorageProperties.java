package vn.hayi.shop.storage.infrastructure;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S3-compatible object storage (Decision H5, AF4). Credentials come from the environment; blank ones fall back to
 * the SDK's default chain (instance role in production). Local dev uses the values in {@code docker/seaweedfs}.
 * @param endpoint the S3 API, e.g. SeaweedFS {@code http://localhost:8333}; it also signs the browser's {@code PUT}
 * @param publicBaseUrl where {@code PUBLIC} objects are served from (CDN or the endpoint); URL = base/bucket/key
 * @param createBucket create the public bucket at startup when missing (local dev and tests only)
 */
@ConfigurationProperties("hayi.storage")
record StorageProperties(String provider, URI endpoint, String region, String accessKey, String secretKey,
		String publicBucket, String publicBaseUrl, boolean createBucket) {

	StorageProperties {
		provider = (provider == null || provider.isBlank()) ? "S3" : provider;
		region = (region == null || region.isBlank()) ? "us-east-1" : region;
		publicBucket = (publicBucket == null || publicBucket.isBlank()) ? "hayi-public" : publicBucket;
	}

}
