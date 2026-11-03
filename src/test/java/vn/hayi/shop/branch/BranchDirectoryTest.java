package vn.hayi.shop.branch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.branch.api.BranchDirectory;
import vn.hayi.shop.branch.application.BranchService;
import vn.hayi.shop.branch.application.NewBranch;
import vn.hayi.shop.platform.persistence.UuidBinary;
import vn.hayi.shop.shared.actor.ActorContext;
import vn.hayi.shop.shared.error.DomainException;

/** Other modules check a Branch through {@code branch.api} before storing config for it (Decision R4). */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class })
class BranchDirectoryTest {

	@Autowired
	BranchDirectory directory;

	@Autowired
	BranchService branches;

	@Autowired
	JdbcClient jdbc;

	@Test
	void unknownBranchIsNotFound() {
		assertCode(() -> this.directory.requireExists(UUID.randomUUID()), "BRANCH_NOT_FOUND");
		assertCode(() -> this.directory.requireConfigurable(UUID.randomUUID()), "BRANCH_NOT_FOUND");
	}

	@Test
	void draftAndInactiveBranchesAreConfigurableButClosedIsNot() {
		UUID branchId = newBranch();
		assertThatNoException().isThrownBy(() -> this.directory.requireConfigurable(branchId));
		setStatus(branchId, "INACTIVE");
		assertThatNoException().isThrownBy(() -> this.directory.requireConfigurable(branchId));

		setStatus(branchId, "CLOSED");

		assertThatNoException().isThrownBy(() -> this.directory.requireExists(branchId));
		assertCode(() -> this.directory.requireConfigurable(branchId), "BRANCH_INVALID_TRANSITION");
	}

	private UUID newBranch() {
		return this.branches.create(new NewBranch("T-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
				"HaYi test", "1 Lê Lợi", null, null, "TP.HCM", null, null, null, null),
				ActorContext.staff(UUID.randomUUID(), null), null, null)
			.branchId();
	}

	private void setStatus(UUID branchId, String status) {
		this.jdbc.sql("UPDATE brn_branch SET status = ? WHERE id = ?")
			.params(status, UuidBinary.toBytes(branchId))
			.update();
	}

	private static void assertCode(Runnable call, String code) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(DomainException.class,
				ex -> assertThat(ex.code().name()).isEqualTo(code));
	}

}
