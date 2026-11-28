package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Isolated synthetic load benchmark, not the original HAND geometry.
 * Uses the original occupied count, dimensions, water and spawner counts.
 * Does not place or excavate any world blocks. */
public final class BuilderHandScaleGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=100,skyAccess=true)
    public void plansAndPartitionsHandSizedPlacement(GameTestHelper h){
        try{
            final int width=113,height=273,depth=142,occupied=327_441;
            final int volume=width*height*depth;
            long started=System.nanoTime();
            var cells=new ArrayList<LitematicFileReader.Block>(occupied);
            // 104729 is coprime to volume; distribute unique occupied
            // cells through the full height without allocating the air.
            for(int i=0;i<occupied;i++){
                int index=(int)((long)i*104729%volume);
                String state=i<130?"minecraft:spawner":i<25_176?
                        "minecraft:water[level=0]":i<26_176?
                        "minecraft:smooth_stone_slab[type=bottom,waterlogged=true]":
                        i%3==0?"minecraft:stone":i%3==1?"minecraft:deepslate":"minecraft:moss_block";
                cells.add(new LitematicFileReader.Block(index%width,
                        -50+index/(width*depth),(index/width)%depth,state));
            }
            var encoded=BuilderPlacementSnapshot.encode("HAND scale synthetic",cells,List.of());
            var schematic=BuilderPlacementSnapshot.decode(encoded);
            long imported=System.nanoTime();
            if(schematic.blocks().size()!=occupied||!schematic.airBlocks().isEmpty()){
                h.fail("HAND scale snapshot did not preserve occupied-only geometry");return;
            }
            var site=BuilderPlacementSnapshot.worldSpaceSite();
            var plan=BuilderConstructionPlan.create(schematic,site);
            long planned=System.nanoTime();
            if(plan.steps().size()!=occupied+1000||!plan.unsupported().isEmpty()
                    ||plan.steps().stream().filter(BuilderConstructionPlan.Step::ignorePlant).count()!=130
                    ||plan.required().containsKey("minecraft:spawner")
                    ||plan.required().getOrDefault(BuilderWaterPass.WATER_BUCKET,0L)!=1L){
                h.fail("HAND scale plan differs: steps="+plan.steps().size()+" unsupported="+plan.unsupported());return;
            }
            var clearing=BuilderSiteClearance.targets(schematic,site);
            if(clearing.size()!=occupied){
                h.fail("HAND scale clearing expanded outside occupied cells");return;
            }
            var footprint=BuilderZones.footprint(plan.steps());
            var roster=new ArrayList<UUID>();
            for(int i=0;i<8;i++)roster.add(new UUID(0,i+1));
            var work=BuilderZoneWorkPlan.compile(plan.steps(),footprint,roster);
            var seen=new BitSet(plan.steps().size());
            for(int z=0;z<8;z++){
                var tasks=work.steps(roster.get(z));
                if(tasks.isEmpty()){h.fail("Empty HAND-scale worker zone");return;}
                for(int index:tasks){
                    if(seen.get(index)||!footprint.contains(plan.steps().get(index),z,8)){
                        h.fail("Duplicate or wrong worker zone");return;
                    }
                    seen.set(index);
                }
            }
            if(seen.cardinality()!=plan.steps().size()){
                h.fail("Worker zone tasks lost construction steps");return;
            }
            long zoned=System.nanoTime();
            var neighborhood=new BuilderWaterNeighborhood(plan.steps());
            for(int i=0;i<plan.steps().size();i++)if(!neighborhood.ready(i,index->true)){
                h.fail("Water prerequisites rejected a fully completed dry plan");return;
            }
            long water=System.nanoTime();
            int floor=7; // relative layer 57 at world origin -50
            var tower=BuilderMaterialRules.apply(plan.steps(),Map.of(),floor,
                    BuilderMaterialRules.FROM_Y_UP,site.rotation(),site.mirror());
            int excluded=0,retained=0;
            for(int i=0;i<tower.size();i++){
                var original=plan.steps().get(i);var filtered=tower.get(i);
                if(original.y()<floor){
                    if(!filtered.ignorePlant()||filtered.requiredItem()!=null){
                        h.fail("Below-floor task was not excluded");return;
                    }
                    excluded++;
                }else{
                    if(original!=filtered){h.fail("Unchanged tower task was duplicated");return;}
                    retained++;
                }
            }
            if(tower.size()!=plan.steps().size()||excluded==0||retained==0){
                h.fail("Height filter changed indexed plan geometry");return;
            }
            long end=System.nanoTime();
            System.out.println("[HAND SCALE PASS] occupied="+occupied+" steps="+plan.steps().size()
                    +" excludedBelowFloor="+excluded+" retained="+retained+" bytes="+encoded.length
                    +" importMs="+(imported-started)/1_000_000+" planMs="+(planned-imported)/1_000_000
                    +" clearanceAndZonesMs="+(zoned-planned)/1_000_000+" waterIndexMs="+(water-zoned)/1_000_000
                    +" filterMs="+(end-water)/1_000_000+" totalMs="+(end-started)/1_000_000
                    +" heapUsedMiB="+(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1048576);
            h.succeed();
        }catch(Exception e){h.fail("HAND scale planning exception: "+e);}
    }
}
