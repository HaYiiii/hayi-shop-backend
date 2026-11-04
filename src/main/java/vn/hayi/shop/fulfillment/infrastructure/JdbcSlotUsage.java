package vn.hayi.shop.fulfillment.infrastructure;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vn.hayi.shop.fulfillment.domain.ClaimedSlot;
import vn.hayi.shop.fulfillment.domain.FulfillmentType;
import vn.hayi.shop.fulfillment.domain.SlotKey;
import vn.hayi.shop.fulfillment.domain.SlotUsage;
import vn.hayi.shop.fulfillment.domain.TimeWindow;
import vn.hayi.shop.fulfillment.domain.UsedCapacity;
import vn.hayi.shop.platform.persistence.UuidBinary;

/**
 * Reads {@code ful_slot_capacity} with plain SQL, as counters are (SOURCE-STRUCTURE §2.6). Times are read as text so
 * no time zone can shift them.
 */
@Repository
class JdbcSlotUsage implements SlotUsage {

	private final JdbcClient jdbc;

	JdbcSlotUsage(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Map<SlotKey, UsedCapacity> between(UUID branchId, FulfillmentType type, LocalDate from, LocalDate to) {
		Map<SlotKey, UsedCapacity> used = new HashMap<>();
		this.jdbc.sql("""
				SELECT slot_date, CAST(slot_start_time AS CHAR) AS slot_start, used_orders, used_items
				FROM ful_slot_capacity
				WHERE branch_id = ? AND fulfillment_type = ? AND slot_date BETWEEN ? AND ?
				""")
			.params(UuidBinary.toBytes(branchId), type.name(), from, to)
			.query((rs, row) -> Map.entry(
					new SlotKey(rs.getObject("slot_date", LocalDate.class), LocalTime.parse(rs.getString("slot_start"))),
					new UsedCapacity(rs.getInt("used_orders"), rs.getInt("used_items"))))
			.list()
			.forEach(entry -> used.put(entry.getKey(), entry.getValue()));
		return used;
	}

	@Override
	public List<ClaimedSlot> claimedFrom(UUID branchId, FulfillmentType type, LocalDate from) {
		return this.jdbc.sql("""
				SELECT slot_date, CAST(slot_start_time AS CHAR) AS slot_start, CAST(slot_end_time AS CHAR) AS slot_end,
				  used_orders, used_items
				FROM ful_slot_capacity
				WHERE branch_id = ? AND fulfillment_type = ? AND slot_date >= ? AND (used_orders > 0 OR used_items > 0)
				ORDER BY slot_date, slot_start_time
				""")
			.params(UuidBinary.toBytes(branchId), type.name(), from)
			.query((rs, row) -> new ClaimedSlot(rs.getObject("slot_date", LocalDate.class),
					new TimeWindow(LocalTime.parse(rs.getString("slot_start")), LocalTime.parse(rs.getString("slot_end"))),
					new UsedCapacity(rs.getInt("used_orders"), rs.getInt("used_items"))))
			.list();
	}

}
