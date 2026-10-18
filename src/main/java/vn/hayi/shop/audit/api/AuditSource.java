package vn.hayi.shop.audit.api;

/** Where an audited action came from ({@code aud_audit_log.source}, ERD 16). */
public enum AuditSource {

	CUSTOMER_WEB,
	BACKOFFICE_WEB,
	JOB,
	SYSTEM

}
