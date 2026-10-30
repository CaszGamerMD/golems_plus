package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.worker.WorkerRoutePlanner;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Geometry-model survey, NOT a magical pre-recorded dome route.
 * Every block comes from the exact checksum-verified user litematic.
 * World footing/headroom come from Minecraft's actual VoxelShapes.
 * All coordinates in the output are the schematic's local XYZ.
 *
 * Two models are kept separate:
 * 1) The complete intended schematic: all 3,537 indexed block entries.
 * 2) Pre-roof constructed snapshot: all nonvegetation entries below Y30,
 *    but not the unfinished upper platform. This is NOT a claim of an
 *    exact tick-by-tick Y30 snapshot; the real dome GameTest separately
 *    logs live worker positions and remaining blocks.
 */
public final class BuilderDomeRouteSurveyGameTest {
    private record Pair(String name,BlockPos start,BlockPos target){}
    private static final Pair[] CASES={
        new Pair("NORTH",new BlockPos(8,26,11),new BlockPos(15,30,11)),
        new Pair("SOUTH",new BlockPos(9,26,24),new BlockPos(15,30,24)),
        new Pair("COBBLE_LEG",new BlockPos(8,26,12),new BlockPos(15,30,12)),
        new Pair("DIORITE_LEG",new BlockPos(22,29,23),new BlockPos(20,30,22))
    };
    private static BlockPos abs(BlockPos o,BlockPos rel){
        return o.offset(rel.getX(),rel.getY(),rel.getZ());
    }
    private static String rel(BlockPos o,BlockPos p){
        return p.subtract(o).toShortString();
    }
    private static char symbol(BlockState s){
        if(s.isAir())return '.';
        if(s.getBlock() instanceof WallBlock)return 'W';
        if(s.getBlock() instanceof FenceBlock)return 'F';
        if(s.getBlock() instanceof SlabBlock)return '=';
        if(s.getBlock() instanceof StairBlock)return '^';
        if(s.getBlock() instanceof TransparentBlock
                ||s.getBlock() instanceof StainedGlassBlock)return 'G';
        String n=s.getBlock().toString();
        if(n.contains("diorite"))return 'D';
        if(n.contains("cobblestone"))return 'C';
        if(n.contains("scaffolding"))return 'H';
        return '#';
    }
    private static String grid(ServerLevel world,BlockPos origin,
            int y,Pair focus,Set<BlockPos> reachable){
        StringBuilder map=new StringBuilder();
        map.append("[ROUTE SLICE] case=").append(focus.name())
                .append(" Y=").append(y).append(" (schematic local)\n");
        map.append("x  ");
        for(int x=5;x<=26;x++)map.append(x%10);
        map.append("\n");
        for(int z=6;z<=28;z++){
            map.append(String.format(java.util.Locale.ROOT,"%02d ",z));
            for(int x=5;x<=26;x++){
                BlockPos local=new BlockPos(x,y,z);
                char c=symbol(world.getBlockState(abs(origin,local)));
                if(reachable.contains(abs(origin,local)))c='o';
                if(local.equals(focus.start()))c='A';
                if(local.equals(focus.target()))c='T';
                map.append(c);
            }
            map.append('\n');
        }
        return map.toString();
    }
    private static String nodePath(BlockPos origin,WorkerRoutePlanner.Route route){
        if(route==null)return "none";
        if(route.steps().isEmpty())return "at_target";
        StringBuilder v=new StringBuilder("length=").append(route.steps().size())
                .append(" cost=").append(route.cost())
                .append(" complete=").append(route.complete()).append(" waypoints=");
        int k=0;
        for(int i=0;i<route.steps().size();i++){
            BlockPos here=route.steps().get(i);
            if(i>0&&i<route.steps().size()-1){
                BlockPos before=route.steps().get(i-1),next=route.steps().get(i+1);
                if(next.getX()-here.getX()==here.getX()-before.getX()
                        &&next.getZ()-here.getZ()==here.getZ()-before.getZ()
                        &&next.getY()-here.getY()==here.getY()-before.getY())
                    continue;
            }
            if(k++>26){v.append("...");break;}
            v.append(' ').append(rel(origin,here));
        }
        return v.toString();
    }
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=180,skyAccess=true)
    public void surveyOriginalDomeRoofSurfaceAndWorkerRoutes(GameTestHelper h){
        ServerLevel world=h.getLevel();
        BlockPos origin=h.absolutePos(new BlockPos(8,2,8));
        world.getChunkSource().addTicketWithRadius(
                net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(origin.getX()>>4,
                        origin.getZ()>>4),4);
        for(int x=4;x<=42;x++)for(int z=4;z<=42;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        var original=new HashMap<BlockPos,BlockState>();
        try{
            var schematic=LitematicFileReader.read(BuilderDomeFixture.unpack());
            for(var cell:schematic.blocks()){
                var resolved=BuilderBlockResolver.resolve(cell.state(),
                        BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
                if(resolved==null)continue;
                BlockPos p=new BlockPos(cell.x(),cell.y(),cell.z());
                original.put(p,resolved.state());
                if(cell.y()<30&&!BuilderVegetation.ignoreSchematicState(cell.state()))
                    world.setBlock(abs(origin,p),resolved.state(),2);
            }
        }catch(Exception ex){h.fail("Original dome model failed "+ex);return;}
        if(original.size()!=3537){h.fail("Blueprint model missing blocks: "
                +original.size());return;}
        // These are LAST OBSERVED physical worker positions, not
        // manufactured locations reached by the golem during this test.
        List<BuilderGolem> workers=new ArrayList<>();
        for(Pair p:CASES){
            BuilderGolem mob=new BuilderGolem(
                    WorksiteContent.BUILDER_GOLEM,world);
            BlockPos spawn=abs(origin,p.start());
            mob.setPos(spawn.getX()+.5,spawn.getY(),spawn.getZ()+.5);
            world.addFreshEntity(mob);
            workers.add(mob);
        }
        h.onEachTick(()->{
            if(h.getTick()!=22)return;
            try{
                Path out=Path.of("route-survey");
                Files.createDirectories(out);
                StringBuilder report=new StringBuilder();
                report.append("# Exact dome collision/route survey\n");
                report.append("Original block entries: ").append(original.size())
                        .append("; expected required placements: 3493")
                        .append("; ignored vegetation: 44\n");
                report.append("World: real Minecraft voxel collision; schematic-local XYZ;")
                        .append(" snapshot contains constructed Y<30,")
                        .append(" and AIR for Y>=30. No prebuilt upper roof.\n");
                StringBuilder csv=new StringBuilder("x,y,z,intendedBlock,physicalBlock,collisionTop\n");
                var ordered=new ArrayList<>(original.entrySet());
                ordered.sort(Comparator.comparingInt((Map.Entry<BlockPos,BlockState> x)->x.getKey().getY())
                        .thenComparingInt(x->x.getKey().getX())
                        .thenComparingInt(x->x.getKey().getZ()));
                for(var cell:ordered){
                    BlockPos local=cell.getKey();
                    BlockPos pos=abs(origin,local);
                    BlockState now=world.getBlockState(pos);
                    VoxelShape actual=now.getCollisionShape(world,pos);
                    csv.append(local.getX()).append(',').append(local.getY()).append(',')
                            .append(local.getZ()).append(',')
                            .append('"').append(cell.getValue()).append('"').append(',')
                            .append('"').append(now).append('"').append(',')
                            .append(actual.isEmpty()?0:actual.max(Direction.Axis.Y)).append('\n');
                }
                Files.writeString(out.resolve("original_dome_exact_model.csv"),csv,
                        StandardCharsets.UTF_8);
                int id=0;
                for(Pair c:CASES){
                    var mob=workers.get(id++);
                    BlockPos start=abs(origin,c.start()),target=abs(origin,c.target());
                    BlockState targetState=original.get(c.target());
                    Set<BlockPos> reachable=WorkerNavigation.groundReachableFrom(
                            world,mob,mob.blockPosition());
                    report.append("\n[ROUTE CASE] ").append(c.name())
                            .append(" start=").append(c.start())
                            .append(" actualFeet=").append(rel(origin,mob.blockPosition()))
                            .append(" grounded=").append(mob.onGround())
                            .append(" goal=").append(c.target())
                            .append(" intended=").append(targetState)
                            .append(" current=").append(world.getBlockState(target))
                            .append(" below=").append(world.getBlockState(target.below()))
                            .append(" reachableNodes=").append(reachable.size())
                            .append(" startStanding=").append(
                                    WorkerNavigation.canStandOn(world,mob,mob.blockPosition()))
                            .append('\n');
                    List<BlockPos> poses=new ArrayList<>();
                    int stand=0,sight=0,escapes=0,walkable=0;
                    for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)
                        for(int dy=0;dy<=1;dy++){
                            BlockPos at=target.offset(dx,dy,dz);
                            if(at.equals(target)||at.distSqr(target)>=14)continue;
                            double px=at.getX()+.5,pz=at.getZ()+.5;
                            double ex=Math.max(0,Math.max(target.getX()-px,
                                    px-target.getX()-1));
                            double ez=Math.max(0,Math.max(target.getZ()-pz,
                                    pz-target.getZ()-1));
                            if(Math.hypot(ex,ez)<.8
                                    ||!WorkerNavigation.canStandOn(world,mob,at))
                                continue;
                            stand++;
                            if(!BuilderConstructionPlanner.lineOfSight(
                                    world,mob,at,target))continue;
                            sight++;
                            if(!BuilderConstructionPlanner.safeStand(
                                    world,mob,target,at))continue;
                            escapes++;
                            poses.add(at);
                            if(reachable.contains(at))walkable++;
                        }
                    report.append("[WORK POSES] standable=").append(stand)
                            .append(" sight=").append(sight)
                            .append(" safeEscape=").append(escapes)
                            .append(" sameComponent=").append(walkable).append('\n');
                    var route=WorkerRoutePlanner.plan(mob.blockPosition(),poses,
                            p->WorkerNavigation.canStandOn(world,mob,p),
                            p->WorkerNavigation.surveyFloorHeight(world,p),null);
                    report.append("[GROUND ROUTE] ")
                            .append(nodePath(origin,route)).append('\n');
                    List<BlockPos> footholds=new ArrayList<>(reachable);
                    footholds.sort(Comparator.comparingDouble(p->
                            p.distSqr(target)));
                    report.append("[CLOSEST REACHABLE FOOTHOLDS]");
                    for(int i=0;i<Math.min(12,footholds.size());i++){
                        var at=footholds.get(i);
                        report.append(' ').append(rel(origin,at))
                            .append(':').append(
                                    world.getBlockState(at.below()).getBlock());
                    }
                    report.append('\n');
                    for(int y:new int[]{26,28,29,30,31})
                        report.append(grid(world,origin,y,c,reachable));
                    System.out.println("[DOME ROUTE SURVEY] "+c.name()
                            +" reachable="+reachable.size()
                            +" safePoses="+escapes+" connected="+walkable
                            +" groundRoute="+nodePath(origin,route));
                }
                Files.writeString(out.resolve("roof_route_survey.txt"),report,
                        StandardCharsets.UTF_8);
                System.out.println("[DOME MODEL] PASS model=3537 original blocks;"
                        +" output=route-survey/original_dome_exact_model.csv"
                        +" output=route-survey/roof_route_survey.txt");
                h.succeed();
            }catch(Exception e){h.fail("Dome survey failed: "+e);}
        });
    }
}
