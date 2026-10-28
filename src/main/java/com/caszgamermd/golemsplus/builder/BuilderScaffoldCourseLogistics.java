package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Physical single-Builder scaffold training-course logistics.
 *
 * The start chest is the ONLY source of supplies. A golem must reach it,
 * withdraw real items, build real scaffold blocks, then recover them on
 * safe solid ground. Recovered items go into its own inventory, NOT the
 * Builder Planner's shared warehouse. Finally it must visit the finish
 * chest and deposit a configurable MINIMUM number of real scaffolds.
 * The finish door may open only from proven deposits, never from a
 * pre-filled chest or from the worker merely touching the destination.
 *
 * One Session == one real Builder on one course attempt. A trainer can
 * evaluate 9 policies in SEPARATE attempts on the same reset course;
 * this class does not run nine Golems at the same time.
 */
public final class BuilderScaffoldCourseLogistics {
    private static final double CHEST_REACH_SQUARED=12.0;
    private BuilderScaffoldCourseLogistics(){}

    public static final class Session {
        private final BlockPos startChest,finishChest,door;
        private final int required;
        private int withdrawn,recovered,delivered;
        private UUID trialWorker;
        private boolean unlocked;
        public Session(BlockPos startChest,BlockPos finishChest,
                       BlockPos door,int minimumDeposit){
            if(startChest==null||finishChest==null||door==null
                    ||minimumDeposit<1)
                throw new IllegalArgumentException("Course requires two chests, a door and a positive deposit");
            this.startChest=startChest.immutable();
            this.finishChest=finishChest.immutable();
            this.door=door.immutable();
            this.required=minimumDeposit;
        }
        private void checkWorker(BuilderGolem worker){
            if(trialWorker==null)trialWorker=worker.getUUID();
            else if(!trialWorker.equals(worker.getUUID()))
                throw new IllegalStateException(
                        "Single-golem course session cannot be shared by a second Builder");
        }
        public int withdrawn(){return withdrawn;}
        public int recovered(){return recovered;}
        public int delivered(){return delivered;}
        public int required(){return required;}
        public boolean unlocked(){return unlocked;}
        public boolean passed(ServerLevel level,BuilderGolem worker,
                              BlockPos finish){
            checkWorker(worker);
            return unlocked&&delivered>=required
                    &&worker.onGround()
                    &&worker.distanceToSqr(finish.getX()+.5,
                            finish.getY(),finish.getZ()+.5)<.70;
        }
        /** Never teleport; caller uses the unified WorkerNavigation to
         * reach this chest before calling. The actual inventory is the
         * proof that a transfer occurred, not an arbitrary score.
         */
        public int collect(ServerLevel level,BuilderGolem worker,int max){
            checkWorker(worker);
            int moved=withdraw(level,worker,startChest,max);
            withdrawn+=moved;
            return moved;
        }
        /** Reclaim actual owned world scaffolds AFTER a safe dismount. */
        public int reclaim(ServerLevel level,BuilderGolem worker,
                           WorkerStationBlockEntity table){
            checkWorker(worker);
            int moved=recover(level,worker,table);
            recovered+=moved;
            return moved;
        }
        /** Transfers physical inventory to the finish chest; only
         * newly deposited items count toward the door unlock. */
        public int deliver(ServerLevel level,BuilderGolem worker,int max){
            checkWorker(worker);
            int moved=deposit(level,worker,finishChest,max);
            delivered+=moved;
            if(delivered>=required){
                unlocked=openDoor(level,door);
            }
            return moved;
        }
        /** A prefilled chest or a manipulated open door cannot grant
         * a completion result. This is enforced every course tick.
         */
        public void enforceGate(ServerLevel level){
            if(delivered<required){
                unlocked=false;
                setDoor(level,door,false);
            }else{
                unlocked=openDoor(level,door);
            }
        }
    }

