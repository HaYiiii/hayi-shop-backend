package vn.hayi.shop.cart.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.cart.domain.Cart;
import vn.hayi.shop.cart.domain.CartItem;
import vn.hayi.shop.cart.domain.CartItemOption;
import vn.hayi.shop.cart.domain.CartRepository;
import vn.hayi.shop.cart.domain.CartSelection;
import vn.hayi.shop.catalog.api.CatalogSelectionResolver;
import vn.hayi.shop.catalog.api.CatalogSelectionResolver.ResolvedLine;
import vn.hayi.shop.catalog.api.CatalogSelectionResolver.ResolvedOption;
import vn.hayi.shop.catalog.api.CatalogSelectionResolver.SelectionRequest;
import vn.hayi.shop.customer.api.CustomerErrorCode;
import vn.hayi.shop.customer.api.CustomerProfiles;
import vn.hayi.shop.platform.persistence.UuidBinary;
import vn.hayi.shop.shared.concurrency.ExpectedVersion;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.patch.Change;

/**
 * The signed-in Customer's server Cart per Branch (Cart & Pricing §6.2–§6.4, API 03 §1, Decision AV). The Customer
 * always comes from the session's user (API 03 §4); every item is checked and priced by the Catalog resolver.
 */
@Service
public class CartService {

	/** Longest note once normalized (Decision AV2). */
	public static final int MAX_NOTE_LENGTH = CartSelection.MAX_NOTE_LENGTH;

	/** Most of one item (Decision AV2). */
	public static final int MAX_QUANTITY = Cart.MAX_QUANTITY;

	private final CartRepository carts;

	private final CatalogSelectionResolver resolver;

	private final CustomerProfiles customers;

	private final JdbcClient jdbc;

	private final Clock clock;

