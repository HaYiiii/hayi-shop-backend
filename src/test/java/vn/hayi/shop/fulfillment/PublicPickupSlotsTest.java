package vn.hayi.shop.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.branch.application.BranchHoursService;
import vn.hayi.shop.branch.application.BranchService;
import vn.hayi.shop.branch.application.NewBranch;
import vn.hayi.shop.branch.application.WeeklyIntervalRequest;
import vn.hayi.shop.fulfillment.application.FulfillmentConfigService;
import vn.hayi.shop.fulfillment.application.FulfillmentRules;
import vn.hayi.shop.fulfillment.application.PickupScheduleService;
import vn.hayi.shop.fulfillment.application.ScheduleWindowInput;
import vn.hayi.shop.shared.actor.ActorContext;
import vn.hayi.shop.shared.concurrency.ExpectedVersion;
import vn.hayi.shop.shared.time.BranchTime;

/** Customers, signed in or not, read a Branch's pickup slots (API 02 §3, Decision T). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class })
class PublicPickupSlotsTest {

	static final ActorContext OWNER = ActorContext.staff(UUID.randomUUID(), null);

	static final LocalDate MONDAY = LocalDate.of(2030, 1, 7);

	@Autowired
	MockMvc mvc;

	@Autowired
	BranchService branches;

	@Autowired
	BranchHoursService branchHours;

	@Autowired
	FulfillmentConfigService configs;

	@Autowired
	PickupScheduleService schedules;

	@Autowired
	MutableClock clock;

	UUID branchId;

	@BeforeEach
	void setUp() {
		this.clock.freezeAt(BranchTime.toInstant(MONDAY, LocalTime.of(10, 0), BranchTime.DEFAULT_ZONE));
		this.branchId = newBranch();
		this.branchHours.replaceWeekly(this.branchId, List.of(new WeeklyIntervalRequest(2, at(7), at(21))),
				new ExpectedVersion(0), OWNER, null, null);
		this.branches.activate(this.branchId, null, OWNER, null, null);
		this.configs.replace(this.branchId, "PICKUP",
				new FulfillmentRules(true, false, 7, 30, 180, 30, null, null, 10, 20, null), new ExpectedVersion(0),
				OWNER, null, null);
		this.schedules.replaceWeekly(this.branchId, "PICKUP",
				List.of(new ScheduleWindowInput(2, at(9), at(10), null, null)), new ExpectedVersion(1), OWNER, null,
				null);
	}

	@AfterEach
	void resetClock() {
		this.clock.reset();
	}

	@Test
	void anyoneReadsSlotsWithoutCapacityNumbers() throws Exception {
		MockHttpServletResponse response = slots("");

		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		String body = response.getContentAsString();
		assertThat(JsonPath.<String>read(body, "$.branchId")).isEqualTo(this.branchId.toString());
		assertThat(JsonPath.<String>read(body, "$.fulfillmentType")).isEqualTo("PICKUP");
		assertThat(JsonPath.<String>read(body, "$.timezone")).isEqualTo("Asia/Ho_Chi_Minh");
		assertThat(JsonPath.<Boolean>read(body, "$.asapAvailable")).isFalse();
		assertThat(JsonPath.<List<String>>read(body, "$.days[*].date")).hasSize(7).first().isEqualTo(MONDAY.toString());
		assertThat(JsonPath.<Boolean>read(body, "$.days[0].isOpen")).isFalse();
		assertThat(JsonPath.<List<Map<String, Object>>>read(body, "$.days[1].slots")).containsExactly(
				Map.of("slotStart", "09:00", "slotEnd", "09:30", "status", "AVAILABLE"),
				Map.of("slotStart", "09:30", "slotEnd", "10:00", "status", "AVAILABLE"));
		assertThat(body).doesNotContain("maxOrders", "maxItems", "usedOrders", "usedItems");
	}

	@Test
	void queryNarrowsTheDays() throws Exception {
		String body = slots("?fromDate=" + MONDAY.plusDays(1) + "&days=2&orderType=GROUP&itemCount=3")
			.getContentAsString();

		assertThat(JsonPath.<List<String>>read(body, "$.days[*].date")).containsExactly(MONDAY.plusDays(1).toString(),
				MONDAY.plusDays(2).toString());
	}

	@Test
	void outsideTheHorizonIsAValidationError() throws Exception {
		assertProblem(slots("?days=8"), 400, "VALIDATION_FAILED");
		assertProblem(slots("?fromDate=" + MONDAY.minusDays(1)), 400, "VALIDATION_FAILED");
		assertProblem(slots("?orderType=PARTY"), 400, "VALIDATION_FAILED");
		assertProblem(slots("?fulfillmentType=DELIVERY"), 400, "VALIDATION_FAILED");
		MockHttpServletResponse itemCount = slots("?itemCount=0");
		assertProblem(itemCount, 400, "VALIDATION_FAILED");
		assertThat(JsonPath.<List<String>>read(itemCount.getContentAsString(), "$.errors[*].field"))
			.containsExactly("itemCount");
	}

	@Test
	void draftOrUnknownBranchIsNotFound() throws Exception {
		UUID draft = newBranch();

		assertProblem(this.mvc.perform(get("/api/public/v1/branches/" + draft + "/fulfillment/slots")).andReturn()
			.getResponse(), 404, "BRANCH_NOT_FOUND");
		assertProblem(this.mvc.perform(get("/api/public/v1/branches/" + UUID.randomUUID() + "/fulfillment/slots"))
			.andReturn()
			.getResponse(), 404, "BRANCH_NOT_FOUND");
	}

	private UUID newBranch() {
		return this.branches.create(new NewBranch("T-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
				"HaYi test", "1 Lê Lợi", null, null, "TP.HCM", null, null, null, null), OWNER, null, null)
			.branchId();
	}

	private MockHttpServletResponse slots(String query) throws Exception {
		return this.mvc.perform(get("/api/public/v1/branches/" + this.branchId + "/fulfillment/slots" + query))
			.andReturn()
			.getResponse();
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

	private static LocalTime at(int hour) {
		return LocalTime.of(hour, 0);
	}

}
