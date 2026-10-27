package com.vikas.stockguard.repository;

import com.vikas.stockguard.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory,Long> {


    Optional<Inventory> findBySku(String sku);

    boolean existsBySku(String sku);

    @Modifying
    @Query(value = """
            UPDATE inventory
            SET available_quantity = available_quantity - :quantity,
                updated_at = CURRENT_TIMESTAMP
            WHERE sku = :sku
              AND available_quantity >= :quantity
            """, nativeQuery = true)
    int reserveStock(
            @Param("sku") String sku,
            @Param("quantity") int quantity
    );
}
