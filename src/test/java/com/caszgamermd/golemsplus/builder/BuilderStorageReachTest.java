package com.caszgamermd.golemsplus.builder;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuilderStorageReachTest {
    @Test void storageUsesOneBlockFromItsEdge(){
        BlockPos depot=new BlockPos(-8,64,-12);
        assertTrue(BuilderReturnService.withinStorageReach(-6,64,-11.5,depot));
        assertFalse(BuilderReturnService.withinStorageReach(-5.999,64,-11.5,depot));
        assertTrue(BuilderReturnService.withinStorageReach(-6.5,64,-10.5,depot));
        assertTrue(BuilderReturnService.withinStorageReach(-6,64,-11,depot));
        assertFalse(BuilderReturnService.withinStorageReach(-6,64,-10,depot));
        assertFalse(BuilderReturnService.withinStorageReach(-7.5,66.01,-11.5,depot));
        assertFalse(BuilderReturnService.withinStorageReach(-3,64,-11.5,depot));
        assertFalse(BuilderReturnService.withinStorageReach(0,0,0,null));
    }
}
