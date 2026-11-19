package vn.hayi.shop.catalog.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import org.hibernate.annotations.BatchSize;

import vn.hayi.shop.shared.id.UuidV7;

/** One stored selling schedule of a Product (ERD 05, Decision AK); part of the {@link Product} aggregate. */
@Entity
@Table(name = "cat_product_selling_schedule")
public class ProductSellingSchedule {

	@Id
	private UUID id;

	private LocalDate startDate;

	private LocalDate endDate;

	/** {@code ACTIVE} only in v1; {@code INACTIVE} is kept in the schema for later. */
	private String status;

	@Column(updatable = false)
	private Instant createdAt;

	private Instant updatedAt;

	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
	@JoinColumn(name = "schedule_id", nullable = false, updatable = false)
	@BatchSize(size = 100)
	private List<ProductSellingWindow> windows = new ArrayList<>();

	protected ProductSellingSchedule() {
	}

	static ProductSellingSchedule of(SellingScheduleSpec spec, Instant now) {
		ProductSellingSchedule schedule = new ProductSellingSchedule();
		schedule.id = UuidV7.generate();
		schedule.startDate = spec.startDate();
		schedule.endDate = spec.endDate();
		schedule.status = "ACTIVE";
		schedule.createdAt = now;
		schedule.updatedAt = now;
		spec.windows().forEach(window -> schedule.windows.add(ProductSellingWindow.of(window)));
		return schedule;
	}

	/** As a value, windows in a stable order, so a replace that changes nothing is recognised. */
	public SellingScheduleSpec spec() {
		return new SellingScheduleSpec(this.startDate, this.endDate, this.windows.stream()
			.map(ProductSellingWindow::spec)
			.sorted(WINDOW_ORDER)
			.toList());
	}

	static final Comparator<SellingScheduleSpec.Window> WINDOW_ORDER = Comparator
		.comparing((SellingScheduleSpec.Window window) -> (window.dayOfWeek() != null) ? window.dayOfWeek() : 0)
		.thenComparing(SellingScheduleSpec.Window::start)
		.thenComparing(SellingScheduleSpec.Window::end);

}
