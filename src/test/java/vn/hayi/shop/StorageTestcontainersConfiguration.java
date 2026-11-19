package vn.hayi.shop;

import java.time.Duration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * A real SeaweedFS S3 API, as in local dev (compose.yaml, Decision H5), with the dev-only credentials of
 * {@code docker/seaweedfs/s3.json}. The public bucket is created at startup.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StorageTestcontainersConfiguration {

	static final int S3_PORT = 8333;

	@Bean
	GenericContainer<?> seaweedfsContainer() {
		return new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:4.48"))
			.withCommand("server", "-dir=/data", "-s3", "-s3.port=" + S3_PORT, "-s3.config=/etc/seaweedfs/s3.json")
			.withCopyFileToContainer(MountableFile.forHostPath("docker/seaweedfs/s3.json"), "/etc/seaweedfs/s3.json")
			.withExposedPorts(S3_PORT)
			.waitingFor(Wait.forHttp("/").forPort(S3_PORT).forStatusCodeMatching(status -> status < 500)
				.withStartupTimeout(Duration.ofMinutes(2)));
	}

	@Bean
	DynamicPropertyRegistrar storageProperties(GenericContainer<?> seaweedfsContainer) {
		return registry -> {
			registry.add("hayi.storage.endpoint",
					() -> "http://" + seaweedfsContainer.getHost() + ":" + seaweedfsContainer.getMappedPort(S3_PORT));
			registry.add("hayi.storage.access-key", () -> "hayi_s3_dev");
			registry.add("hayi.storage.secret-key", () -> "hayi_s3_dev_secret");
			registry.add("hayi.storage.public-bucket", () -> "hayi-public-test");
			registry.add("hayi.storage.create-bucket", () -> "true");
		};
	}

}
