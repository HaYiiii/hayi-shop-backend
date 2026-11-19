package vn.hayi.shop.catalog.application;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import vn.hayi.shop.catalog.domain.SellingScheduleSpec;

/**
 * A short Vietnamese text of a Product's selling schedules for customers (Decision AK3), e.g.
 * {@code 07:00–14:00 hằng ngày} or {@code T7, CN 08:00–12:00 · 01/12–31/12/2026}.
 */
final class SellingScheduleSummary {

	private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

	private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");

	private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	private static final String[] DAYS = { "T2", "T3", "T4", "T5", "T6", "T7", "CN" };

	private SellingScheduleSummary() {
	}

	/** {@code null} when there is no schedule: the Product is sold at any time. */
	static String of(List<SellingScheduleSpec> schedules) {
		if (schedules.isEmpty()) {
			return null;
		}
		return String.join("; ", schedules.stream().map(SellingScheduleSummary::schedule).toList());
	}

	private static String schedule(SellingScheduleSpec schedule) {
		String hours = schedule.windows().isEmpty() ? "cả ngày" : windows(schedule.windows());
		String dates = dates(schedule.startDate(), schedule.endDate());
		return (dates != null) ? hours + " · " + dates : hours;
	}

	/** Windows with the same hours are grouped: {@code T7, CN 08:00–12:00}; every-day ones read {@code … hằng ngày}. */
	private static String windows(List<SellingScheduleSpec.Window> windows) {
		Map<String, List<Integer>> daysByHours = new LinkedHashMap<>();
		for (SellingScheduleSpec.Window window : windows) {
			String hours = time(window.start()) + "–" + time(window.end());
			daysByHours.computeIfAbsent(hours, key -> new ArrayList<>())
				.add((window.dayOfWeek() != null) ? window.dayOfWeek() : 0);
		}
		List<String> parts = new ArrayList<>();
		daysByHours.forEach((hours, days) -> {
			if (days.contains(0)) {
				parts.add(hours + " hằng ngày");
			}
			else {
				parts.add(String.join(", ", days.stream().sorted().map(day -> DAYS[day - 1]).toList()) + " " + hours);
			}
		});
		return String.join(", ", parts);
	}

	private static String dates(LocalDate start, LocalDate end) {
		if (start == null && end == null) {
			return null;
		}
		if (start == null) {
			return "đến " + end.format(FULL);
		}
		if (end == null) {
			return "từ " + start.format(FULL);
		}
		return ((start.getYear() == end.getYear()) ? start.format(DAY_MONTH) : start.format(FULL)) + "–"
				+ end.format(FULL);
	}

	private static String time(LocalTime time) {
		return time.format(HH_MM);
	}

}
