package vn.hayi.shop.fulfillment.domain;

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
 * One override of a date (Decision S3): a closed day, or one pickup window of that day with the date's capacity. Part
 * of the {@link BranchFulfillmentConfig} aggregate (ERD 08 {@code ful_schedule_override}); only found through it.
 */
@Entity
@Table(name = "ful_schedule_override")
public class ScheduleOverride {

	@Id
	private UUID id;

	private LocalDate overrideDate;

	@Column(name = "is_closed")
	private boolean closed;

	@JdbcType(LocalTimeJdbcType.class)
	private LocalTime startTime;

	@JdbcType(LocalTimeJdbcType.class)
	private LocalTime endTime;

	private Integer maxOrders;

	private Integer maxItems;

	private String reason;

	private Instant createdAt;

	protected ScheduleOverride() {
	}

	private ScheduleOverride(DateOverride override, TimeWindow window, Instant now) {
		this.id = UuidV7.generate();
		this.overrideDate = override.date();
		this.closed = override.closed();
		this.startTime = (window != null) ? window.start() : null;
		this.endTime = (window != null) ? window.end() : null;
		this.maxOrders = override.maxOrders();
		this.maxItems = override.maxItems();
		this.reason = override.reason();
		this.createdAt = now;
	}

	static List<ScheduleOverride> of(DateOverride override, Instant now) {
		if (override.closed()) {
			return List.of(new ScheduleOverride(override, null, now));
		}
		return override.windows().stream().map(window -> new ScheduleOverride(override, window, now)).toList();
	}

	public UUID getId() {
		return this.id;
	}

	public LocalDate getDate() {
		return this.overrideDate;
	}

	public boolean isClosed() {
		return this.closed;
	}

	/** {@code null} for a closed day. */
	public TimeWindow getWindow() {
		return (this.startTime != null) ? new TimeWindow(this.startTime, this.endTime) : null;
	}

	public Integer getMaxOrders() {
		return this.maxOrders;
	}

	public Integer getMaxItems() {
		return this.maxItems;
	}

	public String getReason() {
		return this.reason;
	}

}
