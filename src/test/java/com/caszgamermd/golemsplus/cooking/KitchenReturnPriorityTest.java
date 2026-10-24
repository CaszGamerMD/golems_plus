package com.caszgamermd.golemsplus.cooking;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KitchenReturnPriorityTest {
    private record Slot(String name,boolean old,boolean matches,boolean empty){}

    private static List<Slot> order(List<Slot> slots){
        return KitchenReturnPriority.order(slots,
                Slot::old,Slot::matches,Slot::empty);
    }

    @Test void sourceSlotWinsOverExistingMatchingStacks(){
        Slot origin=new Slot("original",true,false,true);
        Slot same=new Slot("same item",false,true,false);
        Slot spare=new Slot("spare",false,false,true);
        assertEquals(List.of(origin,same,spare),
                order(List.of(spare,same,origin)));
    }
    @Test void occupiedSourceFallsBackToMatchingThenVacantStorage(){
        Slot occupied=new Slot("occupied other item",true,false,false);
        Slot same=new Slot("same item",false,true,false);
        Slot empty=new Slot("free slot",false,false,true);
        assertEquals(List.of(same,empty),
                order(List.of(occupied,empty,same)));
    }
    @Test void fullUnmatchedStorageCannotReceiveAnything(){
        assertTrue(order(List.of(new Slot("occupied",true,false,false),
                new Slot("other",false,false,false))).isEmpty());
    }
    @Test void originWithSameItemWinsToo(){
        Slot matchingOrigin=new Slot("old and matching",true,true,false);
        Slot otherMatching=new Slot("different matching",false,true,false);
        assertEquals(List.of(matchingOrigin,otherMatching),
                order(List.of(otherMatching,matchingOrigin)));
    }
}
