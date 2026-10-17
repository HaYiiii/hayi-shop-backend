package vn.hayi.shop.audit.infrastructure;

import static vn.hayi.shop.platform.persistence.UuidBinary.toBytes;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;
import vn.hayi.shop.audit.api.AuditEntry;
import vn.hayi.shop.audit.api.AuditRecorder;
import vn.hayi.shop.platform.observability.TraceIds;
import vn.hayi.shop.shared.actor.ActorContext;
import vn.hayi.shop.shared.id.UuidV7;

/** Append-only insert into {@code aud_audit_log} (ERD 16); there is no update or delete (Audit §17.7). */
@Component
class JdbcAuditRecorder implements AuditRecorder {

	private final JdbcClient jdbc;

	private final JsonMapper json;

	private final Clock clock;

	JdbcAuditRecorder(JdbcClient jdbc, JsonMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.json = json;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void record(AuditEntry entry) {
		ActorContext actor = entry.actor();
		this.jdbc.sql("""
				INSERT INTO aud_audit_log (id, actor_type, actor_user_id, actor_persona, actor_staff_id, actor_job_name,
				  action, entity_type, entity_id, branch_id, source, old_value, new_value, reason, correlation_id,
				  trace_id, ip_address, user_agent, occurred_at)
				VALUES (:id, :actorType, :actorUserId, :actorPersona, :actorStaffId, :actorJobName,
				  :action, :entityType, :entityId, :branchId, :source, :oldValue, :newValue, :reason, :correlationId,
				  :traceId, :ipAddress, :userAgent, :occurredAt)
				""")
			.param("id", toBytes(UuidV7.generate()))
			.param("actorType", actor.actorType().name())
			.param("actorUserId", toBytes(actor.actorUserId()))
			.param("actorPersona", (actor.actorPersona() != null) ? actor.actorPersona().name() : null)
			.param("actorStaffId", toBytes(actor.actorStaffId()))
			.param("actorJobName", actor.jobName())
			.param("action", entry.action())
			.param("entityType", entry.entityType())
			.param("entityId", toBytes(entry.entityId()))
			.param("branchId", toBytes(entry.branchId()))
			.param("source", entry.source().name())
			.param("oldValue", toJson(entry.oldValue()))
			.param("newValue", toJson(entry.newValue()))
			.param("reason", entry.reason())
			.param("correlationId", TraceIds.current())
			.param("traceId", TraceIds.current())
			.param("ipAddress", entry.ipAddress())
			.param("userAgent", entry.userAgent())
			.param("occurredAt", Instant.now(this.clock))
			.update();
	}

	private String toJson(Map<String, ?> value) {
		return (value != null) ? this.json.writeValueAsString(value) : null;
	}

}
