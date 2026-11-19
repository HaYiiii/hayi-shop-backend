-- Customer: the profile created with a Customer registration (Customer §3.2, Identity §2.11). DDL from ERD 02-customer.
CREATE TABLE cus_customer_profile (
  id           BINARY(16)   NOT NULL,
  user_id      BINARY(16)   NOT NULL,
  display_name VARCHAR(128) NOT NULL,
  created_at   DATETIME(6)  NOT NULL,
  updated_at   DATETIME(6)  NOT NULL,
  version      BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cus_customer_profile__user_id (user_id)
);
