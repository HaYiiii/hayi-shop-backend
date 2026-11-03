package vn.hayi.shop.branch.domain;

import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcType;
import org.hibernate.type.descriptor.jdbc.LocalTimeJdbcType;

import vn.hayi.shop.shared.id.UuidV7;

/** A weekly opening range of a Branch, part of the {@link Branch} aggregate (ERD 03 {@code brn_business_hour}). */
@Entity
@Table(name = "brn_business_hour")
class BranchBusinessHour {

	@Id
	private UUID id;

	private byte dayOfWeek;

	/** Wall-clock times: bound as {@code LocalTime}, never shifted by {@code hibernate.jdbc.time_zone}. */
	@JdbcType(LocalTimeJdbcType.class)
	private LocalTime openTime;

	@JdbcType(LocalTimeJdbcType.class)
	private LocalTime closeTime;

	protected BranchBusinessHour() {
	}

	BranchBusinessHour(WeeklyHours hours) {
		this.id = UuidV7.generate();
		this.dayOfWeek = (byte) hours.dayOfWeek();
		this.openTime = hours.range().open();
		this.closeTime = hours.range().close();
	}

	WeeklyHours toWeeklyHours() {
		return new WeeklyHours(this.dayOfWeek, new TimeRange(this.openTime, this.closeTime));
	}

}
