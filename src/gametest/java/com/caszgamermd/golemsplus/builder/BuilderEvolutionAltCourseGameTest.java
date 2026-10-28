package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

/**
 * EVOLUTIONARY TRAINING INSIDE THE REAL MINECRAFT SERVER.
 * Every generation spawns 10 physical 0.65 x 0.65 x 1.6 BuilderGolem
 * entities, drives legal walking/jumping into REAL Minecraft collisions,
 * times out after 260 ticks, and selects/crosses/mutates navigation
 * control genes using ONLY what the real entities physically achieved.
 *
 * Neither a successful headless simulation nor theoretical route length
 * can pass this test. All nine real entities must reach real RED_WOOL in
 * the same 260-tick generation. No flight, scaffold or world modification
 * during runs. The original test fixture is built once before training.
 */
public final class BuilderEvolutionAltCourseGameTest {
    private static final int DEADLINE=260;
    private static final String NATIVE_PROTOCOL="course-entity-ticking-v1";
    private static JsonObject nativeTickCoverage;
    // Preserve the original 13s physics benchmark and require a *real*
    // 10-of-10 arrival by 11.2s to qualify this speed experiment.
    private static final int SPEED_TARGET_TICKS=224;
    private static final int GENERATIONS=24;
    private static final long TRAINING_SEED=2026102602L;
    private static final double SPEED_CAP=0.2;
    // Five bots from each green-wool column, spaced through all ten rows.
    // Keep both columns and the same maze: only concurrent entity count changes.
    private static final int[] SELECTED_BOTS={0,2,4,6,8,11,13,15,17};
    private static Block block(String name){
        return BuiltInRegistries.BLOCK.getValue(
                Identifier.tryParse("minecraft:"+name));
    }
    private static final Block GREEN=block("green_wool");
    private static final Block RED=block("red_wool");
    private static final Block GRAY=block("light_gray_concrete");
    private static final int X_OFFSET=4,Z_OFFSET=4;

    // Exact vanilla Sponge v3 timetrial_alt(2).schem, SHA-256:
    // b8a216c4b1a91359c044fa20546b214670b8431991d305fd47f4e2c8a72d1ea4
    // 27 X, 12 Z, 10 Y; GREEN start, RED goal. Everything from source.
    private static final String[] MAZE={
        "###########################",
        "#GG............#.....#.#SS#",
        "#GG...^^^......#..#..#..SS#",
        "#GG.#.^^^.#.####..#..#.#SS#",
        "#GG.#.^^^.#.#..^..#..#.#SS#",
        "#GG.#.^^^.#.#..^..#....#SS#",
        "#GG.#.^^^.#.#..^..#....#SS#",
        "#GG.#.^^^...#..#..#..#.#SS#",
        "#GG.#.###...#..#..#..#.#SS#",
        "#GG.#....##....#..#..#..SS#",
        "#GG.D.....#....#.#...#.#SS#",
        "###########################"
    };
    // Sponge palette/block indices; y=4..9 are identical, so repeat
    // the final stored layer. No collision shape or door is invented.
    private static final String[][] COURSE_LAYERS={
      {
        "000000000000000000000000000",
        "011222222222222222222222330",
        "011222222222222222222222330",
        "011222222222222222222222330",
        "011222222222222222222222330",
        "011222222222222222222222330",
        "011222222222222222222222330",
        "011222222222222222222222330",
        "011222222222222222222222330",
        "011222222222222222222222330",
        "011222222222222222222222330",
        "000000000000000000000000000"
      },
      {
        "444444444444444444444444444",
        "455555555555555655555656554",
        "455555777555555655655655554",
        "455565777565666655655656554",
        "455565777565655755655656554",
        "455565777565555755655556554",
        "455565777565555755655556554",
        "455565777555555655655656554",
        "455565666555655655655656554",
        "455565555665555655655655554",
        "455585555565555656555656554",
        "444444444444444444444444444"
      },
      {
        "444444444444444444444444444",
        "455555555555555655555656554",
        "455555555555555655955655554",
        "4555655555656aa655b55656554",
        "455565555565655555b55656554",
        "455565555565c55555b55556554",
        "455565555565c55555b55556554",
        "455565555555c55655b55656554",
        "455565666555655655b55656554",
        "455565555665555655d55655554",
        "4555e5555565555656555656554",
        "444444444444444444444444444"
      },
      {
        "444444444444444444444444444",
        "455555555555555b55555555554",
        "455555555555555b55555555554",
        "4555555555556aaf55555555554",
        "455555555555655555555555554",
        "455555555555655555555555554",
        "455555555555655555555555554",
        "455555555555655555555555554",
        "455555555555655555555555554",
        "455555555555555555555555554",
        "455555555555555556555555554",
        "444444444444444444444444444"
      },
      {
        "444444444444444444444444444",
        "455555555555555555555555554",
        "455555555555555555555555554",
        "455555555555555555555555554",
        "455555555555555555555555554",
        "455555555555555555555555554",
        "455555555555555555555555554",
        "455555555555555555555555554",
        "455555555555555555555555554",
        "455555555555555555555555554",
        "455555555555555555555555554",
        "444444444444444444444444444"
      }
    };
    private static final String[] COURSE_PALETTE={
        "minecraft:light_gray_concrete",
        "minecraft:red_wool",
        "minecraft:stone",
        "minecraft:green_wool",
        "minecraft:glass",
        "minecraft:air",
        "minecraft:birch_log[axis=y]",
        "minecraft:birch_slab[type=bottom,waterlogged=false]",
        "minecraft:birch_door[facing=east,half=lower,hinge=left,open=true,powered=false]",
        "minecraft:birch_fence[east=false,north=false,south=true,waterlogged=false,west=false]",
        "minecraft:birch_fence[east=true,north=false,south=false,waterlogged=false,west=true]",
        "minecraft:birch_fence[east=false,north=true,south=true,waterlogged=false,west=false]",
        "minecraft:birch_slab[type=top,waterlogged=false]",
        "minecraft:birch_fence[east=false,north=true,south=false,waterlogged=false,west=false]",
        "minecraft:birch_door[facing=east,half=upper,hinge=left,open=true,powered=false]",
        "minecraft:birch_fence[east=false,north=true,south=false,waterlogged=false,west=true]"
    };
    private static final int[][] STARTS={
        {24,1},{24,2},{24,3},{24,4},{24,5},
        {24,6},{24,7},{24,8},{24,9},{24,10},
        {25,1},{25,2},{25,3},{25,4},{25,5},
        {25,6},{25,7},{25,8},{25,9},{25,10}
    };
    // The prior simulator's hard-coded routes have been removed.
    // Each genotype selects its own route, but only REAL Minecraft
    // BuilderGolem movement can earn fitness or pass the GameTest.

    /**
     * Chromosome for ACTUAL entity walking. Each numeric gene affects
     * real movement on Minecraft's collision geometry: horizontal drive,
     * centerline steering, waypoint acquisition, step-up jump timing and
     * stuck recovery. Mutations cannot grant no-gravity or teleporting.
     */
    private record Genome(double drive,double align,double tolerance,
                          double jumpLead,double jumpPower,
                          int jumpCooldown,int stuckTimeout,
                          double backoff,double slabAvoid,
                          double turnBias,double routeJitter,
                          double doorBias) {
        static Genome random(Random rng){
            return new Genome(.90+rng.nextDouble()*.10,
                    .65+rng.nextDouble()*.35,
                    .09+rng.nextDouble()*.12,
                    .36+rng.nextDouble()*.95,
                    .36+rng.nextDouble()*.08,
                    7+rng.nextInt(17),
                    9+rng.nextInt(27),
                    .06+rng.nextDouble()*.14,
                    rng.nextDouble()*2.0,
                    rng.nextDouble()*.65,
                    rng.nextDouble()*.50,
                    -.14+rng.nextDouble()*.28);
        }
        static Genome cross(Genome a,Genome b,Random rng){
            return new Genome(
                    rng.nextBoolean()?a.drive:b.drive,
                    rng.nextBoolean()?a.align:b.align,
                    rng.nextBoolean()?a.tolerance:b.tolerance,
                    rng.nextBoolean()?a.jumpLead:b.jumpLead,
                    rng.nextBoolean()?a.jumpPower:b.jumpPower,
                    rng.nextBoolean()?a.jumpCooldown:b.jumpCooldown,
                    rng.nextBoolean()?a.stuckTimeout:b.stuckTimeout,
                    rng.nextBoolean()?a.backoff:b.backoff,
                    rng.nextBoolean()?a.slabAvoid:b.slabAvoid,
                    rng.nextBoolean()?a.turnBias:b.turnBias,
                    rng.nextBoolean()?a.routeJitter:b.routeJitter,
                    rng.nextBoolean()?a.doorBias:b.doorBias);
        }
        Genome mutate(Random rng){
            // Local search: keep eleven learned traits intact. Previously
            // every child perturbed all twelve at once, obscuring which
            // change helped and frequently destroying useful combinations.
            int changed=rng.nextInt(12);
            return new Genome(
                    changed==0?clamp(drive+rng.nextGaussian()*.035,.55,1):drive,
                    changed==1?clamp(align+rng.nextGaussian()*.07,.35,1):align,
                    changed==2?clamp(tolerance+rng.nextGaussian()*.0125,.05,.24):tolerance,
                    changed==3?clamp(jumpLead+rng.nextGaussian()*.10,.2,1.65):jumpLead,
                    changed==4?clamp(jumpPower+rng.nextGaussian()*.01,.34,.46):jumpPower,
                    changed==5?(int)clamp(jumpCooldown+(rng.nextBoolean()?1:-1),4,32):jumpCooldown,
                    changed==6?(int)clamp(stuckTimeout+(rng.nextBoolean()?1:-1),5,45):stuckTimeout,
                    changed==7?clamp(backoff+rng.nextGaussian()*.0275,.03,.27):backoff,
                    changed==8?clamp(slabAvoid+rng.nextGaussian()*.225,0,5):slabAvoid,
                    changed==9?clamp(turnBias+rng.nextGaussian()*.10,0,1.8):turnBias,
                    changed==10?clamp(routeJitter+rng.nextGaussian()*.10,0,1.5):routeJitter,
                    changed==11?clamp(doorBias+rng.nextGaussian()*.06,-.16,.16):doorBias);
        }
        private static double clamp(double value,double lo,double hi){
            return Math.max(lo,Math.min(hi,value));
        }
    }
    // Physically verified Minecraft champion: workflow run 40319805415,
    // generation 1, 10/10 real BuilderGolem entities at <=360 ticks.
    // Persist the actual winning chromosomes between GitHub test runs.
    // These are learned motor/route genes, NOT a hard-coded path replay.
    // Future generations still undergo full physical fitness evaluation.
    // Transfer ONLY 12-gene behaviors proven on the original course.
    // No routes or obstacle map are transferred to this different track.
    private static final Genome[] SAVED_PHYSICAL_CHAMPIONS={
        new Genome(0.9488814564462474,0.45296287244329664,0.2199650457909909,0.2,0.4204209959859857,6,31,0.03,1.7379278941011653,0.11558998727788404,0,0.06558463999826915),
        new Genome(0.8364234112192664,0.4113297962549574,0.1256781132722294,1.612378991277391,0.42134491358271997,4,5,0.047257099110992365,0.930637188482702,0,0.6061846965159705,0.16),
        new Genome(0.8920111883705335,0.42466379100723206,0.1800208523707811,1.0007882449192,0.3824628194905035,24,9,0.11366794399618094,1.8253727463076623,0.08528749620211179,0.31541663015666965,0.16),
        new Genome(1,1,0.14351570763669566,0.2,0.35353830836662475,22,27,0.09187581969982929,0.6923078481838354,0.051406179094560994,0,0.11597453157167698),
        new Genome(0.8272682035040223,0.7247170578888141,0.10334841259015944,0.5326740931276187,0.3819824886129413,23,29,0.13808276250236057,0.4412319308441079,0,0.08192189508886007,0.014127490789647007),
        new Genome(0.9168749530439636,0.8026809788090639,0.1825266985261164,1.038582009152946,0.3839876304341242,16,24,0.19701379514586967,0.6220137295884944,0.04821388978704847,0.19416274773945358,0.053253703924759394),
        new Genome(0.8492596311591167,0.6150947664877628,0.15604617068961743,0.7125704608596453,0.389218079827502,29,41,0.07841370166320166,0.4063118091225754,0,0.049639740523128306,0.14768099552169398),
        new Genome(0.9682275689730867,0.8661075083117684,0.1769418214961036,0.9455250814657139,0.3661797294784096,24,23,0.27,0.39097235334508323,0.29175158973173526,0.4203474281839856,0.14242728182919365),
        new Genome(1,0.9363196864900857,0.15492280726342655,0.4274521529672981,0.38707848813677453,15,25,0.181393839999303,0.2988610745667909,0.09367358721997576,0.24985344020678218,0.16),
        new Genome(0.9915760710284254,0.4947791258647092,0.17310944040524376,1.096445363754993,0.41433770584681034,18,16,0.14152972468383765,0,0.1834492714146736,0.5647404070380825,0.16)
    };

    private record Edge(int x,int z,int toX,int toZ){}

    /** Independent REAL-entity result in a shared persistent top-three pool.
     * One record per starting slot prevents a single spawn from crowding
     * out the learnings of the other nine workers.
     */
    private record Knowledge(int slot,Genome dna,double fitness,
                             int arrivedTick,double progress,int generation,
                             NavigationStrategyMemory.Strategy policy,
                             String route){}

