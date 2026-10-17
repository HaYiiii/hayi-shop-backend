package vn.hayi.shop.shared.actor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class ActorContextTest {

	@Test
	void staffUserActorCarriesUserAndStaffIdentity() {
		UUID userId = UUID.randomUUID();
		UUID staffId = UUID.randomUUID();

		ActorContext actor = ActorContext.staff(userId, staffId);

		assertThat(actor.actorType()).isEqualTo(ActorType.USER);
		assertThat(actor.actorPersona()).isEqualTo(Persona.STAFF);
		assertThat(actor.actorUserId()).isEqualTo(userId);
		assertThat(actor.actorStaffId()).isEqualTo(staffId);
	}

	@Test
	void customerUserActorHasNoStaffIdentity() {
		ActorContext actor = ActorContext.customer(UUID.randomUUID());

		assertThat(actor.actorPersona()).isEqualTo(Persona.CUSTOMER);
		assertThat(actor.actorStaffId()).isNull();
	}

	@Test
	void jobActsUnderItsOwnNameAndNeverImpersonatesAUser() {
		ActorContext actor = ActorContext.job("payment-expiry");

		assertThat(actor.actorType()).isEqualTo(ActorType.JOB);
		assertThat(actor.jobName()).isEqualTo("payment-expiry");
		assertThat(actor.actorUserId()).isNull();
		assertThat(actor.actorPersona()).isNull();
	}

	@Test
	void userActorRequiresAUserId() {
		assertThatNullPointerException().isThrownBy(() -> ActorContext.customer(null));
	}

}
