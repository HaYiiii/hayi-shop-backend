package vn.hayi.shop.cart.domain;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import org.hibernate.annotations.BatchSize;

import vn.hayi.shop.shared.id.UuidV7;

/**
 * One shopping intent in a Cart (Cart & Pricing §6.3): changed only through its {@link Cart}. The price kept here is
 * the one the Customer last saw, only to detect a change (ERD 06); it never prices anything.
 */
@Entity
@Table(name = "crt_cart_item")
public class CartItem {

	@Id
	private UUID id;

	@Column(updatable = false)
	private UUID productId;

	private UUID variantId;

	private int quantity;

	private String noteNormalized;

	private String equivalenceKey;

	private Long lastSeenUnitPrice;

	private String issueCode;

	@Column(updatable = false)
	private Instant createdAt;

	private Instant updatedAt;

	@ElementCollection
	@CollectionTable(name = "crt_cart_item_option", joinColumns = @JoinColumn(name = "cart_item_id"))
	@BatchSize(size = 100)
	private Set<CartItemOption> options = new LinkedHashSet<>();

	protected CartItem() {
	}

	static CartItem create(CartSelection selection, int quantity, Long unitPrice, Instant now) {
		CartItem item = new CartItem();
		item.id = UuidV7.generate();
		item.productId = selection.productId();
		item.variantId = selection.variantId();
		item.options.addAll(selection.options());
		item.noteNormalized = selection.note();
		item.equivalenceKey = selection.equivalenceKey();
		item.quantity = quantity;
		item.lastSeenUnitPrice = unitPrice;
		item.createdAt = now;
		item.updatedAt = now;
		return item;
	}

	void increase(int quantity, Long unitPrice, Instant now) {
		this.quantity += quantity;
		this.lastSeenUnitPrice = unitPrice;
		this.updatedAt = now;
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getProductId() {
		return this.productId;
	}

	public UUID getVariantId() {
		return this.variantId;
	}

	public int getQuantity() {
		return this.quantity;
	}

	public String getNote() {
		return this.noteNormalized;
	}

	public String getEquivalenceKey() {
		return this.equivalenceKey;
	}

	public Long getLastSeenUnitPrice() {
		return this.lastSeenUnitPrice;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public List<CartItemOption> getOptions() {
		return List.copyOf(this.options);
	}

}
