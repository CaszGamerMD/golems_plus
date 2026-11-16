package com.caszgamermd.golemsplus.builder;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BuilderNNStripOrderTest {
    private BuilderConstructionPlan.Step s(int x,int y,int z,String state,String item){
        return new BuilderConstructionPlan.Step(x,y,z,state,item);
    }
    @Test void dryStripsBeforeWaterAndVerification(){
        var steps=List.of(s(0,0,0,"minecraft:tuff_bricks","minecraft:tuff_bricks"),
                s(1,0,1,"minecraft:tuff_bricks","minecraft:tuff_bricks"),
                s(0,1,0,"minecraft:tuff_bricks","minecraft:tuff_bricks"),
                s(0,0,1,"minecraft:water[level=0]",BuilderWaterPass.WATER_BUCKET),
                s(0,0,2,"golems_plus:flow_verify|minecraft:water[level=1]",null));
        assertEquals(List.of(0,2,1,3,4),BuilderNNStripOrder.compile(steps,"X",true));
        assertEquals(List.of(0,2,1,3,4),BuilderNNStripOrder.compile(steps,"Z",true));
        assertFalse(BuilderNNStripOrder.sameGroup(steps.get(0),steps.get(3),"X"));
        assertFalse(BuilderNNStripOrder.sameGroup(steps.get(3),steps.get(4),"X"));
        assertThrows(IllegalArgumentException.class,()->BuilderNNStripOrder.compile(steps,"X",false));
    }
    @Test void boundedAdjacentRecoveryAndYFallbackPreserveIndices(){
        var steps=List.of(s(0,0,0,"minecraft:tuff_bricks","minecraft:tuff_bricks"),
                s(1,0,0,"minecraft:tuff_bricks","minecraft:tuff_bricks"),
                s(0,5,0,"minecraft:tuff_bricks","minecraft:tuff_bricks"),
                s(1,2,0,"minecraft:tuff_bricks","minecraft:tuff_bricks"),
                s(1,6,0,"minecraft:tuff_bricks","minecraft:tuff_bricks"),
                s(2,0,0,"minecraft:tuff_bricks","minecraft:tuff_bricks"),
                s(1,0,1,"minecraft:water[level=0]",BuilderWaterPass.WATER_BUCKET));
        assertEquals(List.of(1,3),BuilderNNStripOrder.adjacentRescue(steps,2,"X",i->true,24));
        assertEquals(List.of(3),BuilderNNStripOrder.adjacentRescue(steps,2,"X",i->i!=1,1));
        assertEquals(List.of(0,1,5,3,2,4,6),BuilderNNStripOrder.compile(steps,"Y",true));
        assertTrue(BuilderNNStripOrder.sameGroup(steps.get(0),steps.get(5),"Y"));
        assertFalse(BuilderNNStripOrder.sameGroup(steps.get(0),steps.get(6),"Y"));
        assertEquals(List.of(),BuilderNNStripOrder.adjacentRescue(steps,6,"X",i->true,24));
    }
    @Test void foundationAndBandsPreventTallIsolatedStrips(){
        var steps=List.of(s(0,0,0,"minecraft:tuff_bricks","minecraft:tuff_bricks"),
                s(0,5,0,"minecraft:tuff_bricks","minecraft:tuff_bricks"),
                s(0,9,0,"minecraft:tuff_bricks","minecraft:tuff_bricks"),
                s(1,0,1,"minecraft:tuff_bricks","minecraft:tuff_bricks"),
                s(1,6,1,"minecraft:tuff_bricks","minecraft:tuff_bricks"),
                s(0,8,0,"minecraft:tuff_bricks","minecraft:tuff_bricks"),
                s(0,0,0,"minecraft:water[level=0]",BuilderWaterPass.WATER_BUCKET));
        assertEquals(List.of(0,3,1,5,4,2,6),BuilderNNStripOrder.compileBanded(steps,"X",0));
        assertTrue(BuilderNNStripOrder.sameBandedGroup(steps.get(0),steps.get(3),"X",0));
        assertFalse(BuilderNNStripOrder.sameBandedGroup(steps.get(5),steps.get(4),"X",0));
    }
    @Test void signedStripsRotateEquivalently(){
        var a=List.of(s(0,2,3,"minecraft:tuff_bricks","minecraft:tuff_bricks"),
                s(1,0,1,"minecraft:tuff_bricks","minecraft:tuff_bricks"),
                s(0,0,2,"minecraft:tuff_bricks","minecraft:tuff_bricks"));
        var b=a.stream().map(v->s(-v.z(),v.y(),v.x(),v.encodedState(),v.requiredItem())).toList();
        assertEquals(BuilderNNStripOrder.compile(a,"X",true,1),BuilderNNStripOrder.compile(b,"Z",true,1));
        assertEquals(BuilderNNStripOrder.compile(a,"Z",true,1),BuilderNNStripOrder.compile(b,"X",true,-1));
        assertNotEquals(BuilderNNStripOrder.compile(a,"X",true,1),BuilderNNStripOrder.compile(a,"X",true,-1));
    }
    @Test void axesDifferAndUnsupportedBlocksFailClosed(){
        var steps=List.of(s(0,0,2,"minecraft:tuff_bricks","minecraft:tuff_bricks"),
                s(2,0,0,"minecraft:tuff_bricks","minecraft:tuff_bricks"));
        assertEquals(List.of(0,1),BuilderNNStripOrder.compile(steps,"X",true));
        assertEquals(List.of(1,0),BuilderNNStripOrder.compile(steps,"Z",true));
        assertThrows(IllegalArgumentException.class,()->BuilderNNStripOrder.compile(
                List.of(s(0,0,0,"minecraft:sand","minecraft:sand")),"X",true));
    }
}
