package vn.hayi.shop.platform.observability;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import vn.hayi.shop.TestcontainersConfiguration;

/**
 * Trace id propagation from the frontend (Observability §19.3, API README §9) and health probes (§19.5).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TraceAndHealthTest {

	private static final String FRONTEND_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

	@Autowired
	MockMvc mvc;

	@Test
	void incomingTraceparentIsReusedAsTraceId() throws Exception {
		mvc.perform(get("/api/backoffice/v1/orders")
				.header("traceparent", "00-" + FRONTEND_TRACE_ID + "-00f067aa0ba902b7-01"))
			.andExpect(header().string("X-Trace-Id", FRONTEND_TRACE_ID))
			.andExpect(jsonPath("$.traceId").value(FRONTEND_TRACE_ID));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"00-00000000000000000000000000000000-00f067aa0ba902b7-01", // all-zero trace-id
			"00-" + FRONTEND_TRACE_ID + "-0000000000000000-01",         // all-zero parent-id
			"ff-" + FRONTEND_TRACE_ID + "-00f067aa0ba902b7-01",         // forbidden version
			"00-" + FRONTEND_TRACE_ID + "-00f067aa0ba902b7",            // missing flags
			"garbage" })
	void invalidTraceparentIsIgnoredAndANewTraceIdIsGenerated(String traceparent) throws Exception {
		mvc.perform(get("/api/backoffice/v1/orders").header("traceparent", traceparent))
			.andExpect(header().string("X-Trace-Id", matchesPattern("[0-9a-f]{32}")))
			.andExpect(header().string("X-Trace-Id", not(FRONTEND_TRACE_ID)))
			.andExpect(header().string("X-Trace-Id", not("00000000000000000000000000000000")));
	}

	@Test
	void livenessIsUpWithoutAuthentication() throws Exception {
		mvc.perform(get("/actuator/health/liveness"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void readinessIsUpWithoutAuthenticationAndChecksTheDatabase() throws Exception {
		mvc.perform(get("/actuator/health/readiness"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"))
			.andExpect(jsonPath("$.components.db.status").value("UP"));
	}

}
