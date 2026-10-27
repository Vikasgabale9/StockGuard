package com.vikas.stockguard.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@Setter
@Getter
public class CreateInventoryRequest {
    @NotBlank
    @Size(max = 100)
    private String sku;

    @PositiveOrZero
    private int availableQuantity;

}
