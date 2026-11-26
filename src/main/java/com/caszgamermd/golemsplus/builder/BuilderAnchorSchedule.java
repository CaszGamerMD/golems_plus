package com.caszgamermd.golemsplus.builder;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Compile genuine placement-support dependencies without modifying the
 * world or the persisted step indices. Vanilla (and compatible modded)
 * canSurvive is evaluated against a read-only view of planned neighbors.
 * A normal floating full cube has no artificial support dependency.
 */
public final class BuilderAnchorSchedule {
    private BuilderAnchorSchedule(){}
    private record Cell(int index,BlockState state){}
    public record Plan(int[] layers,int[] order,int[][] anchors,int[] demand){}
    public static int[] withLateOrder(Plan graph,List<BuilderConstructionPlan.Step> steps,Set<Integer> late){
        if(late.isEmpty())return graph.order();
        return Arrays.stream(graph.order()).boxed().sorted(Comparator.comparingInt(i->BuilderDeferredState.verification(steps.get(i).encodedState())?2:late.contains(i)?1:0)).mapToInt(Integer::intValue).toArray();
    }
    private static final class Probe implements InvocationHandler {
        final LevelReader world,view;
        final Map<BlockPos,Cell> cells;
        final Set<Integer> reads=new HashSet<>(),removed=new HashSet<>();
        boolean usePlan;
        int self=-1;
        Probe(LevelReader world,Map<BlockPos,Cell> cells){
            this.world=world;this.cells=cells;
            view=(LevelReader)Proxy.newProxyInstance(LevelReader.class.getClassLoader(),
                    new Class<?>[]{LevelReader.class},this);
        }
        public Object invoke(Object proxy,java.lang.reflect.Method method,Object[] args) throws Throwable {
            if(args!=null&&args.length==1&&args[0] instanceof BlockPos pos){
                String name=method.getName();
                if(name.equals("getBlockState")||name.equals("getFluidState")||name.equals("isEmptyBlock")
                        ||name.equals("getBlockEntity")){
                    Cell cell=cells.get(pos);
                    if(cell!=null){
                        if(cell.index()!=self)reads.add(cell.index());
                        BlockState state=cell.index()==self||usePlan&&!removed.contains(cell.index())
                                ?cell.state():Blocks.AIR.defaultBlockState();
                        return switch(name){
                            case "getBlockState"->state;
                            case "getFluidState"->state.getFluidState();
                            case "isEmptyBlock"->state.isAir();
                            default->null;
                        };
                    }
                }
            }
            try{
                if(method.isDefault())return InvocationHandler.invokeDefault(proxy,method,args==null?new Object[0]:args);
                return method.invoke(world,args);
            }catch(InvocationTargetException failure){throw failure.getCause();}
        }
        boolean survives(BlockState state,BlockPos pos){return state.canSurvive(view,pos);}
        /** Select a sufficient set of planned supports. Try removing
         * higher anchors first in an upward build (and the reverse for
         * downward builds), so alternative faces do not create cycles. */
        Set<Integer> needed(BlockState state,BlockPos pos,int index,
                List<BuilderConstructionPlan.Step> steps,boolean descending){
            self=index;removed.clear();reads.clear();usePlan=false;
            if(survives(state,pos))return Set.of();
            usePlan=true;reads.clear();
            if(!survives(state,pos))return Set.of();
            var tested=new HashSet<Integer>();
            // Native survival checks may short-circuit at the first valid
            // face. Removing that face can reveal a previously unread
            // alternative. Continue until every discovered candidate has
            // been tested, so a surviving alternative is not lost.
            while(true){
                var candidates=new ArrayList<Integer>(reads);
                candidates.removeAll(tested);
                if(candidates.isEmpty())break;
                candidates.sort(Comparator.comparingInt((Integer i)->descending?steps.get(i).y():-steps.get(i).y())
                        .thenComparingInt(Integer::intValue));
                for(int i:candidates){
                    tested.add(i);removed.add(i);
                    if(!survives(state,pos))removed.remove(i);
                }
            }
            var required=new HashSet<Integer>(tested);
            required.removeAll(removed);required.remove(index);
            removed.clear();return required;
        }
    }
    private static int scaffoldDistance(BlockState state){
        return state.getValues()
                .filter(v->v.property().getName().equals("distance"))
                .mapToInt(v->Integer.parseInt(v.valueName())).findFirst().orElse(7);
    }
    /** Vanilla scaffold distance is propagated from sturdy footing, a
     * scaffold immediately below, or a horizontal neighbor of lower
     * distance. Prefer a monotonic path rather than circular alternatives. */
    private static Integer scaffoldAnchor(Probe probe,Map<BlockPos,Cell> cells,BlockPos at,int index,BlockState state){
        int distance=scaffoldDistance(state);
        Cell below=cells.get(at.below());
        if(below!=null&&below.index()!=index){
            if(below.state().is(Blocks.SCAFFOLDING)&&scaffoldDistance(below.state())<=distance)return below.index();
            if(!below.state().is(Blocks.SCAFFOLDING)&&distance==0){
                probe.self=index;probe.removed.clear();probe.usePlan=true;
                if(below.state().isFaceSturdy(probe.view,at.below(),Direction.UP))return below.index();
            }
        }
        if(distance>0){
            Cell best=null;
            for(Direction d:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST}){
                Cell c=cells.get(at.relative(d));
                if(c!=null&&c.index()!=index&&c.state().is(Blocks.SCAFFOLDING)
                        &&scaffoldDistance(c.state())<distance
                        &&(best==null||scaffoldDistance(c.state())<scaffoldDistance(best.state())))best=c;
            }
            if(best!=null)return best.index();
        }
        return null;
    }
    public static Plan compile(LevelReader world,List<BuilderConstructionPlan.Step> steps,
            BuildSite.Rotation rotation,BuildSite.Mirror mirror,boolean descending,Set<Integer> late){
        int n=steps.size();
        var cells=new HashMap<BlockPos,Cell>();
        var bubbles=new HashMap<BlockPos,Integer>();
        BlockState[] states=new BlockState[n];
        int[] layers=new int[n],rank=new int[n],degree=new int[n],demand=new int[n];
        int[][] anchors=new int[n][];
        var dependents=new ArrayList<List<Integer>>(n);
        for(int i=0;i<n;i++){
            var s=steps.get(i);layers[i]=s.y();dependents.add(new ArrayList<>());
            if(s.ignorePlant()||s.requiredItem()==null||BuilderDeferredState.verification(s.encodedState())
                    ||BuilderWaterPass.isWaterlogPass(s.encodedState()))continue;
            var resolved=BuilderBlockResolver.resolve(s.encodedState(),rotation,mirror);
            if(resolved==null)continue;
            states[i]=resolved.state();
            if(BuilderWaterPass.isBubbleColumn(s.encodedState()))bubbles.put(new BlockPos(s.x(),s.y(),s.z()),i);
            // Fluid hosts have their own water/physics scheduling.
            if(!BuilderWaterPass.isWaterStep(s.encodedState())){
                cells.put(new BlockPos(s.x(),s.y(),s.z()),new Cell(i,states[i]));
                if(s.companion()!=null){
                    var c=s.companion();
                    var second=BuilderBlockResolver.resolve(c.encodedState(),rotation,mirror);
                    if(second!=null)cells.put(new BlockPos(c.x(),c.y(),c.z()),new Cell(i,second.state()));
                }
            }
        }
        Probe probe=world==null?null:new Probe(world,cells);
        int edges=0,probeFailures=0;
        for(int i=0;i<n;i++){
            var s=steps.get(i);
            BlockPos at=new BlockPos(s.x(),s.y(),s.z());
            Set<Integer> needed=new HashSet<>();
            if(!s.ignorePlant()&&BuilderWaterPass.isWaterlogPass(s.encodedState())){
                Cell host=cells.get(at);if(host!=null&&host.index()!=i)needed.add(host.index());
            }else if(!s.ignorePlant()&&BuilderWaterPass.isBubbleColumn(s.encodedState())){
                Integer lower=bubbles.get(at.below());
                if(lower!=null&&lower!=i)needed.add(lower);
                else{
                    Cell base=cells.get(at.below());
                    if(base!=null&&(base.state().is(Blocks.SOUL_SAND)||base.state().is(Blocks.MAGMA_BLOCK)))needed.add(base.index());
                }
            }else if(probe!=null&&states[i]!=null&&!BuilderWaterPass.isWaterStep(s.encodedState())){
                try{
                    Integer scaffold=states[i].is(Blocks.SCAFFOLDING)?scaffoldAnchor(probe,cells,at,i,states[i]):null;
                    if(scaffold!=null)needed.add(scaffold);
                    else needed.addAll(probe.needed(states[i],at,i,steps,descending));
                    if(s.companion()!=null){
                        var c=s.companion();BlockPos second=new BlockPos(c.x(),c.y(),c.z());
                        Cell part=cells.get(second);
                        if(part!=null)needed.addAll(probe.needed(part.state(),second,i,steps,descending));
                    }
                    // Preserve every explicitly selected attachment face
                    // for multiface decorations, even when vanilla
                    // canSurvive accepts any one of several faces.
                    if(states[i].is(Blocks.GLOW_LICHEN)||states[i].is(Blocks.SCULK_VEIN)){
                        for(Direction direction:Direction.values()){
                            boolean attached=states[i].getValues().anyMatch(v->v.property().getName().equals(direction.getName())&&v.valueName().equals("true"));
                            if(attached){Cell support=cells.get(at.relative(direction));if(support!=null&&support.index()!=i)needed.add(support.index());}
                        }
                    }
                    // Ascending rails need a second real support at
                    // their raised endpoint when the plan includes one.
                    BlockPos raised=BuilderRailSlope.raisedEnd(at,states[i]);
                    if(raised!=null){
                        Cell support=cells.get(raised.below());
                        if(support!=null&&support.index()!=i){
                            probe.self=i;probe.removed.clear();probe.usePlan=true;
                            if(states[i].getBlock().defaultBlockState().canSurvive(probe.view,raised))
                                needed.add(support.index());
                        }
                    }
                }catch(RuntimeException|LinkageError ex){
                    // A modded survival check may demand a concrete world.
                    // Keep its normal runtime support check; do not invent
                    // an anchor or bypass placement validation.
                    probeFailures++;
                }
            }
            // Do not force the whole push line in front of a powered
            // piston: that reliably moved paid blocks out of their cells.
            // Place redstone blocks only after any nearby planned piston
            // base, so direct power is introduced as late as the local
            // dependency schedule permits. Manual levers remain delayed.
            if(states[i]!=null&&states[i].is(Blocks.REDSTONE_BLOCK)){
                for(Direction side:Direction.values()){
                    for(BlockPos base:new BlockPos[]{at.relative(side),at.relative(side).below()}){
                        Cell piston=cells.get(base);
                        if(piston!=null&&(piston.state().is(Blocks.PISTON)||piston.state().is(Blocks.STICKY_PISTON)))
                            needed.add(piston.index());
                    }
                }
            }
            // A low tunnel can close before its thin paid track is laid.
            // Keep the directly overlying roof block behind the track
            // (including wall torches and other circuit attachments).
            // No blocks are removed after closing the tunnel.
            if(states[i]!=null&&!descending){
                Cell under=cells.get(at.below());
                if(under!=null&&(under.state().getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock
                        ||under.state().is(Blocks.REDSTONE_WALL_TORCH))
                        &&(states[i].is(Blocks.GLASS)||states[i].is(Blocks.SMOOTH_STONE)
                            ||states[i].is(Blocks.STONE)||states[i].is(Blocks.SMOOTH_STONE_SLAB)))
                    needed.add(under.index());
            }
            needed.remove(i);
            anchors[i]=needed.stream().mapToInt(Integer::intValue).sorted().toArray();
            degree[i]=anchors[i].length;edges+=degree[i];
            for(int a:anchors[i]){dependents.get(a).add(i);demand[a]++;}
        }
        var ready=new PriorityQueue<Integer>();
        for(int i=0;i<n;i++)if(degree[i]==0)ready.add(i);
        int next=0,cycleBreaks=0;
        boolean[] emitted=new boolean[n];
        while(next<n){
            if(ready.isEmpty()){
                // Only relax the cyclic node's unresolved incoming
                // edges, then continue ordinary topological processing.
                // Keep all downstream edges and let real canSurvive
                // reject an impossible physical placement.
                int choice=-1;
                for(int i=0;i<n;i++)if(!emitted[i]&&(choice<0
                        ||degree[i]<degree[choice]
                        ||degree[i]==degree[choice]&&(descending?layers[i]>layers[choice]:layers[i]<layers[choice])))choice=i;
                if(choice<0)break;
                int chosen=choice;
                int[] before=anchors[choice];
                anchors[choice]=Arrays.stream(before).filter(a->emitted[a]).toArray();
                for(int anchor:before)if(!emitted[anchor]){
                    dependents.get(anchor).remove(Integer.valueOf(chosen));
                    demand[anchor]=Math.max(0,demand[anchor]-1);
                }
                degree[choice]=0;ready.add(choice);cycleBreaks++;
                System.out.println("[BUILDER ANCHOR CYCLE] index="+choice+" state="+steps.get(choice).encodedState()+" relaxed="+(before.length-anchors[choice].length));
            }
            int i=ready.remove();if(emitted[i])continue;
            emitted[i]=true;rank[i]=next++;
            for(int child:dependents.get(i)){
                if(emitted[child])continue;
                layers[child]=descending?Math.min(layers[child],layers[i]):Math.max(layers[child],layers[i]);
                if(--degree[child]==0)ready.add(child);
            }
        }
        int unresolved=n-next;
        var order=new ArrayList<Integer>(n);
        for(int i=0;i<n;i++)order.add(i);
        order.sort(Comparator.comparingInt((Integer i)->BuilderDeferredState.verification(steps.get(i).encodedState())?2:late.contains(i)?1:0)
                .thenComparingInt(i->descending?-layers[i]:layers[i])
                .thenComparingInt(i->BuilderWaterPass.isWaterStep(steps.get(i).encodedState())?1:0)
                .thenComparingInt(i->rank[i]));
        if(edges>0||probeFailures>0)System.out.println("[BUILDER ANCHORS] edges="+edges+" unresolved="+unresolved+" cycleBreaks="+cycleBreaks+" probeFallbacks="+probeFailures);
        return new Plan(layers,order.stream().mapToInt(Integer::intValue).toArray(),anchors,demand);
    }
}