    /** An elite's evidence belongs to its original starting slot. */
    private static List<Genome> pinElites(List<Genome> population,
                                         List<Knowledge> pool){
        List<Genome> pinned=new ArrayList<>(population);
        for(Knowledge elite:pool)pinned.set(elite.slot(),elite.dna());
        return pinned;
    }
    /** 3 actual winning parents + 2 independently mutated descendants each.
     * Parents keep their original lane, route template and controller DNA;
     * children inherit a parent's route intent but replan from their own
     * location under actual Minecraft collision geometry.
     */
    private record FamilyPlan(List<Genome> genes,
            List<NavigationStrategyMemory.Strategy> policies,
            List<String> parentRoutes,List<Integer> parentSlots){}
    private static FamilyPlan breedFamilies(List<Knowledge> winners,
                                             Random rng,int size){
        if(winners.size()!=3||size!=9)
            throw new IllegalArgumentException("Nine-golem family needs exactly 3 parents");
        List<Genome> genes=new ArrayList<>(java.util.Collections.nCopies(size,null));
        List<NavigationStrategyMemory.Strategy> policies=
                new ArrayList<>(java.util.Collections.nCopies(size,null));
        List<String> routes=new ArrayList<>(java.util.Collections.nCopies(size,null));
        List<Integer> lineage=new ArrayList<>(java.util.Collections.nCopies(size,-1));
        for(Knowledge parent:winners){
            int slot=parent.slot();
            if(slot<0||slot>=size||genes.get(slot)!=null)
                throw new IllegalArgumentException("Parents need distinct physical lanes");
            genes.set(slot,parent.dna());
            policies.set(slot,parent.policy());
            routes.set(slot,parent.route());
            lineage.set(slot,slot);
        }
        List<Integer> vacancies=new ArrayList<>();
        for(int i=0;i<size;i++)if(genes.get(i)==null)vacancies.add(i);
        int cursor=0;
        for(Knowledge parent:winners)for(int clone=0;clone<2;clone++){
            int slot=vacancies.get(cursor++);
            genes.set(slot,parent.dna().mutate(rng));
            // A route experiment is a different legal A* preference,
            // never a copy/paste of route steps from another start tile.
            var policy=parent.policy();
            if(clone==1&&rng.nextInt(4)==0)
                policy=policy==NavigationStrategyMemory.Strategy.DOOR
                        ?NavigationStrategyMemory.Strategy.DIRECT
                        :NavigationStrategyMemory.Strategy.DOOR;
            policies.set(slot,policy);
            routes.set(slot,parent.route());
            lineage.set(slot,parent.slot());
        }
        if(genes.stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalStateException("Unfilled family slot");
        return new FamilyPlan(genes,policies,routes,lineage);
    }

    private static void updateKnowledgePool(Tournament run,Candidate b){
        double score=b.fitness();
        Knowledge record=new Knowledge(b.index,b.dna,score,b.arrivedTick,
                b.bestCursor+(b.cursor<b.route.length() ? 0.0 : 1.0),
                run.completedGenerations+1,
                b.strategyDecision.strategy(),b.initialRoute);
        // Rank THIS generation's physical finishers, not historical lane
        // champions. Failed runs are ranked by genuine forward progress.
        // Exactly three distinct parents feed the next nine-golem cohort.
        run.knowledgePool.removeIf(k->k.slot()==b.index);
        run.knowledgePool.add(record);
        run.knowledgePool.sort(Comparator
                .comparingDouble(Knowledge::fitness).reversed()
                .thenComparingInt(Knowledge::slot));
        if(run.knowledgePool.size()>3)
            run.knowledgePool.subList(3,run.knowledgePool.size()).clear();
        System.out.println("[ALT KNOWLEDGE POOL] slot="+b.index
                +" improved fitness="+score+" arrived="+b.arrivedTick
                +" topThree="+run.knowledgePool.stream()
                    .map(k->Integer.toString(k.slot())).toList());
    }

    private static final class Candidate {
        final Map<Edge,Integer> realBlockFailures=new HashMap<>();
        final int index;
        final BuilderGolem mob;
        // Routes may be replanned from CURRENT physical position after
        // a real obstruction; only the 12-gene logic is inherited.
        String route;
        final String initialRoute;
        int familyParentSlot=-1;
        String familyRole="seed";
        final Genome dna;
        final int initialEntityTicks;
        final JsonArray nativeTickSamples=new JsonArray();
        int controlTick,episodeSerial,episodeCooldown,holdingStage,holdingArrivalTick,holdingAttempts;
        LocalEpisode localEpisode;
        RuleRound ruleRound;
        NavigationRightOfWay rightOfWay;
        final List<NavigationRuleMemory.Episode> localEpisodes=new ArrayList<>();
        String strategyContext;
        NavigationStrategyMemory.Decision strategyDecision;
        final Map<Edge,Integer> inheritedBlockMemory;
        // Actual Minecraft positions are retained for EVERY physical generation,
        // including failed offspring, never simulated or interpolated.
        final JsonArray movementSamples=new JsonArray();
        String movementMode="w";
        boolean capturedArrival;
        double lastRecordedX=Double.NaN,lastRecordedZ=Double.NaN;
        int reducedSpeedTicks,stationaryTicks,reverseTicks,doorTicks,blockedTicks;
        double measuredDistance;
        void captureMovement(int tick){
            if(capturedArrival)return;
            double realX=mob.getX(),realZ=mob.getZ();
            if(Double.isFinite(lastRecordedX)){
                double moved=Math.hypot(realX-lastRecordedX,realZ-lastRecordedZ);
                measuredDistance+=moved;
                if(moved<.005)stationaryTicks++;
                else if(moved<.15)reducedSpeedTicks++;
            }
            lastRecordedX=realX;lastRecordedZ=realZ;
            if("b".equals(movementMode))reverseTicks++;
            if("d".equals(movementMode))doorTicks++;
            if("s".equals(movementMode)||"p".equals(movementMode))
                slabTicks++;
            if("p".equals(movementMode))precisionTicks++;
            if("c".equals(movementMode))blockedTicks++;
            if("t".equals(movementMode))trafficRecoveryTicks++;
            if("q".equals(movementMode)||"y".equals(movementMode))
                yieldingTicks++;
            if("q".equals(movementMode))trafficQueueTicks++;
            if("y".equals(movementMode))trafficCrossingTicks++;
            JsonArray sample=new JsonArray();
            sample.add(tick);
            sample.add((int)Math.round((realX-originX)*1000));
            sample.add((int)Math.round((realZ-originZ)*1000));
            sample.add(arrivedTick>=0?"f":movementMode);
            movementSamples.add(sample);
            JsonArray nativeSample=new JsonArray();
            nativeSample.add(tick);nativeSample.add(mob.tickCount-initialEntityTicks);
            nativeSample.add((int)Math.round(mob.getY()*1000));
            nativeTickSamples.add(nativeSample);
            if(arrivedTick>=0)capturedArrival=true;
        }
        int cursor;
        int x,z;
        int arrivedTick=-1;
        int collisions;
        int jumps;
        int bestCursor;
        int immobile;
        int backoffTicks;
        int ticksSinceJump;
        double doorLaneShift;
        boolean doorOffsetX;
        int slabTicks,reroutes,precisionTicks,staticStalls;
        // Physical evidence for actual low-step learning, NOT a planned
        // slab route or a replay interpolation.
        int slabApproachTicks,successfulSlabAscents,slabEntryStalls;
        int doorEntryAlignTicks,doorEntryStalls;
        int doorNoForwardTicks,doorStateRetries;
        int attemptedDoorX=Integer.MIN_VALUE,attemptedDoorZ=Integer.MIN_VALUE;
        Boolean observedDoorOpen;
        int lastDoorAttemptTick=-100;
        int yieldingTicks,trafficQueueTicks,trafficCrossingTicks;
        int trafficWaitTicks,trafficReroutes,lastTrafficRerouteTick=-1000;
        int safeCornerShortcuts;
        int trafficRecoveryTicks;
        boolean initialRouteUsesDoor;
        int trafficPrioritySlot=-1;
        int lastRerouteTick=-1000;
        int doorTargetX=Integer.MIN_VALUE,doorTargetZ=Integer.MIN_VALUE;
        int exitTargetX=Integer.MIN_VALUE,exitTargetZ=Integer.MIN_VALUE;
        double lastDistance=Double.POSITIVE_INFINITY;
        Candidate(int index,BuilderGolem mob,String route,int x,int z,
                  Genome dna,Map<Edge,Integer> inheritedBlockMemory){
            this.index=index;
            this.mob=mob;
            this.route=route;
            this.initialRoute=route;
            this.x=x;this.z=z;
            this.dna=dna;
            this.initialEntityTicks=mob.tickCount;
            this.inheritedBlockMemory=inheritedBlockMemory;
        }
        double fitness(){
            if(arrivedTick>=0)
                return 100000+(DEADLINE-arrivedTick)*15
                        -collisions*2;
            // Fitness is grounded in physically traversed checkpoints
            // and remaining REAL distance to the next tile.
            double fraction=0;
            if(cursor<route.length()){
                int[] next=next(x,z,route.charAt(cursor));
                double remaining=Math.sqrt(
                        Math.pow((next[0]+.5)-(mob.getX()-originX),2)
                        +Math.pow((next[1]+.5)-(mob.getZ()-originZ),2));
                fraction=Math.max(0,1-Math.min(1,remaining));
            }
            double progress=(bestCursor+fraction)
                    /Math.max(1,route.length());
            return Math.min(1,Math.max(0,progress))*10000
                    -collisions*3-reroutes*10;
        }
        // Absolute positions of the GameTest arena origin, only used to
        // score how close the actual entity came to its next tile.
        double originX,originZ;
        long birthTick;
    }

    private static BlockPos pos(GameTestHelper h,int x,int y,int z){
        return h.absolutePos(new BlockPos(X_OFFSET+x,y,Z_OFFSET+z));
    }

    private static <T extends Comparable<T>> BlockState setPaletteProperty(
            BlockState state,net.minecraft.world.level.block.state.properties.Property<T> property,
            String value){
        return property.getValue(value)
                .map(v->state.setValue(property,v))
                .orElseThrow(()->new IllegalStateException(
                        "Unknown schematic property value "+property.getName()+"="+value));
    }

    private static BlockState parsePaletteState(String spec){
        int bracket=spec.indexOf('[');
        String id=bracket<0?spec:spec.substring(0,bracket);
        Block source=BuiltInRegistries.BLOCK.getOptional(
                    Identifier.tryParse(id))
                .orElseThrow(()->new IllegalStateException(
                    "Exact schematic requires missing block "+id+
                    " (install Diagonal Fences Fabric 26.2 + dependencies in GameTest)"));
        BlockState state=source.defaultBlockState();
        if(bracket<0)return state;
        String props=spec.substring(bracket+1,spec.length()-1);
        for(String entry:props.split(",")){
            String[] pair=entry.split("=",2);
            if(pair.length!=2)throw new IllegalStateException("Bad blockstate "+spec);
            var property=source.getStateDefinition().getProperty(pair[0]);
            if(property==null)throw new IllegalStateException(
                    "Schematic property not registered "+id+"."+pair[0]);
            state=setPaletteProperty(state,property,pair[1]);
        }
        return state;
    }
    private static void fillOriginalCourse(GameTestHelper h){
        // All sixteen palette blockstates are vanilla. Fail immediately
        // if any is missing or properties differ from the upload.
        BlockState[] palette=new BlockState[COURSE_PALETTE.length];
        for(int i=0;i<palette.length;i++)
            palette[i]=parsePaletteState(COURSE_PALETTE[i]);
        if(MAZE.length!=12||STARTS.length!=20||COURSE_LAYERS.length!=5)
            throw new IllegalStateException("Wrong corrected schematic dimensions");
        for(int y=0;y<10;y++)
            for(int z=0;z<12;z++){
                String row=COURSE_LAYERS[Math.min(y,4)][z];
                if(row.length()!=27)throw new IllegalStateException(
                        "Expected exact 27-block schematic row");
                for(int x=0;x<27;x++){
                    int paletteIndex=Character.digit(row.charAt(x),16);
                    if(paletteIndex<0||paletteIndex>=palette.length)
                        throw new IllegalStateException("Invalid schematic palette index");
                    h.setBlock(X_OFFSET+x,1+y,Z_OFFSET+z,palette[paletteIndex]);
                }
            }
        System.out.println("[ALT COURSE] Restored exact VANILLA 27x10x12 track; "
                +"9 real golems race from green wool to red wool in 260 ticks.");
    }

    private static Runnable holdCourseSimulation(GameTestHelper helper){
        BlockPos a=pos(helper,-1,2,-1),b=pos(helper,27,2,12);
        java.util.Set<ChunkPos> chunks=new java.util.HashSet<>();
        for(int x=Math.min(a.getX(),b.getX())>>4;x<=(Math.max(a.getX(),b.getX())>>4);x++)
            for(int z=Math.min(a.getZ(),b.getZ())>>4;z<=(Math.max(a.getZ(),b.getZ())>>4);z++)
                chunks.add(new ChunkPos(x,z));
        return BuilderChunkTickets.simulationLease(helper.getLevel(),chunks);
    }


    private static int[] next(int x,int z,char step){
        return switch(step){
            case 'N' -> new int[]{x,z-1};
            case 'S' -> new int[]{x,z+1};
            case 'E' -> new int[]{x+1,z};
            case 'W' -> new int[]{x-1,z};
            // Keypad diagonals: 7 NW, 9 NE, 3 SE, 1 SW.
            // These are single real movements, not two cardinal moves.
            case '7' -> new int[]{x-1,z-1};
            case '9' -> new int[]{x+1,z-1};
            case '3' -> new int[]{x+1,z+1};
            case '1' -> new int[]{x-1,z+1};
            default -> throw new IllegalArgumentException("Bad replay movement");
        };
    }

    private static boolean walkableCell(int x,int z){
        return z>=0&&z<MAZE.length&&x>=0&&x<MAZE[z].length()
                &&MAZE[z].charAt(x)!='#';
    }

    /**
     * Real geometry, not schematic glyphs: a half-slab has a 0.5-block
     * standing surface, and the full 1.6-block Builder body must fit
     * ABOVE it. A floor slab under an overhead slab can have only one
     * block of headroom and is NOT a legal route, even though both
     * individual blocks are slabs. Keep vanilla 0.6 automatic step.
     */
    private static double standingY(GameTestHelper helper,ServerLevel world,
                                    int x,int z){
        if(!walkableCell(x,z))return Double.NaN;
        BlockPos feet=pos(helper,x,2,z);
        BlockState state=world.getBlockState(feet);
        // Wooden doors are traversable ACTION nodes even when closed.
        // A golem must physically approach and open the door before
        // the native collision resolver lets its body pass through.
        if(MAZE[z].charAt(x)=='D'){
            if(!canOperateDoor(state))return Double.NaN;
            return feet.getY();
        }
        var shape=state.getCollisionShape(world,feet);
        double rise=shape.isEmpty()?0:shape.max(Direction.Axis.Y);
        if(rise>.600001)return Double.NaN;
        return feet.getY()+rise;
    }

    private static boolean canOperateDoor(BlockState state){
        return state.getBlock() instanceof DoorBlock door
                &&door.type().canOpenByHand();
    }

    /** Opening a door is an allowed physical interaction, NOT a wall
     * deletion, teleport, force-open from afar or route exploit.
     */
    private static boolean openDoorFromReach(ServerLevel world,
            BuilderGolem mob,BlockPos lower){
        BlockState state=world.getBlockState(lower);
        if(!canOperateDoor(state))return false;
        if(state.getValue(DoorBlock.OPEN))return true;
        double distance=Math.hypot(mob.getX()-lower.getX()-.5,
                mob.getZ()-lower.getZ()-.5);
        if(distance>1.7||Math.abs(mob.getY()-lower.getY())>1.0)
            return false;
        BlockState upper=world.getBlockState(lower.above());
        if(!canOperateDoor(upper))return false;
        world.setBlock(lower,state.setValue(DoorBlock.OPEN,true),3);
        world.setBlock(lower.above(),
                upper.setValue(DoorBlock.OPEN,true),3);
        System.out.println("[ALT DOOR OPEN] golem opened wooden door at "
                +lower+" distance="+distance);
        return true;
    }

    /** A doorway attempt counts only FORWARD displacement along the
     * planned entering/exiting axis, not lateral alignment, jumping, or
     * another worker's movement. A collision-free step resets its clock.
     * Four consecutive failed attempts open a CLOSED wooden door. An
     * already-open passage stays OPEN; re-evaluate lateral clearance
     * instead of closing a door onto a worker in the doorway.
     */
    private static void recordDoorForwardProgress(Candidate b,
            ServerLevel world,BlockPos door,double oldX,double oldZ,
            int toX,int toZ,boolean teammateBlocking,
            boolean aligning){
        if(door==null){
            b.doorNoForwardTicks=0;
            b.attemptedDoorX=Integer.MIN_VALUE;
            b.attemptedDoorZ=Integer.MIN_VALUE;
            b.observedDoorOpen=null;
            b.lastDoorAttemptTick=-100;
            return;
        }
        if(b.attemptedDoorX!=door.getX()
                ||b.attemptedDoorZ!=door.getZ()){
            b.attemptedDoorX=door.getX();
            b.attemptedDoorZ=door.getZ();
            b.doorNoForwardTicks=0;
            b.observedDoorOpen=null;
        }
        // A teammate hold or a replanning tick interrupted forward
        // movement: require four *consecutive* physical movement ticks.
        if(b.lastDoorAttemptTick+1!=b.controlTick)
            b.doorNoForwardTicks=0;
        b.lastDoorAttemptTick=b.controlTick;
        BlockState state=world.getBlockState(door);
        if(!canOperateDoor(state))return;
        if(Math.hypot(b.mob.getX()-door.getX()-.5,
                b.mob.getZ()-door.getZ()-.5)>1.7
                ||Math.abs(b.mob.getY()-door.getY())>1.0){
            // A distant obstruction cannot be blamed on this doorway.
            b.doorNoForwardTicks=0;
            return;
        }
        boolean currentOpen=state.getValue(DoorBlock.OPEN);
        if(b.observedDoorOpen!=null&&b.observedDoorOpen!=currentOpen){
            // Another golem changed this door between our ticks.
            // Don't immediately reverse its state using stale stalls.
            b.doorNoForwardTicks=0;
        }
        b.observedDoorOpen=currentOpen;
        if(teammateBlocking||aligning){
            // Courtesy queues and deliberate sideways docking are not
            // physical doorway failures; never flip under a teammate.
            b.doorNoForwardTicks=0;
            return;
        }
        int axisX=Integer.compare(toX,b.x);
        int axisZ=Integer.compare(toZ,b.z);
        double forward=(b.mob.getX()-oldX)*axisX
                +(b.mob.getZ()-oldZ)*axisZ;
        if(forward>.01){
            b.doorNoForwardTicks=0;
            return;
        }
        if(++b.doorNoForwardTicks>=4){
            b.doorNoForwardTicks=0;
            if(!currentOpen&&openDoorFromReach(world,b.mob,door)){
                b.doorStateRetries++;
                b.observedDoorOpen=true;
                // Keep the chosen path. Re-evaluate the lane now that
                // the real collision shape of the door has changed.
                b.doorTargetX=Integer.MIN_VALUE;
            }else if(currentOpen){
                // Villager-style: an open door is a traversable path
                // node, not a switch to repeatedly close and reopen.
                // Try a different REAL collision-checked lane without
                // replacing any previously learned route or genome.
                double alternate=safeDoorLane(world,b.mob,door,
                        b.doorLaneShift>=0?-.12:.12,b.doorOffsetX);
                if(Math.abs(alternate-b.doorLaneShift)>.01)
                    b.doorLaneShift=alternate;
            }
            b.lastDistance=Double.POSITIVE_INFINITY;
            b.immobile=0;
            b.backoffTicks=0;
        }
    }

    private static boolean clearBodyAt(ServerLevel world,BuilderGolem mob,
                                        double x,double y,double z){
        if(!Double.isFinite(y))return false;
        double half=mob.getBbWidth()/2.0;
        var box=new net.minecraft.world.phys.AABB(
                x-half,y+.002,z-half,
                x+half,y+mob.getBbHeight()-.002,z+half);
        return !world.getBlockCollisions(mob,box).iterator().hasNext();
    }

    private static boolean physicalCellOpen(GameTestHelper helper,
                    ServerLevel world,BuilderGolem mob,int x,int z){
        if(!walkableCell(x,z))return false;
        double y=standingY(helper,world,x,z);
        if(!Double.isFinite(y))return false;
        BlockPos pos=pos(helper,x,2,z);
        if(MAZE[z].charAt(x)=='D'){
            // Closed wooden doors are legal PLANNED actions, not an
            // impassable wall. Only when physically near may a worker
            // open them; Minecraft collision resolves the actual walk.
            if(!world.getBlockState(pos).getValue(DoorBlock.OPEN))
                return true;
            for(double lane:new double[]{0,-.08,.08,-.12,.12,-.16,.16}){
                if(clearBodyAt(world,mob,pos.getX()+.5,y,pos.getZ()+.5+lane))
                    return true;
                if(clearBodyAt(world,mob,pos.getX()+.5+lane,y,pos.getZ()+.5))
                    return true;
            }
            return false;
        }
        return clearBodyAt(world,mob,pos.getX()+.5,y,pos.getZ()+.5);
    }

    /**
     * Sweep the REAL 0.65 x 1.6 Builder hitbox across a cardinal edge
     * at the higher of the two supported standing heights. This rejects
     * the ceiling-slab opening and corner clipping that a check only at
     * individual cell centers can miss. Legal 0.5 floor slab ramps
     * remain accessible through Minecraft's automatic ground step.
     */
    private static boolean bodySweepClear(GameTestHelper helper,
            ServerLevel world,BuilderGolem mob,int fromX,int fromZ,
            int toX,int toZ){
        double before=standingY(helper,world,fromX,fromZ);
        double after=standingY(helper,world,toX,toZ);
        if(!Double.isFinite(before)||!Double.isFinite(after)
                ||after-before>.600001||before-after>1.000001)return false;
        // The door's open leaf requires an offset; verified in the
        // directional doorway controller and its own real GameTest.
        if(MAZE[fromZ].charAt(fromX)=='D'
                ||MAZE[toZ].charAt(toX)=='D')return true;
        BlockPos origin=pos(helper,0,2,0);
        double y=Math.max(before,after);
        for(int i=0;i<=12;i++){
            double t=i/12.0;
            double x=origin.getX()+fromX+.5+(toX-fromX)*t;
            double z=origin.getZ()+fromZ+.5+(toZ-fromZ)*t;
            if(!clearBodyAt(world,mob,x,y,z))return false;
        }
        return true;
    }

    /**
     * Precision steering through slabs and tight overhead apertures.
     * Direction changes inside these corridors need a real centered
     * body, not a corner cut based solely on the waypoint tolerance.
     */
    private static boolean precisionTransition(GameTestHelper helper,
            ServerLevel world,int fromX,int fromZ,int toX,int toZ){
        if(MAZE[fromZ].charAt(fromX)=='D'
                ||MAZE[toZ].charAt(toX)=='D')return true;
        double before=standingY(helper,world,fromX,fromZ);
        double after=standingY(helper,world,toX,toZ);
        // Precise alignment is essential while stepping ON or OFF a slab,
        // but a broad, same-height slab plateau is ordinary walkable floor.
        // Treating EVERY slab tile as a precision obstacle forces a near
        // full stop at each cell center and wastes the shortcut's benefit.
        // Keep conservative steering by actual height transitions, slab
        // edges and low overhead clearance, not by floor block identity.
        if(Math.abs(before-after)>.01
                ||nearGroundSlabEdge(helper,world,fromX,fromZ)
                ||nearGroundSlabEdge(helper,world,toX,toZ))
            return true;
        for(int[] p:new int[][]{{fromX,fromZ},{toX,toZ}}){
            BlockPos cell=pos(helper,p[0],2,p[1]);
            BlockState head=world.getBlockState(cell.above());
            if(head.getBlock() instanceof SlabBlock)
                return true;
            // A ceiling-slab next to a narrow opening can be
            // hazardous even when the destination center is clear.
            for(Direction side:new Direction[]{
                    Direction.NORTH,Direction.SOUTH,
                    Direction.EAST,Direction.WEST}){
                BlockState near=world.getBlockState(cell.relative(side).above());
                if(near.getBlock() instanceof SlabBlock)return true;
            }
        }
        return false;
    }

    /**
     * A grid diagonal is only a candidate. Inspect Minecraft's REAL
     * solid-block collision at the full 0.65-wide, 1.6-high entity size
     * along the entire diagonal. Keep door openings and steps as
     * cardinal approach moves because their hitbox/height transitions
     * require their own physical alignment/auto-step handling.
     */
    private static boolean diagonalBodyClear(GameTestHelper helper,
            ServerLevel world,BuilderGolem mob,int fromX,int fromZ,
            int toX,int toZ){
        int loX=Math.min(fromX,toX),hiX=Math.max(fromX,toX);
        int loZ=Math.min(fromZ,toZ),hiZ=Math.max(fromZ,toZ);
        // A diagonal must be across one continuous, LEVEL surface:
        // all four cells must support the complete 0.65 x 1.6 body at
        // the same Y. This enables safe diagonals ON a slab plateau,
        // but prevents the fatal "free block on the other side" cut
        // across the vertical EDGE of the first raised slab.
        double y=standingY(helper,world,fromX,fromZ);
        if(!Double.isFinite(y)
                ||Math.abs(y-standingY(helper,world,toX,toZ))>.005)
            return false;
        for(int x=loX;x<=hiX;x++)for(int z=loZ;z<=hiZ;z++){
            if(!walkableCell(x,z)||MAZE[z].charAt(x)=='D'
                    ||Math.abs(y-standingY(helper,world,x,z))>.005
                    ||!physicalCellOpen(helper,world,mob,x,z))
                return false;
        }
        BlockPos origin=pos(helper,0,2,0);
        double half=mob.getBbWidth()*.5;
        for(int i=0;i<=24;i++){
            double t=i/24.0;
            double centerX=origin.getX()+fromX+.5+(toX-fromX)*t;
            double centerZ=origin.getZ()+fromZ+.5+(toZ-fromZ)*t;
            if(!clearBodyAt(world,mob,centerX,y,centerZ))
                return false;
            // Require real floor collision support UNDER the moving
            // footprint. An open diagonal destination does NOT prove
            // that the physical body is supported on the corner cut.
            var support=new net.minecraft.world.phys.AABB(
                    centerX-half+.02,y-.08,centerZ-half+.02,
                    centerX+half-.02,y-.015,centerZ+half-.02);
            if(!world.getBlockCollisions(mob,support).iterator().hasNext())
                return false;
        }
        return true;
    }

    /**
     * Corners adjacent to a half-block floor STEP are hazardous even
     * when both corner-waypoint centers are on clear flat ground.
     * Do not blend directional steering or acquire a waypoint early
     * beside one of these raised slab edges. This does not slow
     * straight walking along a continuous slab plateau.
     */
    private static boolean nearGroundSlabEdge(GameTestHelper helper,
            ServerLevel world,int x,int z){
        BlockPos feet=pos(helper,x,2,z);
        double centerY=standingY(helper,world,x,z);
        if(!Double.isFinite(centerY))return true;
        for(Direction side:new Direction[]{
                Direction.NORTH,Direction.SOUTH,
                Direction.EAST,Direction.WEST}){
            BlockPos adjacent=feet.relative(side);
            BlockState state=world.getBlockState(adjacent);
            if(state.getBlock() instanceof SlabBlock
                    &&Math.abs(centerY-(adjacent.getY()
                        +state.getCollisionShape(world,adjacent)
                            .max(Direction.Axis.Y)))>.01)
                return true;
        }
        return false;
    }

    private record RouteNode(int x,int z,char heading){}
    private record Frontier(RouteNode node,double cost){}

    /** This is only an initial PLAN against the known schematic blocks.
     * No movement is simulated here and no route can be declared a winner.
     * Actual movement/fitness is measured from living Minecraft golems.
     *
     * Route genes allow evolution to prefer shorter paths, fewer turns,
     * avoiding risky slabs or exploring different legal corridors.
     */
    private static String planRoute(int startX,int startZ,Genome dna,
                                    Map<Edge,Integer> realBlockFailures,
                                    GameTestHelper helper,ServerLevel world,
                                    BuilderGolem mob){
        return planRouteTo(startX,startZ,dna,realBlockFailures,
                helper,world,mob,-1,-1);
    }

    // Temporary live-crew traffic costs are NOT permanent blocked edges
    // and are never written into the genetic collision-memory checkpoint.
    private static String planRouteTo(int startX,int startZ,Genome dna,
                                    Map<Edge,Integer> realBlockFailures,
                                    GameTestHelper helper,ServerLevel world,
                                    BuilderGolem mob,int goalX,int goalZ,
                                    Map<Integer,Double> crewCongestion){
        return planRouteToCosted(startX,startZ,dna,realBlockFailures,
                helper,world,mob,goalX,goalZ,crewCongestion);
    }

    /**
     * A* from a current position to an optionally specified real
     * navigable cell; (-1,-1) still targets the original GREEN finish.
     * Used for evaluating alternative discovered openings. Both
     * segments are calculated by the SAME physics-checked planner;
     * no saved route, replay, or world-edit shortcuts.
     */
    private static String planRouteTo(int startX,int startZ,Genome dna,
                                    Map<Edge,Integer> realBlockFailures,
                                    GameTestHelper helper,ServerLevel world,
                                    BuilderGolem mob,int goalX,int goalZ){
        return planRouteToCosted(startX,startZ,dna,realBlockFailures,
                helper,world,mob,goalX,goalZ,Map.of());
    }

    private static String planRouteToCosted(int startX,int startZ,Genome dna,
                                    Map<Edge,Integer> realBlockFailures,
                                    GameTestHelper helper,ServerLevel world,
                                    BuilderGolem mob,int goalX,int goalZ,
                                    Map<Integer,Double> crewCongestion){
        RouteNode start=new RouteNode(startX,startZ,'?');
        PriorityQueue<Frontier> open=new PriorityQueue<>(
                Comparator.comparingDouble(Frontier::cost));
        Map<RouteNode,Double> best=new HashMap<>();
        Map<RouteNode,RouteNode> parents=new HashMap<>();
        Map<RouteNode,Character> directions=new HashMap<>();
        // Cache immutable physical-collision checks for this single plan.
        // The test rebuilds the course before each scored training run.
        Map<Integer,Boolean> physicallyOpen=new HashMap<>();
        open.add(new Frontier(start,0));best.put(start,0.0);
        RouteNode finish=null;
        while(!open.isEmpty()){
            Frontier current=open.poll();
            RouteNode node=current.node();
            if(current.cost()>best.getOrDefault(node,Double.POSITIVE_INFINITY))
                continue;
            if(goalX>=0
                    ?(node.x()==goalX&&node.z()==goalZ)
                    :MAZE[node.z()].charAt(node.x())=='G'){
                finish=node;break;
            }
            for(char direction:new char[]{'N','E','S','W','7','9','3','1'}){
                int[] p=next(node.x(),node.z(),direction);
                int x=p[0],z=p[1];
                if(!walkableCell(x,z))continue;
                int key=z*MAZE[0].length()+x;
                if(!physicallyOpen.computeIfAbsent(key,
                    ignored->physicalCellOpen(helper,world,mob,x,z)))
                    continue;
                double fromY=standingY(helper,world,node.x(),node.z());
                double toY=standingY(helper,world,x,z);
                // Preserve vanilla auto-step and ground-only walking.
                if(!Double.isFinite(fromY)||!Double.isFinite(toY)
                    ||toY-fromY>.600001||fromY-toY>1.000001
                    ||!bodySweepClear(helper,world,mob,
                            node.x(),node.z(),x,z))
                    continue;
                int stepX=x-node.x(),stepZ=z-node.z();
                boolean diagonal=stepX!=0&&stepZ!=0;
                // A 0.65-wide golem cannot cut through two wall
                // corners just because their diagonal destination is
                // nominally open. Require both adjoining side tiles to
                // be open; physical AABB collision validates the move.
                if(diagonal&&(!physicalCellOpen(helper,world,mob,
                            node.x()+stepX,node.z())
                        ||!physicalCellOpen(helper,world,mob,
                            node.x(),node.z()+stepZ)
                        ||!diagonalBodyClear(helper,world,mob,
                                node.x(),node.z(),x,z)))
                    continue;
                char tile=MAZE[z].charAt(x);
                // Half slabs are WALKABLE terrain with normal 0.6
                // auto-step. The prior per-tile slabAvoid (up to +5
                // EACH tile) trained golems to take massive detours.
                // Penalize only actual height transitions, lightly.
                // Walking across a contiguous slab surface is priced
                // like equally long flat ground, including diagonals
                // when verified by the full-body physics sweep.
                boolean heightChange=Math.abs(toY-fromY)>.01;
                double transitionPenalty=heightChange
                        ?Math.min(.25,dna.slabAvoid()*.05):0;
                // An actual unobstructed LEVEL slab plateau is no harder
                // to cross diagonally than ordinary full-height ground.
                // Prefer a legal diagonal over stair-step zigzags instead
                // of charging a large turn penalty for every diagonal.
                // The edge check above still forbids diagonal step-up,
                // corner clipping and low-ceiling crossings.
                boolean slabCruise=diagonal
                        &&MAZE[z].charAt(x)=='^'
                        &&MAZE[node.z()].charAt(node.x())=='^'
                        &&Math.abs(toY-fromY)<.005;
                double turnCost=node.heading()!='?'
                        &&node.heading()!=direction
                        ?(slabCruise?Math.min(.10,dna.turnBias())
                                     :dna.turnBias()):0;
                double weight=(diagonal?Math.sqrt(2):1.0)
                        +transitionPenalty+turnCost
                        -(slabCruise?.16:0);
                // The door must be an EXPLORABLE alternative, not
                // just a legal tile that no genome ever tries. Reuse
                // the existing inherited/mutable signed doorBias gene:
                // positive bias slightly rewards using an OPEN door,
                // negative bias avoids it. No 13th gene / checkpoint
                // migration and NO forced or memorized door route.
                // The cap keeps every A* edge positive (>=0.20) and
                // the actual 10 real golems decide the winning policy
                // with physical arrival fitness, not expected geometry.
                if(tile=='D'){
                    // A one-block wooden door (open or operable closed)
                    // can be physically legal
                    // but much SLOWER than bare ground: the last
                    // real-door experiment finished none of its five
                    // door-using golems before tick 260. Account for
                    // the narrow leaf alignment using a modest 2.5m
                    // traversal cost, modulated by the saved mutable
                    // preference. Never forbid the door completely.
                    weight+=2.5-Math.max(-.3,Math.min(.3,
                            dna.doorBias()*2.0));
                    if(!world.getBlockState(pos(helper,x,2,z))
                            .getValue(DoorBlock.OPEN))
                        weight+=.4; // one grounded opening interaction
                }
                // Deterministic genetic corridor preferences. Small
                // positive perturbations prevent repeatedly selecting
                // exactly the same equally costly route across a crew.
                int hash=Math.floorMod(x*31873+z*19009
                        +direction*7919,1000);
                weight+=dna.routeJitter()*hash/1000.0;
                // Only empirically observed REAL Minecraft block
                // collisions add these extra costs. Do not train on
                // simulated wall guesses or other entity traffic.
                Edge edge=new Edge(node.x(),node.z(),x,z);
                // Preserve all real collision memories but do not let
                // historic congestion permanently forbid a geometrically
                // legal half-slab crossing or the single door.
                BlockState fromFoot=world.getBlockState(pos(helper,node.x(),2,node.z()));
                BlockState toFoot=world.getBlockState(pos(helper,x,2,z));
                boolean checkedBottomSlab=
                        fromFoot.getBlock() instanceof SlabBlock
                            &&fromFoot.getValue(SlabBlock.TYPE)==SlabType.BOTTOM
                        ||toFoot.getBlock() instanceof SlabBlock
                            &&toFoot.getValue(SlabBlock.TYPE)==SlabType.BOTTOM;
                if(!checkedBottomSlab)
                    weight+=Math.min(10,realBlockFailures.getOrDefault(edge,0)*.40);
                // Reservations are soft, session-local hints that help a
                // CREW spread across the six available slab entries. They
                // never invalidate an otherwise legal route and do not
                // overwrite proven fitness or inherited block evidence.
                weight+=crewCongestion.getOrDefault(key,0.0);
                RouteNode nextNode=new RouteNode(x,z,direction);
                double cost=current.cost()+weight;
                if(cost>=best.getOrDefault(nextNode,Double.POSITIVE_INFINITY))
                    continue;
                best.put(nextNode,cost);
                parents.put(nextNode,node);
                directions.put(nextNode,direction);
                open.add(new Frontier(nextNode,cost));
            }
        }
        if(finish==null)throw new IllegalStateException(
                "No reachable red-wool tile from "+startX+","+startZ);
        StringBuilder route=new StringBuilder();
        for(RouteNode at=finish;!at.equals(start);at=parents.get(at))
            route.append(directions.get(at));
        return route.reverse().toString();
    }

    /** True only if this independently planned route ACTUALLY enters
     * the open door's grid cell. This is a diagnostic, not route forcing.
     */
    private static boolean routeEntersDoor(int fromX,int fromZ,String route){
        int x=fromX,z=fromZ;
        for(int i=0;i<route.length();i++){
            int[] ahead=next(x,z,route.charAt(i));
            x=ahead[0];z=ahead[1];
            if(MAZE[z].charAt(x)=='D')return true;
        }
        return false;
    }

    /** A physically planned route enters a continuous 0.5-high slab
     * surface before reaching a wooden door. No saved coordinates or
     * movement instructions are supplied to the navigation policy.
     */
    private static boolean routeCrossesSlabsThenDoor(int fromX,int fromZ,
                                                     String route){
        int x=fromX,z=fromZ,slabCells=0;
        for(char step:route.toCharArray()){
            int[] ahead=next(x,z,step);x=ahead[0];z=ahead[1];
            if(MAZE[z].charAt(x)=='^')slabCells++;
            if(MAZE[z].charAt(x)=='D')return slabCells>=2;
        }
        return false;
    }

    /**
     * Genetic alternative-route sampling through DISCOVERED open doors.
     * A* normally returns only one cheapest plan, starving evolution
     * of data about different corridors. Genomes with an inherited,
     * mutable positive doorBias (>0.075) sample an optional legal
     * opening. They still walk every block using genuine Minecraft
     * collisions and compete by REAL observed 224/260 tick fitness.
     * Other genomes remain unmodified controls. No authored route,
     * old trace, teleport or forced arrival earns a result.
     */
    private static String exploratoryRoute(int startX,int startZ,
            Genome dna,Map<Edge,Integer> memory,GameTestHelper helper,
            ServerLevel world,BuilderGolem mob,
            Map<Integer,Double> crewCongestion,boolean probeDoor){
        String direct=planRouteTo(startX,startZ,dna,memory,helper,world,mob,
                -1,-1,crewCongestion);
        // Alternative exploration is inherited with the genome. A rotating
        // slot/time flag made the SAME genes choose different policies on
        // successive runs, so fitness could not identify a useful door trait.
        if(!probeDoor)return direct;
        for(int z=1;z<MAZE.length-1;z++)
            for(int x=1;x<MAZE[z].length()-1;x++){
                if(MAZE[z].charAt(x)!='D'
                        ||!physicalCellOpen(helper,world,mob,x,z))
                    continue;
                try{
                    String toOpening=planRouteTo(startX,startZ,dna,
                            memory,helper,world,mob,x,z);
                    String fromOpening=planRoute(x,z,dna,memory,
                            helper,world,mob);
                    String option=toOpening+fromOpening;
                    if(!routeEntersDoor(startX,startZ,option))
                        continue;
                    System.out.println("[ALT GENETIC OPENING EXPLORE] "
                        +"doorBias="+dna.doorBias()
                        +" alternateLen="+option.length()
                        +" baselineLen="+direct.length()
                        +" discoveredOpening="+x+","+z);
                    return option;
                }catch(IllegalStateException inaccessible){
                    // A closed, obstructed or inaccessible door
                    // cannot be selected merely due to a gene.
                }
            }
        return direct;
    }

    private record RuleRound(boolean challenger,NavigationRuleMemory.Rule variant,
                             Map<String,NavigationRuleMemory.Binding> bindings){
        NavigationRuleMemory.Rule choose(String context){
            if(challenger)return variant;
            NavigationRuleMemory.Binding rule=bindings.get(context);
            return rule==null?NavigationRuleMemory.Rule.BASELINE:rule.action();
        }
    }
    private static final class LocalEpisode {
        String context,id;NavigationRuleMemory.Rule action;
        int start,goalX,goalZ,startCollisions,startStationary;
        double goalY;
        boolean executed;
    }
    private record StrategyPlan(String route,String context,
                                NavigationStrategyMemory.Decision decision){}
    private static double routeMeters(String route){
        double result=0;
        for(char step:route.toCharArray())
            result+="7931".indexOf(step)>=0?Math.sqrt(2):1;
        return result;
    }
    private static StrategyPlan chooseStrategy(int x,int z,Genome dna,
            Map<Edge,Integer> blockMemory,GameTestHelper helper,
            ServerLevel world,BuilderGolem mob,Map<Integer,Double> congestion,
            NavigationStrategyMemory memory,boolean validation,
            NavigationStrategyMemory.Strategy forced){
        Map<NavigationStrategyMemory.Strategy,String> options=new LinkedHashMap<>();
        String direct=planRouteTo(x,z,dna,blockMemory,helper,world,mob,
                -1,-1,congestion);
        options.put(NavigationStrategyMemory.Strategy.DIRECT,direct);
        Genome smooth=new Genome(dna.drive(),dna.align(),dna.tolerance(),
                dna.jumpLead(),dna.jumpPower(),dna.jumpCooldown(),
                dna.stuckTimeout(),dna.backoff(),dna.slabAvoid(),
                Math.max(1.2,dna.turnBias()),0,dna.doorBias());
        String smoothRoute=planRouteTo(x,z,smooth,blockMemory,helper,world,mob,
                -1,-1,congestion);
        if(!options.containsValue(smoothRoute))
            options.put(NavigationStrategyMemory.Strategy.SMOOTH,smoothRoute);
        String doorRoute=exploratoryRoute(x,z,dna,blockMemory,helper,world,mob,
                congestion,true);
        boolean door=routeEntersDoor(x,z,doorRoute);
        if(door&&!options.containsValue(doorRoute)){
            options.put(NavigationStrategyMemory.Strategy.DOOR,doorRoute);
            System.out.println("[ALT SLAB DOOR CANDIDATE] traversesSlabsThenDoor="
                    +routeCrossesSlabsThenDoor(x,z,doorRoute)
                    +" doorRouteMeters="+routeMeters(doorRoute)
                    +" directMeters="+routeMeters(direct));
        }
        // Abstract geometry/traffic only: no starting slot, coordinates,
        // route string, course ID or historical successful route in the key.
        int slabs=0,turns=0,px=x,pz=z;char last='?';
        for(char step:direct.toCharArray()){
            int[] at=next(px,pz,step);px=at[0];pz=at[1];
            if(MAZE[pz].charAt(px)=='^')slabs++;
            if(last!='?'&&last!=step)turns++;
            last=step;
        }
        double ratio=routeMeters(doorRoute)/Math.max(1,routeMeters(direct));
        String context="door="+(!door?"absent":ratio<=1.1?"near":"detour")
                +";slabs="+(slabs>direct.length()/5?"many":"few")
                +";turns="+(turns>direct.length()/3?"many":"few")
                +";traffic="+(congestion.isEmpty()?"low":"reserved");
        NavigationStrategyMemory.Decision choice=forced==null
                ?memory.choose(context,new ArrayList<>(options.keySet()),validation)
                :new NavigationStrategyMemory.Decision(
                    options.containsKey(forced)?forced:NavigationStrategyMemory.Strategy.DIRECT,
                    validation?"frozen-comparison":"inherited-family-route",
                    memory.summary(context,forced).trials());
        System.out.println("[ALT STRATEGY CHOICE] context="+context
                +" strategy="+choice.strategy()+" reason="+choice.reason()
                +" priorPhysicalTrials="+choice.evidenceTrials());
        return new StrategyPlan(options.get(choice.strategy()),context,choice);
    }

    /** Validate all inherited waypoints against current real collision
     * geometry. A learned route is a proposal, never noclip or telemetry
     * playback. Static doors are explicit legal action cells.
     */
    private static boolean inheritedRouteLegal(int startX,int startZ,
            String route,GameTestHelper helper,ServerLevel world,
            BuilderGolem mob){
        if(route==null||route.isBlank()||route.length()>200)return false;
        int x=startX,z=startZ;
        for(char step:route.toCharArray()){
            int[] ahead;
            try{ahead=next(x,z,step);}
            catch(IllegalArgumentException invalid){return false;}
            if(!physicalCellOpen(helper,world,mob,ahead[0],ahead[1])
                    ||!bodySweepClear(helper,world,mob,
                            x,z,ahead[0],ahead[1]))
                return false;
            if(ahead[0]!=x&&ahead[1]!=z
                    &&!diagonalBodyClear(helper,world,mob,
                            x,z,ahead[0],ahead[1]))return false;
            x=ahead[0];z=ahead[1];
        }
        return MAZE[z].charAt(x)=='G';
    }

    /** A clone approaches a real terrain feature along its own A*
     * prefix, then inherits its successful parent's validated remaining
     * corridor. Mutation can improve the prefix (or switch policy).
     * This is a geometry-checked ROUTE, not an entity-position replay.
     */
    private static String inheritedCorridorVariant(int fromX,int fromZ,
            int parentSlot,String parentRoute,Genome dna,
            Map<Edge,Integer> memory,GameTestHelper helper,
            ServerLevel world,BuilderGolem mob,String baseline){
        if(parentRoute==null||parentRoute.isBlank()
                ||parentSlot<0||parentSlot>=SELECTED_BOTS.length)
            return baseline;
        int[] origin=STARTS[SELECTED_BOTS[parentSlot]];
        int x=origin[0],z=origin[1];
        for(int i=0;i<parentRoute.length();i++){
            int[] step;
            try{step=next(x,z,parentRoute.charAt(i));}
            catch(IllegalArgumentException invalid){return baseline;}
            x=step[0];z=step[1];
            if(!walkableCell(x,z))return baseline;
            // Discover shared terrain by BLOCK TYPE, not a recorded
            // coordinate. The slab plateau is the reusable corridor.
            if(MAZE[z].charAt(x)!='^')continue;
            try{
                String approach=planRouteTo(fromX,fromZ,dna,memory,
                        helper,world,mob,x,z);
                String result=approach+parentRoute.substring(i+1);
                if(routeMeters(result)<=routeMeters(baseline)+12.0
                        &&inheritedRouteLegal(fromX,fromZ,result,
                                helper,world,mob))
                    return result;
            }catch(IllegalStateException inaccessible){
                // Alternative parent corridor is not physically legal
                // from this new start; use independently planned A*.
            }
            return baseline; // First terrain feature is the join point.
        }
        return baseline;
    }

    private static List<Candidate> spawnGeneration(GameTestHelper helper,
            List<Genome> genomes,Map<Edge,Integer> blockMemory,
            NavigationStrategyMemory strategyMemory,boolean validation,
            List<NavigationStrategyMemory.Strategy> frozenStrategies,
            List<NavigationStrategyMemory.Strategy> inheritedStrategies,
            List<String> inheritedRoutes,List<Integer> familyOrigins){
        ServerLevel world=helper.getLevel();
        List<Candidate> bots=new ArrayList<>();
        Map<Integer,Double> slabEntryReservations=new HashMap<>();
        for(int slot=0;slot<SELECTED_BOTS.length;slot++){
            int index=SELECTED_BOTS[slot];
            int[] start=STARTS[index];
            BlockPos at=pos(helper,start[0],2,start[1]);
            if(world.getBlockState(at.below()).getBlock()!=GREEN)
                throw new IllegalStateException(
                        "Trial must spawn only above green wool");
            BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
            mob.setPos(at.getX()+.5,at.getY(),at.getZ()+.5);
            mob.setPersistenceRequired();
            mob.setNoGravity(false);
            world.addFreshEntity(mob);
            if(Math.abs(mob.getBbWidth()-.65)>.01
                    ||Math.abs(mob.getBbHeight()-1.60)>.01)
                throw new IllegalStateException(
                        "Wrong real BuilderGolem body: "+mob.getBbWidth()
                        +" x "+mob.getBbHeight());
            Genome genome=genomes.get(slot);
            StrategyPlan plan=chooseStrategy(start[0],start[1],genome,
                    blockMemory,helper,world,mob,slabEntryReservations,
                    strategyMemory,validation,
                    !frozenStrategies.isEmpty()?frozenStrategies.get(slot):
                    inheritedStrategies.size()==SELECTED_BOTS.length
                            ?inheritedStrategies.get(slot):null);
            String route=plan.route();
            // The exact three parents retain their own proven physical
            // route whenever the current world's collision model permits.
            // The other six carry parent route INTENT and independently
            // replan legal entrances from different spawn positions.
            if(familyOrigins.size()==SELECTED_BOTS.length
                    &&inheritedRoutes.size()==SELECTED_BOTS.length){
                int parentSlot=familyOrigins.get(slot);
                String parentRoute=inheritedRoutes.get(slot);
                if(parentSlot==slot
                        &&inheritedRouteLegal(start[0],start[1],
                                parentRoute,helper,world,mob)){
                    route=parentRoute;
                    System.out.println("[ALT EXACT PARENT ROUTE] slot="+slot
                            +" preserved="+route);
                }else if(parentSlot!=slot
                        &&inheritedStrategies.size()==SELECTED_BOTS.length
                        &&inheritedStrategies.get(slot)
                                ==inheritedStrategies.get(parentSlot)){
                    String inherited=inheritedCorridorVariant(start[0],start[1],
                            parentSlot,parentRoute,genome,blockMemory,
                            helper,world,mob,route);
                    if(!inherited.equals(route)){
                        route=inherited;
                        System.out.println("[ALT CLONE CORRIDOR] slot="+slot
                                +" parent="+parentSlot
                                +" geometryChecked=true"
                                +" routeLength="+routeMeters(route));
                    }
                }
            }
            Candidate b=new Candidate(slot,mob,route,
                    start[0],start[1],genome,blockMemory);
            if(familyOrigins.size()==SELECTED_BOTS.length){
                b.familyParentSlot=familyOrigins.get(slot);
                b.familyRole=b.familyParentSlot==slot?"parent":"mutant";
            }
            b.strategyContext=plan.context();
            b.strategyDecision=plan.decision();
            // All entries are discovered by A* from actual geometry.
            // Spread simultaneous workers among several accessible slab
            // approaches rather than sending them to one doorway-sized
            // tile. These costs are rebuilt every generation, NOT learned
            // blocked-edge penalties or fixed routes.
            int reservedX=start[0],reservedZ=start[1];
            for(int i=0;i<b.route.length();i++){
                int[] step=next(reservedX,reservedZ,b.route.charAt(i));
                if(reservedX==9&&step[0]==8&&step[1]>=2
                        &&step[1]<=7){
                    int entrance=step[1]*MAZE[0].length()+step[0];
                    slabEntryReservations.merge(entrance,1.3,Double::sum);
                    // Also discourage everyone from clustering on two
                    // adjacent entries. Both remain traversable.
                    if(step[1]>2)
                        slabEntryReservations.merge(entrance-MAZE[0].length(),
                                .25,Double::sum);
                    if(step[1]<7)
                        slabEntryReservations.merge(entrance+MAZE[0].length(),
                                .25,Double::sum);
                    break;
                }
                reservedX=step[0];reservedZ=step[1];
            }
            BlockPos origin=pos(helper,0,2,0);
            b.originX=origin.getX();
            b.originZ=origin.getZ();
            b.birthTick=helper.getTick();
            b.initialRouteUsesDoor=routeEntersDoor(
                    start[0],start[1],b.route);
            if(b.initialRouteUsesDoor)
                System.out.println("[ALT OPEN DOOR OPTION] slot="+slot
                        +" planner discovered a usable wooden door route"
                        +" slabsBeforeDoor="+routeCrossesSlabsThenDoor(
                                start[0],start[1],b.route));
            b.captureMovement(0);
            bots.add(b);
        }
        return bots;
    }

    /** Choose a clear corridor through REAL open-door collision shapes.
     * A 0.65-wide mob only has 0.175 blocks of side clearance in a
     * one-block doorway, so the center of the vanilla door block is not
     * always its safe centerline.
     */
    private static double safeDoorLane(ServerLevel world,BuilderGolem mob,
                                       BlockPos door,Genome dna,
                                       boolean offsetX){
        return safeDoorLane(world,mob,door,dna.doorBias(),offsetX);
    }

    private static double safeDoorLane(ServerLevel world,BuilderGolem mob,
                                       BlockPos door,double preferred,
                                       boolean offsetX){
        double[] candidates={0,-.08,.08,-.12,.12,-.16,.16};
        double best=Double.POSITIVE_INFINITY;
        double chosen=0;
        for(double offset:candidates){
            double goalX=door.getX()+.5+(offsetX?offset:0);
            double goalZ=door.getZ()+.5+(offsetX?0:offset);
            boolean clear=true;
            // Probe an ACTUAL 1.6-tall body along both faces of the
            // open, single-wide vanilla door. Use the perpendicular
            // axis to movement, never a fixed world Z offset.
            for(double t:new double[]{.55,.75,.95,1,1.13}){
                double x=mob.getX()+(goalX-mob.getX())*t;
                double z=mob.getZ()+(goalZ-mob.getZ())*t;
                if(!clearBodyAt(world,mob,x,mob.getY(),z)){
                    clear=false;break;
                }
            }
            if(!clear)continue;
            double penalty=Math.abs(offset-preferred)
                    +Math.abs(offset)*.04;
            if(penalty<best){best=penalty;chosen=offset;}
        }
        return chosen;
    }

    /**
     * Replan from the entity's ACTUAL floor cell after confirmed,
     * persistent static obstruction. The same saved 12-gene logic,
     * live collision shapes, and observed block edges choose the new
     * route; no previous route or movement trace is replayed.
     */
    private static boolean recoverBlockedRoute(GameTestHelper helper,
                                                Candidate b){
        if(b.reroutes>=2||b.cursor>=b.route.length())return false;
        int tick=(int)(helper.getTick()-b.birthTick);
        if(b.staticStalls<Math.max(5,b.dna.stuckTimeout()/2)
                ||tick-b.lastRerouteTick<20)return false;
        b.lastRerouteTick=tick;
        ServerLevel world=helper.getLevel();
        int fromX=(int)Math.floor(b.mob.getX()-b.originX);
        int fromZ=(int)Math.floor(b.mob.getZ()-b.originZ);
        if(!physicalCellOpen(helper,world,b.mob,fromX,fromZ))
            return false;
        int[] ahead=next(b.x,b.z,b.route.charAt(b.cursor));
        // A doorway is a reversible interaction, not a permanently
        // obstructed stone edge. Never contaminate learned terrain costs.
        if(MAZE[ahead[1]].charAt(ahead[0])=='D')return false;
        Edge blocked=new Edge(fromX,fromZ,ahead[0],ahead[1]);
        b.realBlockFailures.merge(blocked,15,(a,c)->Math.min(100,a+c));
        Map<Edge,Integer> evidence=new HashMap<>(b.inheritedBlockMemory);
        for(var edge:b.realBlockFailures.entrySet())
            evidence.merge(edge.getKey(),edge.getValue(),Math::max);
        try{
            String alternative=planRoute(fromX,fromZ,b.dna,evidence,
                    helper,world,b.mob);
            if(alternative.equals(b.route.substring(b.cursor))
                    &&fromX==b.x&&fromZ==b.z)return false;
            b.route=alternative;
            b.cursor=0;
            b.x=fromX;
            b.z=fromZ;
            b.reroutes++;
            b.staticStalls=0;
            b.backoffTicks=0;
            b.immobile=0;
            b.lastDistance=Double.POSITIVE_INFINITY;
            b.doorTargetX=Integer.MIN_VALUE;
            b.exitTargetX=Integer.MIN_VALUE;
            b.movementMode="r";
            System.out.println("[ALT PHYSICAL REROUTE] slot="+b.index
                +" tick="+tick+" reroute="+b.reroutes
                +" from="+fromX+","+fromZ+" nextRoute="+alternative);
            return true;
        }catch(IllegalStateException impossible){
            // Real physics recovery remains available without an alternate.
            return false;
        }
    }

    /**
     * Estimated distance ALONG each independently chosen route, used
     * only to establish traffic right-of-way. Not an arrival score,
     * not a saved path and not an evolution shortcut. Closest to the
     * end moves first so followers can take vacated physical space.
     */
    private static double remainingPathMeters(Candidate bot){
        double distance=0;
        int x=bot.x,z=bot.z;
        for(int n=bot.cursor;n<bot.route.length();n++){
            char step=bot.route.charAt(n);
            int[] next=next(x,z,step);
            distance+=Math.hypot(next[0]-x,next[1]-z);
            x=next[0];z=next[1];
        }
        if(bot.cursor<bot.route.length()){
            int[] next=next(bot.x,bot.z,
                    bot.route.charAt(bot.cursor));
            double fromCenter=Math.hypot(next[0]-bot.x,next[1]-bot.z);
            double toWaypoint=Math.hypot(next[0]+.5
                    -(bot.mob.getX()-bot.originX),next[1]+.5
                    -(bot.mob.getZ()-bot.originZ));
            distance+=toWaypoint-fromCenter;
        }
        return Math.max(0,distance);
    }

    private record TrafficMotion(double vx,double vz,
                                 boolean yielding,boolean queue,
                                 int leaderSlot){}

    private static NavigationRuleMemory.Rule localRule(Candidate b){
        return b.localEpisode==null?NavigationRuleMemory.Rule.BASELINE:b.localEpisode.action;
    }
    private static void beginLocalEpisode(GameTestHelper helper,Candidate b,List<Candidate> crew){
        if(b.ruleRound==null||b.localEpisode!=null||b.holdingStage>0
                ||b.controlTick<b.episodeCooldown||b.cursor>=b.route.length())return;
        int[] target=next(b.x,b.z,b.route.charAt(b.cursor));
        boolean door=MAZE[target[1]].charAt(target[0])=='D';
        boolean step=standingY(helper,helper.getLevel(),target[0],target[1])
                -standingY(helper,helper.getLevel(),b.x,b.z)>.1;
        boolean turn=b.cursor+1<b.route.length()
                &&b.route.charAt(b.cursor)!=b.route.charAt(b.cursor+1);
        String traffic="clear";
        int[] heading=next(0,0,b.route.charAt(b.cursor));
        for(Candidate peer:crew){
            if(peer==b||peer.arrivedTick>=0||!peer.mob.isAlive()
                    ||Math.abs(peer.mob.getY()-b.mob.getY())>.7
                    ||Math.hypot(peer.mob.getX()-b.mob.getX(),peer.mob.getZ()-b.mob.getZ())>1.2)
                continue;
            traffic="crossing";
            if(peer.cursor<peer.route.length()){
                int[] ph=next(0,0,peer.route.charAt(peer.cursor));
                if(heading[0]==ph[0]&&heading[1]==ph[1])traffic="following";
            }
            break;
        }
        if(!door&&!step&&!turn&&traffic.equals("clear"))return;
        LocalEpisode e=new LocalEpisode();
        e.context="obstacle="+(door?"door":step?"step":turn?"turn":"lane")+";traffic="+traffic;
        e.id="slot"+b.index+":episode"+(b.episodeSerial++);
        e.action=b.ruleRound.choose(e.context);e.executed=e.action==NavigationRuleMemory.Rule.BASELINE;
        e.start=b.controlTick;e.startCollisions=b.collisions;e.startStationary=b.stationaryTicks;
        e.goalX=b.x;e.goalZ=b.z;
        // Credit clearing two original route edges, not just leaving a queue
        // or reaching a temporary holding spot. World coordinates are not saved.
        for(int i=b.cursor;i<Math.min(b.route.length(),b.cursor+2);i++){
            int[] at=next(e.goalX,e.goalZ,b.route.charAt(i));e.goalX=at[0];e.goalZ=at[1];
        }
        e.goalY=standingY(helper,helper.getLevel(),e.goalX,e.goalZ);
        b.localEpisode=e;
    }
    private static void finishLocalEpisode(Candidate b,boolean endOfTrial){
        LocalEpisode e=b.localEpisode;if(e==null)return;
        int elapsed=b.controlTick-e.start;
        boolean cleared=b.arrivedTick>=0||Math.hypot(
                b.mob.getX()-b.originX-e.goalX-.5,b.mob.getZ()-b.originZ-e.goalZ-.5)<.4
                &&Math.abs(b.mob.getY()-e.goalY)<.25;
        if(elapsed<1||!cleared&&!endOfTrial&&elapsed<60)return;
        b.localEpisodes.add(new NavigationRuleMemory.Episode(e.id,b.index,e.context,
                e.executed?e.action:NavigationRuleMemory.Rule.BASELINE,cleared,
                Math.min(60,elapsed),Math.min(Math.min(60,elapsed),
                    Math.max(0,b.stationaryTicks-e.startStationary)),
                Math.max(0,b.collisions-e.startCollisions)));
        b.localEpisode=null;b.episodeCooldown=b.controlTick+4;
    }
    private static boolean tryHoldingRoute(GameTestHelper helper,Candidate b,List<Candidate> crew){
        if(b.holdingStage>0||b.holdingAttempts>=2||b.trafficWaitTicks<8
                ||localRule(b)!=NavigationRuleMemory.Rule.YIELD_ASIDE)return false;
        b.holdingAttempts++;
        ServerLevel world=helper.getLevel();
        int x=(int)Math.floor(b.mob.getX()-b.originX),z=(int)Math.floor(b.mob.getZ()-b.originZ);
        if(!physicalCellOpen(helper,world,b.mob,x,z))return false;
        String best=null;double bestCost=Double.POSITIVE_INFINITY;
        for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++){
            int tx=x+dx,tz=z+dz;
            if(dx==0&&dz==0||Math.hypot(dx,dz)>1.5||!walkableCell(tx,tz)
                    ||MAZE[tz].charAt(tx)=='G'||!physicalCellOpen(helper,world,b.mob,tx,tz))continue;
            try{
                String path=planRouteTo(x,z,b.dna,b.inheritedBlockMemory,helper,world,b.mob,tx,tz);
                if(path.isEmpty()||path.length()>3)continue;
                boolean clear=true;int px=x,pz=z;
                for(char direction:path.toCharArray()){
                    int sx=px,sz=pz;
                    int[] at=next(px,pz,direction);px=at[0];pz=at[1];
                    for(Candidate peer:crew){
                        if(peer==b||peer.arrivedTick>=0||!peer.mob.isAlive())continue;
                        if(Math.hypot(px+.5-(peer.mob.getX()-peer.originX),
                                pz+.5-(peer.mob.getZ()-peer.originZ))<.85)clear=false;
                        for(double fraction:new double[]{.25,.5,.75})
                            if(Math.hypot(sx+.5+(px-sx)*fraction-(peer.mob.getX()-peer.originX),
                                    sz+.5+(pz-sz)*fraction-(peer.mob.getZ()-peer.originZ))<.75)clear=false;
                        int qx=peer.x,qz=peer.z;
                        for(int i=peer.cursor;i<Math.min(peer.route.length(),peer.cursor+2);i++){
                            int[] q=next(qx,qz,peer.route.charAt(i));qx=q[0];qz=q[1];
                            if(Math.hypot(px-qx,pz-qz)<.85)clear=false;
                        }
                    }
                }
                double cost=routeMeters(path);
                if(clear&&cost<bestCost){best=path;bestCost=cost;}
            }catch(IllegalStateException noHoldingRoute){/* no physically reachable holding space */}
        }
        if(best==null)return false;
        b.localEpisode.executed=true;b.route=best;b.x=x;b.z=z;b.cursor=0;
        b.holdingStage=1;b.trafficWaitTicks=0;b.immobile=0;b.backoffTicks=0;
        b.lastDistance=Double.POSITIVE_INFINITY;
        return true;
    }
    private static boolean resumeAfterHolding(GameTestHelper helper,Candidate b,List<Candidate> crew){
        if(b.holdingStage!=2)return false;
        if(b.controlTick-b.holdingArrivalTick<12&&teammateWithin(b,crew,1.35)){
            b.movementMode="q";return true;
        }
        int x=(int)Math.floor(b.mob.getX()-b.originX),z=(int)Math.floor(b.mob.getZ()-b.originZ);
        try{
            b.route=planRoute(x,z,b.dna,b.inheritedBlockMemory,helper,helper.getLevel(),b.mob);
            b.x=x;b.z=z;b.cursor=0;b.holdingStage=0;b.trafficWaitTicks=0;
            b.immobile=0;b.lastDistance=Double.POSITIVE_INFINITY;
        }catch(IllegalStateException blockedResume){b.movementMode="q";}
        return true;
    }

