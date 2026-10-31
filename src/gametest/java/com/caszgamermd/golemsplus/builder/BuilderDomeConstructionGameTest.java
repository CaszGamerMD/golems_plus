package com.caszgamermd.golemsplus.builder;

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
import net.minecraft.world.item.Item;
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
    public void constructsExactDomeWithRealBuilders(GameTestHelper helper){
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
        final int requiredBlocks=(int)steps.stream()
                .filter(step->!step.ignorePlant()).count();
        final int ignoredPlants=steps.size()-requiredBlocks;
        if(requiredBlocks!=3493||ignoredPlants!=44){
            helper.fail("Dome vegetation count regression: required="
                    +requiredBlocks+" ignored="+ignoredPlants);return;
        }
        System.out.println("[DOME WORK TARGET] schematic="+steps.size()
                +" ignoredPlants="+ignoredPlants+" actualRequired="+requiredBlocks);
        // Minecraft GameTest allocates the containing world at a random
        // coordinate. The *schematic's own coordinate frame* starts at
        // (0,0,0), the ground supporting Y=0 is local Y=-1, and ALL
        // human-readable test reports prioritize this stable local frame.
        System.out.println("[DOME FRAME] schematic=(0,0,0)"
                +" baseY=0 groundY=-1 "
                +" dimensions=(39,39) absoluteGameTestOriginHidden");
        // A full size test structure is essential. The default 8x8
        // GameTest room causes invisible barrier-block path failures.
        for(int x=4;x<=42;x++)for(int z=4;z<=42;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
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
        Path actualFile=folder.resolve(table.builderFile()).normalize();
        System.out.println("[DOME FILE] saved="+table.builderFile()
                +" folder="+folder.toAbsolutePath()
                +" exists="+Files.isRegularFile(actualFile)
                +" length="+(Files.isRegularFile(actualFile)?fileSize(actualFile):-1));
        if(!Files.isRegularFile(actualFile)){
            helper.fail("Dome schematic failed immediate staging; "
                +"input="+filename+" saved="+table.builderFile()
                +" expected="+actualFile.toAbsolutePath());
            return;
        }
        // Genuine chest-only job; no fake warehouse connection.
        // Workers must walk to the real 27-slot OnSite crate to load.
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
        if(!table.startPlacedBuilder(id)){
            helper.fail("Dome Planner rejected start with 4 registered builders");
            return;
        }
        final int[] lastCursor={-1},lastCleared={-1},lastCompleted={-1};
        final long[] lastProgress={helper.getTick()};
        // Diagnostic-only per-worker motion/claim watches. These never
        // change a Builder's task, movement, inventory or scheduling.
        final net.minecraft.world.phys.Vec3[] lastWorkerPose=
                new net.minecraft.world.phys.Vec3[crew.size()];
        final int[] lastWorkerClaim=new int[crew.size()];
        final long[] lastWorkerActivity=new long[crew.size()];
        for(int i=0;i<crew.size();i++){
            BuilderGolem worker=crew.get(i);
            lastWorkerPose[i]=worker.position();
            lastWorkerClaim[i]=table.claimedStep(worker.getUUID());
            lastWorkerActivity[i]=helper.getTick();
        }
        final boolean[] inspected={false},checkpoint={false},goal3333={false},goal3400={false};
        helper.onEachTick(()->{
            long tick=helper.getTick();
            if(tick%20!=0)return;
            // Count genuine placed world states, including independent
            // out-of-order zone completion; don't use obsolete ledger fields.
            int physicalCount=physicallyCorrect(level,steps);
            int sharedY=table.builderActiveLayer(steps);
            // A crew can make overall progress while ONE member is trapped
            // on a scaffold or holds one unreachable placement cell. Keep
            // that member's coordinates and route state in the CI artifact.
            for(int i=0;i<crew.size();i++){
                BuilderGolem worker=crew.get(i);
                int claim=table.claimedStep(worker.getUUID());
                var pose=worker.position();
                if(pose.distanceToSqr(lastWorkerPose[i])>=.0625
                        ||claim!=lastWorkerClaim[i]){
                    lastWorkerPose[i]=pose;
                    lastWorkerClaim[i]=claim;
                    lastWorkerActivity[i]=tick;
                }
                if(tick%400!=0||tick-lastWorkerActivity[i]<600
                        ||claim<0&&table.scaffoldPositions(
                                worker.getUUID()).isEmpty())continue;
                BlockPos target=claim>=0&&claim<steps.size()
                        ?new BlockPos(steps.get(claim).x(),
                                steps.get(claim).y(),steps.get(claim).z())
                        :null;
                String snapshot="[DOME STUCK WORKER] tick="+tick
                        +" idleTicks="+(tick-lastWorkerActivity[i])
                        +" worker="+worker.getName().getString()
                        +" feetXYZ="+worker.position().subtract(
                                net.minecraft.world.phys.Vec3.atLowerCornerOf(
                                        table.builderOrigin()))
                        +" grounded="+worker.onGround()
                        +" noGravity="+worker.isNoGravity()
                        +" claimedStep="+claim
                        +" targetXYZ="+(target==null?"none":
                                target.subtract(table.builderOrigin()))
                        +" ownedScaffolds="+table.scaffoldPositions(
                                worker.getUUID()).size()
                        +" scaffoldPlan="+BuilderScaffoldPlanA.status(worker)
                        +" climb="+BuilderScaffolding.debugClimb(worker)
                        +" grapple="+BuilderGrappleAccess.debug(worker)
                        +" problem="+table.problem()
                        +" | "+BuilderBuildService.debugStatus(level,worker)
                        +" | targetNeighbors="+(target==null?"none":
                                surroundingBlocks(level,target));
                System.out.println(snapshot);
                try{
                    Path report=Path.of("route-survey/dome_stuck_workers.log");
                    Files.createDirectories(report.getParent());
                    Files.writeString(report,snapshot+System.lineSeparator(),
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                }catch(Exception e){
                    System.err.println("[DOME STUCK REPORT] "+e);
                }
            }
            // Fail immediately if a Builder receives an out-of-layer
            // construction assignment; no silent progress on upper Y
            // while an indexed lower-Y cell remains unfinished.
            for(BuilderGolem member:crew){
                int assigned=table.claimedStep(member.getUUID());
                if(assigned>=0&&assigned<steps.size()
                        &&steps.get(assigned).y()>sharedY){
                    helper.fail("Y LAYER VIOLATION: assigned Y="
                            +(steps.get(assigned).y()-table.builderOrigin().getY())
                            +" above active Y="
                            +(sharedY-table.builderOrigin().getY())
                            +" worker="+member.getName().getString());
                    return;
                }
            }
            if(tick%400==0)
                System.out.println("[DOME PROGRESS] tick="+tick
                    +" realBlocks="+physicalCount+"/"+requiredBlocks
                    +" activeY="+(sharedY-table.builderOrigin().getY())
                    +" clear="+table.builderClearCursor()
                    +" cursor="+table.builderCursor()
                    +" problem="+table.problem());
            // When progress becomes intermittent, record each golem's
            // world/dome-relative location and the exact occupied build
            // cell BEFORE the final stall. This is read-only telemetry.
            if(tick%400==0&&tick-lastProgress[0]>800)
                System.out.println("[DOME CREW] tick="+tick
                        +crewCoordinates(level,table,crew,steps));
            // Once a crew is stuck on one height, report exactly which
            // blocks remain unbuilt there. Do not confuse the schematic
            // block's name with the worker's currently held item.
            if(tick%1600==0&&tick-lastProgress[0]>800)
                System.out.println("[DOME REMAINING] tick="+tick+" "
                        +remainingLayerItems(level,table,steps,sharedY,16));
            // Replenish the 27-slot CHEST, never worker inventories.
            Set<Item> needs=new LinkedHashSet<>();
            needs.add(WorksiteContent.GRAPPLE_HOOK);
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
                    ||lastCompleted[0]!=physicalCount){
                lastCursor[0]=table.builderCursor();
                lastCleared[0]=table.builderClearCursor();
                lastCompleted[0]=physicalCount;
                lastProgress[0]=tick;
            }
            if(!checkpoint[0]&&physicalCount>=1700){
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
            if(!goal3333[0]&&physicalCount>=3333){
                if(crew.size()!=4||crew.stream().anyMatch(w->!w.isAlive())){
                    helper.fail("3333 milestone requires four living physical Builders");return;
                }
                goal3333[0]=true;
                System.out.println("[DOME TEAMWORK] PASS 3333 physically verified blocks, "
                        +"FOUR living Builders at tick="+tick);
            }
            if(!goal3400[0]&&physicalCount>=3400){
                goal3400[0]=true;
                System.out.println("[DOME TEAMWORK] PASS 3400 physical blocks,"
                        +" activeY="+(sharedY-table.builderOrigin().getY())
                        +" tick="+tick+" fourAlive="
                        +crew.stream().allMatch(BuilderGolem::isAlive));
            }
            if(!inspected[0]&&table.builderClearCursor()>0){
                inspected[0]=true;
                System.out.println("[DOME E2E] physical clearing started at tick="+tick);
            }
            if(table.phase()==WorkerPhase.COMPLETE){
                int wrong=0;StringBuilder sample=new StringBuilder();
                for(var step:steps){
                    if(step.ignorePlant())continue;
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
                    System.out.println("[DOME E2E] PASS "+requiredBlocks
                            +"/"+requiredBlocks+" required blocks verified; "
                            +ignoredPlants+" ignored vegetation entries; "
                            +"4 workers grounded, scaffolding recovered");
                    helper.succeed();
                }else if(tick-lastProgress[0]>2400){
                    // All schematic cells are correct, but finish-up still
                    // requires a real return to safe ground and physically
                    // recovered scaffold inventory. Do not wait until the
                    // 90k-tick test timeout without showing which worker
                    // remains elevated, suspended or scaffold-bound.
                    StringBuilder stuck=new StringBuilder(
                            "Dome built every required block, but crew "
                            +"could not physically finish cleanup after "
                            +tick+" ticks | grounded="+atGround
                            +" scaffoldsGone="+scaffoldsGone);
                    for(BuilderGolem member:crew)
                        stuck.append(" | ")
                                .append(member.getName().getString())
                                .append(" @ ").append(member.position())
                                .append(" ground=").append(member.onGround())
                                .append(" noGravity=").append(member.isNoGravity())
                                .append(" scaffold=").append(table.scaffoldPositions(
                                        member.getUUID()).size())
                                .append(" ").append(BuilderBuildService.debugStatus(
                                        level,member));
                    helper.fail(stuck.toString());
                }
                return;
            }
            if(tick-lastProgress[0]>2400){
                // Snapshot the actual partly constructed geometry, worker
                // locations, target cells and collision-valid routes.
                // This is read-only and lives in the archived CI artifact.
                BuilderDomeRouteSurveyGameTest.captureLive(
                        level,table,crew,steps);
                StringBuilder message=new StringBuilder(
                        "Dome stalled after "+tick+" ticks | clear="+
                        table.builderClearCursor()+" | contiguous="+table.builderCursor()
                        +" | totalCompleted="+physicalCount
                        +"/"+requiredBlocks+" | ignoredPlants="+ignoredPlants
                        +" | problem="+table.problem()
                        +stageDiagnostic(folder,table));
                for(BuilderGolem worker:crew)
                    message.append(" | ").append(worker.getName().getString())
                        .append(" @ ").append(worker.blockPosition())
                        .append(" ").append(BuilderBuildService.debugStatus(level,worker));
                message.append(crewCoordinates(level,table,crew,steps))
                        .append(" | remainingLayer=")
                        .append(remainingLayerItems(
                                level,table,steps,sharedY,32));
                helper.fail(message.toString());
            }
        });
    }

    /** A stationary screenshot of the real physical dome worksite:
     * absolute world coordinates are retained, and offsets use the
     * schematic's actual chosen origin, not GameTest's random world seed.
     */
    private static String crewCoordinates(ServerLevel level,
            WorkerStationBlockEntity table,List<BuilderGolem> crew,
            List<BuilderConstructionPlan.Step> steps){
        BlockPos origin=table.builderOrigin();
        StringBuilder out=new StringBuilder(" | schematic=(0,0,0)"
                +" (world position omitted; use local XYZ)");
        for(BuilderGolem mob:crew){
            BlockPos at=mob.blockPosition();
            int index=table.claimedStep(mob.getUUID());
            out.append("\n[DOME COORD] golem=").append(mob.getName().getString())
                    .append(" XYZ=").append(at.subtract(origin).toShortString())
                    .append(" preciseXYZ=").append(String.format(
                            java.util.Locale.ROOT,"(%.3f,%.3f,%.3f)",
                            mob.getX()-origin.getX(),
                            mob.getY()-origin.getY(),
                            mob.getZ()-origin.getZ()))
                    .append(" claimedStep=").append(index)
                    .append(" held=").append(BuilderPlacementSpace.debugYield(
                            level,mob,origin))
                    .append(" grapple=").append(BuilderGrappleAccess.debug(mob));
            if(index>=0&&index<steps.size()){
                var step=steps.get(index);
                BlockPos target=new BlockPos(step.x(),step.y(),step.z());
                out.append(" targetXYZ=")
                        .append(target.subtract(origin).toShortString())
                        .append(" placeState=").append(step.encodedState())
                        .append(" placeItem=").append(step.requiredItem())
                        .append(" below=")
                        .append(level.getBlockState(target.below()))
                        .append(" blockNow=")
                        .append(level.getBlockState(target))
                        .append(" occupants=")
                        .append(BuilderPlacementSpace.debugOccupants(
                                level,table,target,origin));
            }
        }
        return out.toString();
    }

    /** Read-only six-sided collision/support context for any stuck target. */
    private static String surroundingBlocks(ServerLevel level,BlockPos pos){
        StringBuilder result=new StringBuilder();
        for(var side:net.minecraft.core.Direction.values()){
            if(result.length()>0)result.append(';');
            result.append(side).append('=')
                    .append(level.getBlockState(pos.relative(side)));
        }
        return result.toString();
    }

    /** Exact outstanding work at the current locked construction Y.
     * This is read-only and used in failure logs, not for job scheduling.
     * Inspect actual world states so out-of-order completed zones count.
     */
    private static String remainingLayerItems(ServerLevel level,
            WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> steps,int activeY,int max){
        BlockPos origin=table.builderOrigin();
        StringBuilder items=new StringBuilder();
        java.util.Map<String,Integer> counts=new java.util.TreeMap<>();
        int remaining=0,shown=0;
        for(int i=0;i<steps.size();i++){
            var step=steps.get(i);
            if(step.ignorePlant()||step.y()!=activeY)continue;
            var intended=BuilderBlockResolver.resolve(step.encodedState(),
                    BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
            if(intended==null)continue;
            BlockPos at=new BlockPos(step.x(),step.y(),step.z());
            if(BuilderPlacementState.matches(level.getBlockState(at),
                    intended.state()))continue;
            remaining++;
            String item=step.requiredItem()==null
                    ?"(free multiblock half)":step.requiredItem();
            counts.merge(item,1,Integer::sum);
            if(shown++>=max)continue;
            if(items.length()>0)items.append("; ");
            int adjacentBuilt=0;
            for(var side:net.minecraft.core.Direction.values()){
                BlockPos neighbor=at.relative(side);
                if(!level.getBlockState(neighbor).isAir())adjacentBuilt++;
            }
            items.append("#").append(i).append(" ")
                    .append(at.subtract(origin).toShortString())
                    .append(" state=").append(step.encodedState())
                    .append(" item=").append(item)
                    .append(" below=").append(level.getBlockState(at.below()))
                    .append(" adjacentOccupied=").append(adjacentBuilt)
                    .append(" current=").append(level.getBlockState(at));
        }
        return "activeY="+(activeY-origin.getY())
                +" outstanding="+remaining+" items="+counts
                +" examples=["+items+"]";
    }

    private static long fileSize(Path path){
        try{return Files.size(path);}catch(Exception ignored){return -1;}
    }
    private static String stageDiagnostic(Path folder,WorkerStationBlockEntity table){
        Path file=folder.resolve(table.builderFile()).normalize();
        return " file="+table.builderFile()+" resolved="+file.toAbsolutePath()
                +" exists="+Files.isRegularFile(file)+" bytes="+fileSize(file);
    }
    private static int physicallyCorrect(ServerLevel level,
            List<BuilderConstructionPlan.Step> plan){
        int count=0;
        for(var step:plan){
            if(step.ignorePlant())continue;
            var expected=BuilderBlockResolver.resolve(step.encodedState(),
                    BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
            if(expected==null)continue;
            if(BuilderPlacementState.matches(
                    level.getBlockState(new BlockPos(step.x(),step.y(),step.z())),
                    expected.state()))count++;
        }
        return count;
    }

    private static void addItem(Set<Item> requested,BuilderConstructionPlan.Step s){
        if(s.requiredItem()==null)return;
        Identifier id=Identifier.tryParse(s.requiredItem());
        if(id==null)return;
        Item item=BuiltInRegistries.ITEM.getValue(id);
        if(item!=null&&item!=Items.AIR)requested.add(item);
    }
}
