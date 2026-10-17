package vn.hayi.shop.identity.domain;

/** Channel of a session; {@code BACKOFFICE_WEB} ↔ persona STAFF, {@code CUSTOMER_WEB} ↔ CUSTOMER (Identity §2.5). */
public enum SessionChannel {

	CUSTOMER_WEB,
	BACKOFFICE_WEB

}