    /**
     * Physical corridor manners: no pushing through the 0.65-wide
     * golem ahead. The nearer-to-finish traveler owns the right of
     * way. A follower makes room in a single-file queue without
     * backtracking or marking the FLOOR as an impassable block.
     * We inspect only ACTUAL living entities; no imaginary blocks
     * or scripted waypoint route is introduced.
     *
     * Movement calls are ordered front-to-back each tick. This
     * lets a leader vacate a place before a queued golem advances.
     */
    private static TrafficMotion coordinateTraffic(Candidate self,
            List<Candidate> group,double vx,double vz){
        double speed=Math.hypot(vx,vz);
        // Do not exhaust a lifetime courtesy allowance after four ticks.
        // Priority must be recomputed from moving LIVE entities on every
        // tick; otherwise a ten-golem platoon pushes into the slab lip.
        // Prolonged waits cause a bounded traffic-only replan below.
        if(speed<.00001||group.size()<2)
            return new TrafficMotion(vx,vz,false,false,-1);
        double dirX=vx/speed,dirZ=vz/speed;
        double allowance=speed;
        boolean yielding=false,queue=false;
        int yieldingFor=-1;
        double ownPriority=remainingPathMeters(self);
        for(Candidate peer:group){
            if(peer==self||peer.arrivedTick>=0||!peer.mob.isAlive()
                    ||Math.abs(peer.mob.getY()-self.mob.getY())>.65)
                continue;
            double rx=peer.mob.getX()-self.mob.getX();
            double rz=peer.mob.getZ()-self.mob.getZ();
            double gap=Math.hypot(rx,rz);
            if(gap>1.35)continue;
            double ahead=rx*dirX+rz*dirZ;
            double lateral=Math.abs(rx*dirZ-rz*dirX);
            if(lateral>=.68)continue;
            // Other golems are NOT a line of traffic just because
            // they are nearby. At the GREEN starting area the lanes
            // cross and sometimes face OPPOSITE directions. The old
            // broad 'ahead' test froze eight of ten real golems.
            // First inspect the peer's own CURRENT route heading.
            if(peer.cursor>=peer.route.length())continue;
            int[] peerTarget=next(peer.x,peer.z,
                    peer.route.charAt(peer.cursor));
            double peerDx=peerTarget[0]+.5
                    -(peer.mob.getX()-peer.originX);
            double peerDz=peerTarget[1]+.5
                    -(peer.mob.getZ()-peer.originZ);
            double peerLength=Math.hypot(peerDx,peerDz);
            if(peerLength<.00001)continue;
            double headingAgreement=(peerDx*dirX+peerDz*dirZ)
                    /peerLength;
            // Queue only in the SAME lane, moving toward the same
            // destination. Side-by-side, head-on and crossing golems
            // must NOT wait for one another until their real body
            // envelopes genuinely overlap.
            // Restrict single-file queuing to a true shared STRAIGHT
            // corridor, not mixed-angle turns or green spawn lanes.
            // Both participants must be committed to the same next
            // three steps. The native movement solver still owns any
            // other real entity contact.
            int selfStraight=0,peerStraight=0;
            if(self.cursor<self.route.length()){
                char ownDirection=self.route.charAt(self.cursor);
                for(int i=self.cursor;i<self.route.length()
                        &&self.route.charAt(i)==ownDirection;i++)
                    selfStraight++;
            }
            if(peer.cursor<peer.route.length()){
                char theirDirection=peer.route.charAt(peer.cursor);
                for(int i=peer.cursor;i<peer.route.length()
                        &&peer.route.charAt(i)==theirDirection;i++)
                    peerStraight++;
            }
            if(headingAgreement>.92&&lateral<.28
                    &&selfStraight>=3&&peerStraight>=3
                    &&ahead>.03&&ahead<.95){
                double legal=Math.max(0,ahead-.70);
                if(legal<allowance){
                    allowance=legal;
                    yielding=true;
                    queue=true;
                    yieldingFor=peer.index;
                    if(boundedTrafficRule(self))self.localEpisode.executed=true;
                }
            }else if(gap<1.02&&ahead>-.18&&lateral<.64){
                // Crossing/merging at slab entries must resolve BEFORE
                // two 0.65-wide hitboxes overlap. Give the nearer-to-
                // finish traveler priority, with slot as a stable tie
                // breaker. The lower-priority traveler eases off while
                // the other moves, avoiding mirrored stop/start dances.
                double priority=remainingPathMeters(peer);
                boolean peerOwns=priority<ownPriority-.01
                        ||(Math.abs(priority-ownPriority)<.01
                            &&peer.index<self.index);
                if(boundedTrafficRule(self)){
                    peerOwns=self.rightOfWay.owner(self.index,ownPriority,
                            peer.index,priority,self.controlTick)==peer.index;
                    self.localEpisode.executed=true;
                }
                if(peerOwns){
                    allowance=Math.min(allowance,Math.max(0,gap-.72));
                    yielding=true;
                    yieldingFor=peer.index;
                }
            }
        }
        if(!yielding)return new TrafficMotion(vx,vz,false,false,-1);
        double ratio=Math.min(1,Math.max(0,allowance/speed));
        return new TrafficMotion(vx*ratio,vz*ratio,
                true,queue,yieldingFor);
    }

