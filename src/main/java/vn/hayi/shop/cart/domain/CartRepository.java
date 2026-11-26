package vn.hayi.shop.cart.domain;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface CartRepository extends Repository<Cart, UUID> {

	@Query("select c from Cart c where c.customerId = :customerId and c.branchId = :branchId and c.status = vn.hayi.shop.cart.domain.CartStatus.ACTIVE")
	Optional<Cart> findActive(UUID customerId, UUID branchId);

	/**
	 * Locks the row, so concurrent changes to one Cart run one after the other, and moves its version once: item
	 * changes alone do not (they are excluded from optimistic locking), and a fresh Cart's {@code updatedAt} may not
	 * change either.
	 */
	@Lock(LockModeType.PESSIMISTIC_FORCE_INCREMENT)
	@Query("select c from Cart c where c.customerId = :customerId and c.branchId = :branchId and c.status = vn.hayi.shop.cart.domain.CartStatus.ACTIVE")
	Optional<Cart> findActiveForUpdate(UUID customerId, UUID branchId);

	Cart save(Cart cart);

}
