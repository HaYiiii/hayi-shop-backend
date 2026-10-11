package vn.hayi.shop;

import org.springframework.boot.SpringApplication;

/**
 * Runs the app against a Testcontainers MySQL instead of compose.yaml.
 */
public class TestHayiShopApplication {

	public static void main(String[] args) {
		SpringApplication.from(HayiShopApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
