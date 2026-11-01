package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.CreativeCrateBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;

/** Both fluid tools are one reusable supply, and a placed crate is virtual. */
public final class BuilderCreativeCrateFluidGameTest {
    @GameTest(maxTicks=80,skyAccess=true)
    public void creativeCrateAndReusableFluidDemand(GameTestHelper h){
        h.setBlock(2,2,2,WorksiteContent.CREATIVE_CRATE);
        if(!(h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(2,2,2)))
                instanceof CreativeCrateBlockEntity crate)){
            h.fail("Creative Crate block entity missing");return;
        }
        if(!crate.isEmpty()||!crate.getItem(0).isEmpty()
                ||!crate.canPlaceItem(0,new ItemStack(WorksiteContent.GRAPPLE_HOOK))){
            h.fail("Creative Crate is not an empty virtual supply depot");return;
        }
        var water=BuilderBlockResolver.resolve("minecraft:water[level=0]",
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        var lava=BuilderBlockResolver.resolve("minecraft:lava[level=0]",
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        if(water==null||water.item()!=WorksiteContent.INFINITE_WATER_BUCKET
                ||lava==null||lava.item()!=WorksiteContent.INFINITE_LAVA_BUCKET
                ||BuilderBlockResolver.resolve("minecraft:water[level=1]",
                        BuildSite.Rotation.NONE,BuildSite.Mirror.NONE)!=null){
            h.fail("Fluid source resolver/bucket mappings incorrect");return;
        }
        var steps=List.of(
                new BuilderConstructionPlan.Step(0,0,0,"minecraft:water[level=0]",
                        "golems_plus:infinite_water_bucket"),
                new BuilderConstructionPlan.Step(1,0,0,"minecraft:water[level=0]",
                        "golems_plus:infinite_water_bucket"),
                new BuilderConstructionPlan.Step(2,0,0,"minecraft:lava[level=0]",
                        "golems_plus:infinite_lava_bucket"));
        var needed=BuilderMaterialDemand.requiredMaterials(steps);
        if(needed.getOrDefault("golems_plus:infinite_water_bucket",0L)!=1L
                ||needed.getOrDefault("golems_plus:infinite_lava_bucket",0L)!=1L){
            h.fail("Infinite fluid bucket was counted once PER block");return;
        }
        h.succeed();
    }
}
