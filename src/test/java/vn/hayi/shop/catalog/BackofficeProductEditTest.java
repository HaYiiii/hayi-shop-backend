package vn.hayi.shop.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
 * Owner/Admin edit a Product and manage its Variants; every change bumps the Product's version (Catalog §5.2, API 08
 * §1.7, Decision AE).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class BackofficeProductEditTest {

	static final String PRODUCTS = "/api/backoffice/v1/products";

	static final MediaType MERGE_PATCH = MediaType.parseMediaType("application/merge-patch+json");

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

	@BeforeEach
	void setUp() {
		this.auth = new BackofficeAuth(this.mvc, this.clock);
		this.admin = this.fixtures.openSession(this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail()));
	}

	// --- PATCH /products/{id} (AE1) -----------------------------------------------------------------------------

	@Test
	void patchChangesOnlySentFieldsAndAuditsTheDiff() throws Exception {
		Product product = product(null);
		String category = category();
		String slug = "sua-" + UUID.randomUUID().toString().substring(0, 8);

		MockHttpServletResponse updated = patchProduct(product.id, "\"v0\"", """
				{"name": "Trà sữa", "slug": "%s", "primaryCategoryId": "%s", "shortDescription": "Ngon",
				 "description": null}
				""".formatted(slug, category));

		assertThat(updated.getStatus()).as(updated.getContentAsString()).isEqualTo(200);
		assertThat(updated.getHeader("ETag")).isEqualTo("\"v1\"");
		String body = detail(product.id).getContentAsString();
		assertThat(JsonPath.<String>read(body, "$.name")).isEqualTo("Trà sữa");
		assertThat(JsonPath.<String>read(body, "$.slug")).isEqualTo(slug);
		assertThat(JsonPath.<String>read(body, "$.primaryCategoryId")).isEqualTo(category);
		assertThat(JsonPath.<String>read(body, "$.shortDescription")).isEqualTo("Ngon");
		List<Map<String, Object>> audits = audits(product.id, "PRODUCT_UPDATE");
		assertThat(audits).hasSize(1);
		Map<String, Object> oldValue = JsonPath.read((String) audits.getFirst().get("old_value"), "$");
		Map<String, Object> newValue = JsonPath.read((String) audits.getFirst().get("new_value"), "$");
		assertThat(oldValue).as("description was already null")
			.containsOnlyKeys("name", "slug", "primaryCategoryId", "shortDescription")
			.containsEntry("name", "Sửa được")
			.containsEntry("primaryCategoryId", null);
		assertThat(newValue).containsEntry("primaryCategoryId", category).containsEntry("shortDescription", "Ngon");
	}

	@Test
	void patchThatChangesNothingKeepsVersionAndIsNotAudited() throws Exception {
		Product product = product(null);

		MockHttpServletResponse same = patchProduct(product.id, "\"v0\"",
				"{\"name\": \"Sửa được\", \"primaryCategoryId\": null}");

		assertThat(same.getStatus()).as(same.getContentAsString()).isEqualTo(200);
		assertThat(same.getHeader("ETag")).isEqualTo("\"v0\"");
		assertThat(audits(product.id, "PRODUCT_UPDATE")).isEmpty();
	}

	@Test
	void patchRequiresTheCurrentVersion() throws Exception {
		Product product = product(null);

		assertProblem(patchProduct(product.id, null, "{\"name\": \"X\"}"), 428, "PRECONDITION_REQUIRED");
		assertProblem(patchProduct(product.id, "\"v3\"", "{\"name\": \"X\"}"), 412, "VERSION_CONFLICT");
	}

	@Test
	void patchRefusesReadOnlyKeysAndBlankRequiredFields() throws Exception {
		Product product = product(null);

		assertInvalid(patchProduct(product.id, "\"v0\"", "{\"productCode\": \"OTHER\"}"), "productCode");
		assertInvalid(patchProduct(product.id, "\"v0\"", "{\"productType\": \"COMBO\"}"), "productType");
		assertInvalid(patchProduct(product.id, "\"v0\"", "{\"status\": \"ACTIVE\"}"), "status");
		assertInvalid(patchProduct(product.id, "\"v0\"", "{\"variants\": []}"), "variants");
		assertInvalid(patchProduct(product.id, "\"v0\"", "{\"name\": null}"), "name");
		assertInvalid(patchProduct(product.id, "\"v0\"", "{\"slug\": \"Không Hợp Lệ\"}"), "slug");
		assertInvalid(patchProduct(product.id, "\"v0\"", "{\"name\": \"" + "x".repeat(161) + "\"}"), "name");
	}

	@Test
	void slugChangeToATakenSlugConflicts() throws Exception {
		Product taken = product(null);
		Product product = product(null);

		assertProblem(patchProduct(product.id, "\"v0\"", "{\"slug\": \"" + taken.slug + "\"}"), 409,
				"CATALOG_SLUG_TAKEN");
	}

	@Test
	void newPrimaryCategoryMustExistAndBeActive() throws Exception {
		Product product = product(null);
		String archived = category();
		assertThat(this.auth.perform(post("/api/backoffice/v1/categories/" + archived + "/archive"), this.admin, true)
			.getStatus()).isEqualTo(200);

		assertProblem(patchProduct(product.id, "\"v0\"", "{\"primaryCategoryId\": \"" + UUID.randomUUID() + "\"}"),
				404, "CATALOG_CATEGORY_NOT_FOUND");
		assertProblem(patchProduct(product.id, "\"v0\"", "{\"primaryCategoryId\": \"" + archived + "\"}"), 409,
				"CATALOG_CATEGORY_ARCHIVED");
	}

	@Test
	void anActiveProductKeepsItsPrimaryCategory() throws Exception {
		Product product = activeProduct();

		MockHttpServletResponse refused = patchProduct(product.id, "\"v1\"", "{\"primaryCategoryId\": null}");

		assertProblem(refused, 422, "PRODUCT_ACTIVATION_INVALID");
		assertThat(JsonPath.<List<String>>read(refused.getContentAsString(), "$.issues[*].code"))
			.containsExactly("PRIMARY_CATEGORY_MISSING");
		assertThat(patchProduct(product.id, "\"v1\"", "{\"primaryCategoryId\": \"" + category() + "\"}").getStatus())
			.as("another ACTIVE Category is fine")
			.isEqualTo(200);
	}

	@Test
	void anArchivedProductCannotBeEditedNorItsVariants() throws Exception {
		Product product = product(null);
		assertThat(this.auth.perform(post(PRODUCTS + "/" + product.id + "/archive"), this.admin, true).getStatus())
			.isEqualTo(200);

		assertProblem(patchProduct(product.id, "\"v1\"", "{\"name\": \"X\"}"), 409,
				"CATALOG_PRODUCT_INVALID_TRANSITION");
		assertProblem(addVariant(product.id, variant("L", 55000, false)), 409, "CATALOG_PRODUCT_INVALID_TRANSITION");
		assertProblem(patchVariant(product.id, product.defaultVariant, "\"v1\"", "{\"name\": \"X\"}"), 409,
				"CATALOG_PRODUCT_INVALID_TRANSITION");
		assertProblem(archiveVariant(product.id, product.defaultVariant), 409, "CATALOG_PRODUCT_INVALID_TRANSITION");
		assertProblem(patchProduct(product.id, "\"v1\"", "{\"primaryCategoryId\": \"" + UUID.randomUUID() + "\"}"),
				409, "CATALOG_PRODUCT_INVALID_TRANSITION");
		assertProblem(addVariant(product.id, "{\"sku\": \"" + product.sku + "\", \"name\": \"L\", \"basePrice\": 1}"),
				409, "CATALOG_PRODUCT_INVALID_TRANSITION");
		assertProblem(archiveVariant(product.id, UUID.randomUUID().toString()), 409,
				"CATALOG_PRODUCT_INVALID_TRANSITION");
	}

	// --- POST /products/{id}/variants (AE2) ---------------------------------------------------------------------

	@Test
	void addsAVariantLastAndBumpsTheProductVersion() throws Exception {
		Product product = product(null);
		String sku = sku();

		MockHttpServletResponse added = addVariant(product.id, """
				{"sku": "%s", "name": "L", "basePrice": 55000}
				""".formatted(sku.toLowerCase()));

		assertThat(added.getStatus()).as(added.getContentAsString()).isEqualTo(201);
		assertThat(added.getHeader("ETag")).isEqualTo("\"v1\"");
		String body = detail(product.id).getContentAsString();
		assertThat(JsonPath.<List<String>>read(body, "$.variants[*].sku")).containsExactly(product.sku, sku);
		assertThat(JsonPath.<List<Integer>>read(body, "$.variants[*].sortOrder")).containsExactly(0, 1);
		assertThat(JsonPath.<List<Boolean>>read(body, "$.variants[*].isDefault")).containsExactly(true, false);
		assertThat(JsonPath.<List<String>>read(body, "$.variants[*].status")).containsExactly("ACTIVE", "ACTIVE");
		List<Map<String, Object>> audits = audits(product.id, "PRODUCT_VARIANT_CREATE");
		assertThat(audits).hasSize(1);
		Map<String, Object> newValue = JsonPath.read((String) audits.getFirst().get("new_value"), "$");
		assertThat(newValue).containsEntry("sku", sku).containsEntry("basePrice", 55000).containsKey("variantId");
	}

	@Test
	void aNewDefaultVariantTakesTheDefaultOver() throws Exception {
		Product product = product(null);

		MockHttpServletResponse added = addVariant(product.id, variant("L", 55000, true));

		assertThat(added.getStatus()).as(added.getContentAsString()).isEqualTo(201);
		assertThat(added.getHeader("ETag")).as("one bump despite writing the old default first").isEqualTo("\"v1\"");
		List<String> ids = JsonPath.read(detail(product.id).getContentAsString(), "$.variants[*].variantId");
		assertThat(JsonPath.<List<Boolean>>read(detail(product.id).getContentAsString(), "$.variants[*].isDefault"))
			.containsExactly(false, true);
		Map<String, Object> audit = audits(product.id, "PRODUCT_VARIANT_CREATE").getFirst();
		assertThat(JsonPath.<String>read((String) audit.get("old_value"), "$.defaultVariantId"))
			.isEqualTo(product.defaultVariant);
		assertThat(JsonPath.<String>read((String) audit.get("new_value"), "$.defaultVariantId"))
			.isEqualTo(ids.getLast());
	}

	@Test
	void addVariantRules() throws Exception {
		Product other = product(null);
		Product product = product(null);

		assertProblem(addVariant(product.id, "{\"sku\": \"" + other.sku + "\", \"name\": \"L\", \"basePrice\": 1}"),
				409, "CATALOG_SKU_TAKEN");
		MockHttpServletResponse negative = addVariant(product.id, variant("L", -1, false));
		assertProblem(negative, 422, "CATALOG_PRICE_NEGATIVE");
		assertThat(JsonPath.<List<String>>read(negative.getContentAsString(), "$.errors[*].field"))
			.containsExactly("basePrice");
		assertProblem(this.auth.perform(post(PRODUCTS + "/" + product.id + "/variants")
			.contentType(MediaType.APPLICATION_JSON)
			.header("If-Match", "\"v7\"")
			.content(variant("L", 1, false)), this.admin, true), 412, "VERSION_CONFLICT");
		assertProblem(addVariant(UUID.randomUUID().toString(), variant("L", 1, false)), 404,
				"CATALOG_PRODUCT_NOT_FOUND");
	}

	@Test
	void atMostTwentyLiveVariants() throws Exception {
		Product product = product(null);
		for (int i = 1; i < 20; i++) {
			assertThat(addVariant(product.id, variant("S" + i, 1000, false)).getStatus()).isEqualTo(201);
		}

		assertProblem(addVariant(product.id, variant("S20", 1000, false)), 409, "CATALOG_VARIANT_LIMIT_REACHED");

		String last = JsonPath.<List<String>>read(detail(product.id).getContentAsString(), "$.variants[*].variantId")
			.getLast();
		assertThat(archiveVariant(product.id, last).getStatus()).isEqualTo(200);
		assertThat(addVariant(product.id, variant("S20", 1000, false)).getStatus())
			.as("archived Variants do not count")
			.isEqualTo(201);
	}

	@Test
	void addingAVariantNeedsCatalogPriceManage() throws Exception {
		Product product = product(null);
		Session noPrice = this.fixtures
			.openSession(CatalogFixtures.catalogManagerWithoutPricePermission(this.jdbc, this.fixtures));

		assertProblem(this.auth.perform(post(PRODUCTS + "/" + product.id + "/variants")
			.contentType(MediaType.APPLICATION_JSON)
			.content(variant("L", 0, false)), noPrice, true), 403, "PERMISSION_DENIED");
	}

	// --- PATCH /products/{id}/variants/{variantId} (AE3) --------------------------------------------------------

	@Test
	void variantPatchChangesNameAndPriceAndAuditsTheDiff() throws Exception {
		Product product = product(null);

		MockHttpServletResponse updated = patchVariant(product.id, product.defaultVariant, "\"v0\"",
				"{\"name\": \"Vừa\", \"basePrice\": 32000}");

		assertThat(updated.getStatus()).as(updated.getContentAsString()).isEqualTo(200);
		assertThat(updated.getHeader("ETag")).isEqualTo("\"v1\"");
		String body = detail(product.id).getContentAsString();
		assertThat(JsonPath.<List<String>>read(body, "$.variants[*].name")).containsExactly("Vừa");
		assertThat(JsonPath.<List<Integer>>read(body, "$.variants[*].basePrice")).containsExactly(32000);
		List<Map<String, Object>> audits = audits(product.id, "PRODUCT_VARIANT_UPDATE");
		assertThat(audits).hasSize(1);
		Map<String, Object> oldValue = JsonPath.read((String) audits.getFirst().get("old_value"), "$");
		Map<String, Object> newValue = JsonPath.read((String) audits.getFirst().get("new_value"), "$");
		assertThat(oldValue).containsOnlyKeys("variantId", "name", "basePrice").containsEntry("basePrice", 30000);
		assertThat(newValue).containsEntry("variantId", product.defaultVariant).containsEntry("basePrice", 32000);
	}

	@Test
	void variantPatchCanMoveTheDefault() throws Exception {
		Product product = product(null);
		assertThat(addVariant(product.id, variant("L", 55000, false)).getStatus()).isEqualTo(201);
		String large = JsonPath.<List<String>>read(detail(product.id).getContentAsString(), "$.variants[*].variantId")
			.getLast();

		MockHttpServletResponse moved = patchVariant(product.id, large, "\"v1\"", "{\"isDefault\": true}");

		assertThat(moved.getStatus()).as(moved.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<List<Boolean>>read(detail(product.id).getContentAsString(), "$.variants[*].isDefault"))
			.containsExactly(false, true);
		Map<String, Object> oldValue = JsonPath
			.read((String) audits(product.id, "PRODUCT_VARIANT_UPDATE").getFirst().get("old_value"), "$");
		assertThat(oldValue).containsEntry("isDefault", false)
			.containsEntry("defaultVariantId", product.defaultVariant);
		assertThat(patchVariant(product.id, large, "\"v2\"", "{\"isDefault\": true}").getHeader("ETag"))
			.as("already the default: no change")
			.isEqualTo("\"v2\"");
	}

	@Test
	void variantPatchThatChangesNothingKeepsVersionAndIsNotAudited() throws Exception {
		Product product = product(null);

		MockHttpServletResponse same = patchVariant(product.id, product.defaultVariant, "\"v0\"",
				"{\"name\": \"Standard\", \"basePrice\": 30000}");

		assertThat(same.getStatus()).as(same.getContentAsString()).isEqualTo(200);
		assertThat(same.getHeader("ETag")).isEqualTo("\"v0\"");
		assertThat(audits(product.id, "PRODUCT_VARIANT_UPDATE")).isEmpty();
	}

	@Test
	void variantPatchRules() throws Exception {
		Product product = product(null);
		Product other = product(null);

		assertInvalid(patchVariant(product.id, product.defaultVariant, "\"v0\"", "{\"isDefault\": false}"),
				"isDefault");
		assertInvalid(patchVariant(product.id, product.defaultVariant, "\"v0\"", "{\"sku\": \"NEW\"}"), "sku");
		assertInvalid(patchVariant(product.id, product.defaultVariant, "\"v0\"", "{\"status\": \"INACTIVE\"}"),
				"status");
		assertInvalid(patchVariant(product.id, product.defaultVariant, "\"v0\"", "{\"basePrice\": null}"),
				"basePrice");
		assertProblem(patchVariant(product.id, product.defaultVariant, "\"v0\"", "{\"basePrice\": -5}"), 422,
				"CATALOG_PRICE_NEGATIVE");
		assertProblem(patchVariant(product.id, other.defaultVariant, "\"v0\"", "{\"name\": \"X\"}"), 404,
				"CATALOG_VARIANT_NOT_FOUND");
		assertProblem(patchVariant(product.id, product.defaultVariant, null, "{\"name\": \"X\"}"), 428,
				"PRECONDITION_REQUIRED");
		assertProblem(patchVariant(product.id, product.defaultVariant, "\"v9\"", "{\"name\": \"X\"}"), 412,
				"VERSION_CONFLICT");
	}

	@Test
	void anArchivedVariantCannotBeEditedOrArchivedAgain() throws Exception {
		Product product = product(null);
		assertThat(addVariant(product.id, variant("L", 55000, false)).getStatus()).isEqualTo(201);
		String large = JsonPath.<List<String>>read(detail(product.id).getContentAsString(), "$.variants[*].variantId")
			.getLast();
		assertThat(archiveVariant(product.id, large).getStatus()).isEqualTo(200);

		assertProblem(patchVariant(product.id, large, "\"v2\"", "{\"name\": \"X\"}"), 409, "CATALOG_VARIANT_ARCHIVED");
		assertProblem(archiveVariant(product.id, large), 409, "CATALOG_VARIANT_ARCHIVED");
	}

	@Test
	void changingTheBasePriceNeedsCatalogPriceManage() throws Exception {
		Product product = product(null);
		Session noPrice = this.fixtures
			.openSession(CatalogFixtures.catalogManagerWithoutPricePermission(this.jdbc, this.fixtures));

		assertProblem(this.auth.perform(variantPatch(product.id, product.defaultVariant, "\"v0\"",
				"{\"basePrice\": 31000}"), noPrice, true), 403, "PERMISSION_DENIED");
		assertProblem(this.auth.perform(variantPatch(product.id, product.defaultVariant, "\"v0\"",
				"{\"basePrice\": -1}"), noPrice, true), 403, "PERMISSION_DENIED");
		MockHttpServletResponse samePrice = this.auth.perform(variantPatch(product.id, product.defaultVariant, "\"v0\"",
				"{\"name\": \"Nhỏ\", \"basePrice\": 30000}"), noPrice, true);
		assertThat(samePrice.getStatus()).as("the current price is not a change").isEqualTo(200);
	}

	// --- POST …/variants/{variantId}/archive (AE4) --------------------------------------------------------------

	@Test
	void archivesANonDefaultVariantAndAuditsIt() throws Exception {
		Product product = product(null);
		assertThat(addVariant(product.id, variant("L", 55000, false)).getStatus()).isEqualTo(201);
		String large = JsonPath.<List<String>>read(detail(product.id).getContentAsString(), "$.variants[*].variantId")
			.getLast();

		MockHttpServletResponse archived = archiveVariant(product.id, large);

		assertThat(archived.getStatus()).as(archived.getContentAsString()).isEqualTo(200);
		assertThat(archived.getHeader("ETag")).isEqualTo("\"v2\"");
		assertThat(JsonPath.<List<String>>read(detail(product.id).getContentAsString(), "$.variants[*].status"))
			.containsExactly("ACTIVE", "ARCHIVED");
		List<Map<String, Object>> audits = audits(product.id, "PRODUCT_VARIANT_ARCHIVE");
		assertThat(audits).hasSize(1);
		Map<String, Object> oldValue = JsonPath.read((String) audits.getFirst().get("old_value"), "$");
		Map<String, Object> newValue = JsonPath.read((String) audits.getFirst().get("new_value"), "$");
		assertThat(oldValue).containsEntry("variantId", large).containsEntry("status", "ACTIVE");
		assertThat(newValue).containsEntry("variantId", large).containsEntry("status", "ARCHIVED");
	}

	@Test
	void archivingAVariantWithAStaleIfMatchConflicts() throws Exception {
		Product product = product(null);
		assertThat(addVariant(product.id, variant("L", 55000, false)).getStatus()).isEqualTo(201);
		String large = JsonPath.<List<String>>read(detail(product.id).getContentAsString(), "$.variants[*].variantId")
			.getLast();

		assertProblem(this.auth.perform(post(PRODUCTS + "/" + product.id + "/variants/" + large + "/archive")
			.header("If-Match", "\"v0\""), this.admin, true), 412, "VERSION_CONFLICT");
	}

	@Test
	void theDefaultVariantCannotBeArchivedWhileOthersRemain() throws Exception {
		Product product = product(null);
		assertThat(addVariant(product.id, variant("L", 55000, false)).getStatus()).isEqualTo(201);

		assertProblem(archiveVariant(product.id, product.defaultVariant), 409, "CATALOG_VARIANT_IS_DEFAULT");
	}

	@Test
	void theLastVariantOfAnActiveProductCannotBeArchived() throws Exception {
		Product product = activeProduct();

		MockHttpServletResponse refused = archiveVariant(product.id, product.defaultVariant);

		assertProblem(refused, 422, "PRODUCT_ACTIVATION_INVALID");
		assertThat(JsonPath.<List<String>>read(refused.getContentAsString(), "$.issues[*].code"))
			.containsExactly("NO_ACTIVE_VARIANT");
	}

	@Test
	void aDraftMayLoseItsLastVariantAndTheNextOneBecomesTheDefault() throws Exception {
		Product product = product(null);

		assertThat(archiveVariant(product.id, product.defaultVariant).getStatus()).isEqualTo(200);
		MockHttpServletResponse added = addVariant(product.id, variant("M", 40000, false));

		assertThat(added.getStatus()).as(added.getContentAsString()).isEqualTo(201);
		assertThat(JsonPath.<List<Boolean>>read(detail(product.id).getContentAsString(), "$.variants[*].isDefault"))
			.as("an archived Variant is never the default")
			.containsExactly(false, true);
	}

	// --- version of the aggregate -------------------------------------------------------------------------------

	@Test
	void aVariantOnlyChangeMakesAnOlderIfMatchStale() throws Exception {
		Product product = product(null);
		assertThat(patchVariant(product.id, product.defaultVariant, "\"v0\"", "{\"name\": \"Vừa\"}").getHeader("ETag"))
			.isEqualTo("\"v1\"");

		assertProblem(patchProduct(product.id, "\"v0\"", "{\"name\": \"X\"}"), 412, "VERSION_CONFLICT");
		assertThat(detail(product.id).getHeader("ETag")).isEqualTo("\"v1\"");
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	record Product(String id, String slug, String sku, String defaultVariant) {
	}

	private Product product(String categoryId) throws Exception {
		String code = ("pe-" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase();
		String category = (categoryId != null) ? ", \"primaryCategoryId\": \"" + categoryId + "\"" : "";
		MockHttpServletResponse created = this.auth.perform(post(PRODUCTS).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"productCode": "%s", "name": "Sửa được", "slug": "%s"%s,
					 "variants": [{"sku": "%s", "name": "Standard", "basePrice": 30000}]}
					""".formatted(code, code.toLowerCase(), category, code)), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		String body = created.getContentAsString();
		return new Product(JsonPath.read(body, "$.productId"), code.toLowerCase(), code,
				JsonPath.read(body, "$.variants[0].variantId"));
	}

	/** An {@code ACTIVE} Product at version 1. */
	private Product activeProduct() throws Exception {
		Product product = product(category());
		MockHttpServletResponse activated = this.auth.perform(post(PRODUCTS + "/" + product.id + "/activate"),
				this.admin, true);
		assertThat(activated.getStatus()).as(activated.getContentAsString()).isEqualTo(200);
		assertThat(activated.getHeader("ETag")).isEqualTo("\"v1\"");
		return product;
	}

	private String category() throws Exception {
		MockHttpServletResponse created = this.auth.perform(post("/api/backoffice/v1/categories")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"slug\": \"pe-" + UUID.randomUUID().toString().substring(0, 8) + "\", \"name\": \"Sửa\"}"),
				this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return JsonPath.read(created.getContentAsString(), "$.categoryId");
	}

	private static String sku() {
		return ("SKU-" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase();
	}

	private static String variant(String name, long price, boolean isDefault) {
		return "{\"sku\": \"" + sku() + "\", \"name\": \"" + name + "\", \"basePrice\": " + price + ", \"isDefault\": "
				+ isDefault + "}";
	}

	private MockHttpServletResponse patchProduct(String product, String ifMatch, String json) throws Exception {
		MockHttpServletRequestBuilder request = patch(PRODUCTS + "/" + product).contentType(MERGE_PATCH).content(json);
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		return this.auth.perform(request, this.admin, true);
	}

	private MockHttpServletResponse addVariant(String product, String json) throws Exception {
		return this.auth.perform(post(PRODUCTS + "/" + product + "/variants").contentType(MediaType.APPLICATION_JSON)
			.content(json), this.admin, true);
	}

	private static MockHttpServletRequestBuilder variantPatch(String product, String variant, String ifMatch,
			String json) {
		MockHttpServletRequestBuilder request = patch(PRODUCTS + "/" + product + "/variants/" + variant)
			.contentType(MERGE_PATCH)
			.content(json);
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		return request;
	}

	private MockHttpServletResponse patchVariant(String product, String variant, String ifMatch, String json)
			throws Exception {
		return this.auth.perform(variantPatch(product, variant, ifMatch, json), this.admin, true);
	}

	private MockHttpServletResponse archiveVariant(String product, String variant) throws Exception {
		return this.auth.perform(post(PRODUCTS + "/" + product + "/variants/" + variant + "/archive"), this.admin,
				true);
	}

	private MockHttpServletResponse detail(String product) throws Exception {
		MockHttpServletResponse response = this.auth.perform(get(PRODUCTS + "/" + product), this.admin, false);
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
		return response;
	}

	private List<Map<String, Object>> audits(String id, String action) {
		return this.jdbc
			.sql("SELECT CAST(old_value AS CHAR) AS old_value, CAST(new_value AS CHAR) AS new_value"
					+ " FROM aud_audit_log WHERE entity_id = ? AND action = ? ORDER BY id")
			.param(UuidBinary.toBytes(UUID.fromString(id)))
			.param(action)
			.query()
			.listOfRows();
	}

	private static void assertInvalid(MockHttpServletResponse response, String field) throws Exception {
		assertProblem(response, 400, "VALIDATION_FAILED");
		assertThat(JsonPath.<List<String>>read(response.getContentAsString(), "$.errors[*].field")).contains(field);
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

}
