package vn.hayi.shop.cart.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.OptimisticLock;

import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.id.UuidV7;

/**
 * A signed-in Customer's mutable shopping intent at one Branch (Cart & Pricing §6.1–§6.3): one {@code ACTIVE} Cart
 * per Customer–Branch, whose Branch never changes. A change is made on the Cart loaded for update, which moves the
 * version once. The row is created by the application ({@code uk_crt_cart__active_customer_branch} decides a race); this class
 * only loads and changes it.
 */
@Entity
@Table(name = "crt_cart")
public class Cart {

	/** Most of one item (as the resolver's quantity, Decision AJ3, AV2). */
	public static final int MAX_QUANTITY = 99;

	/** Most different items in a Cart (as the resolver's lines, Decision AJ4, AV2). */
	public static final int MAX_ITEMS = 50;

	@Id
	private UUID id;

	@Column(updatable = false)
	private UUID customerId;

	@Column(updatable = false)
	private UUID branchId;

	@Enumerated(EnumType.STRING)
	private CartStatus status;

	private String couponCode;

	@Column(updatable = false)
	private Instant createdAt;

	/** Excluded: the version moves once per change through {@link CartRepository#findActiveForUpdate}. */
	@OptimisticLock(excluded = true)
	private Instant updatedAt;

	@Version
	private long version;

	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
	@OptimisticLock(excluded = true)
	@JoinColumn(name = "cart_id", nullable = false, updatable = false)
	@OrderBy("createdAt ASC, id ASC")
	@BatchSize(size = 100)
	private List<CartItem> items = new ArrayList<>();

	protected Cart() {
	}

	/** A new, empty {@code ACTIVE} Cart; the application inserts its row only if none is active yet. */
	public static Cart open(UUID customerId, UUID branchId, Instant now) {
		Cart cart = new Cart();
		cart.id = UuidV7.generate();
		cart.customerId = customerId;
		cart.branchId = branchId;
		cart.status = CartStatus.ACTIVE;
		cart.createdAt = now;
		cart.updatedAt = now;
		return cart;
	}

	/**
	 * Adds the quantity to the equivalent item, or adds a new item (Cart & Pricing §6.3). The price is the one the
	 * Customer has just seen.
	 * @throws DomainException {@code CART_ITEM_QUANTITY_LIMIT}, {@code CART_ITEM_LIMIT}
	 */
	public CartItem add(CartSelection selection, int quantity, Long unitPrice, Instant now) {
		String key = selection.equivalenceKey();
		CartItem item = this.items.stream().filter(candidate -> candidate.getEquivalenceKey().equals(key)).findFirst()
			.orElse(null);
		if (item != null) {
			if (item.getQuantity() + quantity > MAX_QUANTITY) {
				throw new DomainException(CartErrorCode.CART_ITEM_QUANTITY_LIMIT,
						"Cart " + this.id + " item " + item.getId() + " would exceed " + MAX_QUANTITY);
			}
			item.increase(quantity, unitPrice, now);
		}
		else {
			if (quantity > MAX_QUANTITY) {
				throw new DomainException(CartErrorCode.CART_ITEM_QUANTITY_LIMIT,
						"Cart " + this.id + " new item would exceed " + MAX_QUANTITY);
			}
			if (this.items.size() >= MAX_ITEMS) {
				throw new DomainException(CartErrorCode.CART_ITEM_LIMIT,
						"Cart " + this.id + " already holds " + MAX_ITEMS + " items");
			}
			item = CartItem.create(selection, quantity, unitPrice, now);
			this.items.add(item);
		}
		this.updatedAt = now;
		return item;
	}

