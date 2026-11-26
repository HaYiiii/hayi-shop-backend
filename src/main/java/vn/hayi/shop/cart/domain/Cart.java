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