    /**
     * Distinguish traffic from static obstacles using REAL live
     * teammate positions, never a cached map of guessed walls.
     * It is normal for several 0.65-wide golems to approach the
     * same turning point; their mutual push must not trigger
     * backing into each other or permanent blocked-edge mutations.
     */
    private static boolean teammateWithin(Candidate self,
            List<Candidate> crew,double radius){
        for(Candidate other:crew){
            if(other==self||!other.mob.isAlive()
                    ||other.arrivedTick>=0
                    ||Math.abs(other.mob.getY()-self.mob.getY())>.75)
                continue;
            if(Math.hypot(other.mob.getX()-self.mob.getX(),
                    other.mob.getZ()-self.mob.getZ())<radius)
                return true;
        }
        return false;
    }
    private static boolean boundedTrafficRule(Candidate b){
        return b.rightOfWay!=null&&b.localEpisode!=null
                &&(b.localEpisode.action==NavigationRuleMemory.Rule.STABLE_MERGE
                    ||b.localEpisode.action==NavigationRuleMemory.Rule.YIELD_ASIDE);
    }

    /**
     * Dynamic teammate obstruction is NOT a failure of Minecraft floor
     * geometry. Re-plan from the golem's actual standing cell with a
     * short-lived cost around LIVE teammates and their next waypoints.
     * Never retain these costs in blockedEdges, never noclip/jump or
     * force a fixed slab lane. Real AABB and grounded step checks still
     * validate every edge of the new route.
     */
    private static boolean recoverCongestedRoute(GameTestHelper helper,
            Candidate self,List<Candidate> crew){
        if(self.localEpisode!=null||self.holdingStage>0)return false;
        if(self.trafficWaitTicks<9||self.trafficReroutes>=2
                ||self.cursor>=self.route.length())return false;
        int tick=(int)(helper.getTick()-self.birthTick);
        if(tick-self.lastTrafficRerouteTick<18)return false;
        self.lastTrafficRerouteTick=tick;
        ServerLevel world=helper.getLevel();
        int fromX=(int)Math.floor(self.mob.getX()-self.originX);
        int fromZ=(int)Math.floor(self.mob.getZ()-self.originZ);
        if(!physicalCellOpen(helper,world,self.mob,fromX,fromZ))
            return false;
        Map<Integer,Double> crowd=new HashMap<>();
        for(Candidate peer:crew){
            if(peer==self||peer.arrivedTick>=0||!peer.mob.isAlive()
                    ||Math.abs(peer.mob.getY()-self.mob.getY())>.7
                    ||Math.hypot(peer.mob.getX()-self.mob.getX(),
                            peer.mob.getZ()-self.mob.getZ())>4.0)
                continue;
            int px=(int)Math.floor(peer.mob.getX()-self.originX);
            int pz=(int)Math.floor(peer.mob.getZ()-self.originZ);
            if(walkableCell(px,pz))
                crowd.merge(pz*MAZE[0].length()+px,4.0,Double::sum);
            if(peer.cursor<peer.route.length()){
                int[] nextCell=next(peer.x,peer.z,
                        peer.route.charAt(peer.cursor));
                if(walkableCell(nextCell[0],nextCell[1]))
                    crowd.merge(nextCell[1]*MAZE[0].length()+nextCell[0],
                            2.0,Double::sum);
            }
        }
        if(crowd.isEmpty())return false;
        try{
            String detour=planRouteTo(fromX,fromZ,self.dna,
                    self.inheritedBlockMemory,helper,world,self.mob,
                    -1,-1,crowd);
            if(detour.equals(self.route.substring(self.cursor))
                    &&fromX==self.x&&fromZ==self.z)return false;
            self.route=detour;
            self.cursor=0;
            self.x=fromX;self.z=fromZ;
            self.trafficReroutes++;
            self.trafficWaitTicks=0;
            self.immobile=0;
            self.backoffTicks=0;
            self.staticStalls=0;
            self.lastDistance=Double.POSITIVE_INFINITY;
            self.movementMode="r";
            System.out.println("[ALT TRAFFIC REPLAN] slot="+self.index
                    +" tick="+tick+" detour="+detour
                    +" trafficReroutes="+self.trafficReroutes);
            return true;
        }catch(IllegalStateException noAlternative){
            return false;
        }
    }

    /** Controller uses real Minecraft entity move + collisions; only
     * genes controlling WALK / JUMP / alignment can be selected.
     */
    private static void walkOneTick(GameTestHelper helper,Candidate b,
                                     List<Candidate> crew){
        BuilderGolem mob=b.mob;
        b.movementMode="w";
        if(!mob.isAlive()||b.arrivedTick>=0)return;
        ServerLevel world=helper.getLevel();
        if(world.getBlockState(mob.blockPosition().below()).is(RED)){
            b.arrivedTick=(int)(helper.getTick()-b.birthTick);
            // A winner leaves the track; otherwise it obstructs the door
            // or blocks remaining racers at the finish line.
            mob.discard();
            return;
        }
        // Crew congestion uses live occupancy, never inherited block
        // failures. Try a new valid corridor after sustained waiting.
        if(resumeAfterHolding(helper,b,crew))return;
        beginLocalEpisode(helper,b,crew);
        if(tryHoldingRoute(helper,b,crew))return;
        if(recoverCongestedRoute(helper,b,crew))return;
        if(recoverBlockedRoute(helper,b))return;
        int[] ahead=null;
        BlockPos target=null;
        double dx=0,dz=0,distance=0;
        // DO NOT spend a full Minecraft tick at every waypoint just to
        // increment a route cursor. On a 50-60 block route that artificial
        // pause wastes up to three seconds and makes an 13s run impossible.
        while(b.cursor<b.route.length()){
            ahead=next(b.x,b.z,b.route.charAt(b.cursor));
            target=pos(helper,ahead[0],2,ahead[1]);
            double centerShift=0;
            boolean enteringDoor=MAZE[ahead[1]].charAt(ahead[0])=='D';
            boolean leavingDoor=MAZE[b.z].charAt(b.x)=='D';
            if(enteringDoor||leavingDoor)b.movementMode="d";
            if(enteringDoor){
                // Follow a single stable planned path, as villagers do.
                // Opening the nearby door is an action on that path,
                // not a reason to throw it away or stop for four ticks.
                boolean wasOpen=world.getBlockState(target)
                        .getValue(DoorBlock.OPEN);
                if(!wasOpen&&openDoorFromReach(world,mob,target))
                    b.doorTargetX=Integer.MIN_VALUE;
                // A door outside interaction range can still be CLOSED.
                // Do not lock in a centerline estimated against its CLOSED
                // collision shape. Once close and opened, sample the real
                // available lane exactly once and keep it through the exit.
                double doorDistance=Math.hypot(
                        mob.getX()-target.getX()-.5,
                        mob.getZ()-target.getZ()-.5);
                if(b.doorTargetX!=ahead[0]||b.doorTargetZ!=ahead[1]){
                    b.doorOffsetX=ahead[0]==b.x;
                    b.doorLaneShift=0;
                    if(doorDistance<2.7&&world.getBlockState(target)
                            .getValue(DoorBlock.OPEN)){
                        b.doorLaneShift=safeDoorLane(world,mob,target,b.dna,
                                b.doorOffsetX);
                        b.doorTargetX=ahead[0];
                        b.doorTargetZ=ahead[1];
                    }
                }
                centerShift=b.doorLaneShift;
            }else if(leavingDoor){
                // Another worker must not inadvertently close a passage
                // around a body that is currently exiting the threshold.
                openDoorFromReach(world,mob,pos(helper,b.x,2,b.z));
                // Maintain the SAME verified alignment through the EXIT
                // face. Re-solving on the far side causes side-to-side
                // oscillation in a single door.
                centerShift=b.doorLaneShift;
            }
            if(!enteringDoor&&!leavingDoor
                    &&MAZE[ahead[1]].charAt(ahead[0])=='^')
                b.movementMode="s";
            dx=target.getX()+.5+(b.doorOffsetX?centerShift:0)-mob.getX();
            dz=target.getZ()+.5+(b.doorOffsetX?0:centerShift)-mob.getZ();
            distance=Math.hypot(dx,dz);
            boolean diagonalStep=Math.abs(ahead[0]-b.x)==1
                    &&Math.abs(ahead[1]-b.z)==1;
            boolean precise=precisionTransition(helper,world,
                    b.x,b.z,ahead[0],ahead[1]);
            if(precise&&!enteringDoor&&!leavingDoor)
                b.movementMode="p";
            // On a flat, cardinal, physically clear straightaway,
            // hand off to the NEXT waypoint before decelerating at
            // this cell center. Remain precise at corners, slabs,
            // diagonals, doors and overhead headroom openings.
            boolean sameSafeStraight=false;
            if(!precise&&!diagonalStep&&b.cursor+1<b.route.length()
                    &&b.route.charAt(b.cursor)==b.route.charAt(b.cursor+1)){
                int[] after=next(ahead[0],ahead[1],
                        b.route.charAt(b.cursor+1));
                sameSafeStraight=!precisionTransition(helper,world,
                            ahead[0],ahead[1],after[0],after[1])
                        &&bodySweepClear(helper,world,mob,
                            ahead[0],ahead[1],after[0],after[1]);
            }
            double acquisition=sameSafeStraight?.205:
                    diagonalStep||precise
                        ?Math.min(b.dna.tolerance(),.075)
                        :b.dna.tolerance();
            if(distance>=acquisition)break;
            b.x=ahead[0];
            b.z=ahead[1];
            b.cursor++;
            b.bestCursor=Math.max(b.bestCursor,b.cursor);
            b.staticStalls=0;
            b.immobile=0;
            b.lastDistance=Double.POSITIVE_INFINITY;
        }
        if(b.cursor>=b.route.length()){
            if(b.holdingStage==1){b.holdingStage=2;b.holdingArrivalTick=b.controlTick;}
            return;
        }

        if(distance<b.lastDistance-.015){
            b.immobile=0;
            b.lastDistance=distance;
        }else b.immobile++;
        // Reset on progress; otherwise trigger an evolving retreat/jump
        // decision instead of driving forever into a blocking step.
        if(b.immobile>=b.dna.stuckTimeout()){
            b.immobile=0;
            b.lastDistance=Double.POSITIVE_INFINITY;
            if(teammateWithin(b,crew,1.15)){
                // Polite congestion, not a failed nav edge. NEVER
                // reverse into the very neighbor causing the pause.
                b.backoffTicks=0;
                b.movementMode="t";
                b.trafficRecoveryTicks++;
            }else{
                b.backoffTicks=3;
                b.collisions++;
                // Reprobe only on genuine obstruction recovery.
                b.doorTargetX=Integer.MIN_VALUE;
                b.exitTargetX=Integer.MIN_VALUE;
            }
        }
        b.ticksSinceJump++;
        // GameTest's explicit horizontal move(MoverType.SELF,...) can
        // reset the onGround flag. Probe the REAL Minecraft collision
        // shape immediately beneath the golem rather than trusting that
        // stale flag when deciding whether a legal ground jump can start.
        boolean grounded=mob.onGround()||world.getBlockCollisions(mob,
                mob.getBoundingBox().move(0,-.07,0))
                .iterator().hasNext();
        BlockPos nextFeet=target;
        BlockState footState=world.getBlockState(nextFeet);
        // Vanilla STEP_HEIGHT=0.6 resolves these low obstacles using
        // real ground movement. Do NOT jump for a bottom slab (0.5 high).
        // Only obstacles exceeding the automatic step height may trigger
        // the learned jump behaviour. The planner now excludes slab cells
        // with inadequate headroom, before mutations are ever scored.
        var obstacleShape=footState.getCollisionShape(world,nextFeet);
        double stepRise=obstacleShape.isEmpty()?0
                :nextFeet.getY()+obstacleShape.max(Direction.Axis.Y)
                        -mob.getY();
        boolean lowStep=stepRise>0&&stepRise<=.600001;
        double plannedRise=standingY(helper,world,ahead[0],ahead[1])
                -standingY(helper,world,b.x,b.z);
        // Every real Builder must dock squarely with a half-slab lip.
        // Previously this alignment ran ONLY in experimental CENTER_STEP
        // rounds, so ordinary evolution could approach a legal step
        // obliquely, clip its 0.65-wide hitbox on the lip, then falsely
        // learn that the whole slab shortcut was unreliable.
        // A step is a cardinal approach onto a verified <=0.6 surface;
        // never grant flight, a free Y displacement or obstacle clipping.
        boolean cardinalApproach=(ahead[0]==b.x) != (ahead[1]==b.z);
        boolean nativeSlabAscent=cardinalApproach
                &&footState.getBlock() instanceof SlabBlock
                &&footState.getValue(SlabBlock.TYPE)==SlabType.BOTTOM
                &&plannedRise>.01&&plannedRise<=.600001
                &&mob.getY()<standingY(helper,world,ahead[0],ahead[1])-.12;
        boolean experimentalCenter=localRule(b)==NavigationRuleMemory.Rule.CENTER_STEP
                &&(canOperateDoor(footState)||plannedRise>.01&&plannedRise<=.600001);
        boolean centerApproach=nativeSlabAscent||experimentalCenter;
        if(nativeSlabAscent)b.slabApproachTicks++;
        if(centerApproach){
            if(experimentalCenter&&b.localEpisode!=null)
                b.localEpisode.executed=true;
            // Align perpendicular to the low-step face BEFORE driving
            // into it. The final 0.045-block tolerance keeps the entire
            // hitbox safely inside a one-block-wide entrance.
            double lateralTolerance=nativeSlabAscent?.045:.025;
            if(ahead[0]!=b.x&&ahead[1]==b.z
                    &&Math.abs(dz)>lateralTolerance)dx=0;
            else if(ahead[1]!=b.z&&ahead[0]==b.x
                    &&Math.abs(dx)>lateralTolerance)dz=0;
            distance=Math.hypot(dx,dz);
            if(nativeSlabAscent)b.movementMode="p";
        }
        // A 0.65-wide body must be centered on the door's verified
        // collision-safe lane BEFORE driving into its leaf. The old
        // controller drove forward while drifting sideways, clipped the
        // door edge, and sometimes jumped to unstick itself. Preserve
        // Minecraft collision for all real movement: align on the
        // approach tile, then proceed through both door faces.
        boolean enteringDoorCell=MAZE[ahead[1]].charAt(ahead[0])=='D';
        boolean leavingDoorCell=MAZE[b.z].charAt(b.x)=='D';
        if(enteringDoorCell&&!leavingDoorCell&&distance<1.55){
            if(ahead[0]!=b.x&&ahead[1]==b.z&&Math.abs(dz)>.045){
                dx=0;
                b.doorEntryAlignTicks++;
            }else if(ahead[1]!=b.z&&ahead[0]==b.x&&Math.abs(dx)>.045){
                dz=0;
                b.doorEntryAlignTicks++;
            }
            distance=Math.hypot(dx,dz);
        }
        boolean tallObstacle=stepRise>.600001;
        boolean approachingRaisedCell=tallObstacle
                &&distance<=b.dna.jumpLead();
        // Jump from ground, with native gravity and vanilla collision.
        // Never add positive upward acceleration while airborne.
        // Only jump at a real half-step or a collision-confirmed
        // ground obstacle. Never waste the last meters by jumping just
        // because this Builder hit an unrelated obstacle earlier.
        double proposedX=Math.max(-SPEED_CAP,Math.min(SPEED_CAP,dx));
        double proposedZ=Math.max(-SPEED_CAP,Math.min(SPEED_CAP,dz));
        boolean solidAhead=world.getBlockCollisions(mob,
                mob.getBoundingBox().move(proposedX,0,proposedZ))
                .iterator().hasNext();
        if(!centerApproach&&!enteringDoorCell&&!leavingDoorCell
                &&(approachingRaisedCell||solidAhead&&!lowStep&&b.immobile>4)
                &&grounded&&b.ticksSinceJump>=b.dna.jumpCooldown()){
            mob.setDeltaMovement(0,b.dna.jumpPower(),0);
            b.movementMode="j";
            b.ticksSinceJump=0;
            b.jumps++;
        }
        // Exactly 4 m/s whenever unobstructed. Genes still evolve
        // routes, approach alignment and when to jump/back off.
        double step=Math.min(SPEED_CAP,distance);
        // Driving back for 3 ticks breaks a collision lock without
        // passing through solid blocks.
        if(b.backoffTicks>0){
            if(teammateWithin(b,crew,1.15)){
                // A formerly empty escape lane now contains a
                // teammate. Cancel the reversal instead of shoving.
                b.backoffTicks=0;
                b.movementMode="t";
                b.trafficRecoveryTicks++;
            }else{
                b.movementMode="b";
                step=-Math.min(b.dna.backoff(),SPEED_CAP);
                b.backoffTicks--;
            }
        }
        double vx=step*dx/Math.max(.00001,distance);
        double vz=step*dz/Math.max(.00001,distance);
        // Evolve how much to anticipate a corner, WITHOUT reducing
        // horizontal speed. Actual Minecraft block collision still
        // rejects unsafe corner cuts.
        // Turn anticipation helps on open ground, but offsets the
        // body into slab lips and overhead openings. Maintain a
        // centered line until fully clear of any height constraint.
        boolean precision=centerApproach||precisionTransition(helper,world,
                b.x,b.z,ahead[0],ahead[1]);
        boolean nextPrecision=false;
        if(b.cursor+1<b.route.length()){
            int[] look=next(ahead[0],ahead[1],
                    b.route.charAt(b.cursor+1));
            nextPrecision=precisionTransition(helper,world,
                    ahead[0],ahead[1],look[0],look[1]);
        }
        if(!precision&&!nextPrecision
                &&!teammateWithin(b,crew,.90)
                &&b.cursor+1<b.route.length()&&distance<.45){
            int[] corner=next(ahead[0],ahead[1],
                    b.route.charAt(b.cursor+1));
            double lookX=corner[0]-ahead[0],lookZ=corner[1]-ahead[1];
            vx+=lookX*(1-b.dna.align())*b.dna.drive()*.075;
            vz+=lookZ*(1-b.dna.align())*b.dna.drive()*.075;
        }
        double norm=Math.hypot(vx,vz);
        if(norm>SPEED_CAP){
            vx*=SPEED_CAP/norm;
            vz*=SPEED_CAP/norm;
        }
        // Respect a traveling golem ahead rather than letting native
        // entity pushing turn the whole convoy into a collision dance.
        // These traffic holds are NOT stuck ticks and must not trigger
        // jumping, backing off or pollute static collision memory.
        TrafficMotion polite=coordinateTraffic(b,crew,vx,vz);
        if(polite.yielding()){
            b.movementMode=polite.queue()?"q":"y";
            b.trafficPrioritySlot=polite.leaderSlot();
            b.immobile=0;
            b.backoffTicks=0;
            b.staticStalls=0;
            b.lastDistance=Double.POSITIVE_INFINITY;
            if(Math.hypot(polite.vx(),polite.vz())<.002){
                b.trafficWaitTicks++;
                return;
            }
        }else b.trafficPrioritySlot=-1;
        vx=polite.vx();
        vz=polite.vz();
        // Actual mob collision resolver, not a simulated tile hop.
        mob.setNoGravity(false);
        mob.getNavigation().stop();
        Vec3 old=mob.position();
        // A horizontal-only direct move clears the vanilla onGround
        // state on some ticks. Then Minecraft will not auto-step a 0.5
        // bottom slab despite maxUpStep() == 0.6, trapping a 0.65-wide
        // golem precisely at the slab face. Include a small DOWNWARD
        // collision probe in the native move, so actual ground support
        // and Minecraft's normal step-up solver are respected.
        // No manual y-position change, flying, noclip or jump boost.
        double nativeGroundProbe=grounded&&!"j".equals(b.movementMode)
                ?-.08:0;
        mob.move(MoverType.SELF,new Vec3(vx,nativeGroundProbe,vz));
        // Count only the actual real-body elevation produced by
        // Minecraft's collision step resolver (no planned-only credit).
        if(nativeSlabAscent&&mob.getY()-old.y>.42)
            b.successfulSlabAscents++;
        if(nativeSlabAscent&&Math.hypot(mob.getX()-old.x,
                mob.getZ()-old.z)<.035)
            b.slabEntryStalls++;
        if((enteringDoorCell||leavingDoorCell)
                &&Math.hypot(mob.getX()-old.x,mob.getZ()-old.z)<.035)
            b.doorEntryStalls++;
        BlockPos activeDoor=enteringDoorCell?target:
                leavingDoorCell?pos(helper,b.x,2,b.z):null;
        boolean isAligning=enteringDoorCell&&!leavingDoorCell
                &&((ahead[0]!=b.x&&ahead[1]==b.z
                        &&Math.abs(target.getZ()+.5
                                +(b.doorOffsetX?0:b.doorLaneShift)-mob.getZ())>.045)
                    ||(ahead[1]!=b.z&&ahead[0]==b.x
                        &&Math.abs(target.getX()+.5
                                +(b.doorOffsetX?b.doorLaneShift:0)-mob.getX())>.045));
        recordDoorForwardProgress(b,world,activeDoor,old.x,old.z,
                ahead[0],ahead[1],teammateWithin(b,crew,1.15),
                isAligning);
        // Count arrival on the SAME game tick as the physical move,
        // not on the following tick (which could be tick 361).
        if(world.getBlockState(mob.blockPosition().below()).is(RED)){
            b.arrivedTick=(int)(helper.getTick()-b.birthTick);
            mob.discard();
            return;
        }
        double realHorizontal=Math.hypot(mob.getX()-old.x,
                mob.getZ()-old.z);
        boolean hitsStaticBlock=world.getBlockCollisions(mob,
                mob.getBoundingBox().move(vx,0,vz))
                .iterator().hasNext();
        boolean teammateBlocking=teammateWithin(b,crew,1.20);
        if(realHorizontal<.04&&teammateBlocking)
            b.trafficWaitTicks++;
        else if(realHorizontal>.11)b.trafficWaitTicks=0;
        // Teammate contact must NEVER teach the genetics that a slab
        // face is a permanent solid wall. Log static evidence only
        // when a stationary body has no live neighbor blocking it.
        if(enteringDoorCell||leavingDoorCell)
            b.staticStalls=0; // actionable door, not a learned static wall
        else if(realHorizontal<.11&&hitsStaticBlock&&!teammateBlocking)
            b.staticStalls++;
        else if(realHorizontal>.15||teammateBlocking)
            b.staticStalls=0;
        if(mob.position().distanceToSqr(old)<.000004){
            b.movementMode="c";
            b.collisions++;
            if(hitsStaticBlock&&!teammateBlocking&&b.immobile>3
                    &&!enteringDoorCell&&!leavingDoorCell){
                Edge blocked=new Edge(b.x,b.z,ahead[0],ahead[1]);
                b.realBlockFailures.merge(blocked,1,Integer::sum);
            }
        }
    }

