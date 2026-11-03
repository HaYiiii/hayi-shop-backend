package vn.hayi.shop.branch.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcType;
import org.hibernate.type.descriptor.jdbc.LocalTimeJdbcType;

import vn.hayi.shop.shared.id.UuidV7;

/**
 * One row of a date override, part of the {@link Branch} aggregate (ERD 03 {@code brn_business_hour_override}): a
 * closed day is one row with {@code isClosed}, an open day one row per range.
 */
@Entity
@Table(name = "brn_business_hour_override")
class BranchHourOverride {

	@Id
	private UUID id;

	private LocalDate overrideDate;

	@Column(name = "is_closed")
	private boolean closed;

	/** Wall-clock times: bound as {@code LocalTime}, never shifted by {@code hibernate.jdbc.time_zone}. */
	@JdbcType(LocalTimeJdbcType.class)
	private LocalTime openTime;

	@JdbcType(LocalTimeJdbcType.class)
	private LocalTime closeTime;

	private String reason;

	private Instant createdAt;

	protected BranchHourOverride() {
	}

	private BranchHourOverride(LocalDate date, boolean closed, TimeRange range, String reason, Instant now) {
		this.id = UuidV7.generate();
		this.overrideDate = date;
		this.closed = closed;
		this.openTime = (range != null) ? range.open() : null;
		this.closeTime = (range != null) ? range.close() : null;
		this.reason = reason;
		this.createdAt = now;
	}

	static List<BranchHourOverride> rowsOf(DayOverride override, Instant now) {
		if (override.closed()) {
			return List.of(new BranchHourOverride(override.date(), true, null, override.reason(), now));
		}
		return override.ranges().stream()
			.map(range -> new BranchHourOverride(override.date(), false, range, override.reason(), now))
			.toList();
	}

	LocalDate date() {
		return this.overrideDate;
	}

	boolean closed() {
		return this.closed;
	}

	TimeRange range() {
		return (this.openTime != null) ? new TimeRange(this.openTime, this.closeTime) : null;
	}

	String reason() {
		return this.reason;
	}

}
