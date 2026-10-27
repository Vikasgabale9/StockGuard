
package com.vikas.stockguard;

import com.vikas.stockguard.dto.req.CreateInventoryRequest;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vikas.stockguard.entity.Reservation;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;


@SpringBootTest
class InventoryConcurrencyTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @MockitoSpyBean
    private ReservationRepository reservationRepository;

    @Test
    void shouldCreateInventorySuccessfully() {

        String sku = "CREATE-" + UUID.randomUUID();

        CreateInventoryRequest request = new CreateInventoryRequest();
        request.setSku(sku);
        request.setAvailableQuantity(50);

        Inventory createdInventory = null;

        try {
            createdInventory = inventoryService.createInventory(request);

            assertNotNull(createdInventory.getId());
            assertEquals(sku, createdInventory.getSku());
            assertEquals(
                    50,
                    createdInventory.getAvailableQuantity()
            );

        } finally {
            if (createdInventory != null) {
                inventoryRepository.deleteById(createdInventory.getId());
            }
        }
    }

    @Test
    void concurrentReservationsShouldNotOversell() throws Exception {

        String sku = "CONCURRENT-" + UUID.randomUUID();

        Inventory inventory = inventoryRepository.save(
                new Inventory(sku, 100)
        );

        int requestCount = 20;
        int quantityPerRequest = 10;

        ExecutorService executor =
                Executors.newFixedThreadPool(requestCount);

        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);

        List<String> idempotencyKeys = new ArrayList<>();

        try {
            List<Future<Boolean>> futures = new ArrayList<>();

            for (int i = 0; i < requestCount; i++) {

                String key = "CONCURRENT-KEY-" + UUID.randomUUID();
                idempotencyKeys.add(key);

                futures.add(executor.submit(() -> {
                    ReserveInventoryRequest request =
                            new ReserveInventoryRequest();

                    request.setSku(sku);
                    request.setQuantity(quantityPerRequest);

                    ready.countDown();

                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "Timed out waiting for concurrent start"
                        );
                    }

                    String status = inventoryService
                            .reserveInventory(request, key)
                            .getStatus();

                    return "SUCCESS".equals(status);
                }));
            }

            assertTrue(
                    ready.await(15, TimeUnit.SECONDS),
                    "Worker threads did not become ready in time"
            );

            start.countDown();

            int successCount = 0;

            for (Future<Boolean> future : futures) {
                if (future.get(30, TimeUnit.SECONDS)) {
                    successCount++;
                }
            }

            Inventory finalInventory = inventoryRepository
                    .findBySku(sku)
                    .orElseThrow();

            assertEquals(
                    10,
                    successCount,
                    "Exactly 10 reservations should succeed"
            );

            assertEquals(
                    0,
                    finalInventory.getAvailableQuantity(),
                    "Inventory must never become negative or oversell"
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

            for (String key : idempotencyKeys) {
                reservationRepository.deleteByIdempotencyKey(key);
            }

            inventoryRepository.deleteById(inventory.getId());
        }
    }
    @Test
    void stockShouldRollbackWhenReservationSaveFails() {
        String sku = "ROLLBACK-" + java.util.UUID.randomUUID();
        String key = "ROLLBACK-KEY-" + java.util.UUID.randomUUID();

        Inventory inventory = inventoryRepository.saveAndFlush(
                new Inventory(sku, 10)
        );

        try {
            ReserveInventoryRequest request = new ReserveInventoryRequest();
            request.setSku(sku);
            request.setQuantity(4);

            doThrow(new DataIntegrityViolationException(
                    "Simulated reservation persistence failure"))
                    .when(reservationRepository)
                    .save( any(Reservation.class));

            try {
                assertThrows(
                        DataIntegrityViolationException.class,
                        () -> inventoryService.reserveInventory(request, key)
                );
            } finally {
                reset(reservationRepository);
            }

            Inventory stored = inventoryRepository.findBySku(sku)
                    .orElseThrow();

            assertEquals(
                    10,
                    stored.getAvailableQuantity(),
                    "Stock must roll back when reservation saving fails"
            );
        } finally {
            reset(reservationRepository);
            reservationRepository.deleteByIdempotencyKey(key);
            inventoryRepository.deleteById(inventory.getId());
        }
    }

    @Test
    void reserveExactAvailableStock() {
        String sku = "PRODUCT-" + UUID.randomUUID();
        String idempotencyKey = "IdemKey-" + UUID.randomUUID();

        CreateInventoryRequest req = new CreateInventoryRequest();
        req.setSku(sku);
        req.setAvailableQuantity(50);

        Inventory createdInventory = inventoryService.createInventory(req);

        try {
            ReserveInventoryRequest rr = new ReserveInventoryRequest();
            rr.setQuantity(50);
            rr.setSku(sku);

            ReservationResponseDto res =
                    inventoryService.reserveInventory(rr, idempotencyKey);

            assertEquals("SUCCESS", res.getStatus());
            assertEquals(0, inventoryRepository.findBySku(sku)
                    .orElseThrow()
                    .getAvailableQuantity());

        } finally {
            reservationRepository.deleteByIdempotencyKey(idempotencyKey);
            inventoryRepository.deleteById(createdInventory.getId());
        }
    }


    @Test
     void  reservingOneMoreAvailableStock(){
        String sku = "Mobile-"+UUID.randomUUID();
        String idemkey = "IdemKEy-"+UUID.randomUUID();
        CreateInventoryRequest req = new CreateInventoryRequest();
        req.setSku(sku);
        req.setAvailableQuantity(49);
        Inventory createdInventory = inventoryService.createInventory(req);
        try {




            ReserveInventoryRequest rr = new ReserveInventoryRequest();
            rr.setSku(sku);
            rr.setQuantity(50);

            ReservationResponseDto res =  inventoryService.reserveInventory(rr,idemkey);

            assertEquals("REJECTED",res.getStatus());

            assertEquals(
                    49,
                    inventoryRepository.findBySku(sku)
                            .orElseThrow()
                            .getAvailableQuantity()
            );        } finally {
            reservationRepository.deleteByIdempotencyKey(idemkey);
            inventoryRepository.deleteById(createdInventory.getId());
        }


    }

}