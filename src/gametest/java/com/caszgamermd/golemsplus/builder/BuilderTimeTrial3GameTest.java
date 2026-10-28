package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.google.gson.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Original Sponge v3 TimeTrial 3; 27 x 10 x 12, SHA256:
 * d86ad9d206729c188300460463f8e1e727c097f4291a4116292d94497d36a337
 * This trainer has its own generations, fitness, and replays. No alt-track
 * completion times or coordinates are treated as successes on Trial 3.
 * The only movement is real BuilderGolem Minecraft collision/ticking.
 */
public final class BuilderTimeTrial3GameTest {
    static final int LIMIT=500, GENERATIONS=6, COUNT=9;
    private static Block registry(String name){
        return BuiltInRegistries.BLOCK.getValue(Identifier.tryParse("minecraft:"+name));
    }
    private static final Block GRAY=registry("light_gray_concrete");
    private static final Block RED=registry("red_wool");
    private static final Block GREEN=registry("green_wool");
    static final String COURSE_HASH="d86ad9d206729c188300460463f8e1e727c097f4291a4116292d94497d36a337";
    // Gradle GameTests run from a different CWD than the Git checkout.
    static final Path ROOT=Path.of(System.getenv().getOrDefault("GITHUB_WORKSPACE","."));
    static final Path STATE=ROOT.resolve("training/tt3/state/checkpoint.json");
    static final Path REPLAY=ROOT.resolve("training/tt3/replays");
    static final int[][] STARTS={{24,1},{24,3},{24,5},{24,7},{24,9},
                                  {25,2},{25,4},{25,6},{25,10}};
    // Base-36 Sponge palette IDs, in original (y,z,x) order. Exact block
    // properties below retain closed door hinges, gates, and scaffold heights.
    static final String[][] LAYERS={
      {"000000000000000000000000000","011222222222222222222222330","011222222222222222222222330",
       "011222222222222222222222330","011222222222222222222222330","011222222222222222222222330",
       "011222222222222222222222330","011222222222222222222222330","011222222222222222222222330",
       "011222222222222222222222330","011222222222222222222222330","000000000000000000000000000"},
      {"444444444444444444444444444","455565565575565555575585554","4555955655a5565555575555554",
       "4555655b5575555555575555554","455595565575565555575585554","455565565575565555575555554",
       "4555c55d5575565555575555554","455565565575555555578885554","4555c5565575565555575555554",
       "4555655b5575565555575555554","455565565575565585575585554","444444444444444444444444444"},
      {"444444444444444444444444444","455565565555565555575585554","4555e5565555565555575555554",
       "4555655f55555g5555575555554","4555e5565555565555575585554","455565565555565555575555554",
       "4555h55i5555565555575555554","4555655655555g5555575555554","4555h5565555565555575555554",
       "4555655f5555565555575555554","455565565555565585575585554","444444444444444444444444444"},
      {"444444444444444444444444444","455555555555555555555585554","455555555555555555555555554",
       "455555555555555555555555554","455555555555555555555585554","455555555555555555555555554",
       "455555555555555555555555554","455555555555555555555555554","455555555555555555555555554",
       "455555555555555555555555554","455555555555555585555585554","444444444444444444444444444"},
      {"444444444444444444444444444","4555555555555555jklmno85554","455555555555555555555555554",
       "455555555555555555555555554","455555555555555555555555554","455555555555555555555555554",
       "455555555555555555555555554","455555555555555555555555554","455555555555555555555555554",
       "455555555555555555555555554","45555555555555558onmno85554","444444444444444444444444444"}
    };
    private static char at(int x,int y,int z){
        if(x<0||x>=27||z<0||z>=12||y<0||y>=10)return '4';
        return y>=5?(x==0||x==26||z==0||z==11?'4':'5'):LAYERS[y][z].charAt(x);
    }
    private static boolean scaffold(char c){return c=='8'||c>='j'&&c<='o';}
    private static boolean door(char c){return c=='9'||c=='b'||c=='c'||c=='d';}
    private static BlockState block(char c){
        return switch(c){
            case '0'->GRAY.defaultBlockState();
            case '1'->RED.defaultBlockState();
            case '2'->Blocks.STONE.defaultBlockState();
            case '3'->GREEN.defaultBlockState();
            case '4'->Blocks.GLASS.defaultBlockState();
            case '5'->Blocks.AIR.defaultBlockState();
            case '6'->Blocks.JUNGLE_PLANKS.defaultBlockState();
            case '7'->Blocks.ACACIA_FENCE.defaultBlockState()
                    .setValue(FenceBlock.NORTH,true).setValue(FenceBlock.SOUTH,true);
            case '8'->Blocks.SCAFFOLDING.defaultBlockState()
                    .setValue(ScaffoldingBlock.BOTTOM,false).setValue(ScaffoldingBlock.DISTANCE,0);
            case 'a'->Blocks.ACACIA_FENCE_GATE.defaultBlockState()
                    .setValue(FenceGateBlock.FACING,Direction.WEST);
            case 'g'->Blocks.ACACIA_TRAPDOOR.defaultBlockState()
                    .setValue(TrapDoorBlock.FACING,Direction.SOUTH).setValue(TrapDoorBlock.HALF,Half.BOTTOM);
            case '9','b','c','d','e','f','h','i'->{
                boolean upper=c=='e'||c=='f'||c=='h'||c=='i';
                boolean east=c=='9'||c=='c'||c=='e'||c=='h';
                boolean right=c=='9'||c=='b'||c=='e'||c=='f';
                yield Blocks.ACACIA_DOOR.defaultBlockState()
                    .setValue(DoorBlock.FACING,east?Direction.EAST:Direction.WEST)
                    .setValue(DoorBlock.HALF,upper?DoubleBlockHalf.UPPER:DoubleBlockHalf.LOWER)
                    .setValue(DoorBlock.HINGE,right?DoorHingeSide.RIGHT:DoorHingeSide.LEFT);
            }
            case 'j','k','l','m','n','o'->Blocks.SCAFFOLDING.defaultBlockState()
                    .setValue(ScaffoldingBlock.BOTTOM,true)
                    .setValue(ScaffoldingBlock.DISTANCE,switch(c){
                        case 'j'->6;case 'k'->5;case 'l'->4;case 'm'->3;case 'n'->2;default->1;});
            default->throw new IllegalArgumentException("Unrecognized original palette "+c);
        };
    }
    private static BlockPos pos(GameTestHelper t,int x,int y,int z){
        return t.absolutePos(new BlockPos(x+3,y+1,z+3));
    }
    private static void fill(GameTestHelper t){
        for(int y=0;y<10;y++)for(int z=0;z<12;z++)for(int x=0;x<27;x++)
            t.setBlock(x+3,y+1,z+3,block(at(x,y,z)));
    }
    private static Runnable holdCourseSimulation(GameTestHelper t){
        BlockPos a=pos(t,-1,2,-1),b=pos(t,27,2,12);
        Set<ChunkPos> chunks=new HashSet<>();
        for(int x=Math.min(a.getX(),b.getX())>>4;
                x<=(Math.max(a.getX(),b.getX())>>4);x++)
            for(int z=Math.min(a.getZ(),b.getZ())>>4;
                    z<=(Math.max(a.getZ(),b.getZ())>>4);z++)
                chunks.add(new ChunkPos(x,z));
        return BuilderChunkTickets.simulationLease(t.getLevel(),chunks);
    }
    private record Node(int x,int z,int level){}
    private record Step(Node node,double cost){}
    private record Gene(double doorCost,double scaffoldCost,double tolerance,double lane){
        Gene mutate(Random r){
            int i=r.nextInt(4);
            return new Gene(i==0?clamp(doorCost+r.nextGaussian()*.24,.2,4):doorCost,
                i==1?clamp(scaffoldCost+r.nextGaussian()*.48,.2,9):scaffoldCost,
                i==2?clamp(tolerance+r.nextGaussian()*.012,.06,.20):tolerance,
                i==3?clamp(lane+r.nextGaussian()*.04,-.15,.15):lane);
        }
    }
    private static double clamp(double x,double lo,double hi){return Math.max(lo,Math.min(hi,x));}
    private static boolean passable(Node n){
        if(n.x<1||n.x>25||n.z<1||n.z>10)return false;
        if(n.level==1)return scaffold(at(n.x,4,n.z))
                &&at(n.x,5,n.z)=='5';
        char feet=at(n.x,1,n.z),head=at(n.x,2,n.z);
        return (feet=='5'||scaffold(feet)||door(feet)||feet=='a')
            // The source includes two CLOSED overhead trapdoors at x13.
            // A hand-openable trapdoor is a legal action, not a wall.
            &&(head=='5'||scaffold(head)||head=='e'||head=='f'
                ||head=='h'||head=='i'||head=='g');
    }
    private static boolean tower(Node n){
        if(!passable(new Node(n.x,n.z,0))
                ||!passable(new Node(n.x,n.z,1)))return false;
        for(int y=1;y<=4;y++)if(!scaffold(at(n.x,y,n.z)))return false;
        return true;
    }
    private static List<Node> plan(Node start,Gene gene,Set<String> blocked){
        Map<Node,Double> distance=new HashMap<>();
        Map<Node,Node> prev=new HashMap<>();
        PriorityQueue<Step> queue=new PriorityQueue<>(Comparator.comparingDouble(Step::cost));
        distance.put(start,0.);queue.add(new Step(start,0));
        Node end=null;
        while(!queue.isEmpty()){
            Step a=queue.poll();
            if(a.cost>distance.getOrDefault(a.node,Double.POSITIVE_INFINITY)+1e-6)continue;
            Node n=a.node;
            if(n.level==0&&at(n.x,0,n.z)=='1'){end=n;break;}
            List<Step> choices=new ArrayList<>();
            for(int[] d:new int[][]{{-1,0},{0,-1},{0,1},{1,0}}){
                Node q=new Node(n.x+d[0],n.z+d[1],n.level);
                if(!passable(q))continue;
                char c=at(q.x,1,q.z);
                double cost=1+(n.level==0&&(door(c)||c=='a'
                        ||at(q.x,2,q.z)=='g')?gene.doorCost:0);
                choices.add(new Step(q,cost));
            }
            if(tower(n))choices.add(new Step(new Node(n.x,n.z,1-n.level),
                    3+gene.scaffoldCost));
            for(Step e:choices){
                double v=a.cost+e.cost+(blocked.contains(n+"->"+e.node)?30:0);
                if(v>=distance.getOrDefault(e.node,Double.POSITIVE_INFINITY))continue;
                distance.put(e.node,v);prev.put(e.node,n);
                queue.add(new Step(e.node,v));
            }
        }
        if(end==null)return List.of();
        LinkedList<Node> route=new LinkedList<>();
        for(Node p=end;p!=null;p=prev.get(p))route.addFirst(p);
        return route;
    }
    private static final class Runner {
        final int slot;final BuilderGolem mob;final Gene gene;
        final int spawnEntityTick;
        final List<double[]> frames=new ArrayList<>();
        final Set<String> blocked=new HashSet<>();
        List<Node> route;int cursor=1,arrival=-1,stalls,doorStalls;
        int waits,climbs,replans;boolean climbing;
        double lane,score;
        Node selectedDoor;
        Runner(int slot,BuilderGolem mob,Gene gene,Node start){
            this.slot=slot;this.mob=mob;this.gene=gene;
            this.spawnEntityTick=mob.tickCount;
            route=plan(start,gene,blocked);
        }
        void capture(int tick){frames.add(new double[]{tick,mob.getX(),mob.getY(),mob.getZ()});}
    }
    private static double lane(ServerLevel world,BuilderGolem mob,
                               BlockPos p,boolean axisX,double preferred){
        for(double v:new double[]{preferred,0,-.08,.08,-.12,.12,-.16,.16}){
            double px=p.getX()+.5+(axisX?0:v),pz=p.getZ()+.5+(axisX?v:0);
            boolean clear=true;
            for(int i=-8;i<=8;i++){
                double x=px+(axisX?i*.1:0),z=pz+(axisX?0:i*.1);
                double half=mob.getBbWidth()*.5;
                AABB body=new AABB(x-half,mob.getY()+.002,z-half,
                        x+half,mob.getY()+mob.getBbHeight()-.002,z+half);
                if(world.getBlockCollisions(mob,body).iterator().hasNext()){
                    clear=false;break;
                }
            }
            if(clear)return v;
        }
        return 0;
    }
    private static void open(ServerLevel world,BuilderGolem mob,BlockPos p){
        if(Math.hypot(mob.getX()-p.getX()-.5,mob.getZ()-p.getZ()-.5)>1.7)return;
        BlockState s=world.getBlockState(p);
        if(s.getBlock() instanceof DoorBlock door&&door.type().canOpenByHand()
                &&!s.getValue(DoorBlock.OPEN))door.setOpen(mob,world,s,p,true);
        if(s.getBlock() instanceof FenceGateBlock
                &&!s.getValue(FenceGateBlock.OPEN))
            world.setBlock(p,s.setValue(FenceGateBlock.OPEN,true),3);
        BlockState ceiling=world.getBlockState(p.above());
        if(ceiling.getBlock() instanceof TrapDoorBlock
                &&!ceiling.getValue(TrapDoorBlock.OPEN))
            world.setBlock(p.above(),ceiling.setValue(TrapDoorBlock.OPEN,true),3);
    }
    private static double supportedFeetY(ServerLevel world,BlockPos destination){
        // Scaffold has a 1/8-block physical top, NOT a full-height
        // walking surface. The previous fixed Y=20 arrival check
        // rejected the actual native physics height Y=19.125 even
        // after runners had traversed onto the upper platform.
        BlockPos support=destination.below();
        // The full scaffold shape includes 1-block-tall SIDE POSTS. A
        // generic VoxelShape.max(Y) therefore gives 1.0 and strands
        // walkers exactly at the 1/8-high center surface. Native
        // positions from generations 7-18 prove the center support
        // is supportY+0.125, while max(shape) incorrectly reads 1.0.
        if(world.getBlockState(support).is(Blocks.SCAFFOLDING))
            return support.getY()+.125;
        var shape=world.getBlockState(support).getCollisionShape(world,support);
        return shape.isEmpty()?destination.getY():
                support.getY()+shape.max(Direction.Axis.Y);
    }
    private static void drive(Runner r,List<Runner> crew,double x,double z,
                              boolean enteringDoor){
        BuilderGolem mob=r.mob;
        double dx=x-mob.getX(),dz=z-mob.getZ();
        if(enteringDoor&&r.cursor<r.route.size()){
            Node before=r.route.get(r.cursor-1),after=r.route.get(r.cursor);
            if(after.x!=before.x&&Math.abs(dz)>.035&&Math.abs(dx)<1.55)dx=0;
            if(after.z!=before.z&&Math.abs(dx)>.035&&Math.abs(dz)<1.55)dz=0;
        }
        double d=Math.hypot(dx,dz);
        if(d<.0001)return;
        double vx=Math.min(d,.20)*dx/d,vz=Math.min(d,.20)*dz/d;
        for(Runner other:crew){
            if(other==r||other.arrival>=0||!other.mob.isAlive())continue;
            if(Math.abs(other.mob.getY()-mob.getY())>1.25)continue;
            if(Math.hypot(mob.getX()+vx-other.mob.getX(),
                          mob.getZ()+vz-other.mob.getZ())<.68
                    &&r.slot>other.slot){
                r.waits++;r.stalls=0;return;
            }
        }
        Vec3 old=mob.position();
        mob.getNavigation().stop();
        mob.setNoGravity(false);
        mob.move(MoverType.SELF,new Vec3(vx,-.08,vz));
        double movement=Math.hypot(mob.getX()-old.x,mob.getZ()-old.z);
        if(movement<.03){r.stalls++;if(enteringDoor)r.doorStalls++;}
        else r.stalls=0;
    }
    private static void tick(GameTestHelper t,Runner r,List<Runner> crew,int tick){
        if(r.arrival>=0)return;
        BuilderGolem mob=r.mob;
        if(t.getLevel().getBlockState(mob.blockPosition().below()).is(RED)){
            r.arrival=tick;mob.discard();return;
        }
        if(r.route.isEmpty()||r.cursor>=r.route.size())return;
        Node previous=r.route.get(r.cursor-1),target=r.route.get(r.cursor);
        BlockPos base=pos(t,target.x,1,target.z);
        if(previous.level!=target.level){
            if(!r.climbing){
                if(Math.hypot(mob.getX()-base.getX()-.5,mob.getZ()-base.getZ()-.5)>.20){
                    drive(r,crew,base.getX()+.5,base.getZ()+.5,false);return;
                }
                BuilderScaffolding.begin(t.getLevel(),mob,base,
                    previous.level==0?pos(t,target.x,5,target.z).getY()
                        :base.getY()+1,previous.level==1);
                r.climbing=true;r.climbs++;
            }
            if(previous.level==0
                    &&mob.getY()>=pos(t,target.x,5,target.z).getY()-.16){
                r.cursor++;r.climbing=false;BuilderScaffolding.cancel(mob);
            }else if(previous.level==1&&mob.getY()<=base.getY()+1.17){
                if(BuilderScaffolding.dismountAtBase(t.getLevel(),mob,base)){
                    r.cursor++;r.climbing=false;
                }
            }
            return;
        }
        BlockPos p=pos(t,target.x,target.level==0?1:5,target.z);
        boolean door=target.level==0
                &&(door(at(target.x,1,target.z))||at(target.x,1,target.z)=='a'
                    ||at(target.x,2,target.z)=='g');
        if(door){
            open(t.getLevel(),mob,p);
            if(r.selectedDoor==null||!r.selectedDoor.equals(target)){
                r.selectedDoor=target;
                r.lane=lane(t.getLevel(),mob,p,previous.x!=target.x,r.gene.lane);
            }
        }
        double x=p.getX()+.5,z=p.getZ()+.5;
        if(door){
            if(previous.x!=target.x)z+=r.lane;
            else x+=r.lane;
        }
        double feetY=target.level==0?p.getY():supportedFeetY(t.getLevel(),p);
        if(Math.hypot(mob.getX()-x,mob.getZ()-z)<r.gene.tolerance
                &&Math.abs(mob.getY()-feetY)<.32){
            r.cursor++;r.stalls=0;r.selectedDoor=null;
            // Advance to the NEXT legal waypoint on this SAME native
            // Minecraft tick. Previously an already-reached tile wasted
            // an entire 50ms tick, repeated at every turn/scaffold cell.
            // Recursion never moves a body twice: this branch made no
            // move and merely consumed an already-reached waypoint.
            if(r.cursor<r.route.size())tick(t,r,crew,tick);
            return;
        }
        drive(r,crew,x,z,door);
        if(r.stalls>=14){
            if(!door&&r.waits==0)r.blocked.add(previous+"->"+target);
            Node actual=new Node((int)Math.floor(mob.getX()-pos(t,0,0,0).getX()),
                (int)Math.floor(mob.getZ()-pos(t,0,0,0).getZ()),
                mob.getY()>pos(t,0,4,0).getY()-.35?1:0);
            if(passable(actual)){
                List<Node> alternative=plan(actual,r.gene,r.blocked);
                if(!alternative.isEmpty()){r.route=alternative;r.cursor=1;r.replans++;}
            }
            r.stalls=0;r.selectedDoor=null;
        }
    }
    private static List<Gene> initial(){
        // Motor/door behavior learned on Trial 2, but NOT its route
        // coordinates or fitness. Each of three families can mutate.
        return new ArrayList<>(List.of(
            new Gene(1.5,1.0,.11,.06),new Gene(1.2,1.7,.10,-.06),
            new Gene(1.7,.7,.095,0),new Gene(1.5,1.0,.11,.06),
            new Gene(1.2,1.7,.10,-.06),new Gene(1.7,.7,.095,0),
            new Gene(1.5,1.0,.11,.06),new Gene(1.2,1.7,.10,-.06),
            new Gene(1.7,.7,.095,0)));
    }
    private static JsonArray jsonGenes(List<Gene> genes){
        JsonArray arr=new JsonArray();
        for(Gene g:genes){
            JsonArray x=new JsonArray();
            x.add(g.doorCost);x.add(g.scaffoldCost);x.add(g.tolerance);x.add(g.lane);
            arr.add(x);
        }
        return arr;
    }
    private static List<Gene> load(JsonObject checkpoint){
        if(checkpoint==null)return initial();
        if(!COURSE_HASH.equals(checkpoint.get("courseHash").getAsString())
                ||checkpoint.get("limit").getAsInt()!=LIMIT)
            throw new IllegalStateException("Refusing mismatched Time Trial 3 checkpoint");
        JsonArray population=checkpoint.getAsJsonArray("nextPopulation");
        if(population.size()!=COUNT)throw new IllegalStateException("Nine real genomes required");
        List<Gene> out=new ArrayList<>();
        for(JsonElement v:population){
            JsonArray a=v.getAsJsonArray();
            out.add(new Gene(a.get(0).getAsDouble(),a.get(1).getAsDouble(),
                    a.get(2).getAsDouble(),a.get(3).getAsDouble()));
        }
        return out;
    }
    private static JsonObject checkpoint(){
        try{
            if(Files.exists(STATE)){
                JsonObject prior=JsonParser.parseString(Files.readString(STATE)).getAsJsonObject();
                if("course-entity-ticking-v1".equals(prior.has("nativeSimulationProtocol")
                        ?prior.get("nativeSimulationProtocol").getAsString():"")){
                    return prior;
                }
                System.out.println("[TT3 PROTOCOL MIGRATION] Old pre-native samples "
                        +"archived; no unverified fitness imported into true physical training.");
            }
        }catch(IOException ex){throw new IllegalStateException(ex);}
        JsonObject result=new JsonObject();
        result.addProperty("courseHash",COURSE_HASH);
        result.addProperty("nativeSimulationProtocol","course-entity-ticking-v1");
        result.addProperty("limit",LIMIT);
        result.addProperty("physicalBuilders",COUNT);
        result.addProperty("completedGenerations",0);
        result.addProperty("bestFinishers",0);
        result.addProperty("bestCrewTicks",-1);
        result.add("history",new JsonArray());
        result.add("nextPopulation",jsonGenes(initial()));
        return result;
    }
    private static void save(JsonObject state){
        try{
            Files.createDirectories(STATE.getParent());
            Path temporary=STATE.resolveSibling("checkpoint.tmp");
            Files.writeString(temporary,new GsonBuilder().setPrettyPrinting().create().toJson(state));
            try{Files.move(temporary,STATE,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(java.nio.file.AtomicMoveNotSupportedException ex){
                Files.move(temporary,STATE,StandardCopyOption.REPLACE_EXISTING);
            }
        }catch(IOException ex){throw new IllegalStateException(ex);}
    }
    private static List<Runner> spawn(GameTestHelper t,List<Gene> genes){
        List<Runner> crew=new ArrayList<>();
        for(int i=0;i<COUNT;i++){
            int[] start=STARTS[i];BlockPos p=pos(t,start[0],1,start[1]);
            if(!t.getLevel().getBlockState(p.below()).is(GREEN))
                throw new IllegalStateException("Trial 3 start must be original GREEN wool");
            BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,t.getLevel());
            mob.setPos(p.getX()+.5,p.getY(),p.getZ()+.5);
            mob.setNoGravity(false);mob.setPersistenceRequired();
            t.getLevel().addFreshEntity(mob);
            if(Math.abs(mob.getBbWidth()-.65)>.01)
                throw new IllegalStateException("Real BuilderGolem hitbox required");
            Runner r=new Runner(i,mob,genes.get(i),new Node(start[0],start[1],0));
            r.capture(0);crew.add(r);
        }
        return crew;
    }
    private static List<Gene> breed(List<Runner> racers,Random random){
        List<Runner> ranked=new ArrayList<>(racers);
        ranked.sort(Comparator.comparingDouble((Runner r)->r.score).reversed());
        List<Gene> result=new ArrayList<>(Collections.nCopies(COUNT,null));
        for(int i=0;i<3;i++){
            Runner p=ranked.get(i);result.set(p.slot,p.gene);
        }
        int cursor=0;
        for(int i=0;i<3;i++)for(int child=0;child<2;child++){
            while(result.get(cursor)!=null)cursor++;
            result.set(cursor,ranked.get(i).gene.mutate(random));cursor++;
        }
        return result;
    }
    private static void finish(GameTestHelper t,List<Runner> crew,JsonObject state,
                               int generation,Random random,long runId){
        int completed=0,slowest=0,stalls=0,climbs=0;
        JsonArray replay=new JsonArray();
        for(Runner r:crew){
            if(r.arrival>=0){completed++;slowest=Math.max(slowest,r.arrival);}
            else r.score=-Math.hypot(r.mob.getX()-pos(t,2,1,5).getX(),
                                     r.mob.getZ()-pos(t,2,1,5).getZ());
            if(r.arrival>=0)r.score=100000-r.arrival*10;
            r.capture(LIMIT);
            stalls+=r.doorStalls;climbs+=r.climbs;
            JsonObject one=new JsonObject();
            one.addProperty("slot",r.slot);one.addProperty("arrival",r.arrival);
            one.addProperty("doorStalls",r.doorStalls);
            one.addProperty("scaffoldActions",r.climbs);
            one.addProperty("replans",r.replans);
            one.addProperty("trafficWaitTicks",r.waits);
            one.addProperty("routeCells",r.route.size());
            one.addProperty("nativeEntityTicks",r.mob.tickCount-r.spawnEntityTick);
            JsonArray frames=new JsonArray();
            for(double[] f:r.frames){
                JsonArray a=new JsonArray();
                for(double n:f)a.add(n);frames.add(a);
            }
            one.add("physicalPositions",frames);replay.add(one);
            if(r.mob.isAlive()){BuilderScaffolding.cancel(r.mob);r.mob.discard();}
        }
        JsonObject history=new JsonObject();
        history.addProperty("generation",generation);
        history.addProperty("finishedBy500Ticks",completed);
        history.addProperty("slowestTicks",completed==COUNT?slowest:-1);
        history.addProperty("doorStalls",stalls);
        history.addProperty("scaffoldActions",climbs);
        state.getAsJsonArray("history").add(history);
        state.addProperty("completedGenerations",generation);
        state.addProperty("bestFinishers",Math.max(completed,state.get("bestFinishers").getAsInt()));
        if(completed==COUNT&&(state.get("bestCrewTicks").getAsInt()<0
                ||slowest<state.get("bestCrewTicks").getAsInt())){
            state.addProperty("bestCrewTicks",slowest);
            state.add("championGenes",jsonGenes(crew.stream().map(r->r.gene).toList()));
        }
        state.add("nextPopulation",jsonGenes(breed(crew,random)));save(state);
        JsonObject record=new JsonObject();
        record.addProperty("courseHash",COURSE_HASH);
        record.addProperty("generation",generation);
        record.addProperty("runId",runId);
        record.addProperty("deadlineTicks",LIMIT);
        record.add("bots",replay);
        try{
            Files.createDirectories(REPLAY);
            Files.writeString(REPLAY.resolve("generation-"+generation+".json"),new Gson().toJson(record));
        }catch(IOException ex){throw new IllegalStateException(ex);}
        System.out.println("[TT3 REAL TRAINING] generation="+generation
                +" realBuilders=9 finish500t="+completed+"/9 slowest="
                +(completed==COUNT?slowest:"DNF")+" doorStalls="+stalls
                +" scaffoldActions="+climbs+" checkpointSaved=true");
    }
    @GameTest(maxTicks=3400,padding=72,skyAccess=true)
    public void realNineBuilderFamiliesTrainOnTimeTrial3At500Ticks(GameTestHelper t){
        JsonObject state=checkpoint();
        int startGen=state.get("completedGenerations").getAsInt();
        Random random=new Random(2026102703L+startGen*97L);
        final List<Gene>[] dna=new List[]{load(state)};
        fill(t);
        // GameTest structure chunks do not necessarily tick real entities.
        // A physical scaffold climb cannot work without an explicit
        // simulation lease; baseline course demonstrated this empirically.
        final Runnable release=holdCourseSimulation(t);
        final List<Runner>[] racers=new List[]{spawn(t,dna[0])};
        final int[] ticks={0},completed={0};
        long runId=0;
        try{runId=Long.parseLong(System.getenv().getOrDefault("GITHUB_RUN_ID","0"));}
        catch(NumberFormatException ignored){}
        final long run=runId;
        t.onEachTick(()->{
            if(completed[0]>=GENERATIONS)return;
            ticks[0]++;
            int arrivals=0;
            for(Runner r:racers[0]){
                if(r.arrival<0){
                    r.capture(ticks[0]);tick(t,r,racers[0],ticks[0]);
                }
                if(r.arrival>=0)arrivals++;
            }
            if(ticks[0]<LIMIT&&arrivals<COUNT)return;
            // Refuse to breed from coordinates driven by GameTest alone
            // if native Minecraft entity ticks did not actually run.
            for(Runner r:racers[0]){
                int expected=r.arrival<0?LIMIT:r.arrival;
                int actual=r.mob.tickCount-r.spawnEntityTick;
                if(actual<expected-2){
                    release.run();
                    for(Runner peer:racers[0]){
                        BuilderScaffolding.cancel(peer.mob);
                        if(peer.mob.isAlive())peer.mob.discard();
                    }
                    t.fail("Unqualified TT3 entity: slot="+r.slot+" expected "
                            +expected+" native ticks but got "+actual);
                    return;
                }
            }
            finish(t,racers[0],state,startGen+completed[0]+1,random,run);
            completed[0]++;ticks[0]=0;
            if(completed[0]>=GENERATIONS){
                release.run();
                if(state.get("bestFinishers").getAsInt()==COUNT)t.succeed();
                else t.fail("TT3 25-second qualification not yet met: best="
                          +state.get("bestFinishers").getAsInt()+"/9; physics and DNA saved");
                return;
            }
            dna[0]=load(state);
            fill(t);
            racers[0]=spawn(t,dna[0]);
        });
    }
}
