package com.caszgamermd.golemsplus.builder;

import java.nio.file.Files;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

/** Production file reader + construction-planner validation of the exact
 * user-supplied Dome, inside the full-size Minecraft world test template.
 * NOT an assertion that Builder entities have already constructed it.
 */
public final class BuilderDomeImportGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=200,skyAccess=true)
    public void parsesOriginalDomeAndPlansIgnoredVegetation(GameTestHelper helper){
        java.nio.file.Path file=null;
        try {
            file=BuilderDomeFixture.unpack();
            var schematic=LitematicFileReader.read(file);
            if(schematic.blocks().size()!=3537)
                throw new IllegalStateException("Dome non-air="+schematic.blocks().size());
            if(schematic.airBlocks().size()!=37569)
                throw new IllegalStateException("Dome air="+schematic.airBlocks().size());
            if(schematic.stateCounts().size()!=117)
                throw new IllegalStateException("Dome variants="+schematic.stateCounts().size());
            BlockPos origin=helper.absolutePos(new BlockPos(8,2,8));
            var site=new BuildSite(origin.getX(),origin.getY(),origin.getZ(),
                    BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
            var plan=BuilderConstructionPlan.create(schematic,site,true);
            if(plan.steps().size()!=3537)
                throw new IllegalStateException("Dome plan steps="+plan.steps().size());
            if(!plan.unsupported().isEmpty())
                throw new IllegalStateException("Dome cannot build unsupported blocks: "+plan.unsupported());
            boolean wallSign=plan.steps().stream().anyMatch(step->
                    step.encodedState().startsWith("minecraft:dark_oak_wall_sign")
                    &&"minecraft:dark_oak_sign".equals(step.requiredItem()));
            if(!wallSign)
                throw new IllegalStateException("Dark oak wall sign missing physical sign item");
            long ignored=plan.steps().stream().filter(
                    BuilderConstructionPlan.Step::ignorePlant).count();
            if(ignored<20)
                throw new IllegalStateException("Ignore Plants did not skip Dome grass and flowers: "+ignored);
            // Verify the ignored plant states still exist in the plan, so
            // future site clearing remains active at their coordinates.
            if(plan.steps().stream().noneMatch(s->s.ignorePlant()
                    &&s.encodedState().contains("peony")))
                throw new IllegalStateException("Missing ignored upper peony");
            System.out.println("[DOME E2E] source=exact uploaded file blocks="
                    +plan.steps().size()+" ignoredPlants="+ignored
                    +" unsupported="+plan.unsupported().size());
            if(!plan.unsupported().isEmpty())
                System.out.println("[DOME E2E] unsupported="+plan.unsupported());
            helper.succeed();
        } catch(Exception e){
            helper.fail("Dome file/planner validation failed: "+e);
        } finally{
            if(file!=null)try{Files.deleteIfExists(file);}catch(Exception ignored){}
        }
    }
}
