-- Catalog: when a Product may be sold (Catalog §5.7, Decision AK). DDL from ERD 05-catalog.
CREATE TABLE cat_product_selling_schedule (
  id         BINARY(16)  NOT NULL,
  product_id BINARY(16)  NOT NULL,
  start_date DATE        NULL,
  end_date   DATE        NULL,
  status     VARCHAR(32) NOT NULL,                    -- ACTIVE | INACTIVE
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY ix_cat_product_selling_schedule__product_id (product_id),
  CONSTRAINT fk_cat_product_selling_schedule__product_id FOREIGN KEY (product_id) REFERENCES cat_product (id),
  CONSTRAINT ck_cat_product_selling_schedule__range CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date)
);

CREATE TABLE cat_product_selling_window (
  id          BINARY(16) NOT NULL,
  schedule_id BINARY(16) NOT NULL,
  day_of_week TINYINT    NULL,                        -- NULL = mọi ngày; 1..7 ISO
  start_time  TIME       NOT NULL,
  end_time    TIME       NOT NULL,
  PRIMARY KEY (id),
  KEY ix_cat_product_selling_window__schedule_id (schedule_id),
  CONSTRAINT fk_cat_product_selling_window__schedule_id FOREIGN KEY (schedule_id) REFERENCES cat_product_selling_schedule (id),
  CONSTRAINT ck_cat_product_selling_window__range CHECK (start_time < end_time),
  CONSTRAINT ck_cat_product_selling_window__dow CHECK (day_of_week IS NULL OR day_of_week BETWEEN 1 AND 7)
);
