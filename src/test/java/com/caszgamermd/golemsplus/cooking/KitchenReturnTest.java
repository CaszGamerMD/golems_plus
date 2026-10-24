package com.caszgamermd.golemsplus.cooking;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import static org.junit.jupiter.api.Assertions.*;

/** Protect actual storage slot priorities and no-loss behavior. */
class KitchenReturnTest {
    @BeforeAll static void setupMinecraftRegistries(){
        net.minecraft.server.Bootstrap.bootStrap();
    }
    private record Scene(KitchenSupplies kitchen,SimpleContainer origin,
                         SimpleContainer other,BlockPos source){}
    private static Scene scene(){
        SimpleContainer first=new SimpleContainer(3),second=new SimpleContainer(3);
        BlockPos source=new BlockPos(2,64,2),neighbor=new BlockPos(3,64,2);
        Map<Container,BlockPos> positions=new IdentityHashMap<>();
        positions.put(first,source);positions.put(second,neighbor);
        return new Scene(new KitchenSupplies(List.of(first,second),positions),
                         first,second,source);
    }
    private static KitchenSupplies.Origin source(Scene s){
        return new KitchenSupplies.Origin("minecraft:apple",s.source(),0);
    }
    @Test void returnsToExactPreviousSlotBeforeAnyOtherStack(){
        Scene s=scene();
        s.other().setItem(0,new ItemStack(Items.APPLE,40));
        assertTrue(s.kitchen().canInsertAll(List.of(new ItemStack(Items.APPLE,5)),
                                            List.of(source(s))));
        assertTrue(s.origin().getItem(0).isEmpty(),"Simulation must not mutate");
        assertTrue(s.kitchen().insertAll(List.of(new ItemStack(Items.APPLE,5)),
                                         List.of(source(s))));
        assertEquals(5,s.origin().getItem(0).getCount());
        assertEquals(40,s.other().getItem(0).getCount());
    }
    @Test void occupiedOriginFallsBackToMatchingStackThenEmptySlot(){
        Scene s=scene();
        s.origin().setItem(0,new ItemStack(Items.BREAD,1));
        s.other().setItem(1,new ItemStack(Items.APPLE,60));
        assertTrue(s.kitchen().insertAll(List.of(new ItemStack(Items.APPLE,9)),
                                        List.of(source(s))));
        assertTrue(s.origin().getItem(0).is(Items.BREAD));
        assertEquals(64,s.other().getItem(1).getCount());
        assertEquals(5,s.origin().getItem(1).getCount());
    }
    @Test void fullKitchenRefusesWholeBatchWithoutChangingAnySlot(){
        Scene s=scene();
        for(int i=0;i<3;i++){
            s.origin().setItem(i,new ItemStack(Items.BREAD,64));
            s.other().setItem(i,new ItemStack(Items.BREAD,64));
        }
        assertFalse(s.kitchen().canInsertAll(List.of(new ItemStack(Items.APPLE,2)),
                                             List.of(source(s))));
        assertFalse(s.kitchen().insertAll(List.of(new ItemStack(Items.APPLE,2)),
                                          List.of(source(s))));
        for(int i=0;i<3;i++){
            assertEquals(64,s.origin().getItem(i).getCount());
            assertEquals(64,s.other().getItem(i).getCount());
        }
    }
}
