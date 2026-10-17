package vn.hayi.shop.audit.api;

/**
 * Writes business audit in the caller's transaction (Audit §17.8, SOURCE-STRUCTURE §2.6): the record commits and
 * rolls back with the audited mutation. Calling it without an active transaction is a programming error and fails.
 */
public interface AuditRecorder {

	void record(AuditEntry entry);

}
