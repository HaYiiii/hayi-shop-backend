package vn.hayi.shop.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.identity.BackofficeAuth;
import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.identity.IdentityFixtures;
import vn.hayi.shop.platform.persistence.UuidBinary;

/**
 * A Branch's Manager puts Products on sale there, takes them off and sets Branch prices for Variants and options,
 * without touching sold-out / paused states (Catalog §5.5–5.6, API 08 §2, Decision F, W2, W3, AD).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class BackofficeBranchListingTest {

	static final String BRANCHES = "/api/backoffice/v1/branches";

	@Autowired
	MockMvc mvc;

	@Autowired
	IdentityFixtures fixtures;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcClient jdbc;

	BackofficeAuth auth;

	Session admin;

	Session manager;

	String branchId;

	@BeforeEach
	void setUp() throws Exception {
		this.auth = new BackofficeAuth(this.mvc, this.clock);
		this.admin = this.fixtures.openSession(this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail()));
		this.branchId = createBranch();
		UUID managerId = this.fixtures.branchStaff();
		this.fixtures.assignToBranch(managerId, UUID.fromString(this.branchId), "MANAGER");
		this.manager = this.fixtures.openSession(managerId);
	}

	// --- product listing (AD2, W2, W3) --------------------------------------------------------------------------

	@Test
	void theManagerPutsAProductOnSaleWithV0AndItIsAudited() throws Exception {
		Product product = product(2);

		MockHttpServletResponse listed = putListing(this.manager, product.id, "\"v0\"", "AVAILABLE");

		assertThat(listed.getStatus()).as(listed.getContentAsString()).isEqualTo(200);
		assertThat(listed.getHeader("ETag")).isEqualTo("\"v1\"");
		String body = listed.getContentAsString();
		assertThat(JsonPath.<String>read(body, "$.productId")).isEqualTo(product.id);
		assertThat(JsonPath.<String>read(body, "$.listing.availabilityState")).isEqualTo("AVAILABLE");
		assertThat(JsonPath.<List<String>>read(body, "$.listing.variants[*].variantId"))
			.containsExactlyElementsOf(product.variants);
		assertThat(JsonPath.<List<Object>>read(body, "$.listing.variants[*].price")).containsExactly(null, null);
		assertThat(JsonPath.<List<Integer>>read(body, "$.listing.variants[*].effectivePrice"))
			.containsExactly(30000, 40000);
		assertThat(JsonPath.<List<String>>read(body, "$.listing.variants[*].availabilityState"))
			.containsExactly("AVAILABLE", "AVAILABLE");
		List<Map<String, Object>> audits = audits("BRANCH_LISTING_PUT");
		assertThat(audits).hasSize(1);
		assertThat(audits.getFirst()).containsEntry("branch_id", UuidBinary.toBytes(UUID.fromString(this.branchId)));
		assertThat(JsonPath.<String>read((String) audits.getFirst().get("new_value"), "$.availabilityState"))
			.isEqualTo("AVAILABLE");
	}

	@Test
	void listingNeedsTheCurrentVersionAndUnchangedKeepsIt() throws Exception {
		Product product = product(1);

		assertProblem(putListing(this.manager, product.id, null, "AVAILABLE"), 428, "PRECONDITION_REQUIRED");
		assertProblem(putListing(this.manager, product.id, "\"v1\"", "AVAILABLE"), 412, "VERSION_CONFLICT");
		assertThat(putListing(this.manager, product.id, "\"v0\"", "AVAILABLE").getStatus()).isEqualTo(200);
		assertProblem(putListing(this.manager, product.id, "\"v0\"", "AVAILABLE"), 412, "VERSION_CONFLICT");

		MockHttpServletResponse same = putListing(this.manager, product.id, "\"v1\"", "AVAILABLE");
		assertThat(same.getHeader("ETag")).as("no change").isEqualTo("\"v1\"");
		assertThat(audits("BRANCH_LISTING_PUT")).hasSize(1);
	}

	@Test
	void soldOutAndPausedCannotBeSetFromListing() throws Exception {
		Product product = product(1);

		assertProblem(putListing(this.manager, product.id, "\"v0\"", "SOLD_OUT"), 400, "VALIDATION_FAILED");
		assertProblem(putListing(this.manager, product.id, "\"v0\"", "PAUSED"), 400, "VALIDATION_FAILED");
	}

	@Test
	void draftAndInactiveProductsMayBeListedButNotArchivedOnes() throws Exception {
		Product draft = product(1);
		Product archived = product(1);
		assertThat(this.auth.perform(post("/api/backoffice/v1/products/" + archived.id + "/archive"), this.admin,
				true).getStatus()).isEqualTo(200);

		assertThat(putListing(this.manager, draft.id, "\"v0\"", "AVAILABLE").getStatus()).isEqualTo(200);
		assertProblem(putListing(this.manager, archived.id, "\"v0\"", "AVAILABLE"), 409, "CATALOG_PRODUCT_ARCHIVED");
		assertProblem(putListing(this.manager, UUID.randomUUID().toString(), "\"v0\"", "AVAILABLE"), 404,
				"CATALOG_PRODUCT_NOT_FOUND");
	}

	@Test
	void unknownOrClosedBranchIsRefused() throws Exception {
		Product product = product(1);

		assertProblem(this.auth.perform(listingPut(UUID.randomUUID().toString(), product.id, "\"v0\"", "AVAILABLE"),
				this.admin, true), 404, "BRANCH_NOT_FOUND");
		this.jdbc.sql("UPDATE brn_branch SET status = 'CLOSED' WHERE id = ?")
			.param(UuidBinary.toBytes(UUID.fromString(this.branchId)))
			.update();
		assertProblem(putListing(this.admin, product.id, "\"v0\"", "AVAILABLE"), 409, "BRANCH_INVALID_TRANSITION");
	}

	@Test
	void aManagerOfAnotherBranchIsOutOfScope() throws Exception {
		Product product = product(1);
		String other = createBranch();

		assertProblem(this.auth.perform(listingPut(other, product.id, "\"v0\"", "AVAILABLE"), this.manager, true), 403,
				"BRANCH_SCOPE_DENIED");
		assertProblem(this.auth.perform(get(BRANCHES + "/" + other + "/listings"), this.manager, false), 403,
				"BRANCH_SCOPE_DENIED");
	}

	// --- unlist and held state (AD5) ----------------------------------------------------------------------------

	@Test
	void unlistTakesItOffSaleAndIsAudited() throws Exception {
		Product product = product(1);
		assertThat(putListing(this.manager, product.id, "\"v0\"", "AVAILABLE").getStatus()).isEqualTo(200);

		MockHttpServletResponse unlisted = unlist(product.id);

		assertThat(unlisted.getStatus()).as(unlisted.getContentAsString()).isEqualTo(200);
		assertThat(unlisted.getHeader("ETag")).isEqualTo("\"v2\"");
		assertThat(JsonPath.<String>read(unlisted.getContentAsString(), "$.listing.availabilityState"))
			.isEqualTo("NOT_AVAILABLE");
		assertThat(audits("BRANCH_LISTING_UNLIST")).hasSize(1);
		assertThat(unlist(product.id).getHeader("ETag")).as("already off sale").isEqualTo("\"v2\"");
		assertProblem(unlist(product(1).id), 404, "CATALOG_LISTING_NOT_FOUND");
	}

	@Test
	void unlistingASoldOutProductHoldsTheStateAndRelistingRestoresIt() throws Exception {
		Product product = product(1);
		assertThat(putListing(this.manager, product.id, "\"v0\"", "AVAILABLE").getStatus()).isEqualTo(200);
		seedProductState(product.id, "SOLD_OUT");

		MockHttpServletResponse unlisted = unlist(product.id);
		assertThat(JsonPath.<String>read(unlisted.getContentAsString(), "$.listing.availabilityState"))
			.isEqualTo("NOT_AVAILABLE");
		assertThat(heldState("cat_branch_product_listing", product.id)).isEqualTo("SOLD_OUT");

		MockHttpServletResponse relisted = putListing(this.manager, product.id, "\"v2\"", "AVAILABLE");

		assertThat(JsonPath.<String>read(relisted.getContentAsString(), "$.listing.availabilityState"))
			.isEqualTo("SOLD_OUT");
		assertThat(heldState("cat_branch_product_listing", product.id)).isNull();
	}

	@Test
	void puttingAvailableOnAPausedListingKeepsItPaused() throws Exception {
		Product product = product(1);
		assertThat(putListing(this.manager, product.id, "\"v0\"", "AVAILABLE").getStatus()).isEqualTo(200);
		seedProductState(product.id, "PAUSED");

		MockHttpServletResponse same = putListing(this.manager, product.id, "\"v1\"", "AVAILABLE");

		assertThat(JsonPath.<String>read(same.getContentAsString(), "$.listing.availabilityState")).isEqualTo("PAUSED");
		assertThat(same.getHeader("ETag")).isEqualTo("\"v1\"");
	}

	// --- variant listing (AD2, AD4, W2) -------------------------------------------------------------------------

	@Test
	void setsAFullBranchPriceForAVariantAndAuditsIt() throws Exception {
		Product product = product(2);
		assertThat(putListing(this.manager, product.id, "\"v0\"", "AVAILABLE").getStatus()).isEqualTo(200);

		MockHttpServletResponse priced = putVariant(product.id, product.variants.get(1), "\"v1\"",
				"{\"price\": 45000, \"availabilityState\": \"AVAILABLE\"}");

		assertThat(priced.getStatus()).as(priced.getContentAsString()).isEqualTo(200);
		assertThat(priced.getHeader("ETag")).isEqualTo("\"v2\"");
		String body = rowOf(product.id);
		assertThat(JsonPath.<List<Object>>read(body, "$.listing.variants[*].price")).containsExactly(null, 45000);
		assertThat(JsonPath.<List<Integer>>read(body, "$.listing.variants[*].effectivePrice"))
			.containsExactly(30000, 45000);
		List<Map<String, Object>> audits = audits("BRANCH_VARIANT_LISTING_PUT");
		assertThat(audits).hasSize(1);
		Map<String, Object> oldValue = JsonPath.read((String) audits.getFirst().get("old_value"), "$");
		Map<String, Object> newValue = JsonPath.read((String) audits.getFirst().get("new_value"), "$");
		assertThat(oldValue).containsOnlyKeys("variantId", "price").containsEntry("price", null);
		assertThat(newValue).containsEntry("variantId", product.variants.get(1)).containsEntry("price", 45000);

		MockHttpServletResponse back = putVariant(product.id, product.variants.get(1), "\"v2\"",
				"{\"price\": null, \"availabilityState\": \"AVAILABLE\"}");
		assertThat(JsonPath.<List<Integer>>read(back.getContentAsString(), "$.listing.variants[*].effectivePrice"))
			.as("null = basePrice")
			.containsExactly(30000, 40000);
	}

	@Test
	void aVariantCanBeTakenOffSaleAlone() throws Exception {
		Product product = product(2);
		assertThat(putListing(this.manager, product.id, "\"v0\"", "AVAILABLE").getStatus()).isEqualTo(200);

		MockHttpServletResponse off = putVariant(product.id, product.variants.get(1), "\"v1\"",
				"{\"price\": null, \"availabilityState\": \"NOT_AVAILABLE\"}");

		assertThat(JsonPath.<List<String>>read(off.getContentAsString(), "$.listing.variants[*].availabilityState"))
			.containsExactly("AVAILABLE", "NOT_AVAILABLE");
	}

	@Test
	void changingABranchPriceKeepsASoldOutVariantSoldOut() throws Exception {
		Product product = product(1);
		String variant = product.variants.getFirst();
		assertThat(putListing(this.manager, product.id, "\"v0\"", "AVAILABLE").getStatus()).isEqualTo(200);
		assertThat(putVariant(product.id, variant, "\"v1\"", "{\"price\": 31000, \"availabilityState\": \"AVAILABLE\"}")
			.getStatus()).isEqualTo(200);
		seedVariantState(product.id, variant, "SOLD_OUT");

		MockHttpServletResponse repriced = putVariant(product.id, variant, "\"v2\"",
				"{\"price\": 32000, \"availabilityState\": \"AVAILABLE\"}");

		assertThat(repriced.getStatus()).as(repriced.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<List<String>>read(rowOf(product.id), "$.listing.variants[*].availabilityState"))
			.containsExactly("SOLD_OUT");
		assertThat(JsonPath.<List<Integer>>read(rowOf(product.id), "$.listing.variants[*].price"))
			.containsExactly(32000);

		putVariant(product.id, variant, "\"v3\"", "{\"price\": 32000, \"availabilityState\": \"NOT_AVAILABLE\"}");
		assertThat(heldState("cat_branch_variant_listing", product.id)).isEqualTo("SOLD_OUT");
	}

	@Test
	void variantListingRules() throws Exception {
		Product product = product(2);
		Product other = product(1);

		assertProblem(putVariant(product.id, product.variants.getFirst(), "\"v0\"",
				"{\"price\": 1, \"availabilityState\": \"AVAILABLE\"}"), 404, "CATALOG_LISTING_NOT_FOUND");
		assertThat(putListing(this.manager, product.id, "\"v0\"", "AVAILABLE").getStatus()).isEqualTo(200);
		assertProblem(putVariant(product.id, other.variants.getFirst(), "\"v1\"",
				"{\"price\": 1, \"availabilityState\": \"AVAILABLE\"}"), 404, "CATALOG_VARIANT_NOT_FOUND");
		MockHttpServletResponse negative = putVariant(product.id, product.variants.getFirst(), "\"v1\"",
				"{\"price\": -1, \"availabilityState\": \"AVAILABLE\"}");
		assertProblem(negative, 422, "CATALOG_PRICE_NEGATIVE");
		assertThat(JsonPath.<List<String>>read(negative.getContentAsString(), "$.errors[*].field"))
			.containsExactly("price");
		assertProblem(putVariant(product.id, product.variants.getFirst(), "\"v1\"",
				"{\"price\": 1, \"availabilityState\": \"SOLD_OUT\"}"), 400, "VALIDATION_FAILED");
		assertProblem(putVariant(product.id, product.variants.getFirst(), "\"v0\"",
				"{\"price\": 1, \"availabilityState\": \"AVAILABLE\"}"), 412, "VERSION_CONFLICT");
		assertProblem(this.auth.perform(variantPut(product.id, product.variants.getFirst(), null,
				"{\"price\": 1, \"availabilityState\": \"AVAILABLE\"}"), this.manager, true), 428,
				"PRECONDITION_REQUIRED");

		assertThat(this.auth.perform(post("/api/backoffice/v1/products/" + product.id + "/variants/"
				+ product.variants.getLast() + "/archive"), this.admin, true).getStatus()).isEqualTo(200);
		assertProblem(putVariant(product.id, product.variants.getLast(), "\"v1\"",
				"{\"price\": 1, \"availabilityState\": \"AVAILABLE\"}"), 404, "CATALOG_VARIANT_NOT_FOUND");
	}

	// --- option item listing (AD3) ------------------------------------------------------------------------------

	@Test
	void setsAnOptionBranchPriceWithV0AndAuditsIt() throws Exception {
		Item item = optionItem();

		MockHttpServletResponse priced = putOption(item.id, "\"v0\"", "{\"extraPrice\": 7000}");

		assertThat(priced.getStatus()).as(priced.getContentAsString()).isEqualTo(200);
		assertThat(priced.getHeader("ETag")).isEqualTo("\"v1\"");
		assertThat(JsonPath.<Integer>read(priced.getContentAsString(), "$.extraPrice")).isEqualTo(7000);
		assertThat(JsonPath.<String>read(priced.getContentAsString(), "$.availabilityState")).isEqualTo("AVAILABLE");
		assertThat(audits("BRANCH_OPTION_ITEM_LISTING_PUT")).hasSize(1);
		assertThat(putOption(item.id, "\"v1\"", "{\"extraPrice\": 7000}").getHeader("ETag")).isEqualTo("\"v1\"");
		assertProblem(putOption(item.id, "\"v0\"", "{\"extraPrice\": 8000}"), 412, "VERSION_CONFLICT");
		assertThat(putOption(item.id, "\"v1\"", "{\"extraPrice\": null}").getHeader("ETag")).isEqualTo("\"v2\"");
	}

	@Test
	void optionListingRules() throws Exception {
		Item item = optionItem();

		assertProblem(putOption(UUID.randomUUID().toString(), "\"v0\"", "{\"extraPrice\": 1}"), 404,
				"CATALOG_OPTION_ITEM_NOT_FOUND");
		MockHttpServletResponse negative = putOption(item.id, "\"v0\"", "{\"extraPrice\": -1}");
		assertProblem(negative, 422, "CATALOG_PRICE_NEGATIVE");
		assertThat(JsonPath.<List<String>>read(negative.getContentAsString(), "$.errors[*].field"))
			.containsExactly("extraPrice");
		assertThat(this.auth.perform(post("/api/backoffice/v1/option-groups/" + item.groupId + "/items/" + item.id
				+ "/archive"), this.admin, true).getStatus()).isEqualTo(200);
		assertProblem(putOption(item.id, "\"v0\"", "{\"extraPrice\": 1}"), 409, "CATALOG_OPTION_ARCHIVED");
	}

	// --- list (AD6) ---------------------------------------------------------------------------------------------

	@Test
	void listShowsEveryNonArchivedProductWithItsListingOrNull() throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 6);
		Product listed = product(1, "A " + tag);
		Product notListed = product(1, "B " + tag);
		Product archived = product(1, "C " + tag);
		assertThat(this.auth.perform(post("/api/backoffice/v1/products/" + archived.id + "/archive"), this.admin,
				true).getStatus()).isEqualTo(200);
		assertThat(putListing(this.manager, listed.id, "\"v0\"", "AVAILABLE").getStatus()).isEqualTo(200);

		MockHttpServletResponse page = this.auth.perform(get(BRANCHES + "/" + this.branchId + "/listings")
			.param("q", tag), this.manager, false);

		assertThat(page.getStatus()).as(page.getContentAsString()).isEqualTo(200);
		String body = page.getContentAsString();
		assertThat(JsonPath.<List<String>>read(body, "$.items[*].productId")).containsExactly(listed.id, notListed.id);
		assertThat(JsonPath.<String>read(body, "$.items[0].listing.availabilityState")).isEqualTo("AVAILABLE");
		assertThat(JsonPath.<Integer>read(body, "$.items[0].listing.version")).isEqualTo(1);
		assertThat(JsonPath.<Object>read(body, "$.items[1].listing")).isNull();
		assertThat(JsonPath.<Integer>read(body, "$.page.totalItems")).isEqualTo(2);
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	record Product(String id, List<String> variants) {
	}

	record Item(String groupId, String id) {
	}

	private Product product(int variants) throws Exception {
		return product(variants, "Bán tại quán");
	}

	private Product product(int variants, String name) throws Exception {
		String code = ("bl-" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase();
		StringBuilder items = new StringBuilder();
		for (int i = 0; i < variants; i++) {
			items.append((i > 0) ? ", " : "")
				.append("{\"sku\": \"%s-%d\", \"name\": \"S%d\", \"basePrice\": %d, \"isDefault\": %b}".formatted(code, i,
						i, 30000 + i * 10000, i == 0));
		}
		MockHttpServletResponse created = this.auth.perform(post("/api/backoffice/v1/products")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"productCode": "%s", "name": "%s", "slug": "%s", "variants": [%s]}
					""".formatted(code, name, code.toLowerCase(), items)), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		String body = created.getContentAsString();
		return new Product(JsonPath.read(body, "$.productId"), JsonPath.read(body, "$.variants[*].variantId"));
	}

	private Item optionItem() throws Exception {
		String code = ("blo-" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase();
		MockHttpServletResponse created = this.auth.perform(post("/api/backoffice/v1/option-groups")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"code": "%s", "name": "Sữa", "selectionType": "SINGLE", "minSelect": 0, "maxSelect": 1,
					 "items": [{"code": "OAT", "name": "Sữa yến mạch", "defaultExtraPrice": 5000}]}
					""".formatted(code)), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		String body = created.getContentAsString();
		return new Item(JsonPath.read(body, "$.optionGroupId"), JsonPath.read(body, "$.items[0].optionItemId"));
	}

	private String createBranch() throws Exception {
		String code = "L-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		MockHttpServletResponse created = this.auth.perform(post(BRANCHES).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"branchCode": "%s", "name": "HaYi bán", "address": {"line": "1 Lê Lợi", "province": "TP.HCM"}}
					""".formatted(code)), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return JsonPath.read(created.getContentAsString(), "$.branchId");
	}

	private MockHttpServletRequestBuilder listingPut(String branch, String product, String ifMatch, String state) {
		MockHttpServletRequestBuilder request = put(BRANCHES + "/" + branch + "/listings/" + product)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"availabilityState\": \"" + state + "\"}");
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		return request;
	}

	private MockHttpServletResponse putListing(Session session, String product, String ifMatch, String state)
			throws Exception {
		return this.auth.perform(listingPut(this.branchId, product, ifMatch, state), session, true);
	}

	private MockHttpServletResponse unlist(String product) throws Exception {
		return this.auth.perform(post(BRANCHES + "/" + this.branchId + "/listings/" + product + "/unlist"),
				this.manager, true);
	}

	private MockHttpServletRequestBuilder variantPut(String product, String variant, String ifMatch, String json) {
		MockHttpServletRequestBuilder request = put(
				BRANCHES + "/" + this.branchId + "/listings/" + product + "/variants/" + variant)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json);
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		return request;
	}

	private MockHttpServletResponse putVariant(String product, String variant, String ifMatch, String json)
			throws Exception {
		return this.auth.perform(variantPut(product, variant, ifMatch, json), this.manager, true);
	}

	private MockHttpServletResponse putOption(String item, String ifMatch, String json) throws Exception {
		return this.auth.perform(put(BRANCHES + "/" + this.branchId + "/option-item-listings/" + item)
			.contentType(MediaType.APPLICATION_JSON)
			.header("If-Match", ifMatch)
			.content(json), this.manager, true);
	}

	private String rowOf(String product) throws Exception {
		MockHttpServletResponse page = this.auth.perform(get(BRANCHES + "/" + this.branchId + "/listings")
			.param("size", "100"), this.manager, false);
		assertThat(page.getStatus()).as(page.getContentAsString()).isEqualTo(200);
		List<Map<String, Object>> rows = JsonPath.read(page.getContentAsString(),
				"$.items[?(@.productId == '" + product + "')]");
		assertThat(rows).hasSize(1);
		return JsonPath.parse(rows.getFirst()).jsonString();
	}

	/** A sold-out / paused state as the Sold-out workspace (S9) will set it. */
	private void seedProductState(String product, String state) {
		this.jdbc.sql("UPDATE cat_branch_product_listing SET availability_state = ? WHERE branch_id = ? AND product_id = ?")
			.params(state, UuidBinary.toBytes(UUID.fromString(this.branchId)),
					UuidBinary.toBytes(UUID.fromString(product)))
			.update();
	}

	private void seedVariantState(String product, String variant, String state) {
		this.jdbc.sql("""
				UPDATE cat_branch_variant_listing v JOIN cat_branch_product_listing l ON l.id = v.listing_id
				SET v.availability_state = ? WHERE l.branch_id = ? AND l.product_id = ? AND v.variant_id = ?
				""")
			.params(state, UuidBinary.toBytes(UUID.fromString(this.branchId)),
					UuidBinary.toBytes(UUID.fromString(product)), UuidBinary.toBytes(UUID.fromString(variant)))
			.update();
	}

	private String heldState(String table, String product) {
		String sql = table.equals("cat_branch_product_listing")
				? "SELECT held_state FROM cat_branch_product_listing WHERE branch_id = ? AND product_id = ?"
				: "SELECT v.held_state FROM cat_branch_variant_listing v JOIN cat_branch_product_listing l"
						+ " ON l.id = v.listing_id WHERE l.branch_id = ? AND l.product_id = ?";
		return this.jdbc.sql(sql)
			.params(UuidBinary.toBytes(UUID.fromString(this.branchId)), UuidBinary.toBytes(UUID.fromString(product)))
			.query(String.class)
			.optional()
			.orElse(null);
	}

	private List<Map<String, Object>> audits(String action) {
		return this.jdbc
			.sql("SELECT branch_id, CAST(old_value AS CHAR) AS old_value, CAST(new_value AS CHAR) AS new_value"
					+ " FROM aud_audit_log WHERE branch_id = ? AND action = ? ORDER BY id")
			.param(UuidBinary.toBytes(UUID.fromString(this.branchId)))
			.param(action)
			.query()
			.listOfRows();
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

}
