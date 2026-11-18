package vn.hayi.shop.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.catalog.domain.ProductRepository;
import vn.hayi.shop.identity.BackofficeAuth;
import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.identity.IdentityFixtures;

/**
 * A SKU taken after the check (a concurrent request won) fails on the unique key and is still reported on its field
 * (Decision Z2, AE2). The check is made to miss so the unique-key path runs every time.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class BackofficeProductSkuRaceTest {

	static final String PRODUCTS = "/api/backoffice/v1/products";

	@Autowired
	MockMvc mvc;

	@Autowired
	IdentityFixtures fixtures;

	@Autowired
	MutableClock clock;

	@MockitoSpyBean
	ProductRepository products;

	BackofficeAuth auth;

	Session admin;

	@BeforeEach
	void setUp() {
		this.auth = new BackofficeAuth(this.mvc, this.clock);
		this.admin = this.fixtures.openSession(this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail()));
	}

	@Test
	void aSkuTakenAfterTheCheckOnCreateIsReportedOnItsField() throws Exception {
		String taken = code("ra");
		assertThat(create(code("ra"), taken).getStatus()).isEqualTo(201);
		missTheCheck();

		MockHttpServletResponse response = this.auth.perform(post(PRODUCTS).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"productCode": "%s", "name": "Đua", "slug": "%s",
					 "variants": [{"sku": "%s", "name": "M", "basePrice": 1, "isDefault": true},
					              {"sku": "%s", "name": "L", "basePrice": 2, "isDefault": false}]}
					""".formatted(code("rb"), code("rb").toLowerCase(), code("moi"), taken.toLowerCase())),
				this.admin, true);

		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(409);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo("CATALOG_SKU_TAKEN");
		assertThat(JsonPath.<List<String>>read(response.getContentAsString(), "$.errors[*].field"))
			.containsExactly("variants[1].sku");
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.detail")).contains(taken);
		verify(this.products).saveAndFlush(any());
	}

	@Test
	void aSkuTakenAfterTheCheckOnAddVariantIsReportedOnItsField() throws Exception {
		String taken = code("rc");
		assertThat(create(code("rc"), taken).getStatus()).isEqualTo(201);
		MockHttpServletResponse product = create(code("rd"), code("rd-m"));
		String productId = JsonPath.read(product.getContentAsString(), "$.productId");
		missTheCheck();

		MockHttpServletResponse response = this.auth.perform(post(PRODUCTS + "/" + productId + "/variants")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"sku\": \"" + taken.toLowerCase() + "\", \"name\": \"L\", \"basePrice\": 1}"), this.admin,
				true);

		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(409);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo("CATALOG_SKU_TAKEN");
		assertThat(JsonPath.<List<String>>read(response.getContentAsString(), "$.errors[*].field"))
			.containsExactly("sku");
		verify(this.products).saveAndFlush(any());
	}

	/** The SKU check finds nothing, as when the other request commits right after it; only saving can fail. */
	private void missTheCheck() {
		doReturn(List.of()).when(this.products).findTakenSkus(anyCollection());
		clearInvocations(this.products);
	}

	private MockHttpServletResponse create(String code, String sku) throws Exception {
		MockHttpServletResponse created = this.auth.perform(post(PRODUCTS).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"productCode": "%s", "name": "Có SKU", "slug": "%s",
					 "variants": [{"sku": "%s", "name": "Standard", "basePrice": 1000}]}
					""".formatted(code, code.toLowerCase(), sku)), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return created;
	}

	private static String code(String prefix) {
		return (prefix + "-" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase();
	}

}
