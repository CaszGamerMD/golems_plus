package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.HashMap;
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
 * times out after 360 ticks, and selects/crosses/mutates navigation
 * control genes using ONLY what the real entities physically achieved.
 *
 * Neither a successful headless simulation nor theoretical route length
 * can pass this test. All ten real entities must reach real RED_WOOL in
 * the same 360-tick generation. No flight, scaffold or world modification
 * during runs. The original test fixture is built once before training.
 */
public final class BuilderEvolutionTimeTrialGameTest {
    private static final int DEADLINE=360;
    private static final int GENERATIONS=24;
    private static final long TRAINING_SEED=20261026L;
    private static final double SPEED_CAP=0.2;
    // Five bots from each green-wool column, spaced through all ten rows.
    // Keep both columns and the same maze: only concurrent entity count changes.
    private static final int[] SELECTED_BOTS={0,2,4,6,8,11,13,15,17,19};
    private static Block block(String name){
        return BuiltInRegistries.BLOCK.getValue(
                Identifier.tryParse("minecraft:"+name));
    }
    private static final Block GREEN=block("green_wool");
    private static final Block RED=block("red_wool");
    private static final Block GRAY=block("light_gray_concrete");
    private static final int X_OFFSET=4,Z_OFFSET=4;
    // Exact uploaded Sponge .schem floor projection. Prefix ^ means a
    // half slab STEP at Y+1; blocked overhead slabs are handled below.
    private static final String[] MAZE={
        "###########################",
        "#GG#....#...#.#...#.....SS#",
        "#GG#..#.#...#.#..#.#..#.SS#",
        "#GG#....#.#.#.#....##...SS#",
        "#GG#..#.#.#...#.#..#..#.SS#",
        "#GGD..#.#.^.#.#.#..#....SS#",
        "#GGD..#.#.^.#.#.#..#..#.SS#",
        "#GG#..#.#.^.#...#..##...SS#",
        "#GG#..#.#.#.#.#.#..#..#.SS#",
        "#GG###..#.#.#.#..#......SS#",
        "#GG#......#...#...#...#.SS#",
        "###########################"
    };
    private static final int[][] STARTS={
        {24,1},{24,2},{24,3},{24,4},{24,5},
        {24,6},{24,7},{24,8},{24,9},{24,10},
        {25,1},{25,2},{25,3},{25,4},{25,5},
        {25,6},{25,7},{25,8},{25,9},{25,10}
    };
    // Headless time-trial's *winning generation 2*, seed=42.
    // All chosen routes completed its own 360-tick simulated deadline.
    // Movement here is authoritative Minecraft collision-aware movement.
    private static final String[] REPLAYS={
        "WWWSSSSSSSSWWWNNNNNNWWWSSSSWWNNNWWSSSWWSSSWWWNENNNNNNWWWSSWW",
        "WSWWNSSSSSSSWWWNNNNNWNWWSSSSWWSSSWWNNNNNWWNNSSSSSSSWWWENNNNNNNWWWSSWW",
        "WWWNSSSSSSSWWWNNNNNNWWWSSSSSNWWNNNWWSSSWWSSSWWWNENNNNNNWWWSSSWW",
        "WSWWNNNSSSSSSSWWWNNNNNWNWWSSSSWWNNNWWSSWWSSSSWWWENNNNNNNWWWSSWW",
        "WWWSSSSWWWNNNNNNWWWSSSSWWNNNWWSSSWWSSSWWWNENNNNNNWWWSSWW",
        "WNWWNNNSSSSSSWSWWNNNNNWNWWSSSSWWSSSWWNNNWWNNNNSSSSSSSWWWNENNNNNNWWWSSWW",
        "WWWSSWWWNNNNNNWWWSSSSWWSSSWWNNNNNWWNNSSSSSSSWWWNENNNNNNWWWSSSWW",
        "NWWWSSWWWNNNNNNWWWSSSSWWSSSWWNNNNNNNNWWSSSSSSSSWWWNENNNNNNWWWSSSWW",
        "WWWWWWNNNNNWNWWSSSSWWSSSWWNNNWWNNNNSSSSSSSWWWNENNNNNNWWWSSWW",
        "WNWWWWWNNNNNWNWWSSSSWWNNNWWSWWSSSSSWWWNENNNNNNWWWSSWW",
        "WWWWSSSSSSSSWWWNNNNNWNWWSSSSWWSSSWWNNNNWWNNNSSSSSSSWWWNENNNNNNWWWSSWW",
        "WWSWWNSSSSSSSWWWNNNNNNWWWSSSSWWSSSWWNNNNNWWNNSSSSSSSWWWNENNNNNNWWWSSWW",
        "WWWWNSSSSSSSWWWNNNNNWNWWSSSSWWNNNWWSSSWWSSSWWWENNNNNNNWWWSSSWW",
        "WWNNNWWSSSSSSSSWWWNNNNNNWWWSSSSWWNNNWWNNWWSSSSSSSSWWWNENNNNNNWWWSSSWW",
        "WWWWNNNSSSSSSSWWWNNNNNNWWWSSSSWWNNNWWSSSWWSSSWWWNENNNNNNWWWSSWW",
        "WWNWWSSSSWWWNNNNNWNWWSSSSWWSSSWWNNNWWNNNNSSSSSSSWWWENNNNNNWWWSSWW",
        "WWWWSSWWWNNNNNWNWWSSSSWWNNNWWSWWSSSSSWWWNENNNNNNWWWSSWW",
        "WWSWWWWWNNNNNNWWWSSSSSNWWNNNWWSSSWWSSSWWWNENNNNNNWWWSSWW",
        "WWWWWWWNNNNNNWWWSSSSWWNNNWWSSSWWSSSWWWNENNNNNNWWWSSWW",
        "WWNWWWWWNNNNNNWWWSSSSWWNNNWWSSSWWSSSWWWNENNNNNNWWWSSWW"
    };

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
                          double turnBias,double routeJitter) {
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
                    rng.nextDouble()*.50);
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
                    rng.nextBoolean()?a.routeJitter:b.routeJitter);
        }
        Genome mutate(Random rng){
            return new Genome(
                    clamp(drive+rng.nextGaussian()*.07,.55,1),
                    clamp(align+rng.nextGaussian()*.14,.35,1),
                    clamp(tolerance+rng.nextGaussian()*.025,.05,.24),
                    clamp(jumpLead+rng.nextGaussian()*.20,.2,1.65),
                    clamp(jumpPower+rng.nextGaussian()*.02,.34,.46),
                    (int)clamp(jumpCooldown+rng.nextGaussian()*4,4,32),
                    (int)clamp(stuckTimeout+rng.nextGaussian()*7,5,45),
                    clamp(backoff+rng.nextGaussian()*.055,.03,.27),
                    clamp(slabAvoid+rng.nextGaussian()*.45,0,5),
                    clamp(turnBias+rng.nextGaussian()*.2,0,1.8),
                    clamp(routeJitter+rng.nextGaussian()*.20,0,1.5));
        }
        private static double clamp(double value,double lo,double hi){
            return Math.max(lo,Math.min(hi,value));
        }
    }
    private record Edge(int x,int z,int toX,int toZ){}
    private static final class Candidate {
        final Map<Edge,Integer> realBlockFailures=new HashMap<>();
        final int index;
        final BuilderGolem mob;
        final String route;
        final Genome dna;
        int cursor;
        int x,z;
        int arrivedTick=-1;
        int collisions;
        int jumps;
        int bestCursor;
        int immobile;
        int backoffTicks;
        int ticksSinceJump;
        double lastDistance=Double.POSITIVE_INFINITY;
        Candidate(int index,BuilderGolem mob,String route,int x,int z,
                  Genome dna){
            this.index=index;
            this.mob=mob;
            this.route=route;
            this.x=x;this.z=z;
            this.dna=dna;
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
            return Math.max(0,progress)*10000
                    -collisions*3;
        }
        // Absolute positions of the GameTest arena origin, only used to
        // score how close the actual entity came to its next tile.
        double originX,originZ;
        long birthTick;
    }

    private static boolean topSlab(int x,int z){
        return x==19&&(z==2||z==7||z==8)||x==17&&z==9;
    }
    private static BlockPos pos(GameTestHelper h,int x,int y,int z){
        return h.absolutePos(new BlockPos(X_OFFSET+x,y,Z_OFFSET+z));
    }

    private static void fillOriginalCourse(GameTestHelper h){
        for(int z=0;z<MAZE.length;z++){
            if(MAZE[z].length()!=27)
                throw new IllegalStateException("Timetrial row width changed");
            for(int x=0;x<27;x++){
                char cell=MAZE[z].charAt(x);
                boolean border=x==0||z==0||x==26||z==11;
                BlockState floor=cell=='S'?GREEN.defaultBlockState()
                        :cell=='G'?RED.defaultBlockState()
                        :border?GRAY.defaultBlockState()
                        :Blocks.STONE.defaultBlockState();
                h.setBlock(X_OFFSET+x,1,Z_OFFSET+z,floor);
                // First clear original test arena volumes so the uploaded
                // structure, not a stray GameTest template, owns collision.
                for(int y=2;y<=6;y++)
                    h.setBlock(X_OFFSET+x,y,Z_OFFSET+z,Blocks.AIR);
                if(border){
                    for(int y=2;y<=6;y++)
                        h.setBlock(X_OFFSET+x,y,Z_OFFSET+z,Blocks.GLASS);
                }else if(topSlab(x,z)){
                    h.setBlock(X_OFFSET+x,3,Z_OFFSET+z,
                            Blocks.OAK_SLAB.defaultBlockState()
                                .setValue(SlabBlock.TYPE,SlabType.BOTTOM));
                }else if(cell=='#'){
                    h.setBlock(X_OFFSET+x,2,Z_OFFSET+z,Blocks.OAK_PLANKS);
                    h.setBlock(X_OFFSET+x,3,Z_OFFSET+z,Blocks.OAK_PLANKS);
                }else if(cell=='^'){
                    h.setBlock(X_OFFSET+x,2,Z_OFFSET+z,
                            Blocks.OAK_SLAB.defaultBlockState()
                                .setValue(SlabBlock.TYPE,SlabType.BOTTOM));
                }
            }
        }
        // Sponge original has two existing, OPEN spruce doors. Do not
        // mutate the course into a different doorway puzzle.
        for(int z=5;z<=6;z++){
            BlockState door=Blocks.SPRUCE_DOOR.defaultBlockState()
                    .setValue(DoorBlock.FACING,Direction.WEST)
                    .setValue(DoorBlock.OPEN,true)
                    .setValue(DoorBlock.HINGE,z==5
                            ?DoorHingeSide.RIGHT:DoorHingeSide.LEFT);
            h.setBlock(X_OFFSET+3,2,Z_OFFSET+z,
                    door.setValue(DoorBlock.HALF,DoubleBlockHalf.LOWER));
            h.setBlock(X_OFFSET+3,3,Z_OFFSET+z,
                    door.setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER));
        }
    }

    private static int[] next(int x,int z,char step){
        return switch(step){
            case 'N' -> new int[]{x,z-1};
            case 'S' -> new int[]{x,z+1};
            case 'E' -> new int[]{x+1,z};
            case 'W' -> new int[]{x-1,z};
            default -> throw new IllegalArgumentException("Bad replay movement");
        };
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
                                    Map<Edge,Integer> realBlockFailures){
        RouteNode start=new RouteNode(startX,startZ,'?');
        PriorityQueue<Frontier> open=new PriorityQueue<>(
                Comparator.comparingDouble(Frontier::cost));
        Map<RouteNode,Double> best=new HashMap<>();
        Map<RouteNode,RouteNode> parents=new HashMap<>();
        Map<RouteNode,Character> directions=new HashMap<>();
        open.add(new Frontier(start,0));best.put(start,0.0);
        RouteNode finish=null;
        while(!open.isEmpty()){
            Frontier current=open.poll();
            RouteNode node=current.node();
            if(current.cost()>best.getOrDefault(node,Double.POSITIVE_INFINITY))
                continue;
            if(MAZE[node.z()].charAt(node.x())=='G'){
                finish=node;break;
            }
            for(char direction:new char[]{'N','E','S','W'}){
                int[] p=next(node.x(),node.z(),direction);
                int x=p[0],z=p[1];
                if(z<0||z>=MAZE.length||x<0||x>=MAZE[0].length()
                        ||MAZE[z].charAt(x)=='#')continue;
                char tile=MAZE[z].charAt(x);
                double weight=1.0+(tile=='^'?dna.slabAvoid():0)
                        +(node.heading()!='?'&&node.heading()!=direction
                                ?dna.turnBias():0);
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
                weight+=Math.min(65,realBlockFailures.getOrDefault(edge,0)*2.5);
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

    private static List<Candidate> spawnGeneration(GameTestHelper helper,
            List<Genome> genomes,Map<Edge,Integer> blockMemory){
        ServerLevel world=helper.getLevel();
        List<Candidate> bots=new ArrayList<>();
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
            Candidate b=new Candidate(slot,mob,planRoute(start[0],
                    start[1],genome,blockMemory),start[0],start[1],genome);
            BlockPos origin=pos(helper,0,2,0);
            b.originX=origin.getX();
            b.originZ=origin.getZ();
            b.birthTick=helper.getTick();
            bots.add(b);
        }
        return bots;
    }

    /** Controller uses real Minecraft entity move + collisions; only
     * genes controlling WALK / JUMP / alignment can be selected.
     */
    private static void walkOneTick(GameTestHelper helper,Candidate b){
        BuilderGolem mob=b.mob;
        if(!mob.isAlive()||b.arrivedTick>=0)return;
        ServerLevel world=helper.getLevel();
        if(world.getBlockState(mob.blockPosition().below()).is(RED)){
            b.arrivedTick=(int)(helper.getTick()-b.birthTick);
            // A winner leaves the track; otherwise it obstructs the door
            // or blocks remaining racers at the finish line.
            mob.discard();
            return;
        }
        int[] ahead=null;
        BlockPos target=null;
        double dx=0,dz=0,distance=0;
        // DO NOT spend a full Minecraft tick at every waypoint just to
        // increment a route cursor. On a 50-60 block route that artificial
        // pause wastes up to three seconds and makes an 18s run impossible.
        while(b.cursor<b.route.length()){
            ahead=next(b.x,b.z,b.route.charAt(b.cursor));
            target=pos(helper,ahead[0],2,ahead[1]);
            dx=target.getX()+.5-mob.getX();
            dz=target.getZ()+.5-mob.getZ();
            distance=Math.hypot(dx,dz);
            if(distance>=b.dna.tolerance())break;
            b.x=ahead[0];
            b.z=ahead[1];
            b.cursor++;
            b.bestCursor=Math.max(b.bestCursor,b.cursor);
            b.immobile=0;
            b.lastDistance=Double.POSITIVE_INFINITY;
        }
        if(b.cursor>=b.route.length())return;

        if(distance<b.lastDistance-.015){
            b.immobile=0;
            b.lastDistance=distance;
        }else b.immobile++;
        // Reset on progress; otherwise trigger an evolving retreat/jump
        // decision instead of driving forever into a blocking step.
        if(b.immobile>=b.dna.stuckTimeout()){
            b.immobile=0;
            b.lastDistance=Double.POSITIVE_INFINITY;
            b.backoffTicks=3;
            b.collisions++;
        }
        b.ticksSinceJump++;
        // GameTest's explicit horizontal move(MoverType.SELF,...) can
        // reset the onGround flag. Probe the REAL Minecraft collision
        // shape immediately beneath the golem rather than trusting that
        // stale flag when deciding whether a legal ground jump can start.
        boolean grounded=mob.onGround()||!world.noCollision(mob,
                mob.getBoundingBox().move(0,-.07,0));
        BlockPos nextFeet=target;
        BlockState footState=world.getBlockState(nextFeet);
        BlockState aboveState=world.getBlockState(nextFeet.above());
        boolean halfStep=footState.getBlock() instanceof SlabBlock;
        boolean tallerStep=aboveState.getBlock() instanceof SlabBlock;
        boolean approachingRaisedCell=(halfStep||tallerStep)
                &&distance<=b.dna.jumpLead();
        // Jump from ground, with native gravity and vanilla collision.
        // Never add positive upward acceleration while airborne.
        if((approachingRaisedCell||b.collisions>0&&b.immobile>4)
                &&grounded&&b.ticksSinceJump>=b.dna.jumpCooldown()){
            mob.setDeltaMovement(0,b.dna.jumpPower(),0);
            b.ticksSinceJump=0;
            b.jumps++;
        }
        double step=Math.min(SPEED_CAP,
                b.dna.drive()*SPEED_CAP);
        // Driving back for 3 ticks breaks a collision lock without
        // passing through solid blocks.
        if(b.backoffTicks>0){
            step=-Math.min(b.dna.backoff(),SPEED_CAP);
            b.backoffTicks--;
        }
        double vx=step*dx/Math.max(.00001,distance);
        double vz=step*dz/Math.max(.00001,distance);
        // Evolve how much to anticipate a corner, WITHOUT reducing
        // horizontal speed. Actual Minecraft block collision still
        // rejects unsafe corner cuts.
        if(b.cursor+1<b.route.length()&&distance<.45){
            int[] corner=next(ahead[0],ahead[1],
                    b.route.charAt(b.cursor+1));
            double lookX=corner[0]-ahead[0],lookZ=corner[1]-ahead[1];
            vx+=lookX*(1-b.dna.align())*.075;
            vz+=lookZ*(1-b.dna.align())*.075;
        }
        double norm=Math.hypot(vx,vz);
        if(norm>SPEED_CAP){
            vx*=SPEED_CAP/norm;
            vz*=SPEED_CAP/norm;
        }
        // Actual mob collision resolver, not a simulated tile hop.
        mob.setNoGravity(false);
        mob.getNavigation().stop();
        Vec3 old=mob.position();
        mob.move(MoverType.SELF,new Vec3(vx,0,vz));
        if(mob.position().distanceToSqr(old)<.000004){
            b.collisions++;
            boolean hitsStaticBlock=world.getBlockCollisions(mob,
                    mob.getBoundingBox().move(vx,0,vz))
                    .iterator().hasNext();
            if(hitsStaticBlock&&b.immobile>3){
                Edge blocked=new Edge(b.x,b.z,ahead[0],ahead[1]);
                b.realBlockFailures.merge(blocked,1,Integer::sum);
            }
        }
    }

    private static final class Tournament {
        final GameTestHelper helper;
        final Random rng=new Random(TRAINING_SEED);
        final Map<Edge,Integer> collisionMemory=new HashMap<>();
        final Genome[] champions=new Genome[SELECTED_BOTS.length];
        final double[] championScores=new double[SELECTED_BOTS.length];
        List<Genome> generationGenes=new ArrayList<>();
        List<Candidate> bots=new ArrayList<>();
        int generation=0;
        long generationStart=0;
        int bestFinished=0;
        Tournament(GameTestHelper helper){
            this.helper=helper;
            for(int i=0;i<SELECTED_BOTS.length;i++){
                championScores[i]=-Double.MAX_VALUE;
                generationGenes.add(Genome.random(rng));
            }
            bots=spawnGeneration(helper,generationGenes,collisionMemory);
        }
        void tick(){
            long absoluteTick=helper.getTick();
            long roundTick=absoluteTick-generationStart;
            if(roundTick<0)return;
            for(Candidate b:bots)walkOneTick(helper,b);
            if(roundTick<DEADLINE)return;
            int finished=0;
            double mean=0;
            int collisions=0,jumps=0;
            List<Candidate> ranked=new ArrayList<>(bots);
            ranked.sort(Comparator.comparingDouble(Candidate::fitness).reversed());
            StringBuilder blockers=new StringBuilder();
            for(Candidate b:bots){
                if(b.arrivedTick>=0)finished++;
                else if(blockers.length()<550)
                    blockers.append(" #").append(b.index)
                            .append(" step=").append(b.cursor)
                            .append("/").append(b.route.length())
                            .append(" p=").append(b.mob.position());
                mean+=b.fitness();
                collisions+=b.collisions;
                jumps+=b.jumps;
                for(var edge:b.realBlockFailures.entrySet())
                    collisionMemory.merge(edge.getKey(),edge.getValue(),
                            (a,c)->Math.min(100,a+c));
                if(b.fitness()>championScores[b.index]){
                    champions[b.index]=b.dna;
                    championScores[b.index]=b.fitness();
                }
            }
            bestFinished=Math.max(bestFinished,finished);
            System.out.println("[TIMETRIAL REAL GENETICS] gen="+generation
                    +" realBuilderGolems="+bots.size()
                    +" finish18s="+finished+"/"+bots.size()
                    +" best="+bestFinished
                    +" meanFitness="+Math.round(mean/bots.size())
                    +" collisions="+collisions+" jumps="+jumps
                    +" learnedBlockEdges="+collisionMemory.size()
                    +" stalled="+blockers);
            // Always discard all physical entities, even winners.
            for(Candidate b:bots)if(b.mob.isAlive())b.mob.discard();
            if(finished==bots.size()){
                helper.succeed();return;
            }
            if(generation+1>=GENERATIONS){
                helper.fail("Physical evolution failed after "+GENERATIONS
                        +" generations. Best REAL result="+bestFinished
                        +"/"+bots.size()
                        +", latest="+finished+"/10; "+blockers);
                return;
            }
            // Genetic selection uses ONLY actual golem trajectories.
            // Stronger physical performers are more likely to breed,
            // while the best genotype per spawn remains an elite.
            List<Genome> parents=new ArrayList<>();
            int eliteCount=Math.min(4,ranked.size());
            for(int i=0;i<eliteCount;i++)parents.add(ranked.get(i).dna);
            for(Genome elite:champions)if(elite!=null)parents.add(elite);
            List<Genome> offspring=new ArrayList<>();
            for(int i=0;i<SELECTED_BOTS.length;i++){
                if(champions[i]!=null&&championScores[i]>100000){
                    offspring.add(champions[i]);continue;
                }
                if(rng.nextDouble()<.10){
                    offspring.add(Genome.random(rng));continue;
                }
                Genome a=champions[i]!=null&&rng.nextBoolean()
                        ?champions[i]:parents.get(rng.nextInt(parents.size()));
                Genome b=parents.get(rng.nextInt(parents.size()));
                offspring.add(Genome.cross(a,b,rng).mutate(rng));
            }
            generationGenes=offspring;
            generation++;
            generationStart=absoluteTick+1;
            bots=spawnGeneration(helper,generationGenes,collisionMemory);
        }
    }

    @GameTest(maxTicks=8950,padding=64,skyAccess=true)
    public void realBuildersEvolveOnUserMazeInEighteenSeconds(
            GameTestHelper helper){
        fillOriginalCourse(helper);
        if(STARTS.length!=20||REPLAYS.length!=20
                ||SELECTED_BOTS.length!=10){
            helper.fail("Expected exactly 10 live Builder Golem entrants");
            return;
        }
        Tournament training=new Tournament(helper);
        helper.onEachTick(training::tick);
    }
}
