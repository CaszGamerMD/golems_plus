package com.caszgamermd.golemsplus.builder;

import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Saved material rules must not mutate the source schematic or reorder
 * counts; warehouse demand follows only the next vertical eight-layer band.
 */
public final class BuilderMaterialRulesGameTest {
    @GameTest(maxTicks=80,skyAccess=true)
    public void replacementHeightAndWarehouseWindow(GameTestHelper h){
        var input=List.of(
                new BuilderConstructionPlan.Step(0,64,0,"minecraft:stone",
                        "minecraft:stone"),
                new BuilderConstructionPlan.Step(1,65,0,"minecraft:stone",
                        "minecraft:stone"),
                new BuilderConstructionPlan.Step(2,66,0,"minecraft:oak_planks",
                        "minecraft:oak_planks"),
                new BuilderConstructionPlan.Step(3,74,0,"minecraft:stone",
                        "minecraft:stone"));
        var original=List.copyOf(input);
        var rules=Map.of("minecraft:stone","minecraft:glass");
        var revised=BuilderMaterialRules.apply(input,rules,65,
                BuilderMaterialRules.FROM_Y_UP,
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        if(!revised.get(0).ignorePlant()
                ||!"minecraft:glass".equals(revised.get(1).requiredItem())
                ||!"minecraft:glass".equals(revised.get(3).requiredItem())
                ||!input.equals(original)){
            h.fail("Material substitution changed original schematic or failed height filter");
            return;
        }
        var amounts=BuilderMaterialDemand.requiredMaterials(revised);
        if(amounts.getOrDefault("minecraft:glass",0L)!=2L){
            h.fail("Replacement did not preserve two remaining material cells");return;
        }
        var next=BuilderMaterialDemand.nextWindow(revised,0,i->false,8);
        if(next.getOrDefault("minecraft:glass",0L)!=1L
                ||next.getOrDefault("minecraft:oak_planks",0L)!=1L){
            h.fail("Eight-layer window included the ninth/later level");return;
        }
        var downward=BuilderMaterialRules.apply(input,Map.of(),65,
                BuilderMaterialRules.FROM_Y_DOWN,
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        if(downward.get(0).y()!=74||!downward.get(0).ignorePlant()
                ||downward.get(1).y()!=66||!downward.get(1).ignorePlant()
                ||downward.get(2).y()!=65||downward.get(3).y()!=64){
            h.fail("Downward build was not sorted high-to-low with upper cells ignored");
            return;
        }
        var downNext=BuilderMaterialDemand.nextWindow(downward,0,i->false,8);
        if(downNext.getOrDefault("minecraft:stone",0L)!=2L){
            h.fail("Downward eight-layer window lost lower construction materials");
            return;
        }
        var omitted=BuilderMaterialRules.apply(input,
                Map.of("minecraft:stone",BuilderMaterialRules.IGNORE),
                0,BuilderMaterialRules.ALL,
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        if(!omitted.get(0).ignorePlant()||!omitted.get(1).ignorePlant()
                ||!omitted.get(3).ignorePlant()
                ||!BuilderMaterialDemand.requiredMaterials(omitted)
                        .equals(Map.of("minecraft:oak_planks",1L))){
            h.fail("Ignoring a material must suppress its entire demand");return;
        }
        h.succeed();
    }
}
