
package com.vikas.stockguard.repository;

import com.vikas.stockguard.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface ReservationRepository
        extends JpaRepository<Reservation, Long> {

    @Query(value = """
        SELECT pg_advisory_xact_lock(
            hashtextextended(:idempotencyKey, 0)
        )
        """, nativeQuery = true)
    Object lockByIdempotencyKey(
            @Param("idempotencyKey") String idempotencyKey
    );

    Optional<Reservation> findByIdempotencyKey(String idempotencyKey);

    @Transactional
   void deleteByIdempotencyKey(String ipk);
}
