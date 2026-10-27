
package com.vikas.stockguard.controller;

import com.vikas.stockguard.dto.req.CreateInventoryRequest;
import com.vikas.stockguard.dto.req.ReserveInventoryRequest;
import com.vikas.stockguard.dto.res.ReservationResponseDto;
import com.vikas.stockguard.entity.Inventory;
import com.vikas.stockguard.exception.ApiResponse;
import com.vikas.stockguard.service.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
@Tag(
        name = "Inventory",
        description = "APIs for managing inventory and reserving stock"
)
public class InventoryController {

    private final InventoryService inventoryService;

    @Operation(
            summary = "Get all inventory",
            description = "Returns a paginated list of inventory items sorted by ID"
    )
    @GetMapping("/getAll")
    public ResponseEntity<ApiResponse<Page<Inventory>>> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            HttpServletRequest request) {

        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException(
                    "Page must be non-negative and size must be between 1 and 100");
        }

        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by("id").ascending()
        );

        Page<Inventory> inventories =
                inventoryService.getAllInventories(pageable);

        return ResponseEntity.ok(
                ApiResponse.success(
                        "Inventory retrieved successfully",
                        HttpStatus.OK.value(),
                        request.getRequestURI(),
                        inventories
                )
        );
    }

    @Operation(
            summary = "Find inventory by SKU",
            description = "Returns inventory details for the specified SKU"
    )
    @GetMapping("/sku/{sku}")
    public ResponseEntity<ApiResponse<Inventory>> findBySku(
            @PathVariable String sku,
            HttpServletRequest request) {

        Inventory inventory = inventoryService.getInventoryBySku(sku);

        return ResponseEntity.ok(
                ApiResponse.success(
                        "Inventory retrieved successfully",
                        HttpStatus.OK.value(),
                        request.getRequestURI(),
                        inventory
                )
        );
    }

    @Operation(
            summary = "Create inventory",
            description = "Creates a new inventory item"
    )
    @PostMapping("/create")
    public ResponseEntity<ApiResponse<Inventory>> createInventory(
            @Valid @RequestBody CreateInventoryRequest req,
            HttpServletRequest request) {

        Inventory inventory = inventoryService.createInventory(req);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "Inventory created successfully",
                        HttpStatus.CREATED.value(),
                        request.getRequestURI(),
                        inventory
                ));
    }

    @Operation(
            summary = "Reserve inventory",
            description = "Reserves stock using an idempotency key to prevent duplicate reservations"
    )
    @PostMapping("/reserve")
    public ResponseEntity<ApiResponse<ReservationResponseDto>> reserveInventory(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReserveInventoryRequest req,
            HttpServletRequest request) {

        ReservationResponseDto response =
                inventoryService.reserveInventory(req, idempotencyKey);

        return ResponseEntity.ok(
                ApiResponse.success(
                        "Reservation processed",
                        HttpStatus.OK.value(),
                        request.getRequestURI(),
                        response
                )
        );
    }
}
