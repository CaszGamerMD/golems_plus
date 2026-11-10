package com.caszgamermd.golemsplus.builder;

import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** Three real Minecraft objects, six occupied cells but exactly three
 * inventory-backed Builder job actions. This exercises the same physical
 * two-cell placement routine called by live Builder workers. */
public final class BuilderMultiblockGameTest {
    @GameTest(maxTicks=90,skyAccess=true)
    public void onePaymentAndOneJobForEachDoorBedAndPlant(GameTestHelper h){
        var world=h.getLevel();
        var cells=List.of(
                new LitematicFileReader.Block(2,3,2,
                    "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]"),
                new LitematicFileReader.Block(2,4,2,
                    "minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]"),
                new LitematicFileReader.Block(3,3,3,
                    "minecraft:red_bed[facing=east,occupied=false,part=foot]"),
                new LitematicFileReader.Block(4,3,3,
                    "minecraft:red_bed[facing=east,occupied=false,part=head]"),
                new LitematicFileReader.Block(6,3,3,"minecraft:sunflower[half=lower]"),
                new LitematicFileReader.Block(6,4,3,"minecraft:sunflower[half=upper]")
        );
        for(int x=1;x<=7;x++)for(int z=1;z<=6;z++)
            h.setBlock(x,2,z,Blocks.STONE);
        // Sunflowers legitimately require dirt-like ground, unlike doors
        // and beds. This physical test must not fake survival on stone.
        h.setBlock(6,2,3,Blocks.DIRT);
        var anchor=h.absolutePos(BlockPos.ZERO);
        var site=new BuildSite(anchor.getX(),anchor.getY(),anchor.getZ(),
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        var plan=BuilderConstructionPlan.create(
                new LitematicFileReader.Schematic(
                        "Three item-backed objects",cells,Map.of()),site);
        if(plan.steps().size()!=3||!plan.unsupported().isEmpty()
                ||plan.required().size()!=3
                ||plan.required().values().stream().anyMatch(v->v!=1L)){
            h.fail("One-item multi-block accounting failed "+plan.required()
                    +" steps="+plan.steps().size()
                    +" unsupported="+plan.unsupported());return;
        }
        for(var step:plan.steps()){
            if(step.companion()==null||step.requiredItem()==null){
                h.fail("Multi-block half became a separate step "+step);return;
            }
            BlockPos p=new BlockPos(step.x(),step.y(),step.z());
            BlockPos q=BuilderMultiblock.position(step.companion());
            var primary=BuilderBlockResolver.resolve(
                    step.encodedState(),site.rotation(),site.mirror());
            var secondary=BuilderBlockResolver.resolve(
                    step.companion().encodedState(),site.rotation(),site.mirror());
            if(primary==null||secondary==null
                    ||!primary.state().canSurvive(world,p)){
                h.fail("Valid item-backed pair lost resolver or support "+step);
                return;
            }
            var placed=BuilderMultiblock.place(world,p,
                    BuilderBlockInteractions.placementState(primary.state()),
                    q,BuilderBlockInteractions.placementState(secondary.state()),
                    false,false);
            if(!placed.complete()||!placed.primaryPlaced()
                    ||!BuilderPlacementState.matches(world.getBlockState(p),
                            primary.state())
                    ||!BuilderPlacementState.matches(world.getBlockState(q),
                            secondary.state())){
                h.fail("Two-cell item did not physically occupy both cells: "
                        +step+" primary="+world.getBlockState(p)
                        +" secondary="+world.getBlockState(q));return;
            }
        }
        h.onEachTick(()->{
            if(h.getTick()<20)return;
            for(var step:plan.steps()){
                BlockPos p=new BlockPos(step.x(),step.y(),step.z());
                BlockPos q=BuilderMultiblock.position(step.companion());
                var primary=BuilderBlockResolver.resolve(
                        step.encodedState(),site.rotation(),site.mirror());
                var secondary=BuilderBlockResolver.resolve(
                        step.companion().encodedState(),
                        site.rotation(),site.mirror());
                if(!BuilderPlacementState.matches(world.getBlockState(p),
                        primary.state())
                        ||!BuilderPlacementState.matches(world.getBlockState(q),
                                secondary.state())){
                    h.fail("Paired item failed after real neighbor updates "
                            +step+" primary="+world.getBlockState(p)
                            +" secondary="+world.getBlockState(q));return;
                }
            }
            System.out.println("[BUILDER MULTIBLOCK PASS] 3 paid items, "
                    +"3 jobs, 6 real cells, stable through neighbor updates");
            h.succeed();
        });
    }
}
