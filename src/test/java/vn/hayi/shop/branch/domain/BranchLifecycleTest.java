package vn.hayi.shop.branch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import vn.hayi.shop.shared.error.DomainException;

/** The Branch lifecycle of Branch §4.3 (Decision P1–P2). */
class BranchLifecycleTest {

	static final Instant CREATED = Instant.parse("2026-11-01T00:00:00Z");

	static final Instant LATER = Instant.parse("2026-11-03T00:00:00Z");

	@ParameterizedTest(name = "{0} --{1}--> {2}")
	@CsvSource({
			"DRAFT, activate, ACTIVE",
			"DRAFT, close, CLOSED",
			"ACTIVE, deactivate, INACTIVE",
			"ACTIVE, close, CLOSED",
			"INACTIVE, activate, ACTIVE",
			"INACTIVE, close, CLOSED" })
	void allowedTransitions(BranchStatus from, String command, BranchStatus to) {
		Branch branch = branchIn(from);

		assertThat(move(command).apply(branch, LATER)).isEqualTo(from);

		assertThat(branch.getStatus()).isEqualTo(to);
	}

	@ParameterizedTest(name = "{0} --{1}--> refused")
	@CsvSource({
			"DRAFT, deactivate",
			"ACTIVE, activate",
			"INACTIVE, deactivate",
			"CLOSED, activate",
			"CLOSED, deactivate",
			"CLOSED, close" })
	void refusedTransitions(BranchStatus from, String command) {
		Branch branch = branchIn(from);

		assertThatThrownBy(() -> move(command).apply(branch, LATER)).isInstanceOfSatisfying(DomainException.class,
				ex -> assertThat(ex.code()).isEqualTo(BranchErrorCode.BRANCH_INVALID_TRANSITION));
		assertThat(branch.getStatus()).isEqualTo(from);
	}

	@Test
	void activationAndClosureAreTimestamped() {
		Branch branch = branchIn(BranchStatus.DRAFT);
		branch.activate(CREATED);
		branch.deactivate(CREATED);

		branch.activate(LATER);
		assertThat(branch.getActivatedAt()).isEqualTo(LATER);

		branch.close(LATER);
		assertThat(branch.getClosedAt()).isEqualTo(LATER);
	}

	private static BiFunction<Branch, Instant, BranchStatus> move(String command) {
		return switch (command) {
			case "activate" -> Branch::activate;
			case "deactivate" -> Branch::deactivate;
			case "close" -> Branch::close;
			default -> throw new IllegalArgumentException(command);
		};
	}

	private static Branch branchIn(BranchStatus status) {
		Branch branch = Branch.create("Q1-A", "HaYi", new BranchAddress("1 Lê Lợi", null, null, "TP.HCM"), null, null,
				null, CREATED);
		switch (status) {
			case DRAFT -> {
			}
			case ACTIVE -> branch.activate(CREATED);
			case INACTIVE -> {
				branch.activate(CREATED);
				branch.deactivate(CREATED);
			}
			case CLOSED -> branch.close(CREATED);
		}
		return branch;
	}

}
