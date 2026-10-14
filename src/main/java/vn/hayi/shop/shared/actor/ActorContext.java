package vn.hayi.shop.shared.actor;

import java.util.Objects;
import java.util.UUID;

/**
 * The actor behind an action, for Audit and Observability (Identity §2.14). Jobs act as {@code JOB} with their
 * own name and never impersonate a user.
 */
public record ActorContext(ActorType actorType, UUID actorUserId, Persona actorPersona, UUID actorStaffId,
		String jobName) {

	private static final ActorContext SYSTEM = new ActorContext(ActorType.SYSTEM, null, null, null, null);

	public ActorContext {
		Objects.requireNonNull(actorType, "actorType");
		if (actorType == ActorType.USER) {
			Objects.requireNonNull(actorUserId, "actorUserId");
			Objects.requireNonNull(actorPersona, "actorPersona");
		}
		if (actorType == ActorType.JOB) {
			Objects.requireNonNull(jobName, "jobName");
		}
	}

	public static ActorContext customer(UUID userId) {
		return new ActorContext(ActorType.USER, userId, Persona.CUSTOMER, null, null);
	}

	public static ActorContext staff(UUID userId, UUID staffId) {
		return new ActorContext(ActorType.USER, userId, Persona.STAFF, staffId, null);
	}

	public static ActorContext system() {
		return SYSTEM;
	}

	public static ActorContext job(String jobName) {
		return new ActorContext(ActorType.JOB, null, null, null, jobName);
	}

}