	/**
	 * Changes an item to {@code selection} and {@code quantity} (Decision AW1). When it becomes equivalent to another
	 * item, that one gets the quantity and the edited one is removed.
	 * @return the item that remains, and whether the edited one merged into it
	 * @throws DomainException {@code CART_ITEM_NOT_FOUND}, {@code CART_ITEM_QUANTITY_LIMIT}
	 */
	public Edited edit(UUID itemId, CartSelection selection, int quantity, Long unitPrice, Instant now) {
		CartItem item = item(itemId);
		if (quantity > MAX_QUANTITY) {
			throw new DomainException(CartErrorCode.CART_ITEM_QUANTITY_LIMIT,
					"Cart " + this.id + " item " + itemId + " would exceed " + MAX_QUANTITY);
		}
		String key = selection.equivalenceKey();
		CartItem other = this.items.stream()
			.filter(candidate -> candidate != item && candidate.getEquivalenceKey().equals(key))
			.findFirst()
			.orElse(null);
		this.updatedAt = now;
		if (other == null) {
			item.change(selection, quantity, unitPrice, now);
			return new Edited(item.getId(), false);
		}
		if (other.getQuantity() + quantity > MAX_QUANTITY) {
			throw new DomainException(CartErrorCode.CART_ITEM_QUANTITY_LIMIT,
					"Cart " + this.id + " item " + other.getId() + " would exceed " + MAX_QUANTITY);
		}
		other.increase(quantity, unitPrice, now);
		this.items.remove(item);
		return new Edited(other.getId(), true);
	}

	/**
	 * Merges a guest Cart line (Cart & Pricing §6.8, Decision AX2): an equivalent item takes the larger quantity, so
	 * merging the same line again changes nothing; otherwise a new item is added.
	 * @return the item the line is in, whether it was already there, and whether anything changed
	 * @throws DomainException {@code CART_ITEM_QUANTITY_LIMIT}, {@code CART_ITEM_LIMIT}
	 */
	public Merged merge(CartSelection selection, int quantity, Long unitPrice, Instant now) {
		if (quantity > MAX_QUANTITY) {
			throw new DomainException(CartErrorCode.CART_ITEM_QUANTITY_LIMIT,
					"Cart " + this.id + " merged line would exceed " + MAX_QUANTITY);
		}
		String key = selection.equivalenceKey();
		CartItem item = this.items.stream()
			.filter(candidate -> candidate.getEquivalenceKey().equals(key))
			.findFirst()
			.orElse(null);
		if (item != null) {
			boolean raised = item.raiseTo(quantity, unitPrice, now);
			if (raised) {
				this.updatedAt = now;
			}
			return new Merged(item.getId(), true, raised);
		}
		if (this.items.size() >= MAX_ITEMS) {
			throw new DomainException(CartErrorCode.CART_ITEM_LIMIT,
					"Cart " + this.id + " already holds " + MAX_ITEMS + " items");
		}
		CartItem added = CartItem.create(selection, quantity, unitPrice, now);
		this.items.add(added);
		this.updatedAt = now;
		return new Merged(added.getId(), false, true);
	}

	/** The outcome of {@link #merge}. */
	public record Merged(UUID cartItemId, boolean existed, boolean changed) {
	}

	/** @throws DomainException {@code CART_ITEM_NOT_FOUND} (Decision AW3) */
	public void remove(UUID itemId, Instant now) {
		this.items.remove(item(itemId));
		this.updatedAt = now;
	}

	/** Removes every item; the Cart stays {@code ACTIVE} and keeps its coupon (Decision AW3). */
	public void clear(Instant now) {
		this.items.clear();
		this.updatedAt = now;
	}

	/** @throws DomainException {@code CART_ITEM_NOT_FOUND} unless the item is in this Cart */
	public CartItem item(UUID itemId) {
		return this.items.stream()
			.filter(candidate -> candidate.getId().equals(itemId))
			.findFirst()
			.orElseThrow(() -> notFound(itemId));
	}

	/** {@code CART_ITEM_NOT_FOUND}: the item is not in the caller's Cart (Decision AW3). */
	public static DomainException notFound(UUID itemId) {
		return new DomainException(CartErrorCode.CART_ITEM_NOT_FOUND, "Cart item " + itemId + " is not in the Cart");
	}

	/** The outcome of {@link #edit}: {@code cartItemId} is the item that remains. */
	public record Edited(UUID cartItemId, boolean merged) {
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getCustomerId() {
		return this.customerId;
	}

	public UUID getBranchId() {
		return this.branchId;
	}

	public CartStatus getStatus() {
		return this.status;
	}

	public String getCouponCode() {
		return this.couponCode;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

	public long getVersion() {
		return this.version;
	}

	public List<CartItem> getItems() {
		return List.copyOf(this.items);
	}

}