    private static Container chest(ServerLevel level,BlockPos at){
        if(!level.hasChunkAt(at)
                ||!(level.getBlockState(at).getBlock() instanceof ChestBlock))
            return null;
        var be=level.getBlockEntity(at);
        return be instanceof Container c?c:null;
    }
    private static boolean near(BuilderGolem worker,BlockPos at){
        return worker.isAlive()&&worker.distanceToSqr(
                at.getX()+.5,at.getY()+.5,at.getZ()+.5)
                <=CHEST_REACH_SQUARED;
    }
    /** Insert only into matching stacks or completely empty pockets. */
    private static int accept(BuilderGolem worker,int amount){
        int remaining=amount;
        for(int pass=0;pass<2;pass++)
            for(int slot=0;slot<worker.getContainerSize()&&remaining>0;slot++){
                ItemStack held=worker.getItem(slot);
                if(pass==0&&!held.is(Items.SCAFFOLDING))continue;
                if(pass==1&&!held.isEmpty())continue;
                int room=held.isEmpty()?64:held.getMaxStackSize()-held.getCount();
                int moved=Math.min(remaining,Math.max(0,room));
                if(moved<=0)continue;
                worker.setItem(slot,new ItemStack(Items.SCAFFOLDING,
                        held.getCount()+moved));
                remaining-=moved;
            }
        return amount-remaining;
    }
    private static int free(BuilderGolem worker){
        int count=0;
        for(int slot=0;slot<worker.getContainerSize();slot++){
            var held=worker.getItem(slot);
            if(held.isEmpty())count+=64;
            else if(held.is(Items.SCAFFOLDING))
                count+=Math.max(0,held.getMaxStackSize()-held.getCount());
        }
        return count;
    }
    private static int withdraw(ServerLevel level,BuilderGolem worker,
                                 BlockPos source,int max){
        Container inventory=chest(level,source);
        if(inventory==null||max<=0||!near(worker,source))return 0;
        int moved=0;
        for(int slot=0;slot<inventory.getContainerSize()&&moved<max;slot++){
            ItemStack stack=inventory.getItem(slot);
            if(!stack.is(Items.SCAFFOLDING))continue;
            int amount=Math.min(max-moved,Math.min(free(worker),stack.getCount()));
            int accepted=accept(worker,amount);
            if(accepted==0)break;
            inventory.setItem(slot,stack.copyWithCount(stack.getCount()-accepted));
            inventory.setChanged();
            moved+=accepted;
        }
        return moved;
    }
    private static int deposit(ServerLevel level,BuilderGolem worker,
                               BlockPos target,int max){
        Container inventory=chest(level,target);
        if(inventory==null||max<=0||!near(worker,target))return 0;
        int moved=0;
        for(int pocket=0;pocket<worker.getContainerSize()&&moved<max;pocket++){
            ItemStack source=worker.getItem(pocket);
            if(!source.is(Items.SCAFFOLDING))continue;
            int remain=Math.min(max-moved,source.getCount());
            int inserted=0;
            // First fill matching stacks; only then use empty chest slots.
            for(int pass=0;pass<2&&remain>0;pass++)
                for(int slot=0;slot<inventory.getContainerSize()&&remain>0;slot++){
                    ItemStack dest=inventory.getItem(slot);
                    if(pass==0&&!dest.is(Items.SCAFFOLDING))continue;
                    if(pass==1&&!dest.isEmpty())continue;
                    int room=dest.isEmpty()?64:dest.getMaxStackSize()-dest.getCount();
                    int transferred=Math.min(remain,Math.max(0,room));
                    if(transferred<=0)continue;
                    inventory.setItem(slot,new ItemStack(Items.SCAFFOLDING,
                            dest.getCount()+transferred));
                    inserted+=transferred;remain-=transferred;
                }
            if(inserted>0){
                worker.setItem(pocket,source.copyWithCount(source.getCount()-inserted));
                inventory.setChanged();moved+=inserted;
            }
        }
        return moved;
    }
    /** Real top-down retrieval. A worker must be safely on ordinary
     * solid ground and have an actual inventory slot for each recovered
     * item. Never dismantle blocks underneath or adjacent to its body.
     * The course gets zero credit for unowned/unloaded/phantom blocks.
     */
    private static int recover(ServerLevel level,BuilderGolem worker,
                               WorkerStationBlockEntity table){
        if(!worker.onGround()||worker.getY()<level.getMinY()
                ||level.getBlockState(worker.blockPosition().below())
                        .is(Blocks.SCAFFOLDING))
            return 0;
        var positions=new ArrayList<>(table.scaffoldPositions(worker.getUUID()));
        positions.sort(Comparator.comparingInt((BlockPos at)->at.getY()).reversed());
        int recovered=0;
        for(BlockPos at:positions){
            if(!level.hasChunkAt(at)
                    ||!level.getBlockState(at).is(Blocks.SCAFFOLDING)
                    ||free(worker)<1)continue;
            // No remote deconstruction: the golem must physically
            // stand close enough to reach this scaffold block.
            if(worker.distanceToSqr(at.getX()+.5,
                    at.getY()+.5,at.getZ()+.5)>20.25)continue;
            if(Math.abs(at.getX()+.5-worker.getX())<.9
                    &&Math.abs(at.getZ()+.5-worker.getZ())<.9)
                continue; // never remove a physically supporting column
            if(level.setBlock(at,Blocks.AIR.defaultBlockState(),3)){
                // Inventory insertion is guaranteed by the free check.
                int credited=accept(worker,1);
                if(credited==1){
                    table.untrackScaffold(worker.getUUID(),at);
                    recovered++;
                }else{
                    level.setBlock(at,Blocks.SCAFFOLDING.defaultBlockState(),3);
                }
            }
        }
        return recovered;
    }
    private static boolean openDoor(ServerLevel level,BlockPos at){
        return setDoor(level,at,true);
    }
    private static boolean setDoor(ServerLevel level,BlockPos at,boolean open){
        if(!level.hasChunkAt(at))return false;
        var state=level.getBlockState(at);
        if(!(state.getBlock() instanceof DoorBlock)
                ||!state.hasProperty(DoorBlock.OPEN))return false;
        // Both physical halves must agree. Merely toggling the lower
        // half left the upper collision shape shut, causing a real
        // Builder to pay the finish quota but get stuck at the door.
        BlockPos partner=state.getValue(DoorBlock.HALF)==DoubleBlockHalf.LOWER
                ?at.above():at.below();
        var other=level.getBlockState(partner);
        if(!(other.getBlock() instanceof DoorBlock)
                ||!other.hasProperty(DoorBlock.OPEN))return false;
        if(other.getValue(DoorBlock.OPEN)!=open)
            level.setBlock(partner,other.setValue(DoorBlock.OPEN,open),3);
        if(state.getValue(DoorBlock.OPEN)!=open)
            level.setBlock(at,state.setValue(DoorBlock.OPEN,open),3);
        return level.getBlockState(at).getValue(DoorBlock.OPEN)==open
                &&level.getBlockState(partner).getValue(DoorBlock.OPEN)==open;
    }
}
