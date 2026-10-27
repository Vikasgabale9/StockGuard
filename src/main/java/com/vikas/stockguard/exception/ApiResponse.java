
package com.vikas.stockguard.exception;

import java.time.Instant;

public record ApiResponse<T>(
        boolean success,
        String message,
        int status,
        String path,
        Instant timestamp,
        T data
) {

    public static <T> ApiResponse<T> success(
            String message,
            int status,
            String path,
            T data
    ) {
        return new ApiResponse<>(
                true,
                message,
                status,
                path,
                Instant.now(),
                data
        );
    }

    public static ApiResponse<Void> error(
            String message,
            int status,
            String path
    ) {
        return new ApiResponse<>(
                false,
                message,
                status,
                path,
                Instant.now(),
                null
        );
    }
}
