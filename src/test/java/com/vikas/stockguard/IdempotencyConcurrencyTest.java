
package com.vikas.stockguard;

import com.vikas.stockguard.dto.req.ReserveInventoryRequest;
import com.vikas.stockguard.dto.res.ReservationResponseDto;
import com.vikas.stockguard.entity.Inventory;
import com.vikas.stockguard.repository.InventoryRepository;
import com.vikas.stockguard.repository.ReservationRepository;
import com.vikas.stockguard.service.InventoryService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class IdempotencyConcurrencyTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Test
    void sameKeyRequestsShouldReserveStockOnlyOnce() throws Exception {

        String sku = "IDEMP-" + UUID.randomUUID();
        String key = "IDEMP-KEY-" + UUID.randomUUID();

        Inventory inventory = inventoryRepository.save(
                new Inventory(sku, 10)
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<String>> futures = new ArrayList<>();

            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ReserveInventoryRequest request =
                            new ReserveInventoryRequest();

                    request.setSku(sku);
                    request.setQuantity(5);

                    ready.countDown();

                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "Timed out waiting for concurrent start"
                        );
                    }

                    return inventoryService
                            .reserveInventory(request, key)
                            .getStatus();
                }));
            }

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "Worker threads did not become ready in time"
            );

            start.countDown();

            for (Future<String> future : futures) {
                assertEquals(
                        "SUCCESS",
                        future.get(20, TimeUnit.SECONDS)
                );
            }

            Inventory finalInventory = inventoryRepository
                    .findBySku(sku)
                    .orElseThrow();

            assertEquals(
                    5,
                    finalInventory.getAvailableQuantity(),
                    "The same idempotency key should deduct stock only once"
            );

        } finally {
            start.countDown();
            executor.shutdownNow();

            boolean terminated =
                    executor.awaitTermination(10, TimeUnit.SECONDS);

            if (!terminated) {
                throw new IllegalStateException(
                        "Executor did not terminate in time"
                );
            }

            reservationRepository.deleteByIdempotencyKey(key);
            inventoryRepository.deleteById(inventory.getId());
        }
    }

    @Test
    void sameIdempotencyKeyWithDifferentQuantityShouldBeRejected() {

        String sku = "IDEM-" + UUID.randomUUID();
        String idempotencyKey = "IDEM-KEY-" + UUID.randomUUID();

        Inventory savedInventory = inventoryRepository.save(
                new Inventory(sku, 20)
        );

        try {
            ReserveInventoryRequest firstRequest =
                    new ReserveInventoryRequest();

            firstRequest.setSku(sku);
            firstRequest.setQuantity(5);

            ReservationResponseDto firstResponse =
                    inventoryService.reserveInventory(
                            firstRequest,
                            idempotencyKey
                    );

            assertEquals("SUCCESS", firstResponse.getStatus());

            ReserveInventoryRequest secondRequest =
                    new ReserveInventoryRequest();

            secondRequest.setSku(sku);
            secondRequest.setQuantity(10);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> inventoryService.reserveInventory(
                            secondRequest,
                            idempotencyKey
                    )
            );

            Inventory updatedInventory = inventoryRepository
                    .findBySku(sku)
                    .orElseThrow();

            assertEquals(
                    15,
                    updatedInventory.getAvailableQuantity(),
                    "Only the first reservation should deduct stock"
            );

        } finally {
            reservationRepository.deleteByIdempotencyKey(idempotencyKey);
            inventoryRepository.deleteById(savedInventory.getId());
        }
    }
    @Test
    void rejectedReservationShouldRemainRejectedOnRetry() {

        String sku = "REJECT-" + UUID.randomUUID();
        String idempotencyKey = "REJECT-KEY-" + UUID.randomUUID();

        Inventory inventory = inventoryRepository.save(
                new Inventory(sku, 5)
        );

        try {
            // 1. Attempt to reserve 10 units when only 5 are available.
            ReserveInventoryRequest request =
                    new ReserveInventoryRequest();

            request.setSku(sku);
            request.setQuantity(10);

            ReservationResponseDto firstResponse =
                    inventoryService.reserveInventory(
                            request,
                            idempotencyKey
                    );

            assertEquals("REJECTED", firstResponse.getStatus());

            // 2. Increase inventory from 5 to 15 units.
            inventory.setAvailableQuantity(15);
            inventoryRepository.saveAndFlush(inventory);

            // 3. Retry the exact same request with the same key.
            ReservationResponseDto retryResponse =
                    inventoryService.reserveInventory(
                            request,
                            idempotencyKey
                    );

            // 4. The original rejected result must be returned.
            assertEquals("REJECTED", retryResponse.getStatus());

            assertEquals(
                    firstResponse.getMessage(),
                    retryResponse.getMessage(),
                    "Retry should return the original rejection message"
            );

            // 5. Stock must remain at 15; the retry must not reserve anything.
            Inventory updatedInventory = inventoryRepository
                    .findBySku(sku)
                    .orElseThrow();

            assertEquals(
                    15,
                    updatedInventory.getAvailableQuantity(),
                    "A rejected retry must not deduct stock"
            );

        } finally {
            reservationRepository.deleteByIdempotencyKey(idempotencyKey);
            inventoryRepository.deleteById(inventory.getId());
        }
    }
    @Test
    void reservationForNonexistentSkuShouldBeRejected() {

        String sku = "MISSING-" + UUID.randomUUID();
        String idempotencyKey = "MISSING-KEY-" + UUID.randomUUID();

        try {
            // 1. Create a request for a SKU that does not exist.
            ReserveInventoryRequest request =
                    new ReserveInventoryRequest();

            request.setSku(sku);
            request.setQuantity(5);

            // 2. First attempt should be rejected.
            ReservationResponseDto firstResponse =
                    inventoryService.reserveInventory(
                            request,
                            idempotencyKey
                    );

            assertEquals("REJECTED", firstResponse.getStatus());

            assertTrue(
                    firstResponse.getMessage().contains("SKU does not exist"),
                    "Response should explain that the SKU does not exist"
            );

            // 3. Retry using the same request and idempotency key.
            ReservationResponseDto retryResponse =
                    inventoryService.reserveInventory(
                            request,
                            idempotencyKey
                    );

            // 4. The original rejection should be returned.
            assertEquals("REJECTED", retryResponse.getStatus());

            assertEquals(
                    firstResponse.getMessage(),
                    retryResponse.getMessage(),
                    "Retry should return the original rejection message"
            );

            // 5. Confirm no inventory was created for this SKU.
            assertTrue(
                    inventoryRepository.findBySku(sku).isEmpty(),
                    "A reservation attempt must not create inventory"
            );

        } finally {
            // Remove the stored reservation record.
            reservationRepository.deleteByIdempotencyKey(idempotencyKey);
        }
    }

    @Test
    void differentIdempotencyKeysShouldReserveIndependently() {
        String sku = "KEY-" + java.util.UUID.randomUUID();
        String key1 = "key-1-" + java.util.UUID.randomUUID();
        String key2 = "key-2-" + java.util.UUID.randomUUID();

        createInventory(sku, 20);

        try {
            ReserveInventoryRequest first = new ReserveInventoryRequest();
            first.setSku(sku);
            first.setQuantity(5);

            ReserveInventoryRequest second = new ReserveInventoryRequest();
            second.setSku(sku);
            second.setQuantity(7);

            ReservationResponseDto result1 =
                    inventoryService.reserveInventory(first, key1);

            ReservationResponseDto result2 =
                    inventoryService.reserveInventory(second, key2);

            assertEquals("SUCCESS", result1.getStatus());
            assertEquals("SUCCESS", result2.getStatus());

            Inventory stored = inventoryRepository.findBySku(sku)
                    .orElseThrow();

            assertEquals(8, stored.getAvailableQuantity());
        } finally {
            reservationRepository.deleteByIdempotencyKey(key1);
            reservationRepository.deleteByIdempotencyKey(key2);
            inventoryRepository.findBySku(sku)
                    .ifPresent(inventoryRepository::delete);
        }
    }

    @Test
    void sameIdempotencyKeyWithDifferentSkuShouldBeRejected() {
        String sku1 = "SKU-A-" + java.util.UUID.randomUUID();
        String sku2 = "SKU-B-" + java.util.UUID.randomUUID();
        String key = "same-key-" + java.util.UUID.randomUUID();

        createInventory(sku1, 20);
        createInventory(sku2, 20);

        try {
            ReserveInventoryRequest first = new ReserveInventoryRequest();
            first.setSku(sku1);
            first.setQuantity(5);

            ReserveInventoryRequest second = new ReserveInventoryRequest();
            second.setSku(sku2);
            second.setQuantity(5);

            ReservationResponseDto result =
                    inventoryService.reserveInventory(first, key);

            assertEquals("SUCCESS", result.getStatus());

            assertThrows(
                    IllegalArgumentException.class,
                    () -> inventoryService.reserveInventory(second, key)
            );

            assertEquals(
                    15,
                    inventoryRepository.findBySku(sku1)
                            .orElseThrow()
                            .getAvailableQuantity()
            );

            assertEquals(
                    20,
                    inventoryRepository.findBySku(sku2)
                            .orElseThrow()
                            .getAvailableQuantity()
            );
        } finally {
            reservationRepository.deleteByIdempotencyKey(key);

            inventoryRepository.findBySku(sku1)
                    .ifPresent(inventoryRepository::delete);
            inventoryRepository.findBySku(sku2)
                    .ifPresent(inventoryRepository::delete);
        }
    }

    private Inventory createInventory(String sku, int quantity) {
        return inventoryRepository.save(
                new Inventory(sku, quantity)
        );
    }
}
