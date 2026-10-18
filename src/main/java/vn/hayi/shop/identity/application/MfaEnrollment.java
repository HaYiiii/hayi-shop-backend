package vn.hayi.shop.identity.application;

/** A pending TOTP secret, as an {@code otpauth://} URI for the QR code and as base32 for manual entry (Decision L4). */
public record MfaEnrollment(String otpauthUri, String secret) {
}
