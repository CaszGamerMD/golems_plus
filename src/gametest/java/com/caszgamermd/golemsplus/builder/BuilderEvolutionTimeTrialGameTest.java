package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.util.ArrayList;
import java.util.List;
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
 * PHYSICAL Minecraft verification of the first headless-trained 20-bot
 * timetrial winner. Every bot is a real 0.65 x 0.65 x 1.6 BuilderGolem,
 * must cross actual blocks, doors, half slabs and other bots, with no
 * breaking/scaffold/flying and at most 0.20 blocks of movement per tick.
 *
 * This is a REPLAY ACCEPTANCE TEST, not an in-game genetic optimizer.
 * Failed physical runs must inform later generations of the separate
 * training engine; headless success alone is not a GameTest pass.
 */
public final class BuilderEvolutionTimeTrialGameTest {
    private static final int DEADLINE=400;
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
    // All chosen routes completed its own 400-tick simulated deadline.
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

    private static final class Candidate {
        final BuilderGolem mob;
        final String route;
        int cursor;
        int x,z;
        long reached=-1;
        double bestDistance;
        int immobile;
        Candidate(BuilderGolem mob,String route,int x,int z){
            this.mob=mob;this.route=route;this.x=x;this.z=z;
        }
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

    @GameTest(maxTicks=460,padding=64,skyAccess=true)
    public void realBuildersTryUserMazeInTwentySeconds(GameTestHelper helper){
        ServerLevel world=helper.getLevel();
        fillOriginalCourse(helper);
        List<Candidate> bots=new ArrayList<>();
        if(STARTS.length!=20||REPLAYS.length!=20){
            helper.fail("Expected exactly twenty first-generation genomes");
            return;
        }
        for(int i=0;i<20;i++){
            int[] start=STARTS[i];
            if(helper.getBlockState(new BlockPos(
                    X_OFFSET+start[0],1,Z_OFFSET+start[1]))
                    .getBlock()!=GREEN){
                helper.fail("Training bot not placed on green wool");
                return;
            }
            BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
            BlockPos at=pos(helper,start[0],2,start[1]);
            mob.setPos(at.getX()+.5,at.getY(),at.getZ()+.5);
            mob.setPersistenceRequired();
            mob.setNoGravity(false);
            world.addFreshEntity(mob);
            if(Math.abs(mob.getBbWidth()-.65)>.01
                    ||Math.abs(mob.getBbHeight()-1.6)>.01){
                helper.fail("Training Builder physical collision box is "
                        +mob.getBbWidth()+" x "+mob.getBbHeight());
                return;
            }
            bots.add(new Candidate(mob,REPLAYS[i],start[0],start[1]));
        }
        helper.onEachTick(()->{
            long tick=helper.getTick();
            for(Candidate b:bots){
                if(b.reached>=0)continue;
                if(!b.mob.isAlive()){
                    helper.fail("Trial Builder disappeared at tick "+tick);
                    return;
                }
                BlockPos under=b.mob.blockPosition().below();
                if(world.getBlockState(under).is(RED)){
                    b.reached=tick;
                    b.mob.getNavigation().stop();
                    b.mob.setDeltaMovement(Vec3.ZERO);
                    continue;
                }
                if(tick>DEADLINE||b.cursor>=b.route.length())
                    continue;
                int[] destination=next(b.x,b.z,b.route.charAt(b.cursor));
                BlockPos cell=pos(helper,destination[0],2,destination[1]);
                if(b.mob.getX()>cell.getX()+.5-.10
                        &&b.mob.getX()<cell.getX()+.5+.10
                        &&b.mob.getZ()>cell.getZ()+.5-.10
                        &&b.mob.getZ()<cell.getZ()+.5+.10){
                    b.x=destination[0];
                    b.z=destination[1];
                    b.cursor++;
                    continue;
                }
                double vx=Math.max(-.2,Math.min(.2,
                        cell.getX()+.5-b.mob.getX()));
                double vz=Math.max(-.2,Math.min(.2,
                        cell.getZ()+.5-b.mob.getZ()));
                // Real Minecraft movement: no teleporting, no collision
                // bypass, no upward/flight commands, and no block edits.
                // Step height and gravity are the vanilla entity's.
                b.mob.setNoGravity(false);
                b.mob.getNavigation().stop();
                Vec3 velocity=b.mob.getDeltaMovement();
                b.mob.setDeltaMovement(0,velocity.y,0);
                b.mob.move(MoverType.SELF,new Vec3(vx,0,vz));
            }
            if(tick>=DEADLINE){
                int finished=0;
                StringBuilder misses=new StringBuilder();
                for(Candidate b:bots){
                    if(b.reached>=0&&b.reached<=DEADLINE)finished++;
                    else if(misses.length()<1200)
                        misses.append(" start=").append(b.route.length())
                                .append(" idx=").append(b.cursor)
                                .append(" pos=").append(b.mob.position());
                    // Simulate tournament cutoff: kill/reset all bots after
                    // 20s before the next generation would be spawned.
                    b.mob.discard();
                }
                System.out.println("[TIMETRIAL PHYSICAL] "+finished
                        +"/20 arrived on red wool within 400 game ticks; "
                        +"missed:"+misses);
                if(finished==bots.size())helper.succeed();
                else helper.fail("Real Minecraft 20s trial did not pass: "+
                        finished+"/20 reached red; "+misses);
            }
        });
    }
}
