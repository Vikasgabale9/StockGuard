
package com.vikas.stockguard.service.impl;

import com.vikas.stockguard.dto.req.CreateInventoryRequest;
import com.vikas.stockguard.dto.req.ReserveInventoryRequest;
import com.vikas.stockguard.dto.res.ReservationResponseDto;
import com.vikas.stockguard.entity.Inventory;
import com.vikas.stockguard.entity.Reservation;
import com.vikas.stockguard.repository.IdempotencyLockRepository;
import com.vikas.stockguard.repository.InventoryRepository;
import com.vikas.stockguard.repository.ReservationRepository;
import com.vikas.stockguard.service.InventoryService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 150;

    private final InventoryRepository inventoryRepository;
    private final ReservationRepository reservationRepository;
    private final IdempotencyLockRepository idempotencyLockRepository;

    @Override
    @Transactional(readOnly = true)
    public Inventory getInventoryBySku(String sku) {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException(
                    "SKU cannot be null or blank");
        }

        return inventoryRepository.findBySku(sku)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Inventory not found for SKU: " + sku));
    }

    @Override
    @Transactional
    public Inventory createInventory(CreateInventoryRequest req) {
        if (req == null) {
            throw new IllegalArgumentException(
                    "Inventory request cannot be null");
        }

        if (req.getSku() == null || req.getSku().isBlank()) {
            throw new IllegalArgumentException(
                    "SKU cannot be null or blank");
        }

        if (req.getSku().length() > 100) {
            throw new IllegalArgumentException(
                    "SKU cannot exceed 100 characters");
        }

        if (req.getAvailableQuantity() < 0) {
            throw new IllegalArgumentException(
                    "Available quantity cannot be negative");
        }

        String sku = req.getSku();

        if (inventoryRepository.existsBySku(sku)) {
            throw new IllegalArgumentException(
                    "SKU already exists: " + sku);
        }

        try {
            return inventoryRepository.save(dtoToEntity(req));
        } catch (DataIntegrityViolationException ex) {
            // The database unique constraint protects against
            // duplicate SKUs created by concurrent requests.
            throw new IllegalArgumentException(
                    "SKU already exists or violates an inventory constraint: "
                            + sku, ex);
        }
    }

    @Override
    @Transactional
    public ReservationResponseDto reserveInventory(
            ReserveInventoryRequest req,
            String idempotencyKey) {

        validateReservationRequest(req);
        validateIdempotencyKey(idempotencyKey);

        // Serialize requests using the same idempotency key.
        idempotencyLockRepository.lockByIdempotencyKey(
                idempotencyKey);

        Optional<Reservation> existingReservation =
                reservationRepository.findByIdempotencyKey(
                        idempotencyKey);

        if (existingReservation.isPresent()) {
            Reservation existing = existingReservation.get();

            // A key cannot be reused for a different request.
            if (!existing.getSku().equals(req.getSku())
                    || existing.getQuantity() != req.getQuantity()) {
                throw new IllegalArgumentException(
                        "Idempotency key has already been used "
                                + "with different request data");
            }

            // Return the original outcome without changing stock.
            return toResponse(existing);
        }

        // Atomically decrement stock only when enough stock exists.
        int updatedRows = inventoryRepository.reserveStock(
                req.getSku(),
                req.getQuantity());

        if (updatedRows == 0) {
            boolean skuExists =
                    inventoryRepository.existsBySku(req.getSku());

            String message = skuExists
                    ? "Insufficient stock available"
                    : "SKU does not exist: " + req.getSku();

            Reservation rejectedReservation = new Reservation(
                    idempotencyKey,
                    req.getSku(),
                    req.getQuantity(),
                    "REJECTED",
                    message);

            Reservation saved =
                    reservationRepository.save(rejectedReservation);

            return toResponse(saved);
        }

        Reservation successfulReservation = new Reservation(
                idempotencyKey,
                req.getSku(),
                req.getQuantity(),
                "SUCCESS",
                "Stock reserved successfully");

        Reservation saved =
                reservationRepository.save(successfulReservation);

        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Inventory> getAllInventories(Pageable pageable) {


        return inventoryRepository.findAll(pageable);
    }

    public Inventory dtoToEntity(CreateInventoryRequest request) {
        return new Inventory(
                request.getSku(),
                request.getAvailableQuantity());
    }

    private void validateReservationRequest(
            ReserveInventoryRequest req) {

        if (req == null) {
            throw new IllegalArgumentException(
                    "Reservation request cannot be null");
        }

        if (req.getSku() == null || req.getSku().isBlank()) {
            throw new IllegalArgumentException(
                    "SKU cannot be null or blank");
        }

        if (req.getSku().length() > 100) {
            throw new IllegalArgumentException(
                    "SKU cannot exceed 100 characters");
        }

        if (req.getQuantity() <= 0) {
            throw new IllegalArgumentException(
                    "Reservation quantity must be greater than zero");
        }
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Idempotency key cannot be null or blank");
        }

        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Idempotency key cannot exceed "
                            + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
    }

    private ReservationResponseDto toResponse(Reservation reservation) {
        ReservationResponseDto response =
                new ReservationResponseDto();

        response.setIdempotencyKey(
                reservation.getIdempotencyKey());
        response.setSku(reservation.getSku());
        response.setQuantity(reservation.getQuantity());
        response.setStatus(reservation.getStatus());
        response.setMessage(reservation.getMessage());

        return response;
    }
}
