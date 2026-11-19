package vn.hayi.shop.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Instant;
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

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.identity.BackofficeAuth;
import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.identity.IdentityFixtures;
import vn.hayi.shop.platform.persistence.UuidBinary;
import vn.hayi.shop.shared.id.UuidV7;

/**
 * Customers read a Branch's menu without signing in (API 02 §2, Catalog §5.6, Decision F, W3, W6, AH). Sold-out and
 * paused states are seeded as the Sold-out workspace (S9) will set them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class PublicMenuTest {

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

	String branch;

	@BeforeEach
	void setUp() throws Exception {
		this.auth = new BackofficeAuth(this.mvc, this.clock);
		this.admin = this.fixtures.openSession(this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail()));
		this.branch = branch(true);
	}

	// --- shape and visibility (AH1, AH4) ------------------------------------------------------------------------

	@Test
	void theMenuListsSellableProductsUnderTheirCategoriesWithBranchPrices() throws Exception {
		String root = category(null);
		String child = category(root);
		Product latte = product(root, 45000, 55000);
		Product tea = product(child, 30000);
		list(latte.id);
		list(tea.id);
		listVariant(latte, latte.variants.get(1), "{\"price\": 40000, \"availabilityState\": \"AVAILABLE\"}");

		MockHttpServletResponse menu = menu("?at=2030-01-07T02:00:00Z");

		assertThat(menu.getStatus()).as(menu.getContentAsString()).isEqualTo(200);
		assertThat(menu.getHeader("Cache-Control")).isEqualTo("max-age=30, public");
		String body = menu.getContentAsString();
		assertThat(JsonPath.<String>read(body, "$.branchId")).isEqualTo(this.branch);
		assertThat(JsonPath.<String>read(body, "$.evaluatedAt")).isEqualTo("2030-01-07T02:00:00Z");
		assertThat(JsonPath.<List<Object>>read(body, "$.collections")).isEmpty();
		Map<String, Object> rootNode = node(body, root);
		assertThat(JsonPath.<List<String>>read(rootNode, "$.products[*].productId")).containsExactly(latte.id);
		assertThat(JsonPath.<List<Integer>>read(rootNode, "$.products[*].fromPrice")).as("Branch price applied")
			.containsExactly(40000);
		assertThat(JsonPath.<List<String>>read(rootNode, "$.products[*].availability")).containsExactly("AVAILABLE");
		assertThat(JsonPath.<List<Object>>read(rootNode, "$.products[*].primaryImageUrl")).containsExactly((Object) null);
		assertThat(JsonPath.<List<Object>>read(rootNode, "$.products[0].tags")).isEmpty();
		assertThat(JsonPath.<List<String>>read(rootNode, "$.children[*].categoryId")).containsExactly(child);
		assertThat(JsonPath.<List<String>>read(rootNode, "$.children[0].products[*].productId"))
			.containsExactly(tea.id);
	}

	@Test
	void draftUnlistedAndFullyUnlistedProductsAndEmptyCategoriesAreHidden() throws Exception {
		String shown = category(null);
		String empty = category(null);
		Product active = product(shown, 10000);
		Product draft = draft(shown, 10000);
		Product unlisted = product(shown, 10000);
		Product sizesOff = product(shown, 10000);
		list(active.id);
		list(draft.id);
		list(unlisted.id);
		unlist(unlisted.id);
		list(sizesOff.id);
		listVariant(sizesOff, sizesOff.variants.getFirst(), "{\"price\": null, \"availabilityState\": \"NOT_AVAILABLE\"}");

		String body = menu("").getContentAsString();

		assertThat(JsonPath.<List<String>>read(node(body, shown), "$.products[*].productId"))
			.containsExactly(active.id);
		assertThat(JsonPath.<List<String>>read(body, "$.categories[*].categoryId")).doesNotContain(empty);
	}

	@Test
	void anUnknownOrInactiveBranchIsNotFound() throws Exception {
		String draftBranch = branch(false);

		assertProblem(menuOf(UUID.randomUUID().toString(), ""), 404, "BRANCH_NOT_FOUND");
		assertProblem(menuOf(draftBranch, ""), 404, "BRANCH_NOT_FOUND");
		assertThat(menu("?at=not-a-time").getStatus()).isEqualTo(400);
	}

	@Test
	void thePrimaryImageIsShown() throws Exception {
		String category = category(null);
		Product product = product(category, 10000);
		list(product.id);
		UUID object = UuidV7.generate();
		this.jdbc.sql("""
				INSERT INTO sto_stored_object (id, provider, bucket, object_key, purpose, status, visibility, created_at,
				                               updated_at)
				VALUES (?, 'S3', 'hayi-public', ?, 'PRODUCT_IMAGE', 'ACTIVE', 'PUBLIC', NOW(6), NOW(6))
				""").params(UuidBinary.toBytes(object), "product/2030/01/" + object + ".png").update();
		this.jdbc.sql("""
				INSERT INTO cat_product_image (id, product_id, stored_object_id, sort_order, is_primary, created_at)
				VALUES (?, ?, ?, 0, 1, NOW(6))
				""").params(UuidBinary.toBytes(UuidV7.generate()), UuidBinary.toBytes(UUID.fromString(product.id)),
					UuidBinary.toBytes(object)).update();

		String url = JsonPath.<List<String>>read(node(menu("").getContentAsString(), category),
				"$.products[*].primaryImageUrl").getFirst();

		assertThat(url).endsWith("/hayi-public/product/2030/01/" + object + ".png");
	}

	// --- derived availability (AH2, AH3) ------------------------------------------------------------------------

	@Test
	void aManualStateOfTheListingIsShown() throws Exception {
		String category = category(null);
		Product soldOut = product(category, 10000);
		Product paused = product(category, 10000);
		list(soldOut.id);
		list(paused.id);
		seedListing(soldOut.id, "SOLD_OUT");
		seedListing(paused.id, "PAUSED");

		Map<String, Object> node = node(menu("").getContentAsString(), category);

		assertThat(availability(node, soldOut.id)).isEqualTo("SOLD_OUT");
		assertThat(availability(node, paused.id)).isEqualTo("PAUSED");
	}

	@Test
	void everySizeSoldOutMakesTheProductSoldOutAtItsLowestSoldOutPrice() throws Exception {
		String category = category(null);
		Product all = product(category, 30000, 40000);
		Product one = product(category, 30000, 40000);
		list(all.id);
		list(one.id);
		// a Branch price (same as the base) creates the Variant row the seed then marks
		listVariant(all, all.variants.get(0), "{\"price\": 30000, \"availabilityState\": \"AVAILABLE\"}");
		listVariant(all, all.variants.get(1), "{\"price\": 40000, \"availabilityState\": \"AVAILABLE\"}");
		seedVariant(all, all.variants.get(0), "SOLD_OUT");
		seedVariant(all, all.variants.get(1), "PAUSED");
		listVariant(one, one.variants.get(0), "{\"price\": 30000, \"availabilityState\": \"AVAILABLE\"}");
		seedVariant(one, one.variants.get(0), "SOLD_OUT");

		Map<String, Object> node = node(menu("").getContentAsString(), category);

		assertThat(availability(node, all.id)).isEqualTo("SOLD_OUT");
		assertThat(fromPrice(node, all.id)).isEqualTo(30000);
		assertThat(availability(node, one.id)).isEqualTo("AVAILABLE");
		assertThat(fromPrice(node, one.id)).as("only the AVAILABLE size counts").isEqualTo(40000);
	}

	@Test
	void aRequiredOptionGroupWithNothingLeftMakesTheProductSoldOut() throws Exception {
		String category = category(null);
		Group required = group(1, "SINGLE", 1);
		Group optional = group(0, "SINGLE", 1);
		Product needsMilk = product(category, 10000);
		Product optionalOnly = product(category, 10000);
		attach(needsMilk.id, required.id);
		attach(optionalOnly.id, optional.id);
		list(needsMilk.id);
		list(optionalOnly.id);
		soldOutOption(required.items.getFirst());
		soldOutOption(optional.items.getFirst());

		Map<String, Object> node = node(menu("").getContentAsString(), category);

		assertThat(availability(node, needsMilk.id)).isEqualTo("SOLD_OUT");
		assertThat(availability(node, optionalOnly.id)).as("an optional group never blocks").isEqualTo("AVAILABLE");
	}

	@Test
	void hiddenAndArchivedItemsAreNotSellable() throws Exception {
		String category = category(null);
		Group milk = group(1, "SINGLE", 2);
		Group ice = group(1, "SINGLE", 2);
		Product hiding = product(category, 10000);
		Product archiving = product(category, 10000);
		attach(hiding.id, milk.id);
		attach(archiving.id, ice.id);
		list(hiding.id);
		list(archiving.id);
		overrides(hiding.id, "{\"overrides\": [{\"optionItemId\": \"" + milk.items.get(0) + "\", \"isHidden\": true}]}");
		soldOutOption(milk.items.get(1));
		assertThat(this.auth.perform(post("/api/backoffice/v1/option-groups/" + ice.id + "/items/" + ice.items.get(0)
				+ "/archive"), this.admin, true).getStatus()).isEqualTo(200);
		soldOutOption(ice.items.get(1));

		Map<String, Object> node = node(menu("").getContentAsString(), category);
		assertThat(availability(node, hiding.id)).as("one item hidden, the other sold out").isEqualTo("SOLD_OUT");
		assertThat(availability(node, archiving.id)).as("one item archived, the other sold out").isEqualTo("SOLD_OUT");

		overrides(hiding.id, "{\"overrides\": []}");
		assertThat(availability(node(menu("").getContentAsString(), category), hiding.id))
			.as("unhidden again")
			.isEqualTo("AVAILABLE");
	}

	@Test
	void aRequiredGroupArchivedAfterAttachingMakesTheProductSoldOut() throws Exception {
		String category = category(null);
		Group required = group(1, "SINGLE", 2);
		Product product = product(category, 10000);
		attach(product.id, required.id);
		list(product.id);
		assertThat(this.auth.perform(post("/api/backoffice/v1/option-groups/" + required.id + "/archive"), this.admin,
				true).getStatus()).isEqualTo(200);

		assertThat(availability(node(menu("").getContentAsString(), category), product.id)).isEqualTo("SOLD_OUT");
	}

	// --- product detail (AI) ------------------------------------------------------------------------------------

	@Test
	void theDetailShowsSizesAndOptionsWithTheirPriceLayers() throws Exception {
		String category = category(null);
		Product product = product(category, 45000, 55000);
		list(product.id);
		listVariant(product, product.variants.get(1), "{\"price\": 52000, \"availabilityState\": \"AVAILABLE\"}");
		MockHttpServletResponse created = this.auth.perform(post("/api/backoffice/v1/option-groups")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"code": "%s", "name": "Sữa", "selectionType": "SINGLE", "minSelect": 1, "maxSelect": 1,
					 "items": [{"code": "TUOI", "name": "Sữa tươi", "defaultExtraPrice": 0, "isDefaultSelected": true},
					           {"code": "OAT", "name": "Yến mạch", "defaultExtraPrice": 10000},
					           {"code": "HAT", "name": "Hạnh nhân", "defaultExtraPrice": 12000},
					           {"code": "AN", "name": "Ẩn", "defaultExtraPrice": 1}]}
					""".formatted(("mnd-" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase())), this.admin,
				true);
		String groupId = JsonPath.read(created.getContentAsString(), "$.optionGroupId");
		List<String> items = JsonPath.read(created.getContentAsString(), "$.items[*].optionItemId");
		attach(product.id, groupId);
		overrides(product.id, """
				{"overrides": [{"optionItemId": "%s", "extraPriceOverride": 8000, "isHidden": false},
				               {"optionItemId": "%s", "extraPriceOverride": 11000, "isHidden": false},
				               {"optionItemId": "%s", "isHidden": true}]}
				""".formatted(items.get(1), items.get(2), items.get(3)));
		MockHttpServletResponse branchPrice = this.auth.perform(put("/api/backoffice/v1/branches/" + this.branch
				+ "/option-item-listings/" + items.get(2))
			.contentType(MediaType.APPLICATION_JSON)
			.header("If-Match", "\"v0\"")
			.content("{\"extraPrice\": 9000}"), this.admin, true);
		assertThat(branchPrice.getStatus()).as(branchPrice.getContentAsString()).isEqualTo(200);
		soldOutOption(items.get(0));

		MockHttpServletResponse detail = productDetail(product.id);

		assertThat(detail.getStatus()).as(detail.getContentAsString()).isEqualTo(200);
		assertThat(detail.getHeader("Cache-Control")).isEqualTo("max-age=30, public");
		String body = detail.getContentAsString();
		assertThat(JsonPath.<String>read(body, "$.availability")).isEqualTo("AVAILABLE");
		assertThat(JsonPath.<List<Integer>>read(body, "$.variants[*].price")).containsExactly(45000, 52000);
		assertThat(JsonPath.<List<Boolean>>read(body, "$.variants[*].isDefault")).containsExactly(true, false);
		assertThat(JsonPath.<List<String>>read(body, "$.optionGroups[0].items[*].optionItemId"))
			.as("the hidden item is not shown")
			.containsExactly(items.get(0), items.get(1), items.get(2));
		assertThat(JsonPath.<List<Integer>>read(body, "$.optionGroups[0].items[*].extraPrice"))
			.as("library, Product override, Branch price")
			.containsExactly(0, 8000, 9000);
		assertThat(JsonPath.<Boolean>read(body, "$.optionGroups[0].items[0].isDefaultSelected")).isTrue();
		assertThat(JsonPath.<String>read(body, "$.optionGroups[0].items[0].availability"))
			.as("the default is out but stays the default")
			.isEqualTo("SOLD_OUT");
		assertThat(JsonPath.<Integer>read(body, "$.optionGroups[0].minSelect")).isEqualTo(1);
		assertThat(JsonPath.<Object>read(body, "$.sellingSchedule")).isNull();

		String slug = JsonPath.read(body, "$.slug");
		assertThat(JsonPath.<String>read(productDetail(slug).getContentAsString(), "$.productId"))
			.isEqualTo(product.id);
	}

	@Test
	void aStoppedProductStopsEverySizeAndUnlistedSizesAreHidden() throws Exception {
		String category = category(null);
		Product product = product(category, 30000, 40000, 50000);
		list(product.id);
		listVariant(product, product.variants.get(2), "{\"price\": null, \"availabilityState\": \"NOT_AVAILABLE\"}");
		seedListing(product.id, "PAUSED");

		String body = productDetail(product.id).getContentAsString();

		assertThat(JsonPath.<String>read(body, "$.availability")).isEqualTo("PAUSED");
		assertThat(JsonPath.<List<String>>read(body, "$.variants[*].variantId"))
			.containsExactly(product.variants.get(0), product.variants.get(1));
		assertThat(JsonPath.<List<String>>read(body, "$.variants[*].availability")).containsExactly("PAUSED", "PAUSED");
	}

	@Test
	void anArchivedGroupIsNotShown() throws Exception {
		String category = category(null);
		Group optional = group(0, "SINGLE", 1);
		Product product = product(category, 10000);
		attach(product.id, optional.id);
		list(product.id);
		assertThat(this.auth.perform(post("/api/backoffice/v1/option-groups/" + optional.id + "/archive"), this.admin,
				true).getStatus()).isEqualTo(200);

		String body = productDetail(product.id).getContentAsString();

		assertThat(JsonPath.<List<Object>>read(body, "$.optionGroups")).isEmpty();
		assertThat(JsonPath.<String>read(body, "$.availability")).as("the group was optional").isEqualTo("AVAILABLE");
	}

	@Test
	void aProductNotOnTheMenuIsNotFound() throws Exception {
		String category = category(null);
		Product unlisted = product(category, 10000);
		Product draft = draft(category, 10000);
		list(draft.id);
		Product listed = product(category, 10000);
		list(listed.id);

		assertProblem(productDetail(unlisted.id), 404, "CATALOG_PRODUCT_NOT_FOUND");
		assertProblem(productDetail(draft.id), 404, "CATALOG_PRODUCT_NOT_FOUND");
		assertProblem(productDetail("khong-co-mon-nay"), 404, "CATALOG_PRODUCT_NOT_FOUND");
		assertProblem(productDetail(UUID.randomUUID().toString()), 404, "CATALOG_PRODUCT_NOT_FOUND");
		assertProblem(this.mvc.perform(get("/api/public/v1/branches/" + branch(false) + "/products/" + listed.id))
			.andReturn()
			.getResponse(), 404, "BRANCH_NOT_FOUND");
	}

	private MockHttpServletResponse productDetail(String idOrSlug) throws Exception {
		return this.mvc.perform(get("/api/public/v1/branches/" + this.branch + "/products/" + idOrSlug))
			.andReturn()
			.getResponse();
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	record Product(String id, List<String> variants) {
	}

	record Group(String id, List<String> items) {
	}

	private MockHttpServletResponse menu(String query) throws Exception {
		return menuOf(this.branch, query);
	}

	private MockHttpServletResponse menuOf(String branchId, String query) throws Exception {
		return this.mvc.perform(get("/api/public/v1/branches/" + branchId + "/menu" + query)).andReturn().getResponse();
	}

	private static Map<String, Object> node(String body, String categoryId) {
		List<Map<String, Object>> roots = JsonPath.read(body, "$.categories[?(@.categoryId == '" + categoryId + "')]");
		assertThat(roots).as(body).hasSize(1);
		return roots.getFirst();
	}

	private static String availability(Map<String, Object> node, String productId) {
		return JsonPath.<List<String>>read(node, "$.products[?(@.productId == '" + productId + "')].availability")
			.getFirst();
	}

	private static int fromPrice(Map<String, Object> node, String productId) {
		return JsonPath.<List<Integer>>read(node, "$.products[?(@.productId == '" + productId + "')].fromPrice")
			.getFirst();
	}

	private String branch(boolean active) throws Exception {
		String code = "M-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		MockHttpServletResponse created = this.auth.perform(post("/api/backoffice/v1/branches")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"branchCode": "%s", "name": "HaYi menu", "address": {"line": "1 Lê Lợi", "province": "TP.HCM"}}
					""".formatted(code)), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		String id = JsonPath.read(created.getContentAsString(), "$.branchId");
		if (active) {
			this.jdbc.sql("UPDATE brn_branch SET status = 'ACTIVE' WHERE id = ?")
				.param(UuidBinary.toBytes(UUID.fromString(id)))
				.update();
		}
		return id;
	}

	private String category(String parentId) throws Exception {
		String parent = (parentId != null) ? ", \"parentId\": \"" + parentId + "\"" : "";
		MockHttpServletResponse created = this.auth.perform(post("/api/backoffice/v1/categories")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"slug\": \"mn-" + UUID.randomUUID().toString().substring(0, 8) + "\", \"name\": \"Menu\""
					+ parent + "}"), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return JsonPath.read(created.getContentAsString(), "$.categoryId");
	}

	/** An {@code ACTIVE} Product in {@code categoryId} with one Variant per price, the first one the default. */
	private Product product(String categoryId, long... prices) throws Exception {
		Product product = draft(categoryId, prices);
		MockHttpServletResponse activated = this.auth.perform(
				post("/api/backoffice/v1/products/" + product.id + "/activate"), this.admin, true);
		assertThat(activated.getStatus()).as(activated.getContentAsString()).isEqualTo(200);
		return product;
	}

	private Product draft(String categoryId, long... prices) throws Exception {
		String code = ("mn-" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase();
		StringBuilder variants = new StringBuilder();
		for (int i = 0; i < prices.length; i++) {
			variants.append((i > 0) ? ", " : "")
				.append("{\"sku\": \"%s-%d\", \"name\": \"S%d\", \"basePrice\": %d, \"isDefault\": %b}".formatted(code, i,
						i, prices[i], i == 0));
		}
		MockHttpServletResponse created = this.auth.perform(post("/api/backoffice/v1/products")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"productCode": "%s", "name": "Món %s", "slug": "%s", "primaryCategoryId": "%s", "variants": [%s]}
					""".formatted(code, code, code.toLowerCase(), categoryId, variants)), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		String body = created.getContentAsString();
		return new Product(JsonPath.read(body, "$.productId"), JsonPath.read(body, "$.variants[*].variantId"));
	}

	private Group group(int min, String type, int items) throws Exception {
		String code = ("mng-" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase();
		StringBuilder list = new StringBuilder();
		for (int i = 0; i < items; i++) {
			list.append((i > 0) ? ", " : "").append("{\"code\": \"I").append(i).append("\", \"name\": \"Item\"}");
		}
		MockHttpServletResponse created = this.auth.perform(post("/api/backoffice/v1/option-groups")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"code": "%s", "name": "Nhóm", "selectionType": "%s", "minSelect": %d, "maxSelect": 1, "items": [%s]}
					""".formatted(code, type, min, list)), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		String body = created.getContentAsString();
		return new Group(JsonPath.read(body, "$.optionGroupId"), JsonPath.read(body, "$.items[*].optionItemId"));
	}

	private void attach(String productId, String groupId) throws Exception {
		String etag = this.auth.perform(get("/api/backoffice/v1/products/" + productId), this.admin, false)
			.getHeader("ETag");
		MockHttpServletResponse attached = this.auth.perform(put("/api/backoffice/v1/products/" + productId
				+ "/option-groups")
			.contentType(MediaType.APPLICATION_JSON)
			.header("If-Match", etag)
			.content("{\"groups\": [{\"optionGroupId\": \"" + groupId + "\"}]}"), this.admin, true);
		assertThat(attached.getStatus()).as(attached.getContentAsString()).isEqualTo(200);
	}

	private void overrides(String productId, String json) throws Exception {
		String etag = this.auth.perform(get("/api/backoffice/v1/products/" + productId), this.admin, false)
			.getHeader("ETag");
		MockHttpServletResponse put = this.auth.perform(put("/api/backoffice/v1/products/" + productId
				+ "/option-item-overrides")
			.contentType(MediaType.APPLICATION_JSON)
			.header("If-Match", etag)
			.content(json), this.admin, true);
		assertThat(put.getStatus()).as(put.getContentAsString()).isEqualTo(200);
	}

	private void list(String productId) throws Exception {
		MockHttpServletResponse listed = this.auth.perform(put("/api/backoffice/v1/branches/" + this.branch
				+ "/listings/" + productId)
			.contentType(MediaType.APPLICATION_JSON)
			.header("If-Match", "\"v0\"")
			.content("{\"availabilityState\": \"AVAILABLE\"}"), this.admin, true);
		assertThat(listed.getStatus()).as(listed.getContentAsString()).isEqualTo(200);
	}

	private void unlist(String productId) throws Exception {
		assertThat(this.auth.perform(post("/api/backoffice/v1/branches/" + this.branch + "/listings/" + productId
				+ "/unlist"), this.admin, true).getStatus()).isEqualTo(200);
	}

	private void listVariant(Product product, String variantId, String json) throws Exception {
		long version = this.jdbc.sql("SELECT version FROM cat_branch_product_listing WHERE branch_id = ? AND product_id = ?")
			.params(UuidBinary.toBytes(UUID.fromString(this.branch)), UuidBinary.toBytes(UUID.fromString(product.id)))
			.query(Long.class)
			.single();
		MockHttpServletResponse put = this.auth.perform(put("/api/backoffice/v1/branches/" + this.branch
				+ "/listings/" + product.id + "/variants/" + variantId)
			.contentType(MediaType.APPLICATION_JSON)
			.header("If-Match", "\"v" + version + "\"")
			.content(json), this.admin, true);
		assertThat(put.getStatus()).as(put.getContentAsString()).isEqualTo(200);
	}

	private void seedListing(String productId, String state) {
		this.jdbc.sql("UPDATE cat_branch_product_listing SET availability_state = ? WHERE branch_id = ? AND product_id = ?")
			.params(state, UuidBinary.toBytes(UUID.fromString(this.branch)), UuidBinary.toBytes(UUID.fromString(productId)))
			.update();
	}

	private void seedVariant(Product product, String variantId, String state) {
		this.jdbc.sql("""
				UPDATE cat_branch_variant_listing v JOIN cat_branch_product_listing l ON l.id = v.listing_id
				SET v.availability_state = ? WHERE l.branch_id = ? AND l.product_id = ? AND v.variant_id = ?
				""")
			.params(state, UuidBinary.toBytes(UUID.fromString(this.branch)),
					UuidBinary.toBytes(UUID.fromString(product.id)), UuidBinary.toBytes(UUID.fromString(variantId)))
			.update();
	}

	private void soldOutOption(String itemId) {
		this.jdbc.sql("""
				INSERT INTO cat_branch_option_item_listing (branch_id, option_item_id, availability_state, updated_at,
				                                            version)
				VALUES (?, ?, 'SOLD_OUT', ?, 1)
				""").params(UuidBinary.toBytes(UUID.fromString(this.branch)),
					UuidBinary.toBytes(UUID.fromString(itemId)), Instant.now()).update();
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

}
