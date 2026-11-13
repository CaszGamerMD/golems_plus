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
    public static boolean sourceMatches(BlockState actual,BlockState desired){
        if(desired.is(Blocks.PISTON)||desired.is(Blocks.STICKY_PISTON)
                ||desired.is(Blocks.PISTON_HEAD))return actual.equals(desired);
        return BuilderPlacementState.matches(actual,desired);
    }
}
