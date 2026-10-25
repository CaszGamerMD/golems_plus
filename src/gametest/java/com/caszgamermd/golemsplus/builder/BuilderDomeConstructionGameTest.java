package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.warehouse.WarehouseAssignment;
import com.caszgamermd.golemsplus.crane.BuildingCrane;
import com.caszgamermd.golemsplus.crane.CraneContent;
import com.caszgamermd.golemsplus.crane.CraneRailBlock;
import com.caszgamermd.golemsplus.crane.CraneService;
import net.minecraft.core.Direction;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.worker.WorkerPhase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Real full-building test: no teleport, no instant block placements.
 * Builders execute their actual persisted jobs, draw real test stock
 * from a normal chest, and must finish all blocks of the user's Dome.
 * A failure reports the last recorded worker and planner state.
 */
public final class BuilderDomeConstructionGameTest {
    @SuppressWarnings("removal")
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=90000,skyAccess=true)
    public void constructsExactDomeWithCraneAndFourBuilders(GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        final Path source;
        try{
            source=BuilderDomeFixture.unpack();
        }catch(Exception e){helper.fail("Could not unpack Dome "+e);return;}
        final LitematicFileReader.Schematic schematic;
        final List<BuilderConstructionPlan.Step> steps;
        try {
            schematic=LitematicFileReader.read(source);
            BlockPos origin=helper.absolutePos(new BlockPos(8,2,8));
            var site=new BuildSite(origin.getX(),origin.getY(),origin.getZ(),
                    BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
            steps=BuilderConstructionPlan.create(schematic,site,true).steps();
            if(steps.size()!=3537)
                throw new IllegalStateException("Incorrect Dome blueprint count "+steps.size());
        }catch(Exception e){helper.fail("Could not plan Dome "+e);return;}
        // A full size test structure is essential. The default 8x8
        // GameTest room causes invisible barrier-block path failures.
        for(int x=1;x<=45;x++)for(int z=1;z<=45;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        // Connected and physically supported outer-perimeter rail detour.
        // This is well outside x=8..38 and z=8..41 Dome structure.
        var rail=CraneContent.RAIL.defaultBlockState()
                .setValue(CraneRailBlock.FACING,Direction.UP);
        for(int z=3;z<=24;z++){
            helper.getLevel().setBlock(helper.absolutePos(new BlockPos(3,2,z)),rail,2);
            helper.getLevel().setBlock(helper.absolutePos(new BlockPos(44,2,z)),rail,2);
        }
        for(int x=3;x<=44;x++)
            helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x,2,3)),rail,2);
        helper.setBlock(4,2,7,WorksiteContent.BUILDER_TABLE);
        helper.setBlock(5,2,7,Blocks.CHEST);
        BlockPos plannerPos=helper.absolutePos(new BlockPos(4,2,7));
        BlockPos supplyPos=helper.absolutePos(new BlockPos(5,2,7));
        if(!(level.getBlockEntity(plannerPos) instanceof WorkerStationBlockEntity table)){
            helper.fail("Dome Planner block entity not registered");return;
        }
        // GameTest's server player is the owner, not a bypass of world
        // permission checks. If the mock is not in PlayerList, report
        // that explicitly rather than pretending work is progressing.
        ServerPlayer owner=helper.makeMockServerPlayerInLevel();
        UUID id=owner.getUUID();
        if(level.getServer().getPlayerList().getPlayer(id)==null){
            helper.fail("GameTest owner isn't registered as an online ServerPlayer");
            return;
        }
        // GameTest mock players spawn at world 0,0,0, while isolated
        // structures are placed millions of blocks away. Locate the online
        // job owner near the test site so worker/crane chunks actually tick.
        BlockPos witness=helper.absolutePos(new BlockPos(4,3,4));
        owner.setPos(witness.getX()+.5,witness.getY(),witness.getZ()+.5);
        if(!table.claimIfUnowned(id)||!table.setCrewTarget(id,4)){
            helper.fail("Cannot claim Dome Planner or set four builders");return;
        }
        Path folder=FabricLoader.getInstance().getConfigDir()
                .resolve("golems_plus").resolve("schematics");
        String filename="placed-gametest-dome.litematic";
        try {
            Files.createDirectories(folder);
            Files.copy(source,folder.resolve(filename),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception e){helper.fail("Cannot stage Dome for production reader "+e);return;}
        var data=BuilderConstructionPlan.create(schematic,
                new BuildSite(helper.absolutePos(new BlockPos(8,2,8)).getX(),
                        helper.absolutePos(new BlockPos(8,2,8)).getY(),
                        helper.absolutePos(new BlockPos(8,2,8)).getZ(),
                        BuildSite.Rotation.NONE,BuildSite.Mirror.NONE),true);
        if(!table.setPlacedBuilderPlan(id,filename,"Dome",
                helper.absolutePos(new BlockPos(8,2,8)),0,0,
                steps.size(),data.unsupported().size())
                ||!table.setBuilderMaterialStorage(id,supplyPos)
                ||!table.setBuilderIgnorePlants(id,true)){
            helper.fail("Dome Planner configuration was rejected");return;
        }
        table.setBuilderMaterialsForFile(filename,data.required());
        // The tested OnSite chest handles ALL actual stock. The stub
        // warehouse link merely satisfies the existing manual Planner
        // start guard; it does not create shulkers or free items.
        table.setWarehouse(id,new WarehouseAssignment(
                level.dimension().identifier().toString(),
                supplyPos.getX(),supplyPos.getY(),supplyPos.getZ()));
        final List<BuilderGolem> crew=new ArrayList<>();
        for(int i=0;i<4;i++){
            BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
            BlockPos pos=helper.absolutePos(new BlockPos(6+i%2,2,5+i/2));
            worker.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);
            worker.assign(plannerPos,id);
            level.addFreshEntity(worker);
            if(!table.registerBuilderWorker(worker.getUUID())){
                helper.fail("Cannot register Builder "+i);return;
            }
            crew.add(worker);
        }
        // Four registered Builders remain the ONLY construction crew.
        // A fifth physical Builder entity belongs to the same owner and
        // planner, but is dedicated to crane operation and must not occupy
        // a work-order/courier slot. CraneService.controls() isolates it.
        BuilderGolem operator=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        BlockPos operatorSpawn=helper.absolutePos(new BlockPos(3,2,25));
        operator.setPos(operatorSpawn.getX()+.5,operatorSpawn.getY(),
                operatorSpawn.getZ()+.5);
        operator.assign(plannerPos,id);
        if(!level.addFreshEntity(operator)
                ||table.builderWorkerIds().size()!=4
                ||table.builderWorkerIds().contains(operator.getUUID())){
            helper.fail("Crane operator must be a fifth independent entity, "
                    +"not one of the four scheduled builders");return;
        }
        if(!table.startPlacedBuilder(id)){
            helper.fail("Dome Planner rejected start with four registered builders");
            return;
        }
        var crane=new BuildingCrane(CraneContent.CRANE,level);
        BlockPos craneRail=helper.absolutePos(new BlockPos(3,2,24));
        // South-offset basket stays inside the arena throughout rail travel.
        BlockPos craneBasket=helper.absolutePos(new BlockPos(3,2,27));
        crane.place(id,craneRail,Vec3.atBottomCenterOf(craneBasket));
        if(!level.addFreshEntity(crane)||!crane.link(table)
                ||!crane.assignOperator(operator)){
            helper.fail("Could not place and link physical crane or assign operator");
            return;
        }
        // A player-placed crane is tracked immediately. Do the same here,
        // before the first Builder tick, so the fifth operator is excluded
        // from construction and courier tasks from its very first tick.
        CraneService.track(level,crane);
        owner.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(CraneContent.BUILDER_BOX));
        crane.interact(owner,InteractionHand.MAIN_HAND);
        owner.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(CraneContent.BOOM,2));
        crane.interact(owner,InteractionHand.MAIN_HAND);
        crane.interact(owner,InteractionHand.MAIN_HAND);
        if(crane.reach()!=90){
            helper.fail("Two physical boom upgrades were not consumed");return;
        }
        // Give the crane a real requested upper landing on an exterior
        // platform. No builder is teleported or granted free items; the
        // same worker resumes its construction zone after unloading.
        for(int x=5;x<=7;x++)for(int z=24;z<=26;z++)
            helper.setBlock(x,16,z,Blocks.STONE);
        BlockPos elevatedDock=helper.absolutePos(new BlockPos(6,17,25));
        if(!crane.request(crew.get(0),elevatedDock)){
            helper.fail("Crane rejected real first builder's elevated ride");return;
        }
        final Set<UUID> craneBoarded=new LinkedHashSet<>();
        final java.util.Map<UUID,Integer> craneBoardingY=new java.util.HashMap<>();
        final Set<UUID> elevatedDeliveries=new LinkedHashSet<>();
        final Set<UUID> craneUnloaded=new LinkedHashSet<>();
        final int[] maxRiders={0};
        final int[] lastCursor={-1},lastCleared={-1},lastCompleted={-1};
        final long[] lastProgress={helper.getTick()};
        final boolean[] inspected={false},checkpoint={false};
        helper.onEachTick(()->{
            long tick=helper.getTick();
            if(tick%5!=0)return;
            if(tick==100 && (crane.tickCount==0||operator.tickCount==0)){
                helper.fail("Crane/operator is not entity-ticking in Dome arena: "
                        +"craneTicks="+crane.tickCount
                        +" operatorTicks="+operator.tickCount
                        +" owner="+owner.blockPosition()
                        +" crane="+crane.blockPosition()
                        +" gameTime="+level.getGameTime()
                        +" ownerLoaded="+level.hasChunkAt(owner.blockPosition())
                        +" operatorLoaded="+level.hasChunkAt(operator.blockPosition())
                        +" craneLoaded="+level.hasChunkAt(crane.blockPosition())
                        +" plannerLoaded="+level.hasChunkAt(plannerPos)
                        +" craneInLevel="+crane.isAddedToLevel()
                        +" operatorInLevel="+operator.isAddedToLevel());
                return;
            }
            int riding=0;
            for(BuilderGolem worker:crew){
                if(worker.getVehicle()==crane){
                    riding++;
                    craneBoarded.add(worker.getUUID());
                    craneBoardingY.putIfAbsent(worker.getUUID(),
                            worker.blockPosition().getY());
                } else if(craneBoarded.contains(worker.getUUID())){
                    craneUnloaded.add(worker.getUUID());
                    int initialY=craneBoardingY.getOrDefault(worker.getUUID(),
                            worker.blockPosition().getY());
                    if(worker.blockPosition().getY()>=initialY+6)
                        elevatedDeliveries.add(worker.getUUID());
                }
            }
            maxRiders[0]=Math.max(maxRiders[0],riding);
            if(riding>4){
                helper.fail("Crane exceeded four worker passenger seats");return;
            }
            if(tick>400&&(operator.isRemoved()||operator.getVehicle()!=crane)){
                helper.fail("Dedicated builder crane operator did not remain seated: "
                        +crane.status());return;
            }
            // Replenish the 27-slot CHEST, never worker inventories.
            Set<Item> needs=new LinkedHashSet<>();
            needs.add(Items.SCAFFOLDING);
            needs.add(Items.IRON_PICKAXE);
            needs.add(Items.IRON_AXE);
            needs.add(Items.IRON_SHOVEL);
            for(BuilderGolem worker:crew){
                for(Integer n:table.builderWorkOrder(worker.getUUID())){
                    if(n!=null&&n>=0&&n<steps.size())
                        addItem(needs,steps.get(n));
                }
                int current=table.claimedStep(worker.getUUID());
                if(current>=0&&current<steps.size())addItem(needs,steps.get(current));
            }
            for(int i=Math.max(0,table.builderCursor());
                    i<Math.min(steps.size(),table.builderCursor()+48)
                            &&needs.size()<25;i++)
                addItem(needs,steps.get(i));
            BuilderInfiniteOnsiteSupply.restock(level,supplyPos,needs);
            // Independent Builder zones legitimately complete hundreds of
            // out-of-order positions while the first unbuilt index remains
            // pinned. Watching only builderCursor falsely calls that a stall.
            if(lastCursor[0]!=table.builderCursor()
                    ||lastCleared[0]!=table.builderClearCursor()
                    ||lastCompleted[0]!=table.builderCompletedCount()){
                lastCursor[0]=table.builderCursor();
                lastCleared[0]=table.builderClearCursor();
                lastCompleted[0]=table.builderCompletedCount();
                lastProgress[0]=tick;
            }
            if(!checkpoint[0]&&table.builderCompletedCount()>=1700){
                // This intermediate acceptance checkpoint is independent
                // of eventual 3537/3537 completion. It must verify all
                // four actual entities are alive (not merely scheduled).
                if(crew.size()!=4||crew.stream().anyMatch(w->!w.isAlive())){
                    helper.fail("Reached 1700 but four real Builders not alive");
                    return;
                }
                checkpoint[0]=true;
                System.out.println("[DOME TEAMWORK] PASS 1700 placed-block "
                        +"milestone with FOUR real Builders at tick="+tick);
            }
            if(!inspected[0]&&table.builderClearCursor()>0){
                inspected[0]=true;
                System.out.println("[DOME E2E] physical clearing started at tick="+tick);
            }
            if(table.phase()==WorkerPhase.COMPLETE){
                int wrong=0, checked=0, ignored=0;
                StringBuilder sample=new StringBuilder();
                for(var step:steps){
                    if(step.ignorePlant()){ignored++;continue;}
                    checked++;
                    var expected=BuilderBlockResolver.resolve(step.encodedState(),
                            BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
                    if(expected==null){wrong++;continue;}
                    BlockPos pos=new BlockPos(step.x(),step.y(),step.z());
                    if(!BuilderPlacementState.matches(level.getBlockState(pos),
                            expected.state())){
                        wrong++;
                        if(sample.length()<1200)sample.append(pos).append(": ")
                                .append(level.getBlockState(pos)).append(" vs ")
                                .append(expected.state()).append("; ");
                    }
                }
                if(wrong>0){
                    helper.fail("Dome COMPLETED incorrectly: "+wrong+
                            " block-state mismatches; "+sample);return;
                }
                boolean atGround=crew.stream().allMatch(
                        worker->worker.onGround()&&!worker.isNoGravity());
                boolean scaffoldsGone=crew.stream().allMatch(
                        worker->table.scaffoldPositions(worker.getUUID()).isEmpty());
                if(atGround&&scaffoldsGone){
                    if(elevatedDeliveries.isEmpty()){
                        helper.fail("Dome built but crane never physically delivered "
                                +"a builder six blocks above its boarding elevation"
                                +" | boarded="+craneBoarded.size()
                                +" unloaded="+craneUnloaded.size()
                                +" | "+crane.status());return;
                    }
                    System.out.println("[DOME CRANE E2E] PASS "
                            +steps.size()+" blueprint positions, "+checked
                            +" required states matched and "+ignored
                            +" vegetation steps intentionally ignored; "
                            +"four actual builders grounded, scaffolds reclaimed, "
                            +"physical crane boardings="+craneBoarded.size()
                            +" delivered="+craneUnloaded.size()
                            +" elevatedDeliveries="+elevatedDeliveries.size()
                            +" maxSimultaneous="+maxRiders[0]);
                    helper.succeed();
                }
                return;
            }
            if(tick-lastProgress[0]>2400){
                StringBuilder message=new StringBuilder(
                        "Dome stalled after "+tick+" ticks | crane="+crane.status()
                        +" | mounted="+craneBoarded.size()+" unloaded="+craneUnloaded.size()
                        +" elevated="+elevatedDeliveries.size()
                        +" | clear="+
                        table.builderClearCursor()+" | contiguous="+table.builderCursor()
                        +" | totalCompleted="+table.builderCompletedCount()
                        +"/"+steps.size()+" | problem="+table.problem());
                for(BuilderGolem worker:crew)
                    message.append(" | ").append(worker.getName().getString())
                        .append(" @ ").append(worker.blockPosition())
                        .append(" ").append(BuilderBuildService.debugStatus(level,worker));
                helper.fail(message.toString());
            }
        });
    }

    private static void addItem(Set<Item> requested,BuilderConstructionPlan.Step s){
        if(s.requiredItem()==null)return;
        Identifier id=Identifier.tryParse(s.requiredItem());
        if(id==null)return;
        Item item=BuiltInRegistries.ITEM.getValue(id);
        if(item!=null&&item!=Items.AIR)requested.add(item);
    }
}
