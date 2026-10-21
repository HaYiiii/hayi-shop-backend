package vn.hayi.shop.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vn.hayi.shop.identity.application.EffectiveAccess.BranchAccess;
import vn.hayi.shop.platform.security.AccessDecision;
import vn.hayi.shop.platform.security.Permission;

/** Decision rules of effective permission (Identity §2.7, Decision A, M1–M2), without the database. */
class EffectiveAccessTest {

	static final UUID BRANCH_A = UUID.randomUUID();

	static final UUID BRANCH_B = UUID.randomUUID();

	@Test
	void storeRoleGrantsAtEveryBranchAndWithoutBranch() {
		EffectiveAccess admin = new EffectiveAccess(UUID.randomUUID(), Set.of("ADMIN"), Set.of(Permission.REFUND_APPROVE),
				Map.of());

		assertThat(admin.decide(Permission.REFUND_APPROVE, Optional.of(BRANCH_A))).isEqualTo(AccessDecision.GRANTED);
		assertThat(admin.decide(Permission.REFUND_APPROVE, Optional.empty())).isEqualTo(AccessDecision.GRANTED);
		assertThat(admin.decide(Permission.ORDER_VIEW, Optional.of(BRANCH_A))).isEqualTo(AccessDecision.PERMISSION_DENIED);
	}

	@Test
	void branchRoleGrantsOnlyAtItsBranch() {
		EffectiveAccess staff = staff(Map.of(BRANCH_A, new BranchAccess(Set.of("MANAGER"), Set.of(Permission.REFUND_APPROVE)),
				BRANCH_B, new BranchAccess(Set.of("BARISTA"), Set.of(Permission.ORDER_VIEW))));

		assertThat(staff.decide(Permission.REFUND_APPROVE, Optional.of(BRANCH_A))).isEqualTo(AccessDecision.GRANTED);
		assertThat(staff.decide(Permission.REFUND_APPROVE, Optional.of(BRANCH_B))).isEqualTo(AccessDecision.PERMISSION_DENIED);
		assertThat(staff.decide(Permission.REFUND_APPROVE, Optional.of(UUID.randomUUID())))
			.isEqualTo(AccessDecision.BRANCH_SCOPE_DENIED);
	}

	@Test
	void permissionIsCheckedBeforeScope() {
		EffectiveAccess barista = staff(Map.of(BRANCH_A, new BranchAccess(Set.of("BARISTA"), Set.of(Permission.ORDER_VIEW))));

		assertThat(barista.decide(Permission.REFUND_APPROVE, Optional.of(BRANCH_B))).isEqualTo(AccessDecision.PERMISSION_DENIED);
		assertThat(barista.decide(Permission.ORDER_VIEW, Optional.of(BRANCH_B))).isEqualTo(AccessDecision.BRANCH_SCOPE_DENIED);
	}

	@Test
	void crossBranchViewNeedsPermissionInSomeScope() {
		EffectiveAccess barista = staff(Map.of(BRANCH_A, new BranchAccess(Set.of("BARISTA"), Set.of(Permission.ORDER_VIEW))));

		assertThat(barista.decide(Permission.ORDER_VIEW, Optional.empty())).isEqualTo(AccessDecision.GRANTED);
		assertThat(barista.decide(Permission.CATALOG_MANAGE, Optional.empty())).isEqualTo(AccessDecision.PERMISSION_DENIED);
	}

	@Test
	void permissionsAtBranchIncludeStorePermissions() {
		EffectiveAccess both = new EffectiveAccess(UUID.randomUUID(), Set.of("ADMIN"), Set.of(Permission.ROLE_ASSIGN),
				Map.of(BRANCH_A, new BranchAccess(Set.of("BARISTA"), Set.of(Permission.ORDER_PREPARE))));

		assertThat(both.permissionsAt(BRANCH_A)).containsExactlyInAnyOrder(Permission.ROLE_ASSIGN, Permission.ORDER_PREPARE);
		assertThat(both.permissionsAt(BRANCH_B)).containsExactly(Permission.ROLE_ASSIGN);
	}

	private static EffectiveAccess staff(Map<UUID, BranchAccess> branches) {
		return new EffectiveAccess(UUID.randomUUID(), Set.of(), Set.of(), branches);
	}

}
