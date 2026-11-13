package com.caszgamermd.golemsplus.builder;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
/** Two actual chest items, never a free companion. Preserve both inventories
 * while assembling the reciprocal left/right states. */
public final class BuilderChestWork {
    private BuilderChestWork(){}
    public static boolean paired(BlockState s){return s.getBlock() instanceof ChestBlock&&s.getValue(ChestBlock.TYPE)!=ChestType.SINGLE;}
    private static BlockState single(BlockState s){return s.setValue(ChestBlock.TYPE,ChestType.SINGLE);}
    private static BlockPos other(BlockPos pos,BlockState s){
        var facing=s.getValue(ChestBlock.FACING);
        return pos.relative(s.getValue(ChestBlock.TYPE)==ChestType.LEFT?facing.getClockWise():facing.getCounterClockWise());
    }
    private static BlockState partner(BlockPos pos,BlockState wanted,Map<BlockPos,BlockState> plan){
        if(!paired(wanted))return null;
        BlockPos p=other(pos,wanted);BlockState s=plan.get(p);
        return s!=null&&paired(s)&&s.getBlock()==wanted.getBlock()
                &&s.getValue(ChestBlock.FACING)==wanted.getValue(ChestBlock.FACING)
                &&other(p,s).equals(pos)?s:null;
    }
    private static boolean compatible(BlockState actual,BlockState wanted){
        return actual.equals(wanted)||actual.equals(single(wanted));
    }
    public static boolean pendingHalf(ServerLevel level,BlockPos pos,BlockState actual,BlockState wanted,Map<BlockPos,BlockState> plan){
        return paired(wanted)&&actual.equals(single(wanted))&&partner(pos,wanted,plan)!=null
                &&level.hasChunkAt(other(pos,wanted))&&level.getBlockState(other(pos,wanted)).isAir();
    }
    public static boolean canJoin(ServerLevel level,BlockPos pos,BlockState actual,BlockState wanted,Map<BlockPos,BlockState> plan){
        BlockState s=partner(pos,wanted,plan);
        return s!=null&&compatible(actual,wanted)&&level.hasChunkAt(other(pos,wanted))
                &&compatible(level.getBlockState(other(pos,wanted)),s);
    }
    public static boolean join(ServerLevel level,BlockPos pos,BlockState wanted,Map<BlockPos,BlockState> plan,ServerPlayer owner){
        if(!canJoin(level,pos,level.getBlockState(pos),wanted,plan))return false;
        BlockPos p=other(pos,wanted);BlockState s=partner(pos,wanted,plan);
        if(!level.mayInteract(owner,p))return false;
        if(!level.getBlockState(pos).equals(wanted))level.setBlock(pos,wanted,2);
        if(!level.getBlockState(p).equals(s))level.setBlock(p,s,2);
        level.updateNeighborsAt(pos,wanted.getBlock());level.updateNeighborsAt(p,s.getBlock());
        return level.getBlockState(pos).equals(wanted)&&level.getBlockState(p).equals(s);
    }
    public static BuilderMultiblock.Placement place(ServerLevel level,BlockPos pos,BlockState wanted,Map<BlockPos,BlockState> plan,ServerPlayer owner){
        BlockState s=partner(pos,wanted,plan);
        if(s==null)return new BuilderMultiblock.Placement(false,false);
        BlockPos p=other(pos,wanted);
        if(!level.hasChunkAt(p)||!level.mayInteract(owner,p))return new BuilderMultiblock.Placement(false,false);
        BlockState actual=level.getBlockState(p);
        if(!actual.isAir()&&!compatible(actual,s))return new BuilderMultiblock.Placement(false,false);
        if(!level.setBlock(pos,single(wanted),3))return new BuilderMultiblock.Placement(false,false);
        if(actual.isAir())return new BuilderMultiblock.Placement(true,true);
        return new BuilderMultiblock.Placement(join(level,pos,wanted,plan,owner),true);
    }
}
