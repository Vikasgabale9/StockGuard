
package com.vikas.stockguard.dto.res;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ReservationResponseDto {

    private String idempotencyKey;

    private String sku;

    private int quantity;

    private String status;

    private String message;
}
