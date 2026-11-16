package com.caszgamermd.golemsplus.builder;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BuilderNNStrategyAdvisorTest {
    static List<BuilderConstructionPlan.Step> wall(boolean rotate){
        var out=new ArrayList<BuilderConstructionPlan.Step>();
        for(int y=0;y<30;y++)for(int x=0;x<32;x++)if(y<6||x==0||x==31)
            out.add(new BuilderConstructionPlan.Step(rotate?0:x,y,rotate?x:0,"minecraft:stone","minecraft:stone"));
        return out;
    }
    @Test void choosesRotatedOpenWallWithEqualScore(){
        var a=BuilderNNStrategyAdvisor.choose(wall(false),new BlockPos(-1,0,-5),true);
        var b=BuilderNNStrategyAdvisor.choose(wall(true),new BlockPos(5,0,-1),true);
        assertEquals("X",a.axis());assertEquals("Z",b.axis());
        assertEquals(a.scores().get("Y"),b.scores().get("Y"));
        assertEquals(a.scores().get("X+"),b.scores().get("Z+"));
        assertEquals(a.scores().get("X-"),b.scores().get("Z-"));
        assertEquals(BuilderNNStrategyAdvisor.order(wall(false),"X",1),
                BuilderNNStrategyAdvisor.order(wall(true),"Z",1));
    }
    @Test void complexUnsupportedAndMultiworkerStayY(){
        var s=new ArrayList<>(wall(false));
        s.add(new BuilderConstructionPlan.Step(0,30,0,"minecraft:sand","minecraft:sand"));
        assertEquals("Y",BuilderNNStrategyAdvisor.choose(s,BlockPos.ZERO,true).axis());
        assertEquals("Y",BuilderNNStrategyAdvisor.choose(wall(false),BlockPos.ZERO,false).axis());
        s=new ArrayList<>(wall(false));s.remove(0);
        assertEquals("Y",BuilderNNStrategyAdvisor.choose(s,BlockPos.ZERO,true).axis());
    }
}
