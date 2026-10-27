
package com.vikas.stockguard.service;

import com.vikas.stockguard.dto.req.CreateInventoryRequest;
import com.vikas.stockguard.dto.req.ReserveInventoryRequest;
import com.vikas.stockguard.dto.res.ReservationResponseDto;
import com.vikas.stockguard.entity.Inventory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface InventoryService {

     Inventory getInventoryBySku(String sku);

     Inventory createInventory(CreateInventoryRequest req);

     ReservationResponseDto reserveInventory(
             ReserveInventoryRequest req,
             String idempotencyKey
     );

     Page<Inventory> getAllInventories(Pageable pageable);
}
