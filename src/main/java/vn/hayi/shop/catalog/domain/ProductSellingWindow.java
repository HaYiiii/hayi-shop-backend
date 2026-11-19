package vn.hayi.shop.catalog.domain;

import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.descriptor.jdbc.LocalTimeJdbcType;

import vn.hayi.shop.shared.id.UuidV7;

/** One daily window of a {@link ProductSellingSchedule} (ERD 05, Decision AK). */
@Entity
@Table(name = "cat_product_selling_window")
class ProductSellingWindow {

	@Id
	private UUID id;

	@JdbcTypeCode(SqlTypes.TINYINT)
	private Integer dayOfWeek;

	/** Wall-clock times: bound as {@code LocalTime}, never shifted by {@code hibernate.jdbc.time_zone}. */
	@JdbcType(LocalTimeJdbcType.class)
	private LocalTime startTime;

	@JdbcType(LocalTimeJdbcType.class)
	private LocalTime endTime;

	protected ProductSellingWindow() {
	}

	static ProductSellingWindow of(SellingScheduleSpec.Window spec) {
		ProductSellingWindow window = new ProductSellingWindow();
		window.id = UuidV7.generate();
		window.dayOfWeek = spec.dayOfWeek();
		window.startTime = spec.start();
		window.endTime = spec.end();
		return window;
	}

	SellingScheduleSpec.Window spec() {
		return new SellingScheduleSpec.Window(this.dayOfWeek, this.startTime, this.endTime);
	}

}
