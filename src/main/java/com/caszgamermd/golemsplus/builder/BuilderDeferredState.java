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
    public static boolean matches(ServerLevel level,BlockPos pos,String state,BuildSite.Rotation rotation,BuildSite.Mirror mirror){
        if(!flowVerification(state))return BuilderPistonWork.matches(level,pos,state,rotation,mirror);
        var desired=BuilderBlockResolver.expectedState(state.substring(FLOW.length()),rotation,mirror);
        return desired!=null&&level.hasChunkAt(pos)&&level.getBlockState(pos).equals(desired);
    }
}
