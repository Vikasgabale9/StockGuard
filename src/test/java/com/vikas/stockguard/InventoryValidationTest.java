
package com.vikas.stockguard;

import com.vikas.stockguard.dto.req.CreateInventoryRequest;
import com.vikas.stockguard.entity.Inventory;
import com.vikas.stockguard.repository.InventoryRepository;
import com.vikas.stockguard.repository.ReservationRepository;
import com.vikas.stockguard.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class InventoryValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private InventoryService inventoryService;

    @Test
    void zeroQuantityShouldBeRejected() throws Exception {
        verifyInvalidQuantityIsRejected(0);
    }

    @Test
    void negativeQuantityShouldBeRejected() throws Exception {
        verifyInvalidQuantityIsRejected(-5);
    }

    private void verifyInvalidQuantityIsRejected(int quantity)
            throws Exception {

        String sku = "VALIDATION-" + UUID.randomUUID();
        String idempotencyKey = "VALIDATION-KEY-" + UUID.randomUUID();

        Inventory inventory = inventoryRepository.save(
                new Inventory(sku, 10)
        );

        try {
            String requestBody = """
                    {
                      "sku": "%s",
                      "quantity": %d
                    }
                    """.formatted(sku, quantity);

            mockMvc.perform(
                            post("/api/v1/inventory/reserve")
                                    .header("Idempotency-Key", idempotencyKey)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(requestBody)
                    )
                    .andExpect(status().isBadRequest());

            Inventory updatedInventory = inventoryRepository
                    .findBySku(sku)
                    .orElseThrow();

            assertEquals(
                    10,
                    updatedInventory.getAvailableQuantity(),
                    "Invalid quantity must not change stock"
            );

        } finally {
            reservationRepository.deleteByIdempotencyKey(idempotencyKey);
            inventoryRepository.deleteById(inventory.getId());
        }
    }

    @Test
    void duplicateSkuShouldBeRejected() {
        String sku = "DUP-" + UUID.randomUUID();

        CreateInventoryRequest first = new CreateInventoryRequest();
        first.setSku(sku);
        first.setAvailableQuantity(20);

        CreateInventoryRequest duplicate = new CreateInventoryRequest();
        duplicate.setSku(sku);
        duplicate.setAvailableQuantity(100);

        try {
            inventoryService.createInventory(first);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> inventoryService.createInventory(duplicate)
            );

            Inventory stored = inventoryRepository.findBySku(sku)
                    .orElseThrow();

            assertEquals(20, stored.getAvailableQuantity());

        } finally {
            inventoryRepository.findBySku(sku)
                    .ifPresent(inventoryRepository::delete);
        }
    }

    @Test
    void blankSkuShouldBeRejected() {
        CreateInventoryRequest request = new CreateInventoryRequest();
        request.setSku("   ");
        request.setAvailableQuantity(10);

        assertThrows(
                IllegalArgumentException.class,
                () -> inventoryService.createInventory(request)
        );
    }

    @Test
    void negativeInitialQuantityShouldBeRejected() {
        CreateInventoryRequest request = new CreateInventoryRequest();
        request.setSku("NEG-" + UUID.randomUUID());
        request.setAvailableQuantity(-1);

        assertThrows(
                IllegalArgumentException.class,
                () -> inventoryService.createInventory(request)
        );
    }

    @Test
    void missingIdempotencyKeyShouldReturnBadRequest() throws Exception {
        String sku = "HDR-" + UUID.randomUUID();

        CreateInventoryRequest request = new CreateInventoryRequest();
        request.setSku(sku);
        request.setAvailableQuantity(10);

        Inventory inventory = inventoryService.createInventory(request);

        try {
            mockMvc.perform(
                            post("/api/v1/inventory/reserve")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {
                                              "sku": "%s",
                                              "quantity": 2
                                            }
                                            """.formatted(sku))
                    )
                    .andExpect(status().isBadRequest());

            assertEquals(
                    10,
                    inventoryRepository.findBySku(sku)
                            .orElseThrow()
                            .getAvailableQuantity()
            );

        } finally {
            inventoryRepository.deleteById(inventory.getId());
        }
    }
}