    /**
     * Durable checkpoints for REAL Minecraft-only fitness. Each physical
     * generation writes an atomic checkpoint; a verified all-10 winner is
     * independently protected in champion.json. The CI workflow commits
     * both files to the Git branch even when the GameTest returns failure.
     *
     * All numbers used to rank/survive come from the actual entities.
     * Never load a Python/headless trainer result as a valid checkpoint.
     */
    private static final class TrainingMemory {
        private static final String FORMAT="golems-plus-physical-genes-v1";
        private static final String COURSE="timetrial-alt-vanilla-9-builders-260-ticks-v3";
        private static final Gson JSON=new GsonBuilder()
                .setPrettyPrinting().disableHtmlEscaping().create();
        private static final Path DIRECTORY=Path.of(
                System.getenv("GOLEMS_EVOLUTION_STATE_DIR")==null
                ||System.getenv("GOLEMS_EVOLUTION_STATE_DIR").isBlank()
                ?"training/alt/state-nine":System.getenv("GOLEMS_EVOLUTION_STATE_DIR"));
        private static Path checkpoint(){return DIRECTORY.resolve("checkpoint.json");}
        private static Path champion(){return DIRECTORY.resolve("champion.json");}
        private static Path nativeChampion(){return DIRECTORY.resolve("native-champion.json");}
        private static Path history(){return DIRECTORY.resolve("history.jsonl");}
        private static Path replayDirectory(){return Path.of(
                System.getenv("GOLEMS_EVOLUTION_REPLAY_DIR")==null
                ||System.getenv("GOLEMS_EVOLUTION_REPLAY_DIR").isBlank()
                ?"training/alt/replays/live-nine":System.getenv("GOLEMS_EVOLUTION_REPLAY_DIR"));}

