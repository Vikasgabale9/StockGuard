package com.vikas.stockguard.repository;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

@Repository
public class IdempotencyLockRepository {

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyLockRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void lockByIdempotencyKey(String idempotencyKey) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT pg_advisory_xact_lock(
                        hashtextextended(?, 0)
                    )
                    """
            )) {
                statement.setString(1, idempotencyKey);

                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                }
            }

            return null;
        });
    }
}