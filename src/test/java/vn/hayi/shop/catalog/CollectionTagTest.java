package vn.hayi.shop.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * Owner/Admin curate Collections and tag Products; the public menu shows them (Catalog §5.3, API 08 §1.10, API 02
 * §2.1, Decision AL).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class CollectionTagTest {

	static final String BACKOFFICE = "/api/backoffice/v1";

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

	String category;

	@BeforeEach
	void setUp() throws Exception {
		this.auth = new BackofficeAuth(this.mvc, this.clock);
		this.admin = this.fixtures.openSession(this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail()));
		this.category = category();
	}

	// --- Collections (AL1) -------------------------------------------------------------------------------------

	@Test
	void createsEditsAndArchivesACollectionWithAudit() throws Exception {
		String slug = slug("thu");
		MockHttpServletResponse created = this.auth.perform(post(BACKOFFICE + "/collections")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"slug\": \"%s\", \"name\": \"Mùa thu\", \"description\": \"Món mùa thu\"}".formatted(slug)),
				this.admin, true);

		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		assertThat(created.getHeader("ETag")).isEqualTo("\"v0\"");
		String id = JsonPath.read(created.getContentAsString(), "$.collectionId");
		assertThat(created.getHeader("Location")).endsWith("/collections/" + id);
		assertThat(JsonPath.<String>read(created.getContentAsString(), "$.status")).isEqualTo("ACTIVE");
		assertThat(JsonPath.<List<Object>>read(created.getContentAsString(), "$.productIds")).isEmpty();

		MockHttpServletResponse edited = patchCollection(id, "\"v0\"",
				"{\"name\": \"Thu vàng\", \"description\": null, \"sortOrder\": 5, \"status\": \"INACTIVE\"}");
		assertThat(edited.getStatus()).as(edited.getContentAsString()).isEqualTo(200);
		assertThat(edited.getHeader("ETag")).isEqualTo("\"v1\"");
		assertThat(JsonPath.<String>read(edited.getContentAsString(), "$.status")).isEqualTo("INACTIVE");
		assertThat(JsonPath.<Integer>read(edited.getContentAsString(), "$.sortOrder")).isEqualTo(5);
		assertThat(JsonPath.<Object>read(edited.getContentAsString(), "$.description")).isNull();
		assertThat(patchCollection(id, "\"v1\"", "{\"name\": \"Thu vàng\"}").getHeader("ETag")).as("no change")
			.isEqualTo("\"v1\"");

		MockHttpServletResponse fetched = this.auth.perform(get(BACKOFFICE + "/collections/" + id), this.admin, false);
		assertThat(fetched.getHeader("ETag")).isEqualTo("\"v1\"");
		assertThat(listedCollections(false)).contains(id);

		MockHttpServletResponse archived = this.auth.perform(post(BACKOFFICE + "/collections/" + id + "/archive"),
				this.admin, true);
		assertThat(archived.getStatus()).as(archived.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<String>read(archived.getContentAsString(), "$.status")).isEqualTo("ARCHIVED");
		assertThat(listedCollections(false)).doesNotContain(id);
		assertThat(listedCollections(true)).contains(id);
		assertProblem(patchCollection(id, "\"v2\"", "{\"name\": \"Khác\"}"), 409, "CATALOG_COLLECTION_ARCHIVED");
		assertProblem(this.auth.perform(post(BACKOFFICE + "/collections/" + id + "/archive"), this.admin, true), 409,
				"CATALOG_COLLECTION_ARCHIVED");

		assertThat(audits(id, "COLLECTION_CREATE")).hasSize(1);
		assertThat(audits(id, "COLLECTION_UPDATE")).hasSize(1);
		assertThat(audits(id, "COLLECTION_ARCHIVE")).hasSize(1);
	}

	@Test
	void collectionRequestsAreValidated() throws Exception {
		String taken = JsonPath.read(createCollection("Một").getContentAsString(), "$.slug");
		String id = JsonPath.read(createCollection("Hai").getContentAsString(), "$.collectionId");

		assertProblem(this.auth.perform(post(BACKOFFICE + "/collections").contentType(MediaType.APPLICATION_JSON)
			.content("{\"slug\": \"%s\", \"name\": \"Trùng\"}".formatted(taken)), this.admin, true), 409,
				"CATALOG_SLUG_TAKEN");
		assertProblem(patchCollection(id, "\"v0\"", "{\"slug\": \"" + taken + "\"}"), 409, "CATALOG_SLUG_TAKEN");
		assertProblem(patchCollection(id, "\"v0\"", "{\"status\": \"ARCHIVED\"}"), 400, "VALIDATION_FAILED");
		assertProblem(patchCollection(id, "\"v0\"", "{\"sortOrder\": -1}"), 400, "VALIDATION_FAILED");
		assertProblem(patchCollection(id, "\"v0\"", "{\"productIds\": []}"), 400, "VALIDATION_FAILED");
		assertProblem(patchCollection(id, null, "{\"name\": \"X\"}"), 428, "PRECONDITION_REQUIRED");
		assertProblem(patchCollection(id, "\"v7\"", "{\"name\": \"X\"}"), 412, "VERSION_CONFLICT");
		assertProblem(this.auth.perform(get(BACKOFFICE + "/collections/" + UUID.randomUUID()), this.admin, false),
				404, "CATALOG_COLLECTION_NOT_FOUND");
	}

	@Test
	void setsTheProductsOfACollectionInOrder() throws Exception {
		String id = JsonPath.read(createCollection("Combo").getContentAsString(), "$.collectionId");
		String first = product(true);
		String second = product(true);

		MockHttpServletResponse replaced = putProducts(id, "\"v0\"", second, first);
		assertThat(replaced.getStatus()).as(replaced.getContentAsString()).isEqualTo(200);
		assertThat(replaced.getHeader("ETag")).isEqualTo("\"v1\"");
		assertThat(JsonPath.<List<String>>read(replaced.getContentAsString(), "$.productIds"))
			.containsExactly(second, first);
		assertThat(putProducts(id, "\"v1\"", second, first).getHeader("ETag")).as("same order").isEqualTo("\"v1\"");
		MockHttpServletResponse swapped = putProducts(id, "\"v1\"", first, second);
		assertThat(swapped.getHeader("ETag")).isEqualTo("\"v2\"");
		assertThat(JsonPath.<List<String>>read(this.auth.perform(get(BACKOFFICE + "/collections/" + id), this.admin,
				false).getContentAsString(), "$.productIds")).containsExactly(first, second);
		assertThat(audits(id, "COLLECTION_PRODUCTS_REPLACE")).hasSize(2);

		String archived = product(false);
		assertThat(this.auth.perform(post(BACKOFFICE + "/products/" + archived + "/archive"), this.admin, true)
			.getStatus()).isEqualTo(200);
		assertProblem(putProducts(id, "\"v2\"", archived), 409, "CATALOG_PRODUCT_ARCHIVED");
		assertProblem(putProducts(id, "\"v2\"", UUID.randomUUID().toString()), 404, "CATALOG_PRODUCT_NOT_FOUND");
		assertProblem(putProducts(id, "\"v2\"", first, first), 400, "VALIDATION_FAILED");
		assertProblem(putProducts(id, null, first), 428, "PRECONDITION_REQUIRED");
	}

	// --- Tags (AL2) --------------------------------------------------------------------------------------------

	@Test
	void createsRenamesAndAttachesTags() throws Exception {
		String best = createTag(slug("best"), "Bán chạy");
		String hot = createTag(slug("aa-hot"), "Nóng");
		MockHttpServletResponse listed = this.auth.perform(get(BACKOFFICE + "/tags"), this.admin, false);
		List<String> ids = JsonPath.read(listed.getContentAsString(), "$.items[*].tagId");
		assertThat(ids.indexOf(hot)).as("by slug").isLessThan(ids.indexOf(best));

		MockHttpServletResponse renamed = patchTag(best, "{\"name\": \"Best seller\"}");
		assertThat(renamed.getStatus()).as(renamed.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<String>read(renamed.getContentAsString(), "$.name")).isEqualTo("Best seller");
		assertThat(audits(best, "TAG_CREATE")).hasSize(1);
		assertThat(audits(best, "TAG_UPDATE")).hasSize(1);

		String product = product(true);
		MockHttpServletResponse tagged = putTags(product, "\"v1\"", best, hot);
		assertThat(tagged.getStatus()).as(tagged.getContentAsString()).isEqualTo(200);
		assertThat(tagged.getHeader("ETag")).isEqualTo("\"v2\"");
		assertThat(JsonPath.<List<String>>read(tagged.getContentAsString(), "$.tags[*].tagId"))
			.as("by slug")
			.containsExactly(hot, best);
		assertThat(putTags(product, "\"v2\"", hot, best).getHeader("ETag")).as("no change").isEqualTo("\"v2\"");
		assertThat(JsonPath.<List<String>>read(this.auth.perform(get(BACKOFFICE + "/products/" + product),
				this.admin, false).getContentAsString(), "$.tags[*].name")).containsExactly("Nóng", "Best seller");
		assertThat(audits(product, "PRODUCT_TAGS_REPLACE")).hasSize(1);
		assertThat(putTags(product, "\"v2\"").getHeader("ETag")).as("cleared").isEqualTo("\"v3\"");
	}

	@Test
	void tagRequestsAreValidated() throws Exception {
		String slug = slug("dup");
		String tag = createTag(slug, "Một");
		String product = product(true);

		assertProblem(this.auth.perform(post(BACKOFFICE + "/tags").contentType(MediaType.APPLICATION_JSON)
			.content("{\"slug\": \"%s\", \"name\": \"Hai\"}".formatted(slug)), this.admin, true), 409,
				"CATALOG_SLUG_TAKEN");
		assertProblem(patchTag(UUID.randomUUID().toString(), "{\"name\": \"X\"}"), 404, "CATALOG_TAG_NOT_FOUND");
		assertProblem(patchTag(tag, "{\"name\": null}"), 400, "VALIDATION_FAILED");
		assertProblem(putTags(product, "\"v1\"", UUID.randomUUID().toString()), 404, "CATALOG_TAG_NOT_FOUND");
		assertProblem(putTags(product, "\"v1\"", tag, tag), 400, "VALIDATION_FAILED");
		assertProblem(putTags(product, null, tag), 428, "PRECONDITION_REQUIRED");
		assertThat(this.auth.perform(post(BACKOFFICE + "/products/" + product + "/archive"), this.admin, true)
			.getStatus()).isEqualTo(200);
		assertProblem(putTags(product, "\"v2\"", tag), 409, "CATALOG_PRODUCT_INVALID_TRANSITION");
	}

	// --- menu (AL3) --------------------------------------------------------------------------------------------

	@Test
	void theMenuShowsActiveCollectionsWithShownProductsAndTagSlugs() throws Exception {
		String branch = branch();
		String shown = product(true);
		String alsoShown = product(true);
		String unlisted = product(true);
		list(branch, shown);
		list(branch, alsoShown);
		String zTag = createTag(slug("zz"), "Z");
		String aTag = createTag(slug("aa"), "A");
		putTags(shown, "\"v1\"", zTag, aTag);

		String later = JsonPath.read(createCollection("Sau").getContentAsString(), "$.collectionId");
		String sooner = JsonPath.read(createCollection("Trước").getContentAsString(), "$.collectionId");
		String inactive = JsonPath.read(createCollection("Tắt").getContentAsString(), "$.collectionId");
		String onlyHidden = JsonPath.read(createCollection("Ẩn").getContentAsString(), "$.collectionId");
		patchCollection(later, "\"v0\"", "{\"sortOrder\": 900002}");
		patchCollection(sooner, "\"v0\"", "{\"sortOrder\": 900001}");
		putProducts(later, "\"v1\"", alsoShown, unlisted, shown);
		putProducts(sooner, "\"v1\"", shown);
		putProducts(inactive, "\"v0\"", shown);
		patchCollection(inactive, "\"v1\"", "{\"status\": \"INACTIVE\"}");
		putProducts(onlyHidden, "\"v0\"", unlisted);

		MockHttpServletResponse menu = this.mvc.perform(get("/api/public/v1/branches/" + branch + "/menu"))
			.andReturn()
			.getResponse();
		assertThat(menu.getStatus()).as(menu.getContentAsString()).isEqualTo(200);
		String body = menu.getContentAsString();

		List<String> collectionIds = JsonPath.read(body, "$.collections[*].collectionId");
		assertThat(collectionIds.stream().filter(List.of(later, sooner, inactive, onlyHidden)::contains).toList())
			.as("only ACTIVE with a shown Product, by sortOrder")
			.containsExactly(sooner, later);
		assertThat(JsonPath.<List<List<String>>>read(body,
				"$.collections[?(@.collectionId == '" + later + "')].productIds").getFirst())
			.as("only shown Products, in Collection order")
			.containsExactly(alsoShown, shown);
		assertThat(JsonPath.<List<List<String>>>read(body, "$..products[?(@.productId == '" + shown + "')].tags")
			.getFirst()).containsExactly(slugOf(aTag), slugOf(zTag));
		assertThat(JsonPath.<List<List<String>>>read(body, "$..products[?(@.productId == '" + alsoShown + "')].tags")
			.getFirst()).isEmpty();
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private static String slug(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private MockHttpServletResponse createCollection(String name) throws Exception {
		MockHttpServletResponse created = this.auth.perform(post(BACKOFFICE + "/collections")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"slug\": \"%s\", \"name\": \"%s\"}".formatted(slug("col"), name)), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return created;
	}

	private MockHttpServletResponse patchCollection(String id, String ifMatch, String json) throws Exception {
		return this.auth.perform(withIfMatch(patch(BACKOFFICE + "/collections/" + id)
			.contentType("application/merge-patch+json")
			.content(json), ifMatch), this.admin, true);
	}

	private MockHttpServletResponse putProducts(String id, String ifMatch, String... productIds) throws Exception {
		return this.auth.perform(withIfMatch(put(BACKOFFICE + "/collections/" + id + "/products")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"productIds\": [" + quoted(productIds) + "]}"), ifMatch), this.admin, true);
	}

	private List<String> listedCollections(boolean includeArchived) throws Exception {
		return JsonPath.read(this.auth.perform(get(BACKOFFICE + "/collections?includeArchived=" + includeArchived),
				this.admin, false).getContentAsString(), "$.items[*].collectionId");
	}

	private String createTag(String slug, String name) throws Exception {
		MockHttpServletResponse created = this.auth.perform(post(BACKOFFICE + "/tags")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"slug\": \"%s\", \"name\": \"%s\"}".formatted(slug, name)), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return JsonPath.read(created.getContentAsString(), "$.tagId");
	}

	private String slugOf(String tagId) {
		return this.jdbc.sql("SELECT slug FROM cat_tag WHERE id = ?")
			.param(UuidBinary.toBytes(UUID.fromString(tagId)))
			.query(String.class)
			.single();
	}

	private MockHttpServletResponse patchTag(String id, String json) throws Exception {
		return this.auth.perform(patch(BACKOFFICE + "/tags/" + id).contentType("application/merge-patch+json")
			.content(json), this.admin, true);
	}

	private MockHttpServletResponse putTags(String productId, String ifMatch, String... tagIds) throws Exception {
		return this.auth.perform(withIfMatch(put(BACKOFFICE + "/products/" + productId + "/tags")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"tagIds\": [" + quoted(tagIds) + "]}"), ifMatch), this.admin, true);
	}

	private static MockHttpServletRequestBuilder withIfMatch(MockHttpServletRequestBuilder request, String ifMatch) {
		return (ifMatch != null) ? request.header("If-Match", ifMatch) : request;
	}

	private static String quoted(String... ids) {
		return String.join(", ", java.util.Arrays.stream(ids).map(id -> "\"" + id + "\"").toList());
	}

	private List<Map<String, Object>> audits(String id, String action) {
		return this.jdbc.sql("SELECT action FROM aud_audit_log WHERE entity_id = ? AND action = ?")
			.params(UuidBinary.toBytes(UUID.fromString(id)), action)
			.query()
			.listOfRows();
	}

	private String category() throws Exception {
		MockHttpServletResponse created = this.auth.perform(post(BACKOFFICE + "/categories")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"slug\": \"%s\", \"name\": \"Nhóm\"}".formatted(slug("ct"))), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return JsonPath.read(created.getContentAsString(), "$.categoryId");
	}

	/** A Product, {@code ACTIVE} at version 1 when {@code active}, else {@code DRAFT} at version 0. */
	private String product(boolean active) throws Exception {
		String code = ("ct-" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase();
		MockHttpServletResponse created = this.auth.perform(post(BACKOFFICE + "/products")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"productCode": "%s", "name": "Món %s", "slug": "%s", "primaryCategoryId": "%s",
					 "variants": [{"sku": "%s-0", "name": "S", "basePrice": 30000, "isDefault": true}]}
					""".formatted(code, code, code.toLowerCase(), this.category, code)), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		String id = JsonPath.read(created.getContentAsString(), "$.productId");
		if (active) {
			MockHttpServletResponse activated = this.auth.perform(post(BACKOFFICE + "/products/" + id + "/activate"),
					this.admin, true);
			assertThat(activated.getHeader("ETag")).as(activated.getContentAsString()).isEqualTo("\"v1\"");
		}
		return id;
	}

	private String branch() throws Exception {
		MockHttpServletResponse created = this.auth.perform(post(BACKOFFICE + "/branches")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"branchCode": "%s", "name": "HaYi BST", "address": {"line": "1 Lê Lợi", "province": "TP.HCM"}}
					""".formatted(("C-" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase())), this.admin,
				true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		String id = JsonPath.read(created.getContentAsString(), "$.branchId");
		this.jdbc.sql("UPDATE brn_branch SET status = 'ACTIVE' WHERE id = ?")
			.param(UuidBinary.toBytes(UUID.fromString(id)))
			.update();
		return id;
	}

	private void list(String branch, String productId) throws Exception {
		MockHttpServletResponse listed = this.auth.perform(put(BACKOFFICE + "/branches/" + branch + "/listings/"
				+ productId)
			.contentType(MediaType.APPLICATION_JSON)
			.header("If-Match", "\"v0\"")
			.content("{\"availabilityState\": \"AVAILABLE\"}"), this.admin, true);
		assertThat(listed.getStatus()).as(listed.getContentAsString()).isEqualTo(200);
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

}
