package com.caszgamermd.golemsplus.worker;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class WorkerDoorQueueTest {
    @Test void oneWorkerAtATimeWithStableArrivalOrder(){
        var door=new WorkerDoorQueue();
        UUID first=UUID.randomUUID(),second=UUID.randomUUID(),third=UUID.randomUUID();
        assertTrue(door.claim(first,100));
        assertFalse(door.claim(second,101));
        assertFalse(door.claim(third,102));
        assertEquals(1,door.position(first));
        assertEquals(2,door.position(second));
        assertEquals(3,door.position(third));
        // A re-check never cuts in line or rotates the queue.
        assertFalse(door.claim(third,105));
        assertTrue(door.claim(first,110));
        door.release(first);
        assertTrue(door.owns(second));
        assertEquals(1,door.position(second));
        door.release(second);
        assertTrue(door.owns(third));
        door.release(third);
        assertEquals(0,door.count());
    }
    @Test void anOccupantCanLeaveAheadOfAnOutsideQueue(){
        var door=new WorkerDoorQueue();
        UUID outside=UUID.randomUUID(),inside=UUID.randomUUID(),third=UUID.randomUUID();
        assertTrue(door.claim(outside,10));
        assertFalse(door.claim(inside,11));
        assertFalse(door.claim(third,12));
        // Only the actual doorway traffic controller may promote someone
        // already in the physical opening. Other waiting workers retain
        // their original relative order.
        door.prioritizeOccupant(inside,13);
        assertEquals(inside,door.head());
        assertEquals(1,door.position(inside));
        assertEquals(2,door.position(outside));
        assertEquals(3,door.position(third));
        door.release(inside);
        assertEquals(outside,door.head());
        assertEquals(1,door.position(outside));
    }
    @Test void inactiveWorkersExpireWithoutBlockingDoorForever(){
        var door=new WorkerDoorQueue();
        UUID disconnected=UUID.randomUUID(),active=UUID.randomUUID();
        assertTrue(door.claim(disconnected,10));
        assertFalse(door.claim(active,11));
        assertTrue(door.claim(active,111)); // first idle >100 server ticks
        assertEquals(1,door.count());
        assertEquals(0,door.position(disconnected));
    }
    @Test void independentDoorsHaveIndependentOwners(){
        var a=new WorkerDoorQueue();
        var b=new WorkerDoorQueue();
        UUID workerA=UUID.randomUUID(),workerB=UUID.randomUUID();
        assertTrue(a.claim(workerA,1));
        assertFalse(a.claim(workerB,2));
        assertTrue(b.claim(workerB,2));
    }
}

