package com.caszgamermd.golemsplus.builder;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** A paid piston base is structure; its head is a real redstone result.
 * Original step indices remain stable. Never fabricate a head or extension. */
public final class BuilderPistonWork {
    private BuilderPistonWork(){}
    private static final String VERIFY="golems_plus:piston_verify|";
    public static boolean verification(String encoded){
        return encoded!=null&&encoded.startsWith(VERIFY);
    }
    public static String original(String encoded){
        return verification(encoded)?encoded.substring(VERIFY.length()):encoded;
    }
    public static String verificationStep(String encoded){return VERIFY+encoded;}
    public static boolean extended(String encoded){
        String id=BuilderMultiblock.blockId(encoded);
        return (id.equals("minecraft:piston")||id.equals("minecraft:sticky_piston"))
                &&encoded.contains("extended=true");
    }
    public static String neutral(String encoded){
        return extended(encoded)?encoded.replace("extended=true","extended=false"):encoded;
    }
    private static String facing(String encoded){
        int i=encoded.indexOf("facing=");
        if(i<0)return "";
        String tail=encoded.substring(i+7);
        int end=tail.indexOf(',');
        if(end<0)end=tail.indexOf(']');
        return end<0?tail:tail.substring(0,end);
    }
    private static BuilderMultiblock.Cell headCell(LitematicFileReader.Block base){
        int x=base.x(),y=base.y(),z=base.z();
        switch(facing(base.state())){
            case "north" -> z--;
            case "south" -> z++;
            case "east" -> x++;
            case "west" -> x--;
            case "up" -> y++;
            case "down" -> y--;
            default -> {return null;}
        }
        return new BuilderMultiblock.Cell(x,y,z);
    }
    static LitematicFileReader.Block pairedHead(LitematicFileReader.Block base,
            Map<BuilderMultiblock.Cell,LitematicFileReader.Block> cells){
        if(!extended(base.state()))return null;
        var where=headCell(base);
        var head=where==null?null:cells.get(where);
        if(head==null||!BuilderMultiblock.blockId(head.state()).equals("minecraft:piston_head")
                ||!facing(head.state()).equals(facing(base.state()))
                ||head.state().contains("short=true"))return null;
        boolean sticky=BuilderMultiblock.blockId(base.state()).equals("minecraft:sticky_piston");
        return head.state().contains(sticky?"type=sticky":"type=normal")?head:null;
    }
    public static boolean matches(ServerLevel level,BlockPos head,String encoded,
            BuildSite.Rotation rotation,BuildSite.Mirror mirror){
        BlockState desired=BuilderBlockResolver.expectedState(original(encoded),rotation,mirror);
        if(desired==null||!desired.is(Blocks.PISTON_HEAD)||!level.hasChunkAt(head)
                ||!level.getBlockState(head).equals(desired))return false;
        Direction facing=desired.getValue(BlockStateProperties.FACING);
        BlockPos base=head.relative(facing.getOpposite());
        if(!level.hasChunkAt(base))return false;
        BlockState actual=level.getBlockState(base);
        boolean sticky=original(encoded).contains("type=sticky");
        return actual.is(sticky?Blocks.STICKY_PISTON:Blocks.PISTON)
                &&actual.getValue(BlockStateProperties.FACING)==facing
                &&actual.getValue(BlockStateProperties.EXTENDED);
    }
    /** Final verification only: recognize a full line of real blocks shifted
     * by one piston stroke. Never treat a missing block or fluid as displaced
     * just because it is near a piston. */
    public static boolean displacementMatches(ServerLevel level,BlockPos at,BlockState desired,
            Map<BlockPos,BlockState> planned){
        if(!desired.getFluidState().isEmpty()||desired.isAir()||desired.is(Blocks.PISTON_HEAD)
                ||!level.hasChunkAt(at))return false;
        if(BuilderPlacementState.matches(level.getBlockState(at),desired))return false;
        for(Direction direction:Direction.values()){
            for(int distance=1;distance<=13;distance++){
                BlockPos base=at.relative(direction.getOpposite(),distance);
                BlockState wanted=planned.get(base);
                if(wanted==null||!piston(wanted)
                        ||wanted.getValue(BlockStateProperties.FACING)!=direction
                        ||!level.hasChunkAt(base))continue;
                BlockState actual=level.getBlockState(base);
                if(!BuilderPlacementState.matches(actual,wanted))continue;
                BlockPos front=base.relative(direction);
                if(!level.hasChunkAt(front))continue;
                BlockState first=planned.get(front);
                boolean plannedHead=first==null||first.is(Blocks.PISTON_HEAD);
                if(distance==2&&actual.is(Blocks.STICKY_PISTON)
                        &&!actual.getValue(BlockStateProperties.EXTENDED)
                        &&plannedHead&&wanted.getValue(BlockStateProperties.EXTENDED)
                        &&level.getBlockState(at).isAir()
                        &&BuilderPlacementState.matches(level.getBlockState(front),desired))
                    return true;
                if(!actual.getValue(BlockStateProperties.EXTENDED))continue;
                BlockState head=level.getBlockState(front);
                if(!head.is(Blocks.PISTON_HEAD)
                        ||head.getValue(BlockStateProperties.FACING)!=direction)continue;
                int start=plannedHead?2:1;
                if(distance<start)continue;
                for(int i=start;i<=start+11;i++){
                    BlockPos source=base.relative(direction,i);
                    BlockState expected=planned.get(source);
                    if(expected==null||expected.isAir()){
                        if(i>distance)return true;
                        break;
                    }
                    if(!expected.getFluidState().isEmpty()||expected.is(Blocks.PISTON_HEAD))break;
                    BlockPos destination=source.relative(direction);
                    if(!level.hasChunkAt(destination)
                            ||!BuilderPlacementState.matches(level.getBlockState(destination),expected))break;
                    if(i==start+11&&i>=distance&&planned.get(destination)==null)return true;
                }
            }
        }
        return false;
    }
    public static boolean displacementMatches(ServerLevel level,BlockPos at,BlockState desired,
            java.util.List<BuilderConstructionPlan.Step> steps,BuildSite.Rotation rotation,BuildSite.Mirror mirror){
        return displacementMatches(level,at,desired,
                BuilderConstructionPlanner.schematicStates(steps,rotation,mirror));
    }
    private static boolean piston(BlockState state){
        return state.is(Blocks.PISTON)||state.is(Blocks.STICKY_PISTON);
    }
    public static boolean sourceMatches(BlockState actual,BlockState desired){
        // Extension is live redstone behavior, not construction fidelity.
        // The normal placement matcher still requires the exact block
        // (normal versus sticky) and facing, including vertical directions.
        if(desired.is(Blocks.PISTON_HEAD))return actual.equals(desired);
        return BuilderPlacementState.matches(actual,desired);
    }
}
