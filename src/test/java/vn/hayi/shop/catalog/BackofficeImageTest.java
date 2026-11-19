package vn.hayi.shop.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

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
import vn.hayi.shop.StorageTestcontainersConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.identity.BackofficeAuth;
import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.identity.IdentityFixtures;
import vn.hayi.shop.platform.persistence.UuidBinary;

/**
 * Owner/Admin link uploaded images to Products and Categories; unlinking archives the stored file (Catalog §5.2,
 * Storage §18.3, §18.8, API 08 §1.8, Decision AG, W7). Uses the real Storage module and SeaweedFS.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, StorageTestcontainersConfiguration.class,
		MutableClockConfiguration.class, IdentityFixtures.class })
class BackofficeImageTest {

	static final String PRODUCTS = "/api/backoffice/v1/products";

	static final MediaType MERGE_PATCH = MediaType.parseMediaType("application/merge-patch+json");

	static final HttpClient HTTP = HttpClient.newHttpClient();

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

	// --- add (AG1) ----------------------------------------------------------------------------------------------

	@Test
	void theFirstImageIsThePrimaryWithItsPublicUrl() throws Exception {
		String product = product();
		String object = uploaded("PRODUCT_IMAGE");

		MockHttpServletResponse added = addImage(product, "{\"storedObjectId\": \"" + object
				+ "\", \"altText\": \"Ly matcha\"}");

		assertThat(added.getStatus()).as(added.getContentAsString()).isEqualTo(201);
		assertThat(added.getHeader("ETag")).isEqualTo("\"v1\"");
		String body = detail(product);
		assertThat(JsonPath.<List<String>>read(body, "$.images[*].storedObjectId")).containsExactly(object);
		assertThat(JsonPath.<List<Boolean>>read(body, "$.images[*].isPrimary")).containsExactly(true);
		assertThat(JsonPath.<List<String>>read(body, "$.images[*].altText")).containsExactly("Ly matcha");
		assertThat(JsonPath.<List<String>>read(body, "$.images[*].url").getFirst()).endsWith(object + ".png");
		assertThat(audits(product, "PRODUCT_IMAGE_ADD")).hasSize(1);
	}

	@Test
	void aNewPrimaryImageTakesThePrimaryOverWithOneVersionBump() throws Exception {
		String product = product();
		assertThat(addImage(product, image(uploaded("PRODUCT_IMAGE"), false)).getStatus()).isEqualTo(201);

		MockHttpServletResponse added = addImage(product, image(uploaded("PRODUCT_IMAGE"), true));

		assertThat(added.getHeader("ETag")).isEqualTo("\"v2\"");
		assertThat(JsonPath.<List<Boolean>>read(detail(product), "$.images[*].isPrimary")).containsExactly(false, true);
	}

	@Test
	void onlyAnActivePublicProductImageLinkedNowhereElseCanBeAdded() throws Exception {
		String product = product();
		String pending = intent("PRODUCT_IMAGE").id;
		String categoryImage = uploaded("CATEGORY_IMAGE");
		String used = uploaded("PRODUCT_IMAGE");
		assertThat(addImage(product(), image(used, false)).getStatus()).isEqualTo(201);

		assertProblem(addImage(product, image(pending, false)), 422, "CATALOG_IMAGE_INVALID");
		assertProblem(addImage(product, image(categoryImage, false)), 422, "CATALOG_IMAGE_INVALID");
		assertProblem(addImage(product, image(UUID.randomUUID().toString(), false)), 422, "CATALOG_IMAGE_INVALID");
		assertProblem(addImage(product, image(used, false)), 409, "CATALOG_IMAGE_IN_USE");
	}

	@Test
	void atMostTenImages() throws Exception {
		String product = product();
		for (int i = 0; i < 10; i++) {
			assertThat(addImage(product, image(uploaded("PRODUCT_IMAGE"), false)).getStatus()).isEqualTo(201);
		}

		assertProblem(addImage(product, image(uploaded("PRODUCT_IMAGE"), false)), 409, "CATALOG_IMAGE_LIMIT_REACHED");
	}

	@Test
	void anArchivedProductTakesNoImage() throws Exception {
		String product = product();
		assertThat(this.auth.perform(post(PRODUCTS + "/" + product + "/archive"), this.admin, true).getStatus())
			.isEqualTo(200);

		assertProblem(addImage(product, image(uploaded("PRODUCT_IMAGE"), false)), 409,
				"CATALOG_PRODUCT_INVALID_TRANSITION");
	}

	// --- edit, remove, order (AG2, AG3) -------------------------------------------------------------------------

	@Test
	void patchEditsAltTextAndMovesThePrimary() throws Exception {
		String product = product();
		addImage(product, image(uploaded("PRODUCT_IMAGE"), false));
		addImage(product, image(uploaded("PRODUCT_IMAGE"), false));
		List<String> ids = JsonPath.read(detail(product), "$.images[*].imageId");

		MockHttpServletResponse edited = patchImage(product, ids.getLast(), "\"v2\"",
				"{\"altText\": \"Mặt bên\", \"isPrimary\": true}");

		assertThat(edited.getStatus()).as(edited.getContentAsString()).isEqualTo(200);
		assertThat(edited.getHeader("ETag")).isEqualTo("\"v3\"");
		String body = detail(product);
		assertThat(JsonPath.<List<Boolean>>read(body, "$.images[*].isPrimary")).containsExactly(false, true);
		assertThat(JsonPath.<List<String>>read(body, "$.images[*].altText")).containsExactly(null, "Mặt bên");
		Map<String, Object> audit = audits(product, "PRODUCT_IMAGE_UPDATE").getFirst();
		assertThat(JsonPath.<String>read((String) audit.get("old_value"), "$.primaryImageId"))
			.isEqualTo(ids.getFirst());

		assertThat(patchImage(product, ids.getLast(), "\"v3\"", "{\"altText\": \"Mặt bên\"}").getHeader("ETag"))
			.as("no change")
			.isEqualTo("\"v3\"");
		assertInvalid(patchImage(product, ids.getLast(), "\"v3\"", "{\"isPrimary\": false}"), "isPrimary");
		assertInvalid(patchImage(product, ids.getLast(), "\"v3\"", "{\"sortOrder\": 1}"), "sortOrder");
		assertProblem(patchImage(product, UUID.randomUUID().toString(), "\"v3\"", "{\"altText\": \"x\"}"), 404,
				"CATALOG_IMAGE_NOT_FOUND");
		assertProblem(patchImage(product, ids.getLast(), null, "{\"altText\": \"x\"}"), 428, "PRECONDITION_REQUIRED");
	}

	@Test
	void removingThePrimaryArchivesItsFileAndTheNextImageTakesOver() throws Exception {
		String product = product();
		String first = uploaded("PRODUCT_IMAGE");
		addImage(product, image(first, false));
		addImage(product, image(uploaded("PRODUCT_IMAGE"), false));
		List<String> ids = JsonPath.read(detail(product), "$.images[*].imageId");

		MockHttpServletResponse removed = this.auth.perform(delete(PRODUCTS + "/" + product + "/images/"
				+ ids.getFirst()), this.admin, true);

		assertThat(removed.getStatus()).as(removed.getContentAsString()).isEqualTo(200);
		assertThat(removed.getHeader("ETag")).isEqualTo("\"v3\"");
		String body = detail(product);
		assertThat(JsonPath.<List<String>>read(body, "$.images[*].imageId")).containsExactly(ids.getLast());
		assertThat(JsonPath.<List<Boolean>>read(body, "$.images[*].isPrimary")).containsExactly(true);
		assertThat(objectStatus(first)).isEqualTo("ARCHIVED");
		assertThat(audits(product, "PRODUCT_IMAGE_REMOVE")).hasSize(1);
		assertProblem(this.auth.perform(delete(PRODUCTS + "/" + product + "/images/" + ids.getFirst()), this.admin,
				true), 404, "CATALOG_IMAGE_NOT_FOUND");
	}

	@Test
	void reorderTakesExactlyTheCurrentImages() throws Exception {
		String product = product();
		addImage(product, image(uploaded("PRODUCT_IMAGE"), false));
		addImage(product, image(uploaded("PRODUCT_IMAGE"), false));
		List<String> ids = JsonPath.read(detail(product), "$.images[*].imageId");

		MockHttpServletResponse reordered = reorder(product, "\"v2\"", ids.getLast(), ids.getFirst());

		assertThat(reordered.getStatus()).as(reordered.getContentAsString()).isEqualTo(200);
		assertThat(reordered.getHeader("ETag")).isEqualTo("\"v3\"");
		assertThat(JsonPath.<List<String>>read(detail(product), "$.images[*].imageId"))
			.containsExactly(ids.getLast(), ids.getFirst());
		assertThat(audits(product, "PRODUCT_IMAGE_REORDER")).hasSize(1);
		assertThat(reorder(product, "\"v3\"", ids.getLast(), ids.getFirst()).getHeader("ETag")).isEqualTo("\"v3\"");
		assertProblem(reorder(product, "\"v3\"", ids.getFirst()), 409, "CATALOG_IMAGE_ORDER_STALE");
		assertInvalid(reorder(product, "\"v3\"", ids.getFirst(), ids.getFirst()), "imageIds");
		assertProblem(reorder(product, "\"v1\"", ids.getLast(), ids.getFirst()), 412, "VERSION_CONFLICT");
	}

	// --- Category (AG4) -----------------------------------------------------------------------------------------

	@Test
	void aCategoryImageIsSetReplacedAndClearedArchivingTheOldFile() throws Exception {
		String category = category();
		String first = uploaded("CATEGORY_IMAGE");
		String second = uploaded("CATEGORY_IMAGE");

		MockHttpServletResponse set = patchCategory(category, "\"v0\"", "{\"imageObjectId\": \"" + first + "\"}");
		assertThat(set.getStatus()).as(set.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<String>read(set.getContentAsString(), "$.imageObjectId")).isEqualTo(first);
		assertThat(JsonPath.<String>read(set.getContentAsString(), "$.imageUrl")).endsWith(first + ".png");

		assertThat(patchCategory(category, "\"v1\"", "{\"imageObjectId\": \"" + second + "\"}").getStatus())
			.isEqualTo(200);
		assertThat(objectStatus(first)).isEqualTo("ARCHIVED");
		MockHttpServletResponse tree = this.auth.perform(get("/api/backoffice/v1/categories"), this.admin, false);
		List<String> urls = JsonPath.read(tree.getContentAsString(),
				"$.items[?(@.categoryId == '" + category + "')].imageUrl");
		assertThat(urls).singleElement().asString().endsWith(second + ".png");

		MockHttpServletResponse cleared = patchCategory(category, "\"v2\"", "{\"imageObjectId\": null}");
		assertThat(JsonPath.<Object>read(cleared.getContentAsString(), "$.imageUrl")).isNull();
		assertThat(objectStatus(second)).isEqualTo("ARCHIVED");

		List<Map<String, Object>> audits = audits(category, "CATEGORY_UPDATE");
		assertThat(audits).hasSize(3);
		Map<String, Object> replacedOld = JsonPath.read((String) audits.get(1).get("old_value"), "$");
		Map<String, Object> replacedNew = JsonPath.read((String) audits.get(1).get("new_value"), "$");
		assertThat(replacedOld).containsOnlyKeys("imageObjectId").containsEntry("imageObjectId", first);
		assertThat(replacedNew).containsEntry("imageObjectId", second);
		assertThat(JsonPath.<Object>read((String) audits.get(2).get("new_value"), "$.imageObjectId")).isNull();
	}

	@Test
	void removingAnImageThatIsNotThePrimaryKeepsThePrimary() throws Exception {
		String product = product();
		addImage(product, image(uploaded("PRODUCT_IMAGE"), false));
		String second = uploaded("PRODUCT_IMAGE");
		addImage(product, image(second, false));
		List<String> ids = JsonPath.read(detail(product), "$.images[*].imageId");

		assertThat(this.auth.perform(delete(PRODUCTS + "/" + product + "/images/" + ids.getLast()), this.admin, true)
			.getStatus()).isEqualTo(200);

		String body = detail(product);
		assertThat(JsonPath.<List<String>>read(body, "$.images[*].imageId")).containsExactly(ids.getFirst());
		assertThat(JsonPath.<List<Boolean>>read(body, "$.images[*].isPrimary")).containsExactly(true);
		assertThat(objectStatus(second)).isEqualTo("ARCHIVED");
	}

	@Test
	void removingTheLastImageLeavesNone() throws Exception {
		String product = product();
		String only = uploaded("PRODUCT_IMAGE");
		addImage(product, image(only, false));
		String imageId = JsonPath.<List<String>>read(detail(product), "$.images[*].imageId").getFirst();

		assertThat(this.auth.perform(delete(PRODUCTS + "/" + product + "/images/" + imageId), this.admin, true)
			.getStatus()).isEqualTo(200);

		assertThat(JsonPath.<List<Object>>read(detail(product), "$.images")).isEmpty();
		assertThat(objectStatus(only)).isEqualTo("ARCHIVED");
	}

	@Test
	void aCategoryTakesOnlyAFreeCategoryImage() throws Exception {
		String category = category();
		String productImage = uploaded("PRODUCT_IMAGE");
		String used = uploaded("CATEGORY_IMAGE");
		assertThat(patchCategory(category(), "\"v0\"", "{\"imageObjectId\": \"" + used + "\"}").getStatus())
			.isEqualTo(200);

		assertProblem(patchCategory(category, "\"v0\"", "{\"imageObjectId\": \"" + productImage + "\"}"), 422,
				"CATALOG_IMAGE_INVALID");
		assertProblem(patchCategory(category, "\"v0\"", "{\"imageObjectId\": \"" + used + "\"}"), 409,
				"CATALOG_IMAGE_IN_USE");
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	record Intent(String id, String body) {
	}

	private Intent intent(String purpose) throws Exception {
		byte[] png = png();
		MockHttpServletResponse created = this.auth.perform(post("/api/backoffice/v1/uploads")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"purpose": "%s", "fileName": "anh.png", "contentType": "image/png", "sizeBytes": %d,
					 "checksumSha256": "%s"}
					""".formatted(purpose, png.length, sha256(png))), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return new Intent(JsonPath.read(created.getContentAsString(), "$.storedObjectId"),
				created.getContentAsString());
	}

	/** An {@code ACTIVE} stored image of {@code purpose}. */
	private String uploaded(String purpose) throws Exception {
		Intent intent = intent(purpose);
		HttpResponse<String> put = HTTP.send(HttpRequest.newBuilder(URI.create(JsonPath.read(intent.body,
				"$.upload.url")))
			.header("Content-Type", "image/png")
			.PUT(HttpRequest.BodyPublishers.ofByteArray(png()))
			.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(put.statusCode()).as(put.body()).isEqualTo(200);
		MockHttpServletResponse done = this.auth.perform(post("/api/backoffice/v1/uploads/" + intent.id + "/finalize"),
				this.admin, true);
		assertThat(JsonPath.<String>read(done.getContentAsString(), "$.status")).isEqualTo("ACTIVE");
		return intent.id;
	}

	private static byte[] png() throws Exception {
		BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private String product() throws Exception {
		String code = ("im-" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase();
		MockHttpServletResponse created = this.auth.perform(post(PRODUCTS).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"productCode": "%s", "name": "Có ảnh", "slug": "%s",
					 "variants": [{"sku": "%s", "name": "Standard", "basePrice": 30000}]}
					""".formatted(code, code.toLowerCase(), code)), this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return JsonPath.read(created.getContentAsString(), "$.productId");
	}

	private String category() throws Exception {
		MockHttpServletResponse created = this.auth.perform(post("/api/backoffice/v1/categories")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"slug\": \"im-" + UUID.randomUUID().toString().substring(0, 8) + "\", \"name\": \"Ảnh\"}"),
				this.admin, true);
		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		return JsonPath.read(created.getContentAsString(), "$.categoryId");
	}

	private static String image(String storedObjectId, boolean primary) {
		return "{\"storedObjectId\": \"" + storedObjectId + "\", \"isPrimary\": " + primary + "}";
	}

	private MockHttpServletResponse addImage(String product, String json) throws Exception {
		return this.auth.perform(post(PRODUCTS + "/" + product + "/images").contentType(MediaType.APPLICATION_JSON)
			.content(json), this.admin, true);
	}

	private MockHttpServletResponse patchImage(String product, String image, String ifMatch, String json)
			throws Exception {
		MockHttpServletRequestBuilder request = patch(PRODUCTS + "/" + product + "/images/" + image)
			.contentType(MERGE_PATCH)
			.content(json);
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		return this.auth.perform(request, this.admin, true);
	}

	private MockHttpServletResponse reorder(String product, String ifMatch, String... ids) throws Exception {
		StringBuilder json = new StringBuilder("{\"imageIds\": [");
		for (int i = 0; i < ids.length; i++) {
			json.append((i > 0) ? ", " : "").append('"').append(ids[i]).append('"');
		}
		return this.auth.perform(put(PRODUCTS + "/" + product + "/images/order").contentType(MediaType.APPLICATION_JSON)
			.header("If-Match", ifMatch)
			.content(json.append("]}").toString()), this.admin, true);
	}

	private MockHttpServletResponse patchCategory(String category, String ifMatch, String json) throws Exception {
		return this.auth.perform(patch("/api/backoffice/v1/categories/" + category).contentType(MERGE_PATCH)
			.header("If-Match", ifMatch)
			.content(json), this.admin, true);
	}

	private String detail(String product) throws Exception {
		MockHttpServletResponse response = this.auth.perform(get(PRODUCTS + "/" + product), this.admin, false);
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
		return response.getContentAsString();
	}

	private String objectStatus(String id) {
		return this.jdbc.sql("SELECT status FROM sto_stored_object WHERE id = ?")
			.param(UuidBinary.toBytes(UUID.fromString(id)))
			.query(String.class)
			.single();
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
