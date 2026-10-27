package com.vikas.stockguard.dto.req;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor
public class ReserveInventoryRequest {

    @Size(max = 100)
    @NotBlank
    private String sku;

    @Positive
    private int quantity;

}
