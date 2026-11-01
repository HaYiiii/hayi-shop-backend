package vn.hayi.shop.identity.application;

/**
 * Who the Backoffice caller is, for the account header and the step-up dialog (API 01-security §6.2, Decision M4).
 * {@code email} is {@code null} without an EMAIL identifier, {@code displayName} without a StaffProfile.
 */
public record BackofficeAccount(String email, String displayName, boolean mfaEnrolled) {
}
