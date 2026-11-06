package vn.hayi.shop.catalog.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import vn.hayi.shop.catalog.domain.OptionGroup;
import vn.hayi.shop.catalog.domain.OptionItem;

/** An option group with every item, as read in the Backoffice (Decision AB2). */
public record OptionGroupView(UUID optionGroupId, String code, String name, String selectionType, int minSelect,
		int maxSelect, String status, Instant createdAt, Instant updatedAt, long version, List<Item> items) {

	static OptionGroupView of(OptionGroup group) {
		return new OptionGroupView(group.getId(), group.getCode(), group.getName(), group.getSelectionType().name(),
				group.getMinSelect(), group.getMaxSelect(), group.getStatus().name(), group.getCreatedAt(),
				group.getUpdatedAt(), group.getVersion(), group.getItems().stream().map(Item::of).toList());
	}

	public record Item(UUID optionItemId, String code, String name, long defaultExtraPrice, boolean isDefaultSelected,
			String status, int sortOrder) {

		static Item of(OptionItem item) {
			return new Item(item.getId(), item.getCode(), item.getName(), item.getDefaultExtraPrice(),
					item.isDefaultSelected(), item.getStatus().name(), item.getSortOrder());
		}

	}

}
