package vn.hayi.shop.storage.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import vn.hayi.shop.storage.application.ObjectStore;

/**
 * {@link ObjectStore} over the S3 API (AWS SDK v2), so SeaweedFS locally and managed S3 in production work the same
 * (Decision H5, AF4). Path-style addressing keeps one host for every bucket.
 */
@Component
@EnableConfigurationProperties(StorageProperties.class)
class S3ObjectStore implements ObjectStore, AutoCloseable {

	private final StorageProperties properties;

	private final S3Client client;

	private final S3Presigner presigner;

	S3ObjectStore(StorageProperties properties) {
		this.properties = properties;
		AwsCredentialsProvider credentials = isBlank(properties.accessKey())
				? DefaultCredentialsProvider.builder().build()
				: StaticCredentialsProvider.create(
						AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
		S3Configuration pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
		Region region = Region.of(properties.region());
		var clientBuilder = S3Client.builder()
			.httpClientBuilder(UrlConnectionHttpClient.builder())
			.region(region)
			.credentialsProvider(credentials)
			.serviceConfiguration(pathStyle);
		var presignerBuilder = S3Presigner.builder()
			.region(region)
			.credentialsProvider(credentials)
			.serviceConfiguration(pathStyle);
		if (properties.endpoint() != null) {
			clientBuilder.endpointOverride(properties.endpoint());
			presignerBuilder.endpointOverride(properties.endpoint());
		}
		this.client = clientBuilder.build();
		this.presigner = presignerBuilder.build();
		if (properties.createBucket()) {
			createPublicBucketIfMissing();
		}
	}

	private void createPublicBucketIfMissing() {
		try {
			this.client.headBucket(HeadBucketRequest.builder().bucket(publicBucket()).build());
		}
		catch (NoSuchBucketException ex) {
			this.client.createBucket(builder -> builder.bucket(publicBucket()));
		}
		catch (S3Exception ex) {
			if (ex.statusCode() != 404) {
				throw ex;
			}
			this.client.createBucket(builder -> builder.bucket(publicBucket()));
		}
	}

	@Override
	public String provider() {
		return this.properties.provider();
	}

	@Override
	public String publicBucket() {
		return this.properties.publicBucket();
	}

	@Override
	public PresignedPut presignPut(String bucket, String key, String contentType, Duration ttl) {
		PresignedPutObjectRequest presigned = this.presigner.presignPutObject(builder -> builder
			.signatureDuration(ttl)
			.putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build()));
		return new PresignedPut(URI.create(presigned.url().toString()), Map.of("Content-Type", contentType));
	}

	@Override
	public Optional<StoredBytes> read(String bucket, String key, long maxBytes) {
		HeadObjectResponse head;
		try {
			head = this.client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
		}
		catch (NoSuchKeyException ex) {
			return Optional.empty();
		}
		catch (S3Exception ex) {
			if (ex.statusCode() == 404) {
				return Optional.empty();
			}
			throw ex;
		}
		long size = head.contentLength();
		if (size > maxBytes) {
			return Optional.of(new StoredBytes(size, null));
		}
		try (ResponseInputStream<GetObjectResponse> body = this.client
			.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
			return Optional.of(new StoredBytes(size, readAtMost(body, maxBytes)));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** Never more than {@code maxBytes} + 1, whatever storage sends. */
	private static byte[] readAtMost(InputStream body, long maxBytes) throws IOException {
		return body.readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE - 8));
	}

	@Override
	public String publicUrl(String bucket, String key) {
		String base = this.properties.publicBaseUrl();
		if (isBlank(base)) {
			base = (this.properties.endpoint() != null) ? this.properties.endpoint().toString() : "";
		}
		return base.replaceAll("/+$", "") + "/" + bucket + "/" + key;
	}

	@Override
	public void close() {
		this.presigner.close();
		this.client.close();
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

}
