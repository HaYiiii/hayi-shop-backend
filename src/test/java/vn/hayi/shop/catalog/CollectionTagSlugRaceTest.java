package vn.hayi.shop.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import vn.hayi.shop.catalog.domain.CollectionRepository;
import vn.hayi.shop.catalog.domain.TagRepository;
import vn.hayi.shop.identity.BackofficeAuth;
import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.identity.IdentityFixtures;

/**
 * A slug taken by a concurrent request after the check still ends in {@code 409 CATALOG_SLUG_TAKEN}, through the
 * unique key (Decision AL1, AL2).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class CollectionTagSlugRaceTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	IdentityFixtures fixtures;

	@Autowired
	MutableClock clock;

	@MockitoSpyBean
	CollectionRepository collections;

	@MockitoSpyBean
	TagRepository tags;

	BackofficeAuth auth;

	Session admin;

	@BeforeEach
	void setUp() {
		this.auth = new BackofficeAuth(this.mvc, this.clock);
		this.admin = this.fixtures.openSession(this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail()));
	}

	@Test
	void aCollectionSlugTakenAfterTheCheckIsStillAConflict() throws Exception {
		String slug = "race-" + UUID.randomUUID().toString().substring(0, 8);
		assertThat(create("/collections", slug).getStatus()).isEqualTo(201);
		doReturn(false).when(this.collections).existsBySlug(anyString());

		assertSlugTaken(create("/collections", slug));
	}

	@Test
	void aTagSlugTakenAfterTheCheckIsStillAConflict() throws Exception {
		String slug = "race-" + UUID.randomUUID().toString().substring(0, 8);
		assertThat(create("/tags", slug).getStatus()).isEqualTo(201);
		doReturn(false).when(this.tags).existsBySlug(anyString());

		assertSlugTaken(create("/tags", slug));
	}

	private MockHttpServletResponse create(String path, String slug) throws Exception {
		return this.auth.perform(post("/api/backoffice/v1" + path).contentType(MediaType.APPLICATION_JSON)
			.content("{\"slug\": \"%s\", \"name\": \"Đua\"}".formatted(slug)), this.admin, true);
	}

	private static void assertSlugTaken(MockHttpServletResponse response) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(409);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo("CATALOG_SLUG_TAKEN");
	}

}