	CartService(CartRepository carts, CatalogSelectionResolver resolver, CustomerProfiles customers, JdbcClient jdbc,
			Clock clock) {
		this.carts = carts;
		this.resolver = resolver;
		this.customers = customers;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/**
	 * The Cart at {@code at} (default now); an empty one when none is active. Writes nothing (AV1).
	 * @throws DomainException {@code BRANCH_NOT_FOUND} unless the Branch is {@code ACTIVE}
	 */
	@Transactional(readOnly = true)
	public CartView view(UUID userId, UUID branchId, Instant at) {
		UUID customerId = customerOf(userId);
		Cart cart = this.carts.findActive(customerId, branchId).orElse(null);
		List<CartItem> items = (cart != null) ? cart.getItems() : List.of();
		// Always resolve, even with no item, so an unknown or inactive Branch is refused the same way.
		Map<String, ResolvedLine> resolved = new HashMap<>();
		this.resolver.resolve(branchId, items.stream().map(CartService::request).toList(), at)
			.lines()
			.forEach(line -> resolved.put(line.ref(), line));
		if (cart == null) {
			return CartView.empty(branchId);
		}
		List<CartView.Item> views = items.stream().map(item -> item(item, resolved.get(item.getId().toString()))).toList();
		long subtotal = views.stream().map(CartView.Item::lineTotal).filter(Objects::nonNull).mapToLong(Long::longValue)
			.sum();
		boolean blocking = views.stream().anyMatch(view -> !view.issues().isEmpty());
		return new CartView(cart.getId(), branchId, cart.getVersion(), views, cart.getCouponCode(), subtotal, blocking,
				cart.getUpdatedAt());
	}

	/**
	 * Adds an item checked at {@code at} (AV2). The Cart is created on first use; two first adds at once leave one
	 * Cart, and adds to one Cart run one after the other (the row is locked). {@code READ_COMMITTED}, so the items
	 * read after the lock include every change committed before it (see {@link CartRepository#findActiveForUpdate}).
	 * @param note already normalized ({@link CartSelection#normalizeNote})
	 * @throws CartItemInvalidException when the resolver finds any issue; nothing is added
	 * @throws DomainException {@code BRANCH_NOT_FOUND}, {@code CART_ITEM_QUANTITY_LIMIT}, {@code CART_ITEM_LIMIT}
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void add(UUID userId, UUID branchId, UUID productId, UUID variantId, List<UUID> optionItemIds, int quantity,
			String note, Instant at) {
		UUID customerId = customerOf(userId);
		ResolvedLine line = resolveValid(branchId, productId, variantId, optionItemIds, quantity, at);
		Instant now = this.clock.instant();
		Cart cart = activeCartForUpdate(customerId, branchId, now);
		// Managed: a new item is persisted by cascade when the transaction flushes.
		cart.add(selection(productId, variantId, line, note), quantity, line.unitPrice(), now);
	}

	/**
	 * Changes an item (Decision AW1): what the patch leaves out keeps its value; the item after the edit is resolved
	 * at {@code at} and merges into an equivalent item. Runs under the Cart's row lock, {@code READ_COMMITTED} as
	 * {@link #add}.
	 * @param expected the Cart version from {@code If-Match}, or {@code null} (AW2)
	 * @throws CartItemInvalidException when the resolver finds any issue; nothing changes
	 * @throws DomainException {@code VERSION_CONFLICT}, {@code CART_ITEM_NOT_FOUND}, {@code BRANCH_NOT_FOUND},
	 * {@code CART_ITEM_QUANTITY_LIMIT}
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public Edited edit(UUID userId, UUID branchId, UUID cartItemId, ItemPatch patch, ExpectedVersion expected,
			Instant at) {
		UUID customerId = customerOf(userId);
		Cart cart = lockedCart(customerId, branchId, expected).orElseThrow(() -> Cart.notFound(cartItemId));
		CartItem item = cart.item(cartItemId);
		UUID variantId = patch.variantId().applyTo(item.getVariantId());
		List<UUID> optionItemIds = patch.optionItemIds()
			.applyTo(item.getOptions().stream().map(CartItemOption::optionItemId).toList());
		String note = patch.note().applyTo(item.getNote());
		int quantity = patch.quantity().applyTo(item.getQuantity());
		ResolvedLine line = resolveValid(branchId, item.getProductId(), variantId, optionItemIds, quantity, at);
		Cart.Edited edited = cart.edit(cartItemId, selection(item.getProductId(), variantId, line, note), quantity,
				line.unitPrice(), this.clock.instant());
		return new Edited(edited.cartItemId(), edited.merged());
	}

	/** The item that remains after an edit, and whether the edited one merged into it (Decision AW1). */
	public record Edited(UUID cartItemId, boolean merged) {
	}

	/**
	 * Removes an item (Decision AW3).
	 * @param expected the Cart version from {@code If-Match}, or {@code null} (AW2)
	 * @throws DomainException {@code BRANCH_NOT_FOUND}, {@code VERSION_CONFLICT}, {@code CART_ITEM_NOT_FOUND}
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void remove(UUID userId, UUID branchId, UUID cartItemId, ExpectedVersion expected) {
		UUID customerId = customerOf(userId);
		requireActiveBranch(branchId);
		lockedCart(customerId, branchId, expected).orElseThrow(() -> Cart.notFound(cartItemId))
			.remove(cartItemId, this.clock.instant());
	}

	/**
	 * Removes every item; the Cart stays {@code ACTIVE}. No Cart → nothing is created (Decision AW3).
	 * @param expected the Cart version from {@code If-Match}, or {@code null} (AW2); no Cart is version 0
	 * @throws DomainException {@code BRANCH_NOT_FOUND}, {@code VERSION_CONFLICT}
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void clear(UUID userId, UUID branchId, ExpectedVersion expected) {
		UUID customerId = customerOf(userId);
		requireActiveBranch(branchId);
		lockedCart(customerId, branchId, expected).ifPresent(cart -> cart.clear(this.clock.instant()));
	}

	/**
	 * A partial edit of an item ({@code note} already normalized); a {@link Change#keep()} keeps the current value.
	 */
	public record ItemPatch(Change<Integer> quantity, Change<UUID> variantId, Change<List<UUID>> optionItemIds,
			Change<String> note) {
	}

	/** Stripped, inner whitespace collapsed, blank → {@code null} (Cart & Pricing §6.3, Decision AV2). */
	public static String normalizeNote(String note) {
		return CartSelection.normalizeNote(note);
	}

	/** The caller's {@code ACTIVE} Carts, most recently changed first, without prices (AV3). */
	@Transactional(readOnly = true)
	public List<CartView.Summary> list(UUID userId) {
		UUID customerId = customerOf(userId);
		return this.jdbc.sql("""
				SELECT c.id, c.branch_id, c.updated_at, COALESCE(SUM(i.quantity), 0) AS item_count
				FROM crt_cart c LEFT JOIN crt_cart_item i ON i.cart_id = c.id
				WHERE c.customer_id = ? AND c.status = 'ACTIVE'
				GROUP BY c.id, c.branch_id, c.updated_at
				ORDER BY c.updated_at DESC, c.id DESC
				""")
			.param(UuidBinary.toBytes(customerId))
			.query((rs, row) -> new CartView.Summary(UuidBinary.fromBytes(rs.getBytes("id")),
					UuidBinary.fromBytes(rs.getBytes("branch_id")), rs.getInt("item_count"),
					rs.getTimestamp("updated_at").toInstant()))
			.list();
	}

	/**
	 * Inserts an empty {@code ACTIVE} Cart unless one is there ({@code uk_crt_cart__active_customer_branch} waits
	 * for a concurrent first add and then keeps its row), then locks the one that is active.
	 */
	private Cart activeCartForUpdate(UUID customerId, UUID branchId, Instant now) {
		Cart opened = Cart.open(customerId, branchId, now);
		this.jdbc.sql("""
				INSERT INTO crt_cart (id, customer_id, branch_id, status, created_at, updated_at, version)
				VALUES (?, ?, ?, 'ACTIVE', ?, ?, 0)
				ON DUPLICATE KEY UPDATE id = id
				""")
			.params(UuidBinary.toBytes(opened.getId()), UuidBinary.toBytes(customerId), UuidBinary.toBytes(branchId),
					now, now)
			.update();
		return this.carts.findActiveForUpdate(customerId, branchId)
			.orElseThrow(() -> new IllegalStateException("No ACTIVE Cart right after inserting one"));
	}

	/**
	 * Locks the caller's {@code ACTIVE} Cart, if any, and checks {@code expected} against the version it has under
	 * the lock (no Cart is version 0), then loads it for update, which moves the version once.
	 */
	private Optional<Cart> lockedCart(UUID customerId, UUID branchId, ExpectedVersion expected) {
		Optional<Long> version = this.jdbc.sql("""
				SELECT version FROM crt_cart WHERE customer_id = ? AND branch_id = ? AND status = 'ACTIVE' FOR UPDATE
				""")
			.params(UuidBinary.toBytes(customerId), UuidBinary.toBytes(branchId))
			.query(Long.class)
			.optional();
		if (expected != null) {
			expected.verify(version.orElse(0L));
		}
		return version.isPresent() ? this.carts.findActiveForUpdate(customerId, branchId) : Optional.empty();
	}

	/** @throws DomainException {@code BRANCH_NOT_FOUND} unless the Branch is {@code ACTIVE} */
	private void requireActiveBranch(UUID branchId) {
		this.resolver.resolve(branchId, List.of(), null);
	}

	/** @throws CartItemInvalidException when the resolver finds any issue with the line */
	private ResolvedLine resolveValid(UUID branchId, UUID productId, UUID variantId, List<UUID> optionItemIds,
			int quantity, Instant at) {
		ResolvedLine line = this.resolver
			.resolve(branchId, List.of(new SelectionRequest("item", productId, variantId, optionItemIds, quantity)), at)
			.lines()
			.getFirst();
		if (!line.valid()) {
			throw new CartItemInvalidException(line.issues().stream().map(CartView.ItemIssue::of).toList());
		}
		return line;
	}

	private static CartSelection selection(UUID productId, UUID variantId, ResolvedLine line, String note) {
		return new CartSelection(productId, variantId,
				line.options()
					.stream()
					.map(option -> new CartItemOption(option.optionItemId(), option.optionGroupId()))
					.toList(),
				note);
	}

	private UUID customerOf(UUID userId) {
		return this.customers.findByUser(userId)
			.map(CustomerProfiles.Summary::customerId)
			.orElseThrow(() -> new DomainException(CustomerErrorCode.CUSTOMER_PROFILE_NOT_FOUND,
					"User " + userId + " has no Customer profile"));
	}

	private static SelectionRequest request(CartItem item) {
		return new SelectionRequest(item.getId().toString(), item.getProductId(), item.getVariantId(),
				item.getOptions().stream().map(CartItemOption::optionItemId).toList(), item.getQuantity());
	}

	private static CartView.Item item(CartItem item, ResolvedLine line) {
		Map<UUID, ResolvedOption> offered = new HashMap<>();
		line.options().forEach(option -> offered.put(option.optionItemId(), option));
		// The stored set has no order: show the Catalog's (group, then item), unknown items last by id.
		Map<UUID, Integer> position = new HashMap<>();
		List<UUID> order = line.normalizedOptionItemIds();
		for (int i = 0; i < order.size(); i++) {
			position.put(order.get(i), i);
		}
		List<CartView.Option> options = item.getOptions()
			.stream()
			.sorted(Comparator
				.comparingInt((CartItemOption option) -> position.getOrDefault(option.optionItemId(), Integer.MAX_VALUE))
				.thenComparing(option -> option.optionItemId().toString()))
			.map(option -> {
				ResolvedOption resolved = offered.get(option.optionItemId());
				return (resolved != null)
						? new CartView.Option(option.optionItemId(), resolved.optionGroupName(),
								resolved.optionItemName(), resolved.extraPrice())
						: new CartView.Option(option.optionItemId(), null, null, null);
			})
			.toList();
		return new CartView.Item(item.getId(), item.getProductId(), line.productName(), item.getVariantId(),
				line.variantName(), options, item.getNote(), item.getQuantity(), line.unitPrice(), line.lineTotal(),
				line.issues().stream().map(CartView.ItemIssue::of).toList());
	}

}
