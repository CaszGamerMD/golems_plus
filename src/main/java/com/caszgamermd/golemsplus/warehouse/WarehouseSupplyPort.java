package com.caszgamermd.golemsplus.warehouse;

import java.util.Map;
import java.util.UUID;

/**
 * Deliberate integration seam for Caszual Additions. No contents are copied
 * or spawned by Golems Plus. The actual warehouse adapter must authenticate
 * the request, reserve existing stock and deliver it physically.
 */
public interface WarehouseSupplyPort {
    record Request(UUID workOrder, UUID requester, WarehouseAssignment warehouse,
                   Map<String, Long> quantities) {
        public Request {
            if (workOrder == null || requester == null || warehouse == null)
                throw new IllegalArgumentException("Missing warehouse request identity");
            quantities = Map.copyOf(quantities);
            if (quantities.isEmpty() || quantities.entrySet().stream().anyMatch(
                    e -> e.getKey() == null || e.getKey().isBlank()
                            || e.getValue() == null || e.getValue() <= 0))
                throw new IllegalArgumentException("Invalid material request");
        }
    }
    enum Outcome { ACCEPTED, WAITING_FOR_STOCK, DENIED, OFFLINE }
    /** Repeating a workOrder must never allocate a second copy of stock. */
    Outcome request(Request request);
}