        private static JsonArray encode(Genome genome){
            JsonArray a=new JsonArray();
            for(double value:new double[]{
                    genome.drive(),genome.align(),genome.tolerance(),
                    genome.jumpLead(),genome.jumpPower(),genome.jumpCooldown(),
                    genome.stuckTimeout(),genome.backoff(),genome.slabAvoid(),
                    genome.turnBias(),genome.routeJitter(),genome.doorBias()})
                a.add(value);
            return a;
        }
        private static Genome decode(JsonElement element){
            JsonArray a=element.getAsJsonArray();
            if(a.size()!=12)throw new IllegalArgumentException(
                    "Expected 12 evolution genes, found "+a.size());
            double[] p=new double[12];
            for(int i=0;i<p.length;i++){
                p[i]=a.get(i).getAsDouble();
                if(!Double.isFinite(p[i]))
                    throw new IllegalArgumentException("Invalid gene "+i);
            }
            if(p[0]<.5||p[0]>1.1||p[1]<.3||p[1]>1.1
                    ||p[2]<.04||p[2]>.3||p[3]<.15||p[3]>1.8
                    ||p[4]<.3||p[4]>.5||p[5]<3||p[5]>40
                    ||p[6]<4||p[6]>50||p[7]<0||p[7]>.3
                    ||p[8]<0||p[8]>6||p[9]<0||p[9]>2
                    ||p[10]<0||p[10]>2||p[11]<-.2||p[11]>.2
                    ||p[5]!=Math.rint(p[5])||p[6]!=Math.rint(p[6]))
                throw new IllegalArgumentException("Genome out of bounds");
            return new Genome(p[0],p[1],p[2],p[3],p[4],
                    (int)p[5],(int)p[6],p[7],p[8],p[9],p[10],p[11]);
        }
        private static JsonObject read(Path path){
            if(!Files.exists(path))return null;
            try(var reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)){
                if(Files.size(path)>64L*1024*1024)
                    throw new IllegalArgumentException("Checkpoint too large: "+path);
                JsonObject obj=JsonParser.parseReader(reader).getAsJsonObject();
                if(!FORMAT.equals(obj.get("format").getAsString())
                        ||!COURSE.equals(obj.get("course").getAsString())
                        ||!obj.get("physicalEntities").getAsBoolean())
                    throw new IllegalStateException("Incompatible saved training state: "+path);
                return obj;
            }catch(IOException|RuntimeException e){
                // Never reset to old random genes and erase valuable saved
                // training if a checkpoint is unreadable.
                throw new IllegalStateException("Cannot load physical training state "+path,e);
            }
        }
        private static void writeAtomic(Path path,JsonObject obj){
            try{
                Files.createDirectories(path.getParent());
                Path temporary=path.resolveSibling(path.getFileName()+".tmp");
                Files.writeString(temporary,JSON.toJson(obj)+"\n",
                        StandardCharsets.UTF_8);
                try{
                    Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                }catch(AtomicMoveNotSupportedException unsupported){
                    Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);
                }
            }catch(IOException e){
                throw new IllegalStateException(
                        "Could not durably save physical genes to "+path,e);
            }
        }
        private static JsonObject header(){
            JsonObject obj=new JsonObject();
            obj.addProperty("format",FORMAT);
            obj.addProperty("course",COURSE);
            obj.addProperty("physicalEntities",true);
            obj.addProperty("nativeSimulationProtocol",NATIVE_PROTOCOL);
            if(nativeTickCoverage!=null)obj.add("nativeTickCoverage",nativeTickCoverage.deepCopy());
            obj.addProperty("bots",SELECTED_BOTS.length);
            obj.addProperty("deadlineTicks",DEADLINE);
            obj.addProperty("speedCapMetersPerSecond",4.0);
            return obj;
        }
        private static JsonArray encodePopulation(List<Genome> population){
            JsonArray a=new JsonArray();
            for(Genome g:population)a.add(encode(g));
            return a;
        }
        private static List<Genome> decodePopulation(JsonArray a){
            if(a.size()!=SELECTED_BOTS.length)
                throw new IllegalStateException("Saved population must have 9 genomes");
            List<Genome> result=new ArrayList<>();
            for(JsonElement e:a)result.add(decode(e));
            return result;
        }
        static int restore(Tournament run){
            JsonObject checkpoint=read(checkpoint());
            JsonObject champion=read(nativeChampion());
            boolean nativeWinner=champion!=null;
            if(nativeWinner&&!NATIVE_PROTOCOL.equals(champion.get("nativeSimulationProtocol").getAsString()))
                throw new IllegalStateException("Unknown native champion protocol");
            if(champion==null)champion=read(champion());
            if(checkpoint!=null){
                boolean nativeCheckpoint=checkpoint.has("nativeSimulationProtocol")
                        &&NATIVE_PROTOCOL.equals(checkpoint.get("nativeSimulationProtocol").getAsString());
                if(checkpoint.has("legacyLocalRuleMemory"))
                    run.legacyRuleMemory=checkpoint.getAsJsonObject("legacyLocalRuleMemory").deepCopy();
                if(checkpoint.has("legacyStrategyMemory"))
                    run.legacyStrategyMemory=checkpoint.getAsJsonObject("legacyStrategyMemory").deepCopy();
                if(checkpoint.has("legacyGeneticKnowledge"))
                    run.legacyGeneticKnowledge=checkpoint.getAsJsonObject("legacyGeneticKnowledge").deepCopy();
                if(checkpoint.has("consistencyPopulation"))
                    run.frozenPopulation=decodePopulation(checkpoint.getAsJsonArray("consistencyPopulation"));
                if(checkpoint.has("consistencyStrategies"))
                    run.frozenStrategies=List.of(JSON.fromJson(checkpoint.get("consistencyStrategies"),
                            NavigationStrategyMemory.Strategy[].class));
                if(checkpoint.has("localRuleMemory")){
                    JsonObject local=checkpoint.getAsJsonObject("localRuleMemory");
                    if(!nativeCheckpoint){run.legacyRuleMemory=local.deepCopy();}
                    else if(!NavigationRuleMemory.FORMAT.equals(local.get("format").getAsString()))
                        throw new IllegalStateException("Unknown local rule format");
                    else for(NavigationRuleMemory.Trial trial:JSON.fromJson(local.get("trials"),
                            NavigationRuleMemory.Trial[].class))run.ruleMemory.observe(trial);
                }
                if(checkpoint.has("strategyMemory")){
                    JsonObject memory=checkpoint.getAsJsonObject("strategyMemory");
                    if(!nativeCheckpoint){run.legacyStrategyMemory=memory.deepCopy();}
                    else if(!NavigationStrategyMemory.FORMAT.equals(
                            memory.get("format").getAsString()))
                        throw new IllegalStateException("Unknown strategy memory format");
                    else for(NavigationStrategyMemory.Observation outcome:JSON.fromJson(
                            memory.get("observations"),
                            NavigationStrategyMemory.Observation[].class))
                        run.strategyMemory.observe(outcome);
                }
                System.out.println("[ALT STRATEGY MEMORY] Restored actual episodes="
                        +run.strategyMemory.observations().size());
                // Exploration genomes are untested offspring and must
                // NEVER displace a real complete 10/10 team for the
                // next round's generation-zero reliability check.
                run.pendingExploration=decodePopulation(
                        checkpoint.getAsJsonArray("nextPopulation"));
                run.generationGenes=new ArrayList<>(run.pendingExploration);
                if(checkpoint.has("nextStrategies")){
                    NavigationStrategyMemory.Strategy[] policies=JSON.fromJson(
                            checkpoint.get("nextStrategies"),
                            NavigationStrategyMemory.Strategy[].class);
                    String[] routes=JSON.fromJson(checkpoint.get("nextFamilyRoutes"),
                            String[].class);
                    Integer[] origins=JSON.fromJson(checkpoint.get("nextFamilyOrigins"),
                            Integer[].class);
                    if(policies.length!=SELECTED_BOTS.length
                            ||routes.length!=SELECTED_BOTS.length
                            ||origins.length!=SELECTED_BOTS.length)
                        throw new IllegalStateException("Invalid nine-family route inheritance");
                    for(int i=0;i<SELECTED_BOTS.length;i++){
                        if(policies[i]==null||routes[i]==null||origins[i]==null
                                ||origins[i]<0||origins[i]>=SELECTED_BOTS.length)
                            throw new IllegalStateException("Broken saved family lineage");
                    }
                    run.generationStrategies=new ArrayList<>(java.util.Arrays.asList(policies));
                    run.generationRoutes=new ArrayList<>(java.util.Arrays.asList(routes));
                    run.generationFamilyOrigins=new ArrayList<>(java.util.Arrays.asList(origins));
                }
                if(checkpoint.has("lastSuccessfulPopulation")){
                    List<Genome> fullTeam=decodePopulation(checkpoint
                            .getAsJsonArray("lastSuccessfulPopulation"));
                    JsonArray arrivals=checkpoint
                            .getAsJsonArray("lastSuccessfulArrivalTicks");
                    int generation=checkpoint.get(
                            "lastSuccessfulGeneration").getAsInt();
                    if(arrivals.size()!=SELECTED_BOTS.length
                            ||generation<1
                            ||generation>checkpoint.get(
                                "completedGenerations").getAsInt())
                        throw new IllegalStateException(
                                "Invalid verified full-crew checkpoint");
                    List<Integer> ticks=new ArrayList<>();
                    for(JsonElement arrival:arrivals){
                        int tick=arrival.getAsInt();
                        if(tick<0||tick>DEADLINE)
                            throw new IllegalStateException(
                                    "Last successful full crew was late");
                        ticks.add(tick);
                    }
                    int maximum=ticks.stream().mapToInt(Integer::intValue)
                            .max().orElse(Integer.MAX_VALUE);
                    if(maximum!=checkpoint.get(
                            "lastSuccessfulSlowestTicks").getAsInt())
                        throw new IllegalStateException(
                                "Winning cohort arrival proof mismatched");
                    run.lastSuccessfulPopulation=new ArrayList<>(fullTeam);
                    run.lastSuccessfulArrivalTicks=ticks;
                    run.lastSuccessfulGeneration=generation;
                    run.lastSuccessfulNativeVerified=nativeCheckpoint&&checkpoint.has("lastSuccessfulNativeTicksVerified")
                            &&checkpoint.get("lastSuccessfulNativeTicksVerified").getAsBoolean();
                    // Explicit validation can replay the historically
                    // successful TEN genes, but normal training must
                    // restore independent pooled offspring instead.
                    if("validate".equalsIgnoreCase(
                            System.getenv("GOLEMS_EVOLUTION_MODE"))){
                        run.generationGenes=new ArrayList<>(fullTeam);
                        System.out.println("[TIMETRIAL MEMORY] GEN0 "
                            +"restoring latest VERIFIED 9/9 cohort from "
                            +"generation "+generation+" (not offspring)");
                    }else System.out.println("[TIMETRIAL MEMORY] GEN0 "
                            +"exploration mode uses saved mutated offspring");
                }
                JsonArray a=checkpoint.getAsJsonArray("personalBests");
                JsonArray scores=checkpoint.getAsJsonArray("personalScores");
                if(a.size()!=SELECTED_BOTS.length
                        ||scores.size()!=SELECTED_BOTS.length)
                    throw new IllegalStateException("Invalid saved personal bests");
                for(int i=0;i<a.size();i++){
                    run.champions[i]=a.get(i).isJsonNull()?null:decode(a.get(i));
                    run.championScores[i]=scores.get(i).getAsDouble();
                    if(!Double.isFinite(run.championScores[i]))
                        throw new IllegalStateException("Invalid fitness checkpoint");
                }
                if(checkpoint.has("knowledgePool")){
                    for(JsonElement member:checkpoint.getAsJsonArray("knowledgePool")){
                        JsonObject k=member.getAsJsonObject();
                        int slot=k.get("slot").getAsInt();
                        double fitness=k.get("fitness").getAsDouble();
                        if(slot<0||slot>=SELECTED_BOTS.length
                                ||!Double.isFinite(fitness)
                                ||run.knowledgePool.stream().anyMatch(p->p.slot()==slot))
                            throw new IllegalStateException("Invalid independent pool member");
                        run.knowledgePool.add(new Knowledge(slot,
                                decode(k.get("genes")),fitness,
                                k.get("arrivedTick").getAsInt(),
                                k.get("progress").getAsDouble(),
                                k.get("generation").getAsInt(),
                                NavigationStrategyMemory.Strategy.valueOf(
                                        k.get("strategy").getAsString()),
                                k.get("route").getAsString()));
                    }
                    if(run.knowledgePool.size()>3)
                        throw new IllegalStateException("Knowledge pool exceeds three");
                }
                // Migrate the original physically scored PERSONAL bests.
                // Older checkpoints used the same real-entity fitness but
                // never exposed an explicitly named top-three pool.
                for(int slot=0;slot<SELECTED_BOTS.length;slot++){
                    if(run.champions[slot]==null)continue;
                    final int currentSlot=slot;
                    if(run.knowledgePool.stream().anyMatch(
                            p->p.slot()==currentSlot))
                        continue;
                    run.knowledgePool.add(new Knowledge(slot,run.champions[slot],
                            run.championScores[slot],-1,0,
                            checkpoint.get("completedGenerations").getAsInt(),
                            NavigationStrategyMemory.Strategy.DIRECT,""));
                }
                run.knowledgePool.sort(Comparator
                        .comparingDouble(Knowledge::fitness).reversed()
                        .thenComparingInt(Knowledge::slot));
                if(run.knowledgePool.size()>3)
                    run.knowledgePool.subList(3,run.knowledgePool.size()).clear();
                // Migration: the older checkpoint's nextPopulation was
                // produced by mixed, unrelated parents. Start the FIRST
                // upgraded run from the preserved three personal bests.
                // Once saved in the new format, continue its offspring.
                if(!checkpoint.has("knowledgePool")
                        &&!"validate".equalsIgnoreCase(
                            System.getenv("GOLEMS_EVOLUTION_MODE"))
                        &&!run.knowledgePool.isEmpty()){
                    List<Genome> seed=run.knowledgePool.stream()
                            .map(Knowledge::dna).toList();
                    Random initial=new Random(TRAINING_SEED
                            +1000003L*checkpoint.get(
                                    "completedGenerations").getAsInt());
                    run.generationGenes.clear();
                    for(int i=0;i<SELECTED_BOTS.length;i++){
                        if(i<seed.size())run.generationGenes.add(seed.get(i));
                        else run.generationGenes.add(Genome.cross(
                                seed.get(initial.nextInt(seed.size())),
                                seed.get(initial.nextInt(seed.size())),
                                initial).mutate(initial));
                    }
                    System.out.println("[ALT KNOWLEDGE POOL] Migrated legacy "
                            +"nextPopulation to top-three parents");
                }
                System.out.println("[ALT KNOWLEDGE POOL] Restored "
                        +run.knowledgePool.size()+" independent physical elites");
                run.completedGenerations=checkpoint.get("completedGenerations").getAsInt();
                run.bestFinished=checkpoint.get("bestFinishedEver").getAsInt();
                if(run.completedGenerations<0||run.bestFinished<0
                        ||run.bestFinished>SELECTED_BOTS.length)
                    throw new IllegalStateException("Invalid generation counter");
                if(!nativeCheckpoint){
                    JsonObject old=new JsonObject();
                    for(String key:List.of("personalBests","personalScores","knowledgePool"))
                        if(checkpoint.has(key))old.add(key,checkpoint.get(key).deepCopy());
                    run.legacyGeneticKnowledge=old;
                    java.util.Arrays.fill(run.championScores,-Double.MAX_VALUE);
                    run.knowledgePool.clear();run.bestFinished=0;
                }else if(checkpoint.has("bestNativeFinishedEver"))
                    run.bestFinished=checkpoint.get("bestNativeFinishedEver").getAsInt();
                for(JsonElement item:checkpoint.getAsJsonArray("blockedEdges")){
                    JsonObject e=item.getAsJsonObject();
                    run.collisionMemory.put(new Edge(e.get("x").getAsInt(),
                            e.get("z").getAsInt(),e.get("toX").getAsInt(),
                            e.get("toZ").getAsInt()),e.get("failures").getAsInt());
                }
                System.out.println("[TIMETRIAL MEMORY] RESTORED checkpoint generations="
                        +run.completedGenerations+" best="+run.bestFinished
                        +" edges="+run.collisionMemory.size());
            }else{
                // Migrate useful REAL 10-worker learning into nine-worker
                // genes only. Old 10/10 results and checkpoint history stay
                // untouched at training/alt/state/, and are NOT compared
                // to nine-worker fitness or claimed as nine-worker wins.
                Path legacy=Path.of("training/alt/state/checkpoint.json");
                List<Knowledge> seedParents=new ArrayList<>();
                if(Files.exists(legacy))try{
                    JsonObject prior=JsonParser.parseString(
                            Files.readString(legacy)).getAsJsonObject();
                    if(prior.get("bots").getAsInt()==10
                            &&prior.get("physicalEntities").getAsBoolean()
                            &&"timetrial-alt-vanilla-10-builders-260-ticks-v2"
                                .equals(prior.get("course").getAsString())){
                        for(JsonElement row:prior.getAsJsonArray("lastRealEntityResults")){
                            JsonObject e=row.getAsJsonObject();
                            int slot=e.get("slot").getAsInt();
                            if(slot<0||slot>=SELECTED_BOTS.length)continue;
                            seedParents.add(new Knowledge(slot,
                                decode(e.get("genes")),e.get("fitness").getAsDouble(),
                                e.get("arrivedTicks").getAsInt(),
                                e.get("progressWaypoints").getAsDouble(),0,
                                NavigationStrategyMemory.Strategy.valueOf(
                                    e.get("strategy").getAsString()),""));
                        }
                        seedParents.sort(Comparator.comparingDouble(
                                Knowledge::fitness).reversed());
                    }
                }catch(IOException|RuntimeException unreadable){
                    System.out.println("[ALT NINE FAMILY] Legacy seed not usable: "
                            +unreadable.getMessage());
                }
                if(seedParents.size()<3)for(int i=0;i<SELECTED_BOTS.length;i++)
                    run.generationGenes.add(SAVED_PHYSICAL_CHAMPIONS[i]);
                else{
                    run.knowledgePool.addAll(seedParents.subList(0,3));
                    FamilyPlan first=breedFamilies(run.knowledgePool,
                            new Random(TRAINING_SEED),SELECTED_BOTS.length);
                    run.generationGenes=new ArrayList<>(first.genes());
                    run.generationStrategies=new ArrayList<>(first.policies());
                    run.generationRoutes=new ArrayList<>(first.parentRoutes());
                    run.generationFamilyOrigins=new ArrayList<>(first.parentSlots());
                    System.out.println("[ALT NINE FAMILY] Seeded from archived "
                            +"physical 10-worker finishers, without inherited 10/10 claim");
                }
            }
            if(champion!=null){
                List<Genome> gold=decodePopulation(
                        champion.getAsJsonArray("winningGenomes"));
                int worst=champion.get("slowestTicks").getAsInt();
                if(worst>DEADLINE)
                    throw new IllegalStateException("Saved champion was late!");
                if(nativeWinner){
                    run.bestChampionSlowest=worst;
                    run.bestChampionAverage=champion.get("meanTicks").getAsDouble();
                }
                // Champion is an immutable, physically proven anchor.
                // Use these in the parent pool, but do not overwrite the
                // checkpoint population of fresh mutations.
                run.verifiedChampion=gold;
                System.out.println("[TIMETRIAL MEMORY] LOADED saved gene cohort "
                        +"worstTicks="+worst+" nativeTickQualified="+nativeWinner);
            }
            return run.completedGenerations;
        }

        static void save(Tournament run,List<Candidate> bots,int finished,
                         int slowest,double mean,List<Genome> nextPopulation){
            JsonObject obj=header();
            obj.addProperty("policyRevision",run.consistencyMode?"matched-local-rules-v1":"context-strategy-v1");
            if(run.consistencyMode){
                obj.add("consistencyPopulation",encodePopulation(run.frozenPopulation));
                obj.add("consistencyStrategies",JSON.toJsonTree(run.frozenStrategies));
                JsonObject local=new JsonObject();
                local.addProperty("format",NavigationRuleMemory.FORMAT);
                local.add("trials",JSON.toJsonTree(run.ruleMemory.checkpointTrials()));
                local.add("bindings",JSON.toJsonTree(run.ruleMemory.bindings().values()));
                obj.add("localRuleMemory",local);
                obj.add("experiment",run.experimentMetadata());
            }
            JsonObject strategyMemory=new JsonObject();
            strategyMemory.addProperty("format",NavigationStrategyMemory.FORMAT);
            strategyMemory.add("observations",JSON.toJsonTree(
                    run.strategyMemory.observations()));
            obj.add("strategyMemory",strategyMemory);
            obj.addProperty("completedGenerations",run.completedGenerations+1);
            obj.addProperty("bestFinishedEver",run.bestFinished);
            obj.addProperty("bestNativeFinishedEver",run.bestFinished);
            if(run.legacyRuleMemory!=null)obj.add("legacyLocalRuleMemory",run.legacyRuleMemory.deepCopy());
            if(run.legacyStrategyMemory!=null)obj.add("legacyStrategyMemory",run.legacyStrategyMemory.deepCopy());
            if(run.legacyGeneticKnowledge!=null)obj.add("legacyGeneticKnowledge",run.legacyGeneticKnowledge.deepCopy());
            obj.addProperty("lastCompleted",finished);
            obj.addProperty("lastSlowestTicks",finished==SELECTED_BOTS.length?slowest:-1);
            obj.addProperty("lastMeanFitness",mean);
            obj.addProperty("lastGenerationIndex",run.generation);
            // Both lineages are retained separately: nextPopulation
            // contains new untested genetic offspring, while the last
            // full-crew 10/10 winner is always saved unchanged until a
            // NEW complete physical success replaces it.
            obj.add("nextPopulation",encodePopulation(nextPopulation));
            if(!run.consistencyMode){
                obj.add("nextStrategies",JSON.toJsonTree(run.generationStrategies));
                obj.add("nextFamilyRoutes",JSON.toJsonTree(run.generationRoutes));
                obj.add("nextFamilyOrigins",JSON.toJsonTree(run.generationFamilyOrigins));
                obj.addProperty("familyModel","three-parents-two-mutated-children-v1");
            }
            if(run.lastSuccessfulPopulation.size()==SELECTED_BOTS.length){
                obj.add("lastSuccessfulPopulation",
                        encodePopulation(run.lastSuccessfulPopulation));
                JsonArray arrivals=new JsonArray();
                for(int tick:run.lastSuccessfulArrivalTicks)arrivals.add(tick);
                obj.add("lastSuccessfulArrivalTicks",arrivals);
                obj.addProperty("lastSuccessfulGeneration",
                        run.lastSuccessfulGeneration);
                obj.addProperty("lastSuccessfulNativeTicksVerified",run.lastSuccessfulNativeVerified);
                obj.addProperty("lastSuccessfulSlowestTicks",arrivals.size()==0
                        ?-1:run.lastSuccessfulArrivalTicks.stream()
                            .mapToInt(Integer::intValue).max().orElse(-1));
            }
            JsonArray personal=new JsonArray(),scores=new JsonArray();
            for(int i=0;i<SELECTED_BOTS.length;i++){
                if(run.champions[i]==null)personal.add(com.google.gson.JsonNull.INSTANCE);
                else personal.add(encode(run.champions[i]));
                scores.add(run.championScores[i]);
            }
            obj.add("personalBests",personal);
            obj.add("personalScores",scores);
            JsonArray pool=new JsonArray();
            for(Knowledge k:run.knowledgePool){
                JsonObject member=new JsonObject();
                member.addProperty("slot",k.slot());
                member.addProperty("fitness",k.fitness());
                member.addProperty("arrivedTick",k.arrivedTick());
                member.addProperty("progress",k.progress());
                member.addProperty("generation",k.generation());
                member.add("genes",encode(k.dna()));
                member.addProperty("strategy",k.policy().name());
                member.addProperty("route",k.route());
                pool.add(member);
            }
            obj.add("knowledgePool",pool);
            JsonArray edges=new JsonArray();
            for(var edge:run.collisionMemory.entrySet()){
                JsonObject e=new JsonObject();
                e.addProperty("x",edge.getKey().x());
                e.addProperty("z",edge.getKey().z());
                e.addProperty("toX",edge.getKey().toX());
                e.addProperty("toZ",edge.getKey().toZ());
                e.addProperty("failures",Math.min(100,edge.getValue()));
                edges.add(e);
            }
            obj.add("blockedEdges",edges);
            String session=System.getenv("GOLEMS_EVOLUTION_SESSION");
            if(session==null||session.isBlank())session="manual";
            if(!session.matches("[A-Za-z0-9_-]{1,90}"))
                throw new IllegalStateException("Invalid physical replay session");
            String replayName="trial-"+session+"-generation-"
                    +(run.completedGenerations+1)+".json";
            obj.addProperty("replayFile",replayName);
            JsonObject replay=header();
            replay.addProperty("format","golems-plus-real-movement-v1");
            replay.addProperty("trial",session);
            replay.addProperty("generation",run.generation);
            replay.addProperty("completedGeneration",run.completedGenerations+1);
            replay.addProperty("finished",finished);
            if(run.consistencyMode)replay.add("experiment",run.experimentMetadata());
            replay.addProperty("deadlineTicks",DEADLINE);
            JsonArray physicalTraces=new JsonArray();
            JsonArray physicalFitness=new JsonArray();
            for(Candidate b:bots){
                JsonObject entry=new JsonObject();
                entry.addProperty("slot",b.index);
                entry.addProperty("familyParentSlot",b.familyParentSlot);
                entry.addProperty("familyRole",b.familyRole);
                entry.addProperty("arrivedTicks",b.arrivedTick);
                entry.addProperty("fitness",b.fitness());
                entry.addProperty("nativeEntityTicks",b.mob.tickCount-b.initialEntityTicks);
                entry.addProperty("strategyContext",b.strategyContext);
                entry.addProperty("strategy",b.strategyDecision.strategy().name());
                entry.addProperty("strategyReason",b.strategyDecision.reason());
                entry.addProperty("priorStrategyTrials",b.strategyDecision.evidenceTrials());
                entry.add("localEpisodes",JSON.toJsonTree(b.localEpisodes));
                entry.addProperty("progressWaypoints",b.bestCursor);
                entry.addProperty("collisions",b.collisions);
                entry.addProperty("jumps",b.jumps);
                entry.add("genes",encode(b.dna));
                entry.addProperty("reducedSpeedTicks",b.reducedSpeedTicks);
                entry.addProperty("stationaryTicks",b.stationaryTicks);
                entry.addProperty("reverseTicks",b.reverseTicks);
                entry.addProperty("doorTicks",b.doorTicks);
                entry.addProperty("blockedTicks",b.blockedTicks);
                entry.addProperty("slabTicks",b.slabTicks);
                entry.addProperty("slabApproachTicks",b.slabApproachTicks);
                entry.addProperty("successfulSlabAscents",b.successfulSlabAscents);
                entry.addProperty("slabEntryStalls",b.slabEntryStalls);
                entry.addProperty("doorEntryAlignTicks",b.doorEntryAlignTicks);
                entry.addProperty("doorEntryStalls",b.doorEntryStalls);
                entry.addProperty("doorStateRetries",b.doorStateRetries);
                entry.addProperty("precisionTicks",b.precisionTicks);
                entry.addProperty("reroutes",b.reroutes);
                entry.addProperty("yieldingTicks",b.yieldingTicks);
                entry.addProperty("trafficQueueTicks",b.trafficQueueTicks);
                entry.addProperty("trafficCrossingTicks",b.trafficCrossingTicks);
                entry.addProperty("trafficRecoveryTicks",b.trafficRecoveryTicks);
                entry.addProperty("safeCornerShortcuts",b.safeCornerShortcuts);
                entry.addProperty("initialRouteUsesDoor",b.initialRouteUsesDoor);
                entry.addProperty("distanceMeters",b.measuredDistance);
                physicalFitness.add(entry);
                JsonObject trace=new JsonObject();
                trace.addProperty("slot",b.index);
                trace.addProperty("familyParentSlot",b.familyParentSlot);
                trace.addProperty("familyRole",b.familyRole);
                trace.addProperty("strategyContext",b.strategyContext);
                trace.addProperty("strategy",b.strategyDecision.strategy().name());
                trace.addProperty("strategyReason",b.strategyDecision.reason());
                trace.addProperty("spawnIndex",SELECTED_BOTS[b.index]);
                trace.addProperty("startX",STARTS[SELECTED_BOTS[b.index]][0]);
                trace.addProperty("startZ",STARTS[SELECTED_BOTS[b.index]][1]);
                trace.addProperty("arrivedTicks",b.arrivedTick);
                trace.addProperty("plannedRoute",b.initialRoute);
                trace.addProperty("finalRoute",b.route);
                trace.addProperty("nativeEntityTicks",b.mob.tickCount-b.initialEntityTicks);
                trace.add("nativeTickSamples",b.nativeTickSamples);
                trace.add("localEpisodes",JSON.toJsonTree(b.localEpisodes));
                trace.addProperty("collisions",b.collisions);
                trace.addProperty("jumps",b.jumps);
                trace.addProperty("reducedSpeedTicks",b.reducedSpeedTicks);
                trace.addProperty("stationaryTicks",b.stationaryTicks);
                trace.addProperty("reverseTicks",b.reverseTicks);
                trace.addProperty("doorTicks",b.doorTicks);
                trace.addProperty("blockedTicks",b.blockedTicks);
                trace.addProperty("slabTicks",b.slabTicks);
                trace.addProperty("slabApproachTicks",b.slabApproachTicks);
                trace.addProperty("successfulSlabAscents",b.successfulSlabAscents);
                trace.addProperty("slabEntryStalls",b.slabEntryStalls);
                trace.addProperty("doorEntryAlignTicks",b.doorEntryAlignTicks);
                trace.addProperty("doorEntryStalls",b.doorEntryStalls);
                trace.addProperty("doorStateRetries",b.doorStateRetries);
                trace.addProperty("precisionTicks",b.precisionTicks);
                trace.addProperty("reroutes",b.reroutes);
                trace.addProperty("yieldingTicks",b.yieldingTicks);
                trace.addProperty("trafficQueueTicks",b.trafficQueueTicks);
                trace.addProperty("trafficCrossingTicks",b.trafficCrossingTicks);
                trace.addProperty("trafficRecoveryTicks",b.trafficRecoveryTicks);
                trace.addProperty("trafficReroutes",b.trafficReroutes);
                trace.addProperty("safeCornerShortcuts",b.safeCornerShortcuts);
                trace.addProperty("initialRouteUsesDoor",b.initialRouteUsesDoor);
                trace.addProperty("distanceMeters",b.measuredDistance);
                trace.add("genes",encode(b.dna));
                trace.add("positions",b.movementSamples);
                physicalTraces.add(trace);
            }
            replay.add("bots",physicalTraces);
            writeAtomic(replayDirectory().resolve(replayName),replay);
            obj.add("lastRealEntityResults",physicalFitness);
            // Save after EVERY scored generation, even if all 10 fail.
            writeAtomic(checkpoint(),obj);
            JsonObject historyEntry=obj.deepCopy();
            historyEntry.remove("strategyMemory");
            historyEntry.remove("localRuleMemory");
            historyEntry.remove("legacyLocalRuleMemory");
            historyEntry.remove("legacyStrategyMemory");
            historyEntry.remove("legacyGeneticKnowledge");
            historyEntry.addProperty("localRuleTrialCount",run.ruleMemory.trials().size());
            historyEntry.addProperty("strategyObservationCount",
                    run.strategyMemory.observations().size());
            try{
                // JSONL requires exactly one compact JSON object per
                // line. Pretty printing here made prior history invalid.
                Files.writeString(history(),new Gson().toJson(historyEntry)+"\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            }catch(IOException e){
                throw new IllegalStateException("Cannot record physical learning history",e);
            }

            if(finished==SELECTED_BOTS.length){
                double meanArrival=bots.stream().mapToInt(b->b.arrivedTick)
                        .average().orElse(Double.POSITIVE_INFINITY);
                // Never regress or erase the best VERIFIED complete
                // generation from prior runs. A later losing run cannot
                // even reach this branch.
                if(slowest<run.bestChampionSlowest
                        ||(slowest==run.bestChampionSlowest
                            &&meanArrival<run.bestChampionAverage)){
                    JsonObject winner=header();
                    winner.addProperty("policyRevision",run.consistencyMode?"matched-local-rules-v1":"context-strategy-v1");
                    if(run.consistencyMode)winner.add("localRuleMemory",obj.get("localRuleMemory").deepCopy());
                    winner.add("strategyMemory",strategyMemory.deepCopy());
                    winner.addProperty("wonAtCompletedGeneration",
                            run.completedGenerations+1);
                    winner.addProperty("slowestTicks",slowest);
                    winner.addProperty("meanTicks",meanArrival);
                    JsonArray winningGenes=new JsonArray();
                    JsonArray arrivals=new JsonArray();
                    for(Candidate b:bots){
                        winningGenes.add(encode(b.dna));
                        JsonObject hit=new JsonObject();
                        hit.addProperty("slot",b.index);
                        hit.addProperty("ticks",b.arrivedTick);
                        hit.addProperty("nativeEntityTicks",b.mob.tickCount-b.initialEntityTicks);
                        hit.addProperty("plannedRoute",b.route);
                        arrivals.add(hit);
                    }
                    winner.add("winningGenomes",winningGenes);
                    winner.add("physicalArrivalRecords",arrivals);
                    writeAtomic(nativeChampion(),winner);
                    run.bestChampionSlowest=slowest;
                    run.bestChampionAverage=meanArrival;
                    run.verifiedChampion=new ArrayList<>();
                    for(Candidate b:bots)run.verifiedChampion.add(b.dna);
                    System.out.println("[TIMETRIAL MEMORY] SAVED new real-entity "
                            +"9/9 champion, slowest="+slowest
                            +" mean="+meanArrival);
                }else System.out.println("[TIMETRIAL MEMORY] 9/9 won but "
                        +"retained faster earlier verified champion");
            }
            System.out.println("[TIMETRIAL MEMORY] CHECKPOINT saved real generation "
                    +(run.completedGenerations+1)+" -> "+checkpoint());
        }
    }

    private static final class Tournament {
        final GameTestHelper helper;
        // Fixed seed for each PROCESS used to repeat the same crossover
        // sequence every session. Include the persisted generation
        // counter to make new exploratory mutations diverge across runs.
        final Random rng;
        final Map<Edge,Integer> collisionMemory=new HashMap<>();
        final NavigationStrategyMemory strategyMemory=new NavigationStrategyMemory();
        final NavigationRuleMemory ruleMemory=new NavigationRuleMemory();
        JsonObject legacyRuleMemory,legacyStrategyMemory,legacyGeneticKnowledge;
        Runnable releaseSimulation=()->{};
        long warmupUntil;
        boolean started;
        List<Genome> frozenPopulation=List.of();
        List<NavigationStrategyMemory.Strategy> frozenStrategies=List.of();
        Map<String,NavigationRuleMemory.Binding> blockBindings=Map.of();
        // An absent mode must TRAIN, not silently freeze all ten genes.
        // Matched-rule consistency and historical validation are explicit
        // experiments; they never masquerade as evolutionary progress.
        final String trainingMode=System.getenv("GOLEMS_EVOLUTION_MODE")==null
                ?"evolve":System.getenv("GOLEMS_EVOLUTION_MODE");
        final boolean consistencyMode="consistency".equalsIgnoreCase(trainingMode)
                ||"validate".equalsIgnoreCase(trainingMode);
        final List<Integer> comparisonFinishes=new ArrayList<>();
        String comparisonFingerprint;
        final Genome[] champions=new Genome[SELECTED_BOTS.length];
        final double[] championScores=new double[SELECTED_BOTS.length];
        final List<Knowledge> knowledgePool=new ArrayList<>();
        List<Genome> generationGenes=new ArrayList<>();
        List<Candidate> bots=new ArrayList<>();
        int generation=0;
        boolean ended;
        final String strategySession=System.getenv("GOLEMS_EVOLUTION_SESSION")==null
                ?"local-"+java.util.UUID.randomUUID():System.getenv("GOLEMS_EVOLUTION_SESSION");
        final boolean strategyValidation=consistencyMode;
        long generationStart=0;
        int bestFinished=0;
        int bestFinishedThisSession=0,targetQualifiedRounds;
        int completedGenerations=0;
        int bestChampionSlowest=Integer.MAX_VALUE;
        double bestChampionAverage=Double.POSITIVE_INFINITY;
        List<Genome> verifiedChampion=List.of();
        List<Genome> lastSuccessfulPopulation=List.of();
        List<Integer> lastSuccessfulArrivalTicks=List.of();
        List<Genome> pendingExploration=List.of();
        List<NavigationStrategyMemory.Strategy> generationStrategies=new ArrayList<>();
        List<String> generationRoutes=new ArrayList<>();
        List<Integer> generationFamilyOrigins=new ArrayList<>();
        int lastSuccessfulGeneration;
        boolean lastSuccessfulNativeVerified;
        final boolean exploreMode="explore".equalsIgnoreCase(
                System.getenv("GOLEMS_EVOLUTION_MODE"));
        Tournament(GameTestHelper helper){
            this.helper=helper;
            for(int i=0;i<SELECTED_BOTS.length;i++){
                championScores[i]=-Double.MAX_VALUE;
            }
            // Preserve real progress from PREVIOUS GitHub Actions runs.
            // No headless scores may seed or qualify the physical test.
            TrainingMemory.restore(this);
            System.out.println("[ALT TRAINING MODE] "+trainingMode
                    +" frozenComparison="+consistencyMode
                    +" independentLearning="+!consistencyMode
                    +" existingGenerations="+completedGenerations
                    +" savedIndividualElites="+knowledgePool.size());
            if(pendingExploration.isEmpty())pendingExploration=new ArrayList<>(generationGenes);
            if(consistencyMode){
                if(frozenPopulation.isEmpty())frozenPopulation=new ArrayList<>(
                        !verifiedChampion.isEmpty()?verifiedChampion:
                        !lastSuccessfulPopulation.isEmpty()?lastSuccessfulPopulation:generationGenes);
                generationGenes=new ArrayList<>(frozenPopulation);
            }
            rng=new Random(TRAINING_SEED
                    +1000003L*(long)completedGenerations);
            releaseSimulation=holdCourseSimulation(helper);
            warmupUntil=helper.getTick()+20;
        }
        private NavigationRuleMemory.Rule challengerRule(){
            return switch((generation/6)%4){
                case 0 -> NavigationRuleMemory.Rule.STABLE_MERGE;
                case 1 -> NavigationRuleMemory.Rule.YIELD_ASIDE;
                case 2 -> NavigationRuleMemory.Rule.BASELINE;
                default -> NavigationRuleMemory.Rule.CENTER_STEP;
            };
        }
        private void startRound(){
            // Restore the EXACT original geometry for each generation,
            // not just the frozen-rule control runs. A previous worker
            // may have opened a wooden door; its state must not leak into
            // another generation and change the next population's trial.
            // Generation zero was already built by the enclosing GameTest.
            if(generation>0)fillOriginalCourse(helper);
            if(consistencyMode&&generation%6==0)
                blockBindings=ruleMemory.bindings();
            bots=spawnGeneration(helper,generationGenes,collisionMemory,
                    strategyMemory,strategyValidation,
                    consistencyMode?frozenStrategies:List.of(),
                    generationStrategies,generationRoutes,generationFamilyOrigins);
            if(consistencyMode){
                if(frozenStrategies.isEmpty())frozenStrategies=bots.stream()
                        .map(b->b.strategyDecision.strategy()).toList();
                RuleRound round=new RuleRound(generation%2==1,challengerRule(),blockBindings);
                NavigationRightOfWay leases=new NavigationRightOfWay();
                for(Candidate b:bots){b.ruleRound=round;b.rightOfWay=leases;}
                String signature=TrainingMemory.encodePopulation(generationGenes)+"|"
                        +bots.stream().map(b->b.initialRoute).toList()+"|"
                        +collisionMemory.entrySet().stream().map(e->e.getKey()+"="+e.getValue())
                            .sorted().toList()+"|"+pos(helper,0,2,0);
                try{
                    comparisonFingerprint=java.util.HexFormat.of().formatHex(
                            java.security.MessageDigest.getInstance("SHA-256").digest(
                                signature.getBytes(StandardCharsets.UTF_8)));
                }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
            }
        }
        private JsonObject experimentMetadata(){
            JsonObject data=new JsonObject();
            data.addProperty("block",strategySession+":block"+(generation/6));
            data.addProperty("repeat",(generation%6)/2);
            data.addProperty("arm",generation%2==1?"challenger":"baseline");
            data.addProperty("testedRule",challengerRule().name());
            data.addProperty("fingerprint",comparisonFingerprint);
            data.addProperty("frozenGenes",true);
            data.addProperty("frozenRoutePolicy",true);
            data.addProperty("frozenBlockedEdges",true);
            data.addProperty("rememberedRules",ruleMemory.bindings().size());
            return data;
        }
        void tick(){
            try{tickWithSimulation();}
            catch(RuntimeException failure){ended=true;releaseSimulation.run();throw failure;}
        }
        private void tickWithSimulation(){
            if(ended)return;
            long absoluteTick=helper.getTick();
            if(!started){
                if(absoluteTick<warmupUntil)return;
                startRound();started=true;generationStart=absoluteTick+1;return;
            }
            long roundTick=absoluteTick-generationStart;
            if(roundTick<0)return;
            // End at exactly 260 ticks, without allowing another
            // movement after the deadline.
            if(roundTick<DEADLINE){
                // The crew shares one traffic plan; closer-to-goal
                // golems clear the corridor before their followers.
                // Genetics, real collisions and end conditions stay
                // unchanged, only cooperative dispatch order differs.
                List<Candidate> ordered=new ArrayList<>(bots);
                ordered.sort(Comparator
                    .comparingDouble(
                        BuilderEvolutionAltCourseGameTest::remainingPathMeters)
                    .thenComparingInt(b->b.index));
                for(Candidate b:ordered){
                    b.controlTick=(int)roundTick+1;
                    walkOneTick(helper,b,bots);
                    b.captureMovement((int)roundTick+1);
                    finishLocalEpisode(b,false);
                }
            }
            if(roundTick<DEADLINE)return;
            for(Candidate b:bots){
                int expected=b.arrivedTick<0?DEADLINE:b.arrivedTick;
                int actual=b.mob.tickCount-b.initialEntityTicks;
                if(actual<expected-2){
                    ended=true;releaseSimulation.run();
                    for(Candidate peer:bots)if(peer.mob.isAlive())peer.mob.discard();
                    helper.fail("Incomplete native entity simulation: slot="+b.index
                            +" expectedTicks="+expected+" actualTicks="+actual
                            +"; refusing to learn from this round");
                    return;
                }
            }
            int finished=0,finishedTarget=0;
            long doorPlans=bots.stream().filter(b->b.initialRouteUsesDoor)
                    .count();
            long doorCrossings=bots.stream().filter(b->b.doorTicks>0)
                    .count();
            double mean=0;
            int collisions=0,jumps=0;
            int fastest=DEADLINE+1,slowest=0;
            List<Candidate> ranked=new ArrayList<>(bots);
            ranked.sort(Comparator.comparingDouble(Candidate::fitness).reversed());
            StringBuilder blockers=new StringBuilder();
            for(Candidate b:bots){
                if(b.arrivedTick>=0&&b.arrivedTick<=DEADLINE){
                    finished++;
                    if(b.arrivedTick<=SPEED_TARGET_TICKS)finishedTarget++;
                    fastest=Math.min(fastest,b.arrivedTick);
                    slowest=Math.max(slowest,b.arrivedTick);
                }else if(blockers.length()<550)
                    blockers.append(" #").append(b.index)
                            .append(" step=").append(b.cursor)
                            .append("/").append(b.route.length())
                            .append(" p=").append(b.mob.position());
                mean+=b.fitness();
                collisions+=b.collisions;
                jumps+=b.jumps;
                finishLocalEpisode(b,true);
                if(!consistencyMode){
                strategyMemory.observe(new NavigationStrategyMemory.Observation(
                        strategySession+":g"+generation+":slot"+b.index,
                        (long)(completedGenerations+1)*10+b.index,
                        b.strategyContext,b.strategyDecision.strategy(),
                        b.arrivedTick,DEADLINE,b.stationaryTicks,b.collisions,
                        b.measuredDistance));
                NavigationStrategyMemory.Summary evidence=strategyMemory.summary(
                        b.strategyContext,b.strategyDecision.strategy());
                System.out.println("[ALT STRATEGY OUTCOME] slot="+b.index
                        +" context="+b.strategyContext
                        +" strategy="+b.strategyDecision.strategy()
                        +" arrival="+b.arrivedTick
                        +" stationary="+b.stationaryTicks
                        +" recentTrips="+evidence.trials()
                        +" recentFinished="+evidence.finished()
                        +" meanReward="+evidence.meanReward());
                }
                if(!consistencyMode)for(var edge:b.realBlockFailures.entrySet())
                    collisionMemory.merge(edge.getKey(),edge.getValue(),
                            (a,c)->Math.min(100,a+c));
                if(!consistencyMode&&b.fitness()>championScores[b.index]){
                    champions[b.index]=b.dna;
                    championScores[b.index]=b.fitness();
                    // An INDIVIDUAL improvement is retained even if the
                    // other nine workers fail, without team qualification.
                }
            }
            if(!consistencyMode){
                // Parents are the THREE best real entities that just ran.
                // Historical personal bests remain separately protected.
                knowledgePool.clear();
                for(Candidate b:bots)updateKnowledgePool(this,b);
                if(knowledgePool.size()!=3)
                    throw new IllegalStateException("Expected three real generation parents");
            }
            bestFinished=Math.max(bestFinished,finished);
            bestFinishedThisSession=Math.max(bestFinishedThisSession,finished);
            if(finishedTarget==bots.size())targetQualifiedRounds++;
            if(consistencyMode){
                List<NavigationRuleMemory.Episode> episodes=bots.stream()
                        .flatMap(b->b.localEpisodes.stream()).toList();
                double meanArrival=bots.stream().mapToInt(b->b.arrivedTick<0?DEADLINE:b.arrivedTick)
                        .average().orElse(DEADLINE);
                ruleMemory.observe(new NavigationRuleMemory.Trial(
                        strategySession+":round"+generation,completedGenerations+1L,
                        strategySession+":block"+(generation/6),(generation%6)/2,generation%2==1,
                        comparisonFingerprint,finished,meanArrival,episodes));
                comparisonFinishes.add(finished);
                System.out.println("[ALT MATCHED TRIAL] "+experimentMetadata()
                        +" finished="+finished+" localEpisodes="+episodes.size());
                if(generation%6==5)System.out.println("[ALT RULE BINDINGS] "+ruleMemory.bindings());
            }
            if(!consistencyMode)System.out.println("[ALT INDEPENDENT LEARNING] gen="+generation
                    +" pooledSlots="+knowledgePool.stream()
                        .map(k->Integer.toString(k.slot())).toList()
                    +" pooledFitness="+knowledgePool.stream()
                        .map(Knowledge::fitness).toList());
            System.out.println("[TIMETRIAL REAL GENETICS] gen="+generation
                    +" realBuilderGolems="+bots.size()
                    +" finish13s="+finished+"/"+bots.size()
                    +" finish11_2s="+finishedTarget+"/"+bots.size()
                    +" doorPlans="+doorPlans
                    +" doorCrossings="+doorCrossings
                    +" best="+bestFinished
                    +" bestThisSession="+bestFinishedThisSession
                    +" meanFitness="+Math.round(mean/bots.size())
                    +" collisions="+collisions+" jumps="+jumps
                    +" learnedBlockEdges="+collisionMemory.size()
                    +" slabAscents="+bots.stream()
                        .mapToInt(b->b.successfulSlabAscents).sum()
                    +" slabEntryStalls="+bots.stream()
                        .mapToInt(b->b.slabEntryStalls).sum()
                    +" doorEntryAlignTicks="+bots.stream()
                        .mapToInt(b->b.doorEntryAlignTicks).sum()
                    +" doorEntryStalls="+bots.stream()
                        .mapToInt(b->b.doorEntryStalls).sum()
                    +" doorStateRetries="+bots.stream()
                        .mapToInt(b->b.doorStateRetries).sum()
                    +" fastestTicks="+(finished>0?fastest:-1)
                    +" slowestTicks="+(finished>0?slowest:-1)
                    +" stalled="+blockers);
            // Three winners FROM THIS physical generation are exact
            // parents; each has precisely TWO locally mutated offspring.
            // Retain their route policies/templates across generations.
            FamilyPlan families=consistencyMode?null:
                    breedFamilies(knowledgePool,rng,SELECTED_BOTS.length);
            List<Genome> offspring=consistencyMode
                    ?new ArrayList<>(pendingExploration)
                    :new ArrayList<>(families.genes());
            if(!consistencyMode){
                generationStrategies=new ArrayList<>(families.policies());
                generationRoutes=new ArrayList<>(families.parentRoutes());
                generationFamilyOrigins=new ArrayList<>(families.parentSlots());
                System.out.println("[ALT NINE FAMILY BREED] generation="+generation
                        +" parentSlots="+knowledgePool.stream().map(Knowledge::slot).toList()
                        +" lineages="+generationFamilyOrigins
                        +" policies="+generationStrategies);
            }
            // The complete 10/10 TEAM must survive intact, whether or
            // not it beats the protected historical speed record.
            // Crossing ten individually successful genes can produce a
            // very different, unsuccessful ensemble.
            if(finished==bots.size()){
                lastSuccessfulPopulation=new ArrayList<>();
                lastSuccessfulArrivalTicks=new ArrayList<>();
                for(Candidate b:bots){
                    lastSuccessfulPopulation.add(b.dna);
                    lastSuccessfulArrivalTicks.add(b.arrivedTick);
                }
                lastSuccessfulGeneration=completedGenerations+1;
                lastSuccessfulNativeVerified=true;
                System.out.println("[TIMETRIAL MEMORY] Retained entire "
                        +"latest VERIFIED 9/9 team independently of"
                        +" best-ever champion and mutated offspring");
            }
            // Data durability is NOT conditional on success. Even a
            // failed 24-generation experiment contributes nextPopulation,
            // the best motor genes per starting lane and observed
            // blocked edges for the following server run.
            TrainingMemory.save(this,bots,finished,slowest,
                    mean/bots.size(),offspring);
            completedGenerations++;

            // Always discard all real entities after their actual 13s
            // trial; the next generation must spawn physical golems.
            for(Candidate b:bots)if(b.mob.isAlive())b.mob.discard();
            if(!consistencyMode&&finishedTarget==bots.size()){
                for(Candidate b:bots)
                    System.out.println("[TIMETRIAL REAL CHAMPION] slot="+b.index
                            +" arrivedTicks="+b.arrivedTick
                            +" route="+b.route
                            +" genes="+b.dna);
                System.out.println("[TIMETRIAL MEMORY] Next validation "
                        +"run will START with all nine verified winning "
                        +"genes; exploratory offspring saved separately");
                ended=true;
                releaseSimulation.run();
                helper.succeed();return;
            }
            if(generation+1>=GENERATIONS){
                ended=true;
                releaseSimulation.run();
                if(consistencyMode){
                    System.out.println("[ALT CONSISTENCY REPORT] finishes="+comparisonFinishes
                            +" retainedRules="+ruleMemory.bindings()
                            +" qualified224TickRounds="+targetQualifiedRounds);
                    if(targetQualifiedRounds==GENERATIONS){helper.succeed();return;}
                }
                helper.fail("Physical evolution did not qualify all 9"
                        +" by 11.2s after "+GENERATIONS
                        +" generations. Best REAL 13s result="+bestFinishedThisSession
                        +"/"+bots.size()
                        +", latest="+finished+"/"+bots.size()+"; "
                        +"checkpoint saved for future runs; "+blockers);
                return;
            }
            // Continue from this generation's three best physical
            // routes and controllers, plus their six mutated children.
            if(!consistencyMode)generationGenes=offspring;
            generation++;
            generationStart=absoluteTick+1;
            startRound();
        }
    }


    /**
     * A turning/merging worker must yield before the real 0.65-wide
     * hitboxes overlap, even when the other golem is heading a
     * different direction. Old logic waited until distance < 0.52.
     * This evaluates the same live-entity courtesy calculation used
     * by the main physical trial, with no artificial movement.
     */
    @GameTest(maxTicks=80,padding=18,skyAccess=true)
    public void realBuildersYieldBeforePhysicalLaneMerge(
            GameTestHelper helper){
        for(int x=1;x<=10;x++)for(int z=1;z<=6;z++){
            helper.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=5;y++)helper.setBlock(x,y,z,Blocks.AIR);
        }
        ServerLevel world=helper.getLevel();
        BuilderGolem lead=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        BuilderGolem follow=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        BlockPos a=helper.absolutePos(new BlockPos(5,2,3));
        BlockPos b=helper.absolutePos(new BlockPos(6,2,3));
        lead.setPos(a.getX()+.5,a.getY(),a.getZ()+.5);
        follow.setPos(b.getX()+.35,b.getY(),b.getZ()+.5);
        world.addFreshEntity(lead);
        world.addFreshEntity(follow);
        Candidate front=new Candidate(0,lead,"N",5,3,
                SAVED_PHYSICAL_CHAMPIONS[0],Map.of());
        Candidate behind=new Candidate(1,follow,"WWWW",6,3,
                SAVED_PHYSICAL_CHAMPIONS[1],Map.of());
        front.originX=a.getX()-5;front.originZ=a.getZ()-3;
        behind.originX=front.originX;behind.originZ=front.originZ;
        double gap=Math.hypot(lead.getX()-follow.getX(),
                lead.getZ()-follow.getZ());
        TrafficMotion motion=coordinateTraffic(behind,
                List.of(front,behind),-.2,0);
        if(gap<=.65||!motion.yielding()
                ||motion.vx()>=0||motion.vx()<=-.199
                ||motion.leaderSlot()!=0)
            helper.fail("Early slab-approach merge priority rejected: "
                    +" gap="+gap+" yield="+motion.yielding()
                    +" adjusted="+motion.vx());
        else helper.succeed();
        lead.discard();
        follow.discard();
    }

    /**
     * Two ACTUAL Builder entities in a one-cell corridor: the forward
     * one must have right-of-way while the trailing one walks without
     * clipping, shoving or triggering backward stuck-recovery.
     */
    @GameTest(maxTicks=100,padding=18,skyAccess=true)
    public void realBuildersQueueWithoutShoving(GameTestHelper helper){
        for(int x=1;x<=11;x++)for(int z=1;z<=5;z++){
            helper.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=5;y++)helper.setBlock(x,y,z,Blocks.AIR);
        }
        ServerLevel world=helper.getLevel();
        BuilderGolem lead=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        BuilderGolem follow=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        BlockPos a=helper.absolutePos(new BlockPos(6,2,3));
        BlockPos b=helper.absolutePos(new BlockPos(7,2,3));
        lead.setPos(a.getX()+.50,a.getY(),a.getZ()+.5);
        follow.setPos(b.getX()+.30,b.getY(),b.getZ()+.5);
        world.addFreshEntity(lead);
        world.addFreshEntity(follow);
        Candidate first=new Candidate(0,lead,"WWWWW",6,3,
                SAVED_PHYSICAL_CHAMPIONS[0],Map.of());
        Candidate second=new Candidate(1,follow,"WWWWW",7,3,
                SAVED_PHYSICAL_CHAMPIONS[1],Map.of());
        first.originX=a.getX()-6;first.originZ=a.getZ()-3;
        second.originX=first.originX;second.originZ=first.originZ;
        List<Candidate> crew=List.of(first,second);
        // Opposite-direction traffic 0.8 blocks away is NOT a
        // same-lane queue. Regression for the original green start
        // positions: never freeze opposing walkers prematurely.
        if(coordinateTraffic(first,crew,.2,0).queue()){
            helper.fail("Opposite-direction golems were misidentified"
                    +" as same-lane followers");
            lead.discard();follow.discard();return;
        }
        final int[] ticks={0},yielded={0};
        helper.onEachTick(()->{
            if(ticks[0]++>=15){
                if(yielded[0]<1)helper.fail("Follower never yielded to leader");
                else if(lead.getX()>=a.getX()+.5-1.0)
                    helper.fail("Leader could not make forward progress");
                else helper.succeed();
                lead.discard();follow.discard();return;
            }
            // Native movement only. Sort front-to-back before moving.
            TrafficMotion ahead=coordinateTraffic(first,crew,-.2,0);
            TrafficMotion behind=coordinateTraffic(second,crew,-.2,0);
            if(behind.yielding())yielded[0]++;
            if(!ahead.yielding()){
                lead.move(MoverType.SELF,new Vec3(ahead.vx(),-.08,ahead.vz()));
            }
            if(behind.vx()<-.001)
                follow.move(MoverType.SELF,new Vec3(
                        behind.vx(),-.08,behind.vz()));
            if(follow.getX()<lead.getX()+.64){
                helper.fail("Golems overlapped, convoy order not respected");
                lead.discard();follow.discard();
            }
        });
    }

    /**
     * Regression for the exact real OPEN birch door, which the A*
     * planner previously misclassified as a one-block floor riser.
     * This test asserts availability and natural route discovery.
     * The separate real door traversal test proves entity collision.
     */
    @GameTest(maxTicks=120,padding=64,skyAccess=true)
    public void realBuilderPlannerDiscoversOpenBirchDoor(
            GameTestHelper helper){
        fillOriginalCourse(helper);
        ServerLevel world=helper.getLevel();
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        BlockPos start=pos(helper,5,2,10);
        mob.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        world.addFreshEntity(mob);
        double floor=standingY(helper,world,4,10);
        boolean physicallyOpen=physicalCellOpen(helper,world,mob,4,10);
        String planned;
        try{
            planned=planRoute(5,10,SAVED_PHYSICAL_CHAMPIONS[0],
                    Map.of(),helper,world,mob);
        }catch(RuntimeException e){
            helper.fail("Real door cannot be considered by planner: "+e);
            mob.discard();
            return;
        }
        if(!Double.isFinite(floor)||!physicallyOpen
                ||!routeEntersDoor(5,10,planned)){
            helper.fail("Open door erroneously excluded by planner: "
                    +" floor="+floor+" bodyOpen="+physicallyOpen
                    +" route="+planned);
        }else{
            System.out.println("[ALT OPEN DOOR REGRESSION] Planner "
                    +"discovered open door via real physical geometry: "
                    +planned);
            helper.succeed();
        }
        mob.discard();
    }

    /**
     * The original x=15 ground slabs have open air above. The old AI
     * incorrectly stalled because direct horizontal movement lost ground
     * contact. Verify this legal step separately from a deliberately
     * constructed low-ceiling slab (regression-only fixture).
     */

    /**
     * The apparent short passage under the top slabs is too short for
     * a 1.6-tall real golem. Validate THREE overhead slab tiles as
     * blocked without rejecting the adjacent open ground corridor.
     */
    @GameTest(maxTicks=120,padding=64,skyAccess=true)
    public void realBuilderPlansAroundUpperSlabHeadroomHole(
            GameTestHelper helper){
        fillOriginalCourse(helper);
        ServerLevel world=helper.getLevel();
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        BlockPos start=pos(helper,13,2,5);
        mob.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        world.addFreshEntity(mob);
        for(int z=5;z<=7;z++){
            if(physicalCellOpen(helper,world,mob,12,z)
                    ||bodySweepClear(helper,world,mob,13,z,12,z)){
                helper.fail("Overhead slab opening wrongly marked passable at "
                        +"x12 z"+z);
                mob.discard();
                return;
            }
        }
        boolean open=physicalCellOpen(helper,world,mob,12,9)
                &&bodySweepClear(helper,world,mob,13,9,12,9);
        boolean bottom=bodySweepClear(helper,world,mob,16,5,15,5)
                &&precisionTransition(helper,world,16,5,15,5);
        if(!open||!bottom){
            helper.fail("Open path or legal bottom-slab transition rejected "
                    +open+"/"+bottom);
        }else{
            System.out.println("[ALT OVERHEAD SLAB] blocked all 3 low"
                +" openings; physical open path and 0.5-step valid");
            helper.succeed();
        }
        mob.discard();
    }

    /**
     * The original broad 3x6 bottom-slab field is a legal SHORTCUT.
     * Assert both the low-slab entry/exit and a continuous same-height
     * diagonal atop the slabs while disallowing diagonal cuts across
     * the 0.5-high edge. No artificial track blocks are added.
     */
    @GameTest(maxTicks=120,padding=64,skyAccess=true)
    public void realBuilderPrefersWideGroundSlabShortcut(
            GameTestHelper helper){
        fillOriginalCourse(helper);
        ServerLevel world=helper.getLevel();
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        BlockPos at=pos(helper,9,2,5);
        mob.setPos(at.getX()+.5,at.getY(),at.getZ()+.5);
        world.addFreshEntity(mob);
        boolean plateauDiagonal=diagonalBodyClear(helper,world,mob,
                6,4,7,5);
        boolean edgeDiagonal=diagonalBodyClear(helper,world,mob,
                9,4,8,5);
        // Stepping up and back down needs careful hitbox alignment;
        // cruising across two fully supported open-air slab tiles does not.
        // This guards against restoring an expensive per-slab slowdown.
        boolean plateauCruise=!precisionTransition(helper,world,7,4,7,5);
        boolean stepUpPrecision=precisionTransition(helper,world,9,5,8,5);
        boolean stepDownPrecision=precisionTransition(helper,world,8,5,9,5);
        String planned=planRouteTo(9,5,SAVED_PHYSICAL_CHAMPIONS[0],
                Map.of(new Edge(9,5,8,5),100),helper,world,mob,5,5);
        int x=9,z=5,slabCount=0;
        for(int i=0;i<planned.length();i++){
            int[] p=next(x,z,planned.charAt(i));
            x=p[0];z=p[1];
            if(x>=6&&x<=8&&z>=2&&z<=7)slabCount++;
        }
        System.out.println("[ALT WIDE SLAB ROUTING] route="+planned
                +" slabCount="+slabCount
                +" levelDiagonal="+plateauDiagonal
                +" illegalEdgeDiagonal="+edgeDiagonal
                +" plateauCruise="+plateauCruise
                +" stepUpPrecision="+stepUpPrecision
                +" stepDownPrecision="+stepDownPrecision);
        if(!plateauDiagonal||edgeDiagonal||!plateauCruise
                ||!stepUpPrecision||!stepDownPrecision||slabCount<3
                ||planned.length()>6||x!=5||z!=5)
            helper.fail("Pathfinder is still avoiding safe slab"
                +" shortcut, or allowing a blocked half-slab corner:"
                +" route="+planned+" slabs="+slabCount
                +" plateau="+plateauDiagonal
                +" edge="+edgeDiagonal);
        else helper.succeed();
        mob.discard();
    }

    /**
     * Validate the discovered slab-then-wooden-door alternative on the
     * original real-collision fixture. This test checks legal strategy
     * availability, NOT successful timing (that requires ten live bots).
     * No recorded route is injected or made mandatory in evolution.
     */
    @GameTest(maxTicks=120,padding=64,skyAccess=true)
    public void realBuilderDiscoversWideSlabThenDoorAlternative(
            GameTestHelper helper){
        fillOriginalCourse(helper);
        ServerLevel world=helper.getLevel();
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        BlockPos at=pos(helper,24,2,1);
        mob.setPos(at.getX()+.5,at.getY(),at.getZ()+.5);
        world.addFreshEntity(mob);
        Genome dna=SAVED_PHYSICAL_CHAMPIONS[0];
        String route=exploratoryRoute(24,1,dna,Map.of(),
                helper,world,mob,Map.of(),true);
        boolean slabDoor=routeCrossesSlabsThenDoor(24,1,route);
        System.out.println("[ALT SLAB THEN DOOR ROUTING] "
                +"legalCombinedRoute="+slabDoor
                +" plannedMeters="+routeMeters(route)
                +" traversesDoor="+routeEntersDoor(24,1,route));
        mob.discard();
        if(!slabDoor)
            helper.fail("Could not discover legal slab-to-door shortcut"
                    +" on original collision geometry");
        else helper.succeed();
    }

    /**
     * Movement is scored from an ACTUAL Builder entity walking
     * horizontally onto, ACROSS and off the original bottom slabs.
     * A continuous slab surface does not require jumping or detours.
     */
    /**
     * Full-maze route discovery: slab savings must be usable all the
     * way to the GREEN finish without turning the successful shortcut
     * into a forced slow walk through the birch door at z=10.
     * We still independently test door accessibility and can explore
     * it using GOLEMS_EVOLUTION_MODE=explore.
     */
    @GameTest(maxTicks=120,padding=64,skyAccess=true)
    public void realBuilderFindsGlobalBottomSlabShortcutWithoutDoor(
            GameTestHelper helper){
        fillOriginalCourse(helper);
        ServerLevel world=helper.getLevel();
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        BlockPos at=pos(helper,16,2,5);
        mob.setPos(at.getX()+.5,at.getY(),at.getZ()+.5);
        world.addFreshEntity(mob);
        String route=planRoute(16,5,SAVED_PHYSICAL_CHAMPIONS[0],
                Map.of(new Edge(16,5,15,5),100,new Edge(9,5,8,5),100),helper,world,mob);
        int x=16,z=5,field=0;
        for(int i=0;i<route.length();i++){
            int[] nextStep=next(x,z,route.charAt(i));
            x=nextStep[0];z=nextStep[1];
            if(x>=6&&x<=8&&z>=2&&z<=7)field++;
        }
        boolean door=routeEntersDoor(16,5,route);
        System.out.println("[ALT GLOBAL SLAB ROUTING] field="+field
                +" door="+door+" steps="+route.length()
                +" route="+route);
        if(field<2||door||MAZE[z].charAt(x)!='G'){
            helper.fail("Global A* failed to exploit physical slab"
                    +" shortcut without wasting time at the door:"
                    +" field="+field+" door="+door
                    +" route="+route);
        }else helper.succeed();
        mob.discard();
    }

    /**
     * Regression using the ACTUAL evolved movement controller, not the
     * earlier manually driven MoverType.SELF slab test. Start offset
     * towards the slab's outer edge, dock the whole 0.65-wide hitbox,
     * ascend normally, walk the big field, and descend without a jump.
     * This catches regressions invisible to the simple direct-move test.
     */
    private static void verifyControllerCrossesSlabField(
            GameTestHelper helper,int lane,double lateralOffset){
        fillOriginalCourse(helper);
        ServerLevel world=helper.getLevel();
        BlockPos at=pos(helper,9,2,lane),origin=pos(helper,0,2,0);
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(at.getX()+.5,at.getY(),at.getZ()+.5+lateralOffset);
        mob.setNoGravity(false);
        world.addFreshEntity(mob);
        Candidate b=new Candidate(0,mob,"WWWW",9,lane,
                SAVED_PHYSICAL_CHAMPIONS[0],Map.of());
        b.originX=origin.getX();b.originZ=origin.getZ();
        b.birthTick=helper.getTick();
        final int[] ticks={0},slabTicks={0};
        final boolean[] done={false};
        helper.onEachTick(()->{
            if(done[0])return;
            if(!mob.isAlive()){
                done[0]=true;
                helper.fail("Slab controller walker disappeared");return;
            }
            if(mob.getY()>at.getY()+.43)slabTicks[0]++;
            if(mob.getX()<pos(helper,5,2,lane).getX()+.67
                    &&Math.abs(mob.getY()-at.getY())<.14){
                done[0]=true;
                if(slabTicks[0]<4||b.jumps>0)
                    helper.fail("Controller did not use legal grounded slab"
                            +" step, lane="+lane+" slabTicks="+slabTicks[0]
                            +" jumps="+b.jumps);
                else{
                    System.out.println("[ALT CONTROLLER SLAB FIELD] lane="+lane
                            +" offset="+lateralOffset+" ticks="+ticks[0]
                            +" elevated="+slabTicks[0]
                            +" jumps="+b.jumps);
                    helper.succeed();
                }
                mob.discard();return;
            }
            if(ticks[0]++>85){
                done[0]=true;
                helper.fail("Physical controller could not enter/cross slab"
                        +" plateau lane="+lane+" offset="+lateralOffset
                        +" position="+mob.position()+" cursor="+b.cursor
                        +" collisions="+b.collisions);
                mob.discard();return;
            }
            b.controlTick++;
            walkOneTick(helper,b,List.of(b));
        });
    }

    @GameTest(maxTicks=135,padding=64,skyAccess=true)
    public void realControllerWalksEntireWideSlabFieldOffCenter(
            GameTestHelper helper){
        verifyControllerCrossesSlabField(helper,5,.27);
    }

    @GameTest(maxTicks=135,padding=64,skyAccess=true)
    public void realControllerWalksOuterSlabLaneWithoutCornerClipping(
            GameTestHelper helper){
        verifyControllerCrossesSlabField(helper,7,.27);
    }

    @GameTest(maxTicks=115,padding=64,skyAccess=true)
    public void realBuilderWalksAcrossWideBottomSlabField(
            GameTestHelper helper){
        fillOriginalCourse(helper);
        ServerLevel world=helper.getLevel();
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        BlockPos at=pos(helper,9,2,5);
        BlockPos end=pos(helper,5,2,5);
        mob.setPos(at.getX()+.5,at.getY(),at.getZ()+.5);
        mob.setNoGravity(false);
        world.addFreshEntity(mob);
        final int[] ticks={0},onPlateau={0};
        helper.onEachTick(()->{
            if(!mob.isAlive()){
                helper.fail("Slab field walker disappeared");return;
            }
            if(mob.getY()>at.getY()+.45)onPlateau[0]++;
            if(mob.getX()<end.getX()+.56
                    &&Math.abs(mob.getY()-end.getY())<.12){
                if(onPlateau[0]<3)
                    helper.fail("Did not physically walk over slab field");
                else{
                    System.out.println("[ALT WIDE SLAB WALK] crossed in "
                        +ticks[0]+" ticks, slabTicks="+onPlateau[0]);
                    helper.succeed();
                }
                mob.discard();return;
            }
            if(ticks[0]++>=75){
                helper.fail("Real golem stuck at wide bottom slabs: "
                        +mob.position()+" elevatedTicks="+onPlateau[0]);
                mob.discard();return;
            }
            mob.getNavigation().stop();
            mob.move(MoverType.SELF,new Vec3(-.2,-.08,0));
        });
    }

    @GameTest(maxTicks=120,padding=64,skyAccess=true)
    public void realBuilderRejectsLowCeilingSlabButCanUseOpenHalfSlab(
            GameTestHelper helper){
        fillOriginalCourse(helper);
        ServerLevel level=helper.getLevel();
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        BlockPos spawn=pos(helper,16,2,4);
        mob.setPos(spawn.getX()+.5,spawn.getY(),spawn.getZ()+.5);
        level.addFreshEntity(mob);
        boolean exposed4=physicalCellOpen(helper,level,mob,15,4);
        boolean exposed5=physicalCellOpen(helper,level,mob,15,5);
        // Add an overhead obstacle ONLY inside this isolated regression.
        // The actual scored user track is never altered.
        helper.setBlock(X_OFFSET+15,3,Z_OFFSET+5,
                Blocks.BIRCH_SLAB.defaultBlockState()
                   .setValue(SlabBlock.TYPE,SlabType.TOP));
        boolean roofed5=physicalCellOpen(helper,level,mob,15,5);
        System.out.println("[ALT SLAB REGRESSION] open4="+exposed4
                +" open5="+exposed5+" syntheticLowCeiling="+roofed5);
        if(!exposed4||!exposed5||roofed5)
            helper.fail("Slab AABB clearance must distinguish exposed"
                +" slab from real overhead obstruction");
        else helper.succeed();
        mob.discard();
    }

    /**
     * Verify a REAL ground-only Builder steps onto a legal 0.5 slab
     * without teleporting, removing blocks or jumping. This was the
     * primary physical failure seen at x=16.325,z=6.5.
     */
    @GameTest(maxTicks=140,padding=64,skyAccess=true)
    public void realBuilderStepsOntoAltCourseBottomSlab(
            GameTestHelper helper){
        fillOriginalCourse(helper);
        ServerLevel level=helper.getLevel();
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        BlockPos spawn=pos(helper,16,2,4);
        mob.setPos(spawn.getX()+.5,spawn.getY(),spawn.getZ()+.5);
        mob.setNoGravity(false);
        level.addFreshEntity(mob);
        if(mob.maxUpStep()<.599){
            helper.fail("Native 0.6 auto-step is not enabled on real Builder");
            mob.discard();
            return;
        }
        final int[] elapsed={0};
        helper.onEachTick(()->{
            if(!mob.isAlive()){
                helper.fail("Real Builder disappeared on bottom slab");
                return;
            }
            if(mob.getX()<=pos(helper,15,2,4).getX()+.65
                    &&mob.getY()>=spawn.getY()+.48){
                System.out.println("[ALT SLAB STEP] physical grounded ascent"
                    +" succeeded in "+elapsed[0]+" ticks y="+mob.getY());
                mob.discard();
                helper.succeed();
                return;
            }
            if(elapsed[0]++>65){
                helper.fail("Builder did not step up half slab using native"
                    +" ground collision at "+mob.position()
                    +" onGround="+mob.onGround());
                mob.discard();
                return;
            }
            mob.getNavigation().stop();
            mob.move(MoverType.SELF,new Vec3(-.2,-.08,0));
        });
    }

    /**
     * Closed wooden doors must be PLANNABLE and require an actual
     * near-body opening action before native collision traversal.
     * This isolated fixture never alters the scored user's schematic.
     */
    /**
     * Use the actual evolutionary movement controller when entering the
     * real birch door slightly off-center. The old code repeatedly drove
     * its hitbox into the open door leaf and then jumped before finally
     * clearing it. A direct-move-only door test did not catch that.
     */
    @GameTest(maxTicks=100,padding=64,skyAccess=true)
    public void realControllerEntersDoorOffCenterWithoutJumping(
            GameTestHelper helper){
        fillOriginalCourse(helper);
        ServerLevel world=helper.getLevel();
        BlockPos start=pos(helper,5,2,10),origin=pos(helper,0,2,0);
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(start.getX()+.5,start.getY(),start.getZ()+.26);
        mob.setNoGravity(false);
        world.addFreshEntity(mob);
        Candidate b=new Candidate(0,mob,"WWWW",5,10,
                SAVED_PHYSICAL_CHAMPIONS[0],Map.of());
        b.originX=origin.getX();b.originZ=origin.getZ();
        b.birthTick=helper.getTick();
        final int[] ticks={0};
        final boolean[] done={false};
        helper.onEachTick(()->{
            if(done[0])return;
            if(b.arrivedTick>=0){
                done[0]=true;
                System.out.println("[ALT REAL CONTROLLER DOOR] ticks="+ticks[0]
                        +" jumps="+b.jumps
                        +" stalls="+b.doorEntryStalls
                        +" alignTicks="+b.doorEntryAlignTicks);
                if(b.jumps!=0||b.doorEntryStalls>2||ticks[0]>33)
                    helper.fail("Actual controller stuttered entering door:"
                            +" ticks="+ticks[0]+" jumps="+b.jumps
                            +" stalls="+b.doorEntryStalls);
                else helper.succeed();
                return;
            }
            if(!mob.isAlive()||ticks[0]++>40){
                done[0]=true;
                helper.fail("Actual controller failed to cross off-center "
                        +"door, ticks="+ticks[0]+" position="+mob.position()
                        +" cursor="+b.cursor+" jumps="+b.jumps
                        +" stalls="+b.doorEntryStalls);
                mob.discard();return;
            }
            b.controlTick++;
            walkOneTick(helper,b,List.of(b));
        });
    }

    /**
     * Vanilla-style door handling: a closed, hand-operable door may be
     * opened after four genuinely stalled attempts, but an OPEN door is
     * never shut to recover from a collision. Both halves must agree.
     * Teammate congestion and genuine forward movement reset the timer.
     */
    @GameTest(maxTicks=80,padding=64,skyAccess=true)
    public void realDoorRetryOpensClosedAndPreservesOpenPassage(
            GameTestHelper helper){
        fillOriginalCourse(helper);
        ServerLevel world=helper.getLevel();
        BlockPos door=pos(helper,4,2,10),start=pos(helper,5,2,10);
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        world.addFreshEntity(mob);
        Candidate runner=new Candidate(0,mob,"WWWW",5,10,
                SAVED_PHYSICAL_CHAMPIONS[0],Map.of());
        try{
            for(boolean startingOpen:new boolean[]{false,true}){
                BlockState lower=world.getBlockState(door);
                BlockState upper=world.getBlockState(door.above());
                if(!canOperateDoor(lower)||!canOperateDoor(upper))
                    throw new IllegalStateException("Real birch door missing");
                world.setBlock(door,lower.setValue(DoorBlock.OPEN,startingOpen),3);
                world.setBlock(door.above(),
                        upper.setValue(DoorBlock.OPEN,startingOpen),3);
                runner.doorNoForwardTicks=0;
                runner.observedDoorOpen=null;
                runner.lastDoorAttemptTick=-100;
                int before=runner.doorStateRetries;
                for(int tick=1;tick<=8;tick++){
                    runner.controlTick++;
                    recordDoorForwardProgress(runner,world,door,
                            mob.getX(),mob.getZ(),4,10,false,false);
                    boolean expected=startingOpen||tick>=4;
                    if(world.getBlockState(door).getValue(DoorBlock.OPEN)!=expected
                            ||world.getBlockState(door.above())
                                 .getValue(DoorBlock.OPEN)!=expected)
                        throw new IllegalStateException("Door changed incorrectly at "
                                +tick+" stalled ticks, startOpen="+startingOpen);
                }
                int expectedRetries=startingOpen?0:1;
                if(runner.doorStateRetries!=before+expectedRetries)
                    throw new IllegalStateException(
                            "Open door was flipped or closed door never opened");
                // Waiting behind another real worker cannot flip the door.
                for(int tick=0;tick<6;tick++){
                    runner.controlTick++;
                    recordDoorForwardProgress(runner,world,door,
                            mob.getX(),mob.getZ(),4,10,true,false);
                }
                if(runner.doorStateRetries!=before+expectedRetries
                        ||!world.getBlockState(door).getValue(DoorBlock.OPEN))
                    throw new IllegalStateException(
                            "A waiting worker altered the open passage");
            }
            // A real movement step resets a three-tick stalled attempt.
            runner.doorNoForwardTicks=3;
            double oldX=mob.getX(),oldZ=mob.getZ();
            mob.move(MoverType.SELF,new Vec3(-.08,-.08,0));
            if(mob.getX()>=oldX-.01)
                throw new IllegalStateException("Real approach movement failed");
            int retriesBefore=runner.doorStateRetries;
            runner.controlTick++;
            recordDoorForwardProgress(runner,world,door,
                    oldX,oldZ,4,10,false,false);
            if(runner.doorNoForwardTicks!=0
                    ||runner.doorStateRetries!=retriesBefore
                    ||!world.getBlockState(door).getValue(DoorBlock.OPEN))
                throw new IllegalStateException(
                        "Forward movement incorrectly changed open door");
            System.out.println("[ALT VILLAGER DOOR RETRY] open retained, "
                    +"closed opened, progress and queue exclusions verified");
            helper.succeed();
        }catch(IllegalStateException failure){
            helper.fail(failure.getMessage());
        }finally{mob.discard();}
    }

    /** The production WALK controller should OPEN a reachable closed
     * wooden door proactively, retain its planned path, and finish
     * without jumping or slamming the door shut during passage.
     */
    @GameTest(maxTicks=110,padding=64,skyAccess=true)
    public void realControllerRetriesClosedDoorWithoutJumping(
            GameTestHelper helper){
        fillOriginalCourse(helper);
        ServerLevel world=helper.getLevel();
        BlockPos door=pos(helper,4,2,10);
        BlockState lower=world.getBlockState(door);
        BlockState upper=world.getBlockState(door.above());
        world.setBlock(door,lower.setValue(DoorBlock.OPEN,false),3);
        world.setBlock(door.above(),
                upper.setValue(DoorBlock.OPEN,false),3);
        BlockPos at=pos(helper,5,2,10),origin=pos(helper,0,2,0);
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(at.getX()+.5,at.getY(),at.getZ()+.5);
        mob.setNoGravity(false);world.addFreshEntity(mob);
        Candidate b=new Candidate(0,mob,"WWWW",5,10,
                SAVED_PHYSICAL_CHAMPIONS[0],Map.of());
        b.originX=origin.getX();b.originZ=origin.getZ();
        b.birthTick=helper.getTick();
        final int[] ticks={0};
        final boolean[] done={false};
        helper.onEachTick(()->{
            if(done[0])return;
            if(b.arrivedTick>=0){
                done[0]=true;
                System.out.println("[ALT CLOSED DOOR PHYSICAL RETRY] "
                        +"ticks="+ticks[0]+" flips="+b.doorStateRetries
                        +" stalls="+b.doorEntryStalls+" jumps="+b.jumps);
                if(b.jumps>0||!world.getBlockState(door)
                        .getValue(DoorBlock.OPEN)||ticks[0]>55)
                    helper.fail("Villager-style closed-door crossing failed");
                else helper.succeed();
                return;
            }
            if(!mob.isAlive()||ticks[0]++>60){
                done[0]=true;
                helper.fail("Closed-door controller stuck after "
                        +ticks[0]+" ticks, toggles="+b.doorStateRetries
                        +" position="+mob.position());
                mob.discard();return;
            }
            b.controlTick++;
            walkOneTick(helper,b,List.of(b));
        });
    }

    @GameTest(maxTicks=140,padding=64,skyAccess=true)
    public void realBuilderOpensClosedDoorAndWalksThrough(
            GameTestHelper helper){
        fillOriginalCourse(helper);
        ServerLevel world=helper.getLevel();
        BlockPos door=pos(helper,4,2,10);
        BlockState lower=world.getBlockState(door);
        BlockState upper=world.getBlockState(door.above());
        if(!canOperateDoor(lower)||!canOperateDoor(upper)){
            helper.fail("Original birch door fixture is missing");return;
        }
        world.setBlock(door,lower.setValue(DoorBlock.OPEN,false),3);
        world.setBlock(door.above(),
                upper.setValue(DoorBlock.OPEN,false),3);
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        BlockPos spawn=pos(helper,5,2,10);
        mob.setPos(spawn.getX()+.5,spawn.getY(),spawn.getZ()+.5);
        mob.setNoGravity(false);
        world.addFreshEntity(mob);
        boolean candidate=physicalCellOpen(helper,world,mob,4,10);
        String route=planRouteTo(5,10,SAVED_PHYSICAL_CHAMPIONS[0],
                Map.of(),helper,world,mob,4,10);
        if(!candidate||route.isEmpty()||route.charAt(0)!='W'){
            mob.discard();
            helper.fail("Closed wooden door rejected as openable route: "+route);
            return;
        }
        final int[] tick={0},openedAt={-1};
        final double[] safeLane={Double.NaN};
        helper.onEachTick(()->{
            if(!mob.isAlive())return;
            if(tick[0]++>70){
                helper.fail("Real Builder could not traverse opened door");
                mob.discard();return;
            }
            if(!world.getBlockState(door).getValue(DoorBlock.OPEN)){
                if(openDoorFromReach(world,mob,door))
                    openedAt[0]=tick[0];
            }
            if(world.getBlockState(mob.blockPosition().below()).is(RED)){
                if(openedAt[0]<0
                        ||!world.getBlockState(door.above())
                             .getValue(DoorBlock.OPEN))
                    helper.fail("Worker crossed without opening both halves");
                else {
                    System.out.println("[ALT CLOSED DOOR WALK] opened tick="
                            +openedAt[0]+" crossed tick="+tick[0]);
                    helper.succeed();
                }
                mob.discard();return;
            }
            // Maintain a single verified centerline through BOTH
            // faces of the opened door, as the production controller
            // does. A blind central trajectory may clip its door leaf.
            if(Double.isNaN(safeLane[0]))
                safeLane[0]=safeDoorLane(world,mob,door,
                        SAVED_PHYSICAL_CHAMPIONS[0],false);
            double goalX=pos(helper,2,2,10).getX()+.5;
            double goalZ=door.getZ()+.5+safeLane[0];
            double dx=goalX-mob.getX(),dz=goalZ-mob.getZ();
            double distance=Math.hypot(dx,dz);
            double advance=Math.min(.2,distance);
            mob.getNavigation().stop();
            mob.move(MoverType.SELF,new Vec3(
                    advance*dx/Math.max(.00001,distance),-.08,
                    advance*dz/Math.max(.00001,distance)));
        });
    }

    /**
     * Standalone single-open-birch-door crossing with the physical Builder
     * body. No teleport, player-like privileges, breaking or flight.
     */
    @GameTest(maxTicks=140,padding=64,skyAccess=true)
    public void realBuilderCrossesExistingSingleOpenBirchDoor(
            GameTestHelper helper){
        fillOriginalCourse(helper);
        ServerLevel level=helper.getLevel();
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        BlockPos start=pos(helper,5,2,10);
        BlockPos door=pos(helper,4,2,10);
        mob.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        mob.setNoGravity(false);
        level.addFreshEntity(mob);
        double shift=safeDoorLane(level,mob,door,
                SAVED_PHYSICAL_CHAMPIONS[0],false);
        final int[] attempts={0};
        helper.onEachTick(()->{
            if(!mob.isAlive()){
                helper.fail("Live Builder disappeared while crossing birch door");
                return;
            }
            if(level.getBlockState(mob.blockPosition().below()).is(RED)){
                System.out.println("[ALT DOOR REGRESSION] physical open door"
                    +" passed in "+attempts[0]+" ticks offset="+shift);
                mob.discard();
                helper.succeed();
                return;
            }
            if(attempts[0]++>95){
                helper.fail("Single birch door blocked physical Builder at "
                    +mob.position()+" shift="+shift);
                mob.discard();
                return;
            }
            // Preserve the chosen lane through BOTH faces of the door.
            double tx=pos(helper,2,2,10).getX()+.5;
            double tz=door.getZ()+.5+shift;
            double dx=tx-mob.getX(),dz=tz-mob.getZ();
            double distance=Math.hypot(dx,dz);
            double step=Math.min(.2,distance);
            mob.getNavigation().stop();
            mob.move(MoverType.SELF,new Vec3(
                    step*dx/Math.max(.00001,distance),0,
                    step*dz/Math.max(.00001,distance)));
        });
    }

    /**
     * Separate REAL-ENTITY physics regression: a Builder must travel
     * diagonally while the native collision solver steps up a bottom
     * half-slab (0.5 blocks), WITHOUT receiving any jump command.
     * An ordinary full-height block behind the slab remains untouched.
     */
    @GameTest(maxTicks=160,padding=12,skyAccess=true)
    public void realBuilderWalksDiagonallyOntoHalfSlabWithoutJump(
            GameTestHelper helper){
        for(int x=1;x<=9;x++)for(int z=1;z<=9;z++){
            helper.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=5;y++)helper.setBlock(x,y,z,Blocks.AIR);
        }
        helper.setBlock(6,2,6,Blocks.OAK_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE,SlabType.BOTTOM));
        // This wall ensures we're not simply relying on a blanket
        // collision bypass to walk through full-height geometry.
        helper.setBlock(8,2,8,Blocks.OAK_PLANKS);
        helper.setBlock(8,3,8,Blocks.OAK_PLANKS);
        ServerLevel world=helper.getLevel();
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        BlockPos spawn=helper.absolutePos(new BlockPos(3,2,3));
        BlockPos goal=helper.absolutePos(new BlockPos(6,2,6));
        mob.setPos(spawn.getX()+.5,spawn.getY(),spawn.getZ()+.5);
        mob.setNoGravity(false);
        world.addFreshEntity(mob);
        if(Math.abs(mob.maxUpStep()-.6)>.0001){
            helper.fail("Builder auto-step must be 0.6 blocks, got "
                    +mob.maxUpStep());
            return;
        }
        final int[] diagonalMoves={0};
        helper.onEachTick(()->{
            if(!mob.isAlive()){
                helper.fail("Diagonal ground trial golem disappeared");
                return;
            }
            double dx=goal.getX()+.5-mob.getX();
            double dz=goal.getZ()+.5-mob.getZ();
            double distance=Math.hypot(dx,dz);
            if(distance<.16&&mob.getY()>=goal.getY()+.45){
                if(diagonalMoves[0]<6)
                    helper.fail("Golem did not really traverse diagonally");
                else helper.succeed();
                mob.discard();
                return;
            }
            if(helper.getTick()>100){
                helper.fail("Actual Builder could not auto-step .5-high slab"
                        +" after "+diagonalMoves[0]+" diagonal moves"
                        +" position="+mob.position());
                mob.discard();
                return;
            }
            double vx=Math.min(.2,distance)*dx/Math.max(.000001,distance);
            double vz=Math.min(.2,distance)*dz/Math.max(.000001,distance);
            Vec3 before=mob.position();
            mob.getNavigation().stop();
            mob.move(MoverType.SELF,new Vec3(vx,0,vz));
            if(Math.abs(mob.getX()-before.x)>.05
                    &&Math.abs(mob.getZ()-before.z)>.05)
                diagonalMoves[0]++;
        });
    }

    @GameTest(maxTicks=100,padding=64,skyAccess=true)
    public void realBuilderUsesClearHoldingSpace(GameTestHelper helper){
        fillOriginalCourse(helper);
        ServerLevel world=helper.getLevel();
        List<Candidate> crew=new ArrayList<>();
        for(int slot=0;slot<2;slot++){
            int x=10-slot,z=7;
            BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
            BlockPos at=pos(helper,x,2,z);
            mob.setPos(at.getX()+.5,at.getY(),at.getZ()+.5);world.addFreshEntity(mob);
            Candidate bot=new Candidate(slot,mob,"WWW",x,z,SAVED_PHYSICAL_CHAMPIONS[0],Map.of());
            BlockPos origin=pos(helper,0,2,0);bot.originX=origin.getX();bot.originZ=origin.getZ();
            bot.birthTick=helper.getTick();bot.controlTick=1;
            bot.ruleRound=new RuleRound(true,NavigationRuleMemory.Rule.YIELD_ASIDE,Map.of());
            crew.add(bot);
        }
        Candidate moving=crew.get(0),waiting=crew.get(1);
        moving.trafficWaitTicks=8;
        beginLocalEpisode(helper,moving,crew);
        if(!tryHoldingRoute(helper,moving,crew)){
            helper.fail("No legal holding route beside a real blocked lane");
            for(Candidate bot:crew)bot.mob.discard();return;
        }
        final boolean[] holdingTestEnded={false};
        helper.onEachTick(()->{
            if(holdingTestEnded[0])return;
            moving.controlTick++;
            walkOneTick(helper,moving,crew);
            if(moving.mob.getBoundingBox().intersects(waiting.mob.getBoundingBox())){
                holdingTestEnded[0]=true;
                helper.fail("Holding golem entered another golem's body");
                for(Candidate bot:crew)bot.mob.discard();return;
            }
            if(moving.holdingStage==2){
                holdingTestEnded[0]=true;
                helper.succeed();for(Candidate bot:crew)bot.mob.discard();
            }else if(helper.getTick()>80){
                holdingTestEnded[0]=true;
                helper.fail("Real golem did not reach its holding space");
                for(Candidate bot:crew)bot.mob.discard();
            }
        });
    }

    @GameTest(maxTicks=160,padding=64,skyAccess=true)
    public void realBuilderCentersAtSlabCorner(GameTestHelper helper){
        fillOriginalCourse(helper);
        ServerLevel world=helper.getLevel();
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        BlockPos at=pos(helper,9,2,7),origin=pos(helper,0,2,0);
        // Deliberately approach off-center beside the slab field's wall.
        mob.setPos(at.getX()+.9,at.getY(),at.getZ()+.78);world.addFreshEntity(mob);
        Candidate bot=new Candidate(0,mob,"WWWW",9,7,SAVED_PHYSICAL_CHAMPIONS[0],Map.of());
        bot.originX=origin.getX();bot.originZ=origin.getZ();bot.birthTick=helper.getTick();
        bot.ruleRound=new RuleRound(true,NavigationRuleMemory.Rule.CENTER_STEP,Map.of());
        final boolean[] done={false};
        helper.onEachTick(()->{
            if(done[0])return;
            bot.controlTick++;
            walkOneTick(helper,bot,List.of(bot));
            bot.captureMovement(bot.controlTick);
            finishLocalEpisode(bot,false);
            if(mob.getX()-bot.originX<6){
                done[0]=true;
                System.out.println("[ALT CENTER STEP] Real off-center slab corner cleared; ticks="
                        +bot.controlTick+" jumps="+bot.jumps);
                helper.succeed();mob.discard();
            }else if(helper.getTick()>140){
                done[0]=true;helper.fail("Centered real Builder did not clear the slab corner");mob.discard();
            }
        });
    }

    @GameTest(maxTicks=130,padding=64,skyAccess=true)
    public void realBuildersReceiveNativeTicksAcrossCourse(GameTestHelper helper){
        fillOriginalCourse(helper);
        List<BuilderGolem> actors=new ArrayList<>();
        for(int x:new int[]{2,13,25}){
            BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,helper.getLevel());
            BlockPos at=pos(helper,x,2,5);
            mob.setPos(at.getX()+.5,at.getY(),at.getZ()+.5);
            helper.getLevel().addFreshEntity(mob);actors.add(mob);
        }
        int[] before=new int[3],start=new int[3];
        boolean[] leased={false},measuring={false},done={false};
        Runnable[] release={()->{}};
        helper.onEachTick(()->{
            if(done[0])return;
            long tick=helper.getTick();
            if(tick>=30&&!leased[0]){
                for(int i=0;i<3;i++)before[i]=actors.get(i).tickCount;
                release[0]=holdCourseSimulation(helper);leased[0]=true;
            }
            if(tick>=50&&!measuring[0]){
                for(int i=0;i<3;i++)start[i]=actors.get(i).tickCount;
                measuring[0]=true;
            }
            if(tick>=80){
                done[0]=true;int[] advanced=new int[3];
                for(int i=0;i<3;i++)advanced[i]=actors.get(i).tickCount-start[i];
                nativeTickCoverage=new JsonObject();
                nativeTickCoverage.add("unleased30",TrainingMemory.JSON.toJsonTree(before));
                nativeTickCoverage.add("leased30",TrainingMemory.JSON.toJsonTree(advanced));
                nativeTickCoverage.addProperty("passed",java.util.Arrays.stream(advanced).allMatch(n->n>=28));
                release[0].run();for(BuilderGolem actor:actors)actor.discard();
                System.out.println("[ALT NATIVE TICK COVERAGE] unleased30="
                        +java.util.Arrays.toString(before)+" leased30="+java.util.Arrays.toString(advanced));
                if(java.util.Arrays.stream(advanced).anyMatch(n->n<28))
                    helper.fail("Course simulation tickets did not tick every real Builder");
                else helper.succeed();
            }
        });
    }

    @GameTest(maxTicks=8950,padding=64,skyAccess=true)
    public void realBuildersEvolveOnAltMazeInThirteenSeconds(
            GameTestHelper helper){
        fillOriginalCourse(helper);
        if(STARTS.length!=20||SELECTED_BOTS.length!=9){
            helper.fail("Expected exactly 10 live Builder Golem entrants");
            return;
        }
        Tournament training=new Tournament(helper);
        helper.onEachTick(training::tick);
    }
}
