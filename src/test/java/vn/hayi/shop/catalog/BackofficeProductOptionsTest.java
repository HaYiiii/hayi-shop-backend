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

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.identity.BackofficeAuth;
import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.identity.IdentityFixtures;
import vn.hayi.shop.platform.persistence.UuidBinary;

/**
 * Owner/Admin attach option groups to a Product and override item prices or hide items (Catalog §5.4, API 08 §1.6,
 * Decision W4, AC).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class BackofficeProductOptionsTest {

	static final String PRODUCTS = "/api/backoffice/v1/products";

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

	// --- option groups ------------------------------------------------------------------------------------------

	@Test
	void replacesTheGroupsInOrderWithEffectiveMinMaxAndAuditsIt() throws Exception {
		String product = product();
		Group milk = group("SINGLE", 1, 1, 2);
		Group topping = group("MULTIPLE", 0, 3, 3);

		MockHttpServletResponse replaced = putGroups(product, "\"v0\"", """
				{"groups": [{"optionGroupId": "%s", "maxSelectOverride": 2},
				            {"optionGroupId": "%s", "minSelectOverride": null}]}
				""".formatted(topping.id, milk.id));

		assertThat(replaced.getStatus()).as(replaced.getContentAsString()).isEqualTo(200);
		assertThat(replaced.getHeader("ETag")).isEqualTo("\"v1\"");
		String body = replaced.getContentAsString();
		assertThat(JsonPath.<List<String>>read(body, "$.optionGroups[*].optionGroupId"))
			.containsExactly(topping.id, milk.id);
		assertThat(JsonPath.<List<Integer>>read(body, "$.optionGroups[*].maxSelect")).containsExactly(2, 1);
		assertThat(JsonPath.<List<Integer>>read(body, "$.optionGroups[*].maxSelectOverride")).containsExactly(2, null);
		assertThat(JsonPath.<List<Integer>>read(body, "$.optionGroups[*].sortOrder")).containsExactly(0, 1);
		assertThat(audits(product, "PRODUCT_OPTION_GROUPS_REPLACE")).hasSize(1);
		assertThat(JsonPath.<List<String>>read(detail(product).getContentAsString(), "$.optionGroups[*].code"))
			.containsExactly(topping.code, milk.code);

		MockHttpServletResponse same = putGroups(product, "\"v1\"", """
				{"groups": [{"optionGroupId": "%s", "maxSelectOverride": 2}, {"optionGroupId": "%s"}]}
				""".formatted(topping.id, milk.id));
		assertThat(same.getHeader("ETag")).as("no change").isEqualTo("\"v1\"");
		assertThat(audits(product, "PRODUCT_OPTION_GROUPS_REPLACE")).hasSize(1);
	}

	@Test
	void groupsMustExistBeActiveAndKeepValidLimits() throws Exception {
		String product = product();
		Group single = group("SINGLE", 0, 1, 2);
		Group archived = group("MULTIPLE", 0, 2, 2);
		assertThat(this.auth.perform(post("/api/backoffice/v1/option-groups/" + archived.id + "/archive"), this.admin,
				true).getStatus()).isEqualTo(200);

		assertProblem(putGroups(product, "\"v0\"", one(UUID.randomUUID().toString(), "")), 404,
				"CATALOG_OPTION_GROUP_NOT_FOUND");
		assertProblem(putGroups(product, "\"v0\"", one(archived.id, "")), 409, "CATALOG_OPTION_ARCHIVED");
		assertProblem(putGroups(product, "\"v0\"", one(single.id, ", \"maxSelectOverride\": 2")), 422,
				"OPTION_GROUP_MINMAX_INVALID");
		assertProblem(putGroups(product, "\"v0\"", one(single.id, ", \"minSelectOverride\": 2")), 422,
				"OPTION_GROUP_MINMAX_INVALID");
		assertInvalid(putGroups(product, "\"v0\"", """
				{"groups": [{"optionGroupId": "%s"}, {"optionGroupId": "%s"}]}
				""".formatted(single.id, single.id)), "groups");
		assertProblem(putGroups(product, null, one(single.id, "")), 428, "PRECONDITION_REQUIRED");
		assertProblem(putGroups(product, "\"v5\"", one(single.id, "")), 412, "VERSION_CONFLICT");
	}

	// --- item overrides -----------------------------------------------------------------------------------------

	@Test
	void overridesPricesAndHidesItemsOfAttachedGroups() throws Exception {
		String product = product();
		Group milk = group("SINGLE", 1, 1, 2);
		assertThat(putGroups(product, "\"v0\"", one(milk.id, "")).getStatus()).isEqualTo(200);

		MockHttpServletResponse replaced = putOverrides(product, "\"v1\"", """
				{"overrides": [{"optionItemId": "%s", "extraPriceOverride": 8000, "isHidden": false},
				               {"optionItemId": "%s", "extraPriceOverride": null, "isHidden": true}]}
				""".formatted(milk.items.get(0), milk.items.get(1)));

		assertThat(replaced.getStatus()).as(replaced.getContentAsString()).isEqualTo(200);
		assertThat(replaced.getHeader("ETag")).isEqualTo("\"v2\"");
		String body = replaced.getContentAsString();
		assertThat(JsonPath.<List<Integer>>read(body, "$.optionItemOverrides[*].extraPriceOverride"))
			.containsExactlyInAnyOrder(8000, null);
		assertThat(JsonPath.<List<Boolean>>read(body, "$.optionItemOverrides[*].isHidden"))
			.containsExactlyInAnyOrder(false, true);
		assertThat(audits(product, "PRODUCT_OPTION_ITEM_OVERRIDES_REPLACE")).hasSize(1);
	}

	@Test
	void overrideRules() throws Exception {
		String product = product();
		Group milk = group("SINGLE", 0, 1, 1);
		Group other = group("SINGLE", 0, 1, 1);
		assertThat(putGroups(product, "\"v0\"", one(milk.id, "")).getStatus()).isEqualTo(200);

		assertProblem(putOverrides(product, "\"v1\"", override(other.items.getFirst(), "0", false)), 422,
				"OPTION_ITEM_NOT_ATTACHED");
		MockHttpServletResponse negative = putOverrides(product, "\"v1\"", override(milk.items.getFirst(), "-1", false));
		assertProblem(negative, 422, "CATALOG_PRICE_NEGATIVE");
		assertInvalid(putOverrides(product, "\"v1\"", """
				{"overrides": [{"optionItemId": "%s", "isHidden": false}, {"optionItemId": "%s", "isHidden": true}]}
				""".formatted(milk.items.getFirst(), milk.items.getFirst())), "overrides");
	}

	@Test
	void removingAGroupDropsTheOverridesOfItsItems() throws Exception {
		String product = product();
		Group milk = group("SINGLE", 0, 1, 1);
		Group ice = group("SINGLE", 0, 1, 1);
		assertThat(putGroups(product, "\"v0\"", """
				{"groups": [{"optionGroupId": "%s"}, {"optionGroupId": "%s"}]}
				""".formatted(milk.id, ice.id)).getStatus()).isEqualTo(200);
		assertThat(putOverrides(product, "\"v1\"", """
				{"overrides": [{"optionItemId": "%s", "isHidden": true}, {"optionItemId": "%s", "isHidden": true}]}
				""".formatted(milk.items.getFirst(), ice.items.getFirst())).getStatus()).isEqualTo(200);

		MockHttpServletResponse replaced = putGroups(product, "\"v2\"", one(ice.id, ""));

		assertThat(JsonPath.<List<String>>read(replaced.getContentAsString(), "$.optionItemOverrides[*].optionItemId"))
			.containsExactly(ice.items.getFirst());
	}

	@Test
	void changingAnOverridePriceNeedsCatalogPriceManage() throws Exception {
		String product = product();
		Group milk = group("SINGLE", 0, 1, 1);
		assertThat(putGroups(product, "\"v0\"", one(milk.id, "")).getStatus()).isEqualTo(200);
		Session noPrice = this.fixtures
			.openSession(CatalogFixtures.catalogManagerWithoutPricePermission(this.jdbc, this.fixtures));

		assertProblem(this.auth.perform(put(PRODUCTS + "/" + product + "/option-item-overrides")
			.contentType(MediaType.APPLICATION_JSON)
			.header("If-Match", "\"v1\"")
			.content(override(milk.items.getFirst(), "5000", false)), noPrice, true), 403, "PERMISSION_DENIED");
		MockHttpServletResponse hideOnly = this.auth.perform(put(PRODUCTS + "/" + product + "/option-item-overrides")
			.contentType(MediaType.APPLICATION_JSON)
			.header("If-Match", "\"v1\"")
			.content(override(milk.items.getFirst(), "null", true)), noPrice, true);
		assertThat(hideOnly.getStatus()).as("hiding needs no price permission").isEqualTo(200);
	}

	// --- activation (W4) ----------------------------------------------------------------------------------------

	@Test
	void hidingItemsCanMakeActivationFailForThatGroup() throws Exception {
		String product = product(category());
		Group size = group("SINGLE", 1, 1, 2);
		assertThat(putGroups(product, "\"v0\"", one(size.id, "")).getStatus()).isEqualTo(200);
		assertThat(putOverrides(product, "\"v1\"", """
				{"overrides": [{"optionItemId": "%s", "isHidden": true}, {"optionItemId": "%s", "isHidden": true}]}
				""".formatted(size.items.get(0), size.items.get(1))).getStatus()).isEqualTo(200);

		MockHttpServletResponse refused = this.auth.perform(post(PRODUCTS + "/" + product + "/activate"), this.admin,
				true);

		assertProblem(refused, 422, "PRODUCT_ACTIVATION_INVALID");
		assertThat(JsonPath.<List<String>>read(refused.getContentAsString(), "$.issues[*].code"))
			.containsExactly("OPTION_CONFIG_INVALID");
		assertThat(JsonPath.<List<String>>read(refused.getContentAsString(), "$.issues[*].optionGroupId"))
			.containsExactly(size.id);

		assertThat(putOverrides(product, "\"v2\"", "{\"overrides\": []}").getStatus()).isEqualTo(200);
		assertThat(this.auth.perform(post(PRODUCTS + "/" + product + "/activate"), this.admin, true).getStatus())
			.isEqualTo(200);
	}

	@Test
	void aGroupArchivedAfterAttachingOrWithTooFewItemsBlocksActivation() throws Exception {
		String product = product(category());
		Group topping = group("MULTIPLE", 0, 3, 2);
		Group later = group("SINGLE", 0, 1, 1);
		assertThat(putGroups(product, "\"v0\"", """
				{"groups": [{"optionGroupId": "%s"}, {"optionGroupId": "%s"}]}
				""".formatted(topping.id, later.id)).getStatus()).isEqualTo(200);
		assertThat(this.auth.perform(post("/api/backoffice/v1/option-groups/" + later.id + "/archive"), this.admin,
				true).getStatus()).isEqualTo(200);

		MockHttpServletResponse refused = this.auth.perform(post(PRODUCTS + "/" + product + "/activate"), this.admin,
				true);

		assertProblem(refused, 422, "PRODUCT_ACTIVATION_INVALID");
		assertThat(JsonPath.<List<String>>read(refused.getContentAsString(), "$.issues[*].optionGroupId"))
			.as("max 3 > 2 items, and archived")
			.containsExactly(topping.id, later.id);
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	record Group(String id, String code, List<String> items) {
	}

	private Group group(String type, int min, int max, int itemCount) throws Exception {
		String code = ("opt-" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase();
		StringBuilder items = new StringBuilder();
		for (int i = 0; i < itemCount; i++) {
			items.append((i > 0) ? ", " : "").append("{\"code\": \"i").append(i).append("\", \"name\": \"Item ")
				.append(i).append("\"}");
		}
		MockHttpServletResponse created = this.auth.perform(post("/api/backoffice/v1/option-groups")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"code": "%s", "name": "Nhóm", "selectionType": "%s", "minSelect": %d, "maxSelect": %d, "items": [%s]}
					""".formatted(code, type, min, max, items)), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		String body = created.getContentAsString();
		return new Group(JsonPath.read(body, "$.optionGroupId"), code, JsonPath.read(body, "$.items[*].optionItemId"));
	}

	private String product() throws Exception {
		return product(null);
	}

	private String product(String categoryId) throws Exception {
		String code = ("po-" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase();
		String category = (categoryId != null) ? ", \"primaryCategoryId\": \"" + categoryId + "\"" : "";
		MockHttpServletResponse created = this.auth.perform(post(PRODUCTS).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"productCode": "%s", "name": "Có option", "slug": "%s"%s,
					 "variants": [{"sku": "%s", "name": "Standard", "basePrice": 30000}]}
					""".formatted(code, code.toLowerCase(), category, code)), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return JsonPath.read(created.getContentAsString(), "$.productId");
	}

	private String category() throws Exception {
		MockHttpServletResponse created = this.auth.perform(post("/api/backoffice/v1/categories")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"slug\": \"po-" + UUID.randomUUID().toString().substring(0, 8) + "\", \"name\": \"Option\"}"),
				this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return JsonPath.read(created.getContentAsString(), "$.categoryId");
	}

	private static String one(String groupId, String extra) {
		return "{\"groups\": [{\"optionGroupId\": \"" + groupId + "\"" + extra + "}]}";
	}

	private static String override(String itemId, String price, boolean hidden) {
		return "{\"overrides\": [{\"optionItemId\": \"" + itemId + "\", \"extraPriceOverride\": " + price
				+ ", \"isHidden\": " + hidden + "}]}";
	}

	private MockHttpServletResponse putGroups(String product, String ifMatch, String json) throws Exception {
		var request = put(PRODUCTS + "/" + product + "/option-groups").contentType(MediaType.APPLICATION_JSON)
			.content(json);
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		return this.auth.perform(request, this.admin, true);
	}

	private MockHttpServletResponse putOverrides(String product, String ifMatch, String json) throws Exception {
		return this.auth.perform(put(PRODUCTS + "/" + product + "/option-item-overrides")
			.contentType(MediaType.APPLICATION_JSON)
			.header("If-Match", ifMatch)
			.content(json), this.admin, true);
	}

	private MockHttpServletResponse detail(String product) throws Exception {
		MockHttpServletResponse response = this.auth.perform(get(PRODUCTS + "/" + product), this.admin, false);
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
		return response;
	}

	private List<Map<String, Object>> audits(String id, String action) {
		return this.jdbc.sql("SELECT action FROM aud_audit_log WHERE entity_id = ? AND action = ?")
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
