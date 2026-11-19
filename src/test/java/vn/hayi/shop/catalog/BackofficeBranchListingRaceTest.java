package vn.hayi.shop.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.Optional;
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
import vn.hayi.shop.catalog.domain.BranchListingRepository;
import vn.hayi.shop.catalog.domain.BranchOptionItemListingRepository;
import vn.hayi.shop.identity.BackofficeAuth;
import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.identity.IdentityFixtures;

/**
 * Two first saves of one listing at once: the later one loses on the unique key / primary key and gets
 * {@code 412 VERSION_CONFLICT}, like any lost race (Decision AD2, AD3, R2). The lookup is made to miss so the second
 * {@code "v0"} reaches the insert every time.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class BackofficeBranchListingRaceTest {

	static final String BRANCHES = "/api/backoffice/v1/branches";

	@Autowired
	MockMvc mvc;

	@Autowired
	IdentityFixtures fixtures;

	@Autowired
	MutableClock clock;

	@MockitoSpyBean
	BranchListingRepository listings;

	@MockitoSpyBean
	BranchOptionItemListingRepository optionListings;

	BackofficeAuth auth;

	Session admin;

	String branchId;

	@BeforeEach
	void setUp() throws Exception {
		this.auth = new BackofficeAuth(this.mvc, this.clock);
		this.admin = this.fixtures.openSession(this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail()));
		MockHttpServletResponse branch = this.auth.perform(post(BRANCHES).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"branchCode": "R-%s", "name": "HaYi đua", "address": {"line": "1 Lê Lợi", "province": "TP.HCM"}}
					""".formatted(UUID.randomUUID().toString().substring(0, 8).toUpperCase())), this.admin, true);
		assertThat(branch.getStatus()).as(branch.getContentAsString()).isEqualTo(201);
		this.branchId = JsonPath.read(branch.getContentAsString(), "$.branchId");
	}

	@Test
	void aSecondFirstListingOfOneProductLosesWithVersionConflict() throws Exception {
		String code = ("br-" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase();
		MockHttpServletResponse product = this.auth.perform(post("/api/backoffice/v1/products")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"productCode": "%s", "name": "Đua", "slug": "%s",
					 "variants": [{"sku": "%s", "name": "Standard", "basePrice": 1000}]}
					""".formatted(code, code.toLowerCase(), code)), this.admin, true);
		String productId = JsonPath.read(product.getContentAsString(), "$.productId");
		assertThat(putListing(productId).getStatus()).isEqualTo(200);
		doReturn(Optional.empty()).when(this.listings).findByBranchIdAndProductId(any(), any());

		MockHttpServletResponse lost = putListing(productId);

		assertThat(lost.getStatus()).as(lost.getContentAsString()).isEqualTo(412);
		assertThat(JsonPath.<String>read(lost.getContentAsString(), "$.code")).isEqualTo("VERSION_CONFLICT");
	}

	@Test
	void aSecondFirstOptionPriceLosesWithVersionConflict() throws Exception {
		String code = ("bro-" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase();
		MockHttpServletResponse group = this.auth.perform(post("/api/backoffice/v1/option-groups")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"code": "%s", "name": "Sữa", "selectionType": "SINGLE", "minSelect": 0, "maxSelect": 1,
					 "items": [{"code": "OAT", "name": "Yến mạch"}]}
					""".formatted(code)), this.admin, true);
		String itemId = JsonPath.read(group.getContentAsString(), "$.items[0].optionItemId");
		assertThat(putOption(itemId, 5000).getStatus()).isEqualTo(200);
		doReturn(Optional.empty()).when(this.optionListings).findById(any());

		MockHttpServletResponse lost = putOption(itemId, 6000);

		assertThat(lost.getStatus()).as(lost.getContentAsString()).isEqualTo(412);
		assertThat(JsonPath.<String>read(lost.getContentAsString(), "$.code")).isEqualTo("VERSION_CONFLICT");
	}

	private MockHttpServletResponse putListing(String productId) throws Exception {
		return this.auth.perform(put(BRANCHES + "/" + this.branchId + "/listings/" + productId)
			.contentType(MediaType.APPLICATION_JSON)
			.header("If-Match", "\"v0\"")
			.content("{\"availabilityState\": \"AVAILABLE\"}"), this.admin, true);
	}

	private MockHttpServletResponse putOption(String itemId, long price) throws Exception {
		return this.auth.perform(put(BRANCHES + "/" + this.branchId + "/option-item-listings/" + itemId)
			.contentType(MediaType.APPLICATION_JSON)
			.header("If-Match", "\"v0\"")
			.content("{\"extraPrice\": " + price + "}"), this.admin, true);
	}

}
