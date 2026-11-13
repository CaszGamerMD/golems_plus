package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable verified plan from a file and exact placed Litematica anchor. */
public final class BuilderConstructionPlan {
    /** The second physical cell of a single item-backed object.
     * This is verified as part of the primary step; it is never a job. */
    public record Companion(int x,int y,int z,String encodedState) {}
    public record Step(int x,int y,int z,String encodedState,String requiredItem,
            boolean ignorePlant,Companion companion){
        public Step(int x,int y,int z,String encodedState,String requiredItem){
            this(x,y,z,encodedState,requiredItem,false,null);
        }
        public Step(int x,int y,int z,String encodedState,String requiredItem,
                boolean ignorePlant){
            this(x,y,z,encodedState,requiredItem,ignorePlant,null);
        }
    }
    public record Result(List<Step> steps,Map<String,Long> required,
                         Set<String> unsupported){
        public Result{
            steps=List.copyOf(steps);required=Map.copyOf(required);
            unsupported=Set.copyOf(unsupported);
        }
    }
    private BuilderConstructionPlan(){}
    public static Result create(LitematicFileReader.Schematic schematic,BuildSite site){
        return create(schematic,site,false);
    }
    public static Result create(LitematicFileReader.Schematic schematic,
            BuildSite site,boolean ignorePlants){
        if(schematic.blocks().size()>100_000)
            throw new IllegalArgumentException("At most 100,000 blocks per build in this version");
        List<Step> steps=new ArrayList<>();
        Map<String,Long> quantities=new LinkedHashMap<>();
        java.util.Set<String> unsupported=new java.util.LinkedHashSet<>();
        // Low-to-high, with a SECOND water phase on each Y. Even another
        // Builder's water source must wait until every dry cell is finished.
        var blocks=new ArrayList<>(schematic.blocks());
        blocks.sort(java.util.Comparator.comparingInt(LitematicFileReader.Block::y));
        // Pair only exact, reciprocal parts in this imported schematic.
        // A solitary upper half or incomplete primary is an unsupported
        // object, never a free item or an invisible completed task.
        var byCell=new HashMap<BuilderMultiblock.Cell,LitematicFileReader.Block>();
        for(var b:blocks)byCell.put(BuilderMultiblock.cell(b),b);
        // Valid piston pairs retain both original indexed cells. Place
        // the paid base retracted, verify the head after the circuit exists.
        var pistonHeads=new HashSet<BuilderMultiblock.Cell>();
        var pistonBases=new HashSet<BuilderMultiblock.Cell>();
        for(var b:blocks){
            var head=BuilderPistonWork.pairedHead(b,byCell);
            if(head!=null){
                pistonBases.add(BuilderMultiblock.cell(b));
                pistonHeads.add(BuilderMultiblock.cell(head));
            }
        }
        var companions=new HashMap<BuilderMultiblock.Cell,LitematicFileReader.Block>();
        var pairedSecondaries=new HashSet<BuilderMultiblock.Cell>();
        for(var b:blocks){
            if(!BuilderMultiblock.isPrimary(b.state()))continue;
            var where=BuilderMultiblock.companionCell(b);
            var other=where==null?null:byCell.get(where);
            if(other!=null&&BuilderMultiblock.matchingPair(b.state(),other.state())){
                companions.put(BuilderMultiblock.cell(b),other);
                pairedSecondaries.add(where);
            }
        }
        for(var b:blocks){
            if(pairedSecondaries.contains(BuilderMultiblock.cell(b)))continue;
            boolean incompletePair=(BuilderMultiblock.isPrimary(b.state())
                    ||BuilderMultiblock.isSecondary(b.state()))
                    &&!companions.containsKey(BuilderMultiblock.cell(b));
            boolean ignored=ignorePlants&&BuilderVegetation.ignoreSchematicState(b.state());
            boolean waterlogged=!ignored&&BuilderWaterPass.isWaterlogged(b.state());
            String dry=waterlogged?BuilderWaterPass.dryState(b.state()):b.state();
            boolean verifyPiston=pistonHeads.contains(BuilderMultiblock.cell(b));
            boolean verifyFlow=BuilderDeferredState.flowing(dry);
            if(pistonBases.contains(BuilderMultiblock.cell(b)))dry=BuilderPistonWork.neutral(dry);
            var resolved=ignored||incompletePair?null:
                    BuilderBlockResolver.resolve(dry,site.rotation(),site.mirror());
            if(waterlogged&&BuilderBlockResolver.resolve(b.state(),
                    site.rotation(),site.mirror())==null)resolved=null;
            var world=site.locate(new BuilderMaterialPlan.Placement(
                    b.x(),b.y(),b.z(),b.state(),resolved==null?null:
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(resolved.item()).toString()));
            if(world.y()<-64||world.y()>511)
                throw new IllegalArgumentException("Placement exceeds supported world height");
            if(resolved==null&&!ignored&&!verifyPiston&&!verifyFlow)unsupported.add(b.state());
            String item=resolved==null||ignored?null:
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(resolved.item()).toString();
            Companion companion=null;
            var other=companions.get(BuilderMultiblock.cell(b));
            if(other!=null){
                var second=site.locate(new BuilderMaterialPlan.Placement(
                        other.x(),other.y(),other.z(),other.state(),null));
                if(second.y()<-64||second.y()>511)
                    throw new IllegalArgumentException("Multiblock partner outside world height");
                companion=new Companion(second.x(),second.y(),second.z(),other.state());
            }
            // Incomplete objects are explicitly unsupported; never
            // silently construct half a door/bed/plant and call it done.
            String encoded=verifyPiston?BuilderPistonWork.verificationStep(dry):
                    verifyFlow?BuilderDeferredState.flowStep(dry):
                    incompletePair&&!ignored?"golems_plus:unpaired_multiblock|"+dry:dry;
            // Each paired item creates ONE claim, ONE material demand and
            // ONE completion entry; the companion is verified on placement.
            steps.add(new Step(world.x(),world.y(),world.z(),
                    encoded,item,ignored,companion));
            if(item!=null)quantities.merge(item,1L,Long::sum);
            if(waterlogged&&resolved!=null){
                steps.add(new Step(world.x(),world.y(),world.z(),
                        BuilderWaterPass.waterlog(b.state()),BuilderWaterPass.WATER_BUCKET));
                quantities.merge(BuilderWaterPass.WATER_BUCKET,1L,Long::sum);
            }
        }
        steps.sort(java.util.Comparator.comparingInt(Step::y)
                .thenComparingInt(s->BuilderWaterPass.isWaterStep(s.encodedState())?1:0));
        if(steps.size()>100_000)
            throw new IllegalArgumentException("At most 100,000 construction steps per build");
        for(String id:java.util.List.copyOf(quantities.keySet()))
            if(BuilderBlockResolver.reusableSupply(id))quantities.put(id,1L);
        return new Result(steps,quantities,unsupported);
    }
}
