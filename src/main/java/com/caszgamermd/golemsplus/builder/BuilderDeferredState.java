package com.caszgamermd.golemsplus.builder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
/** Results of vanilla physics, not independent paid inventory placements. */
public final class BuilderDeferredState {
    private BuilderDeferredState(){}
    private static final String FLOW="golems_plus:flow_verify|";
    public static boolean flowing(String state){
        return state!=null&&(state.startsWith("minecraft:water[")||state.startsWith("minecraft:lava["))
                &&state.matches(".*level=([1-9]|1[0-5])(?:,|\\]).*");
    }
    public static String flowStep(String state){return FLOW+state;}
    public static boolean flowVerification(String state){return state!=null&&state.startsWith(FLOW);}
    public static boolean verification(String state){return flowVerification(state)||BuilderPistonWork.verification(state);}
    /** Ask the existing vanilla machinery to reevaluate itself; no
     * synthetic source, power, piston head or flowing state is written. */
    static void refresh(ServerLevel level,BlockPos pos,String state,BuildSite.Rotation rotation,BuildSite.Mirror mirror,
            net.minecraft.server.level.ServerPlayer owner){
        if(!level.hasChunkAt(pos)||!level.mayInteract(owner,pos))return;
        if(flowVerification(state)){
            for(var direction:net.minecraft.core.Direction.values()){
                BlockPos at=pos.relative(direction);
                if(!level.hasChunkAt(at)||!level.mayInteract(owner,at))continue;
                var fluid=level.getFluidState(at);
                if(!fluid.isEmpty())level.scheduleTick(at,fluid.getType(),1);
            }
            var fluid=level.getFluidState(pos);
            if(!fluid.isEmpty())level.scheduleTick(pos,fluid.getType(),1);
            // An empty flow cell may be several blocks downstream from
            // the last real source. Wake nearby existing sources and
            // waterlogged hosts, not synthetic source blocks in air.
            // A vanilla source can feed up to seven horizontal cells,
            // and water can fall from above before spreading. Search the
            // complete horizontal reach plus nearby vertical feeds.
            // Wake the downstream flowing chain too. A source tick
            // alone need not reschedule a previously unchanged intermediate
            // flow cell after a late obstruction has been removed.
            for(int dy=0;dy<=4;dy++)for(int dx=-7;dx<=7;dx++)for(int dz=-7;dz<=7;dz++){
                if(Math.abs(dx)+Math.abs(dz)>7)continue;
                BlockPos at=pos.offset(dx,dy,dz);
                if(!level.hasChunkAt(at)||!level.mayInteract(owner,at))continue;
                var source=level.getFluidState(at);
                if(!source.isEmpty())level.scheduleTick(at,source.getType(),1);
            }
            return;
        }
        if(!BuilderPistonWork.verification(state))return;
        var desired=BuilderBlockResolver.expectedState(BuilderPistonWork.original(state),rotation,mirror);
        if(desired==null||!desired.is(net.minecraft.world.level.block.Blocks.PISTON_HEAD))return;
        var facing=desired.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING);
        BlockPos base=pos.relative(facing.getOpposite());
        if(!level.hasChunkAt(base)||!level.mayInteract(owner,base))return;
        var actual=level.getBlockState(base);
        if(!actual.is(net.minecraft.world.level.block.Blocks.PISTON)
                &&!actual.is(net.minecraft.world.level.block.Blocks.STICKY_PISTON))return;
        // Notify from neighboring real blocks, including the block above
        // for quasi-connectivity. Vanilla alone decides whether to extend.
        for(var direction:net.minecraft.core.Direction.values()){
            BlockPos neighbor=base.relative(direction);
            if(level.hasChunkAt(neighbor)&&level.mayInteract(owner,neighbor))
                level.updateNeighborsAt(neighbor,level.getBlockState(neighbor).getBlock());
        }
    }
    public static boolean matches(ServerLevel level,BlockPos pos,String state,BuildSite.Rotation rotation,BuildSite.Mirror mirror){
        if(!flowVerification(state))return BuilderPistonWork.matches(level,pos,state,rotation,mirror);
        var desired=BuilderBlockResolver.expectedState(state.substring(FLOW.length()),rotation,mirror);
        return desired!=null&&level.hasChunkAt(pos)&&level.getBlockState(pos).equals(desired);
    }
}
