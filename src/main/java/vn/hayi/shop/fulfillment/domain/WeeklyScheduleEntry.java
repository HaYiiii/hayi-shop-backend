package vn.hayi.shop.fulfillment.domain;

import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcType;
import org.hibernate.type.descriptor.jdbc.LocalTimeJdbcType;

import vn.hayi.shop.shared.id.UuidV7;

/** A weekly pickup window, part of the {@link BranchFulfillmentConfig} aggregate (ERD 08 {@code ful_weekly_schedule}). */
@Entity
@Table(name = "ful_weekly_schedule")
class WeeklyScheduleEntry {

	@Id
	private UUID id;

	private byte dayOfWeek;

	/** Wall-clock times: bound as {@code LocalTime}, never shifted by {@code hibernate.jdbc.time_zone}. */
	@JdbcType(LocalTimeJdbcType.class)
	private LocalTime startTime;

	@JdbcType(LocalTimeJdbcType.class)
	private LocalTime endTime;

	private Integer maxOrders;

	private Integer maxItems;

	protected WeeklyScheduleEntry() {
	}

	WeeklyScheduleEntry(WeeklyWindow window) {
		this.id = UuidV7.generate();
		this.dayOfWeek = (byte) window.dayOfWeek();
		this.startTime = window.window().start();
		this.endTime = window.window().end();
		this.maxOrders = window.maxOrders();
		this.maxItems = window.maxItems();
	}

	WeeklyWindow toWindow() {
		return new WeeklyWindow(this.dayOfWeek, new TimeWindow(this.startTime, this.endTime), this.maxOrders,
				this.maxItems);
	}

}
