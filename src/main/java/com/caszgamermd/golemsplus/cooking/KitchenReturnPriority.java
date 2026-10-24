package com.caszgamermd.golemsplus.cooking;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Pure priority ordering shared by simulated and committed pantry deposits.
 * Never selects an occupied slot holding a different item. The caller still
 * enforces the container's per-slot placement rules and stack-size limits.
 */
final class KitchenReturnPriority {
    private KitchenReturnPriority(){}
    static <T> List<T> order(List<T> slots,Predicate<T> oldSlot,
                             Predicate<T> matches,Predicate<T> empty){
        List<T> result=new ArrayList<>(slots.size());
        for(T slot:slots)
            if(oldSlot.test(slot)&&(matches.test(slot)||empty.test(slot)))
                result.add(slot);
        for(T slot:slots)
            if(matches.test(slot)&&!result.contains(slot))result.add(slot);
        for(T slot:slots)
            if(empty.test(slot)&&!result.contains(slot))result.add(slot);
        return result;
    }
}
