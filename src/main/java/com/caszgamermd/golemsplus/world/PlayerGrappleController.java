package com.caszgamermd.golemsplus.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Server-authoritative player grapple: right-click a solid ledge within
 * twenty blocks to reel physically toward it. A side column is used for
 * vertical travel so the player does not clip through the anchor.
 *
 * Right-click again to retract. No teleports, no blocks created, and no
 * creative-flight fallback. The reusable item is not consumed.
 */
public final class PlayerGrappleController {
    public static final int MAX_REACH=20;
    private static final int MAX_TICKS=180;
    private static final int STALL_TICKS=35;
    private enum Phase { APPROACH, VERTICAL, LAND }
    private static final class Rope {
        final ResourceKey<Level> dimension;
        final BlockPos anchor;
        final Vec3 approach,column,landing;
        final long started;
        GrappleLineVisuals.Line visibleLine;
        Phase phase=Phase.APPROACH;
        double bestDistance=Double.POSITIVE_INFINITY;
        long lastProgress;
        Rope(ServerLevel level,BlockPos anchor,Vec3 approach,Vec3 column,
                Vec3 landing){
            this.dimension=level.dimension();
            this.anchor=anchor.immutable();
            this.approach=approach;
            this.column=column;
            this.landing=landing;
            this.started=level.getGameTime();
            this.lastProgress=started;
        }
        Vec3 target(){
            return switch(phase){
                case APPROACH -> approach;
                case VERTICAL -> column;
                case LAND -> landing;
            };
        }
        void advance(long now){
            phase=switch(phase){
                case APPROACH -> Phase.VERTICAL;
                case VERTICAL, LAND -> Phase.LAND;
            };
            bestDistance=Double.POSITIVE_INFINITY;
            lastProgress=now;
        }
    }
    private static final Map<UUID,Rope> active=new HashMap<>();
    private PlayerGrappleController(){}

    public static void initialize(){
        ServerTickEvents.END_SERVER_TICK.register(server->{
            Set<UUID> online=new HashSet<>();
            for(ServerPlayer player:server.getPlayerList().getPlayers()){
                online.add(player.getUUID());
                tick(player);
            }
            active.keySet().removeIf(id->!online.contains(id));
        });
    }

    public static boolean active(ServerPlayer player){
        return active.containsKey(player.getUUID());
    }

    public static void noAnchor(ServerPlayer player){
        player.sendSystemMessage(Component.literal(
                "Grapple: aim at a solid ledge within 20 blocks."));
    }

    public static void release(ServerPlayer player,boolean manual){
        Rope old=active.remove(player.getUUID());
        if(old!=null&&old.visibleLine!=null)old.visibleLine.close();
        if(manual)player.sendSystemMessage(Component.literal(
                "Grapple retracted."));
    }

    private static boolean clear(ServerLevel level,ServerPlayer player,Vec3 feet){
        AABB box=player.getBoundingBox().move(feet.subtract(player.position()));
        return level.noCollision(player,box)
                &&level.getFluidState(BlockPos.containing(feet)).isEmpty();
    }

    private static Direction preferredSide(ServerPlayer player,BlockPos anchor,
            Direction hitSide){
        if(hitSide.getAxis().isHorizontal())return hitSide;
        double dx=player.getX()-anchor.getX()-.5;
        double dz=player.getZ()-anchor.getZ()-.5;
        return Math.abs(dx)>Math.abs(dz)
                ?(dx>=0?Direction.EAST:Direction.WEST)
                :(dz>=0?Direction.SOUTH:Direction.NORTH);
    }

    public static boolean attach(ServerLevel level,ServerPlayer player,
            BlockHitResult hit){
        BlockPos anchor=hit.getBlockPos();
        if(!level.hasChunkAt(anchor)
                ||player.getEyePosition().distanceToSqr(hit.getLocation())
                        >MAX_REACH*MAX_REACH
                ||!level.getBlockState(anchor).isFaceSturdy(
                        level,anchor,Direction.UP)){
            player.sendSystemMessage(Component.literal(
                    "Grapple needs a sturdy ledge within 20 blocks."));
            return false;
        }
        Vec3 landing=new Vec3(anchor.getX()+.5,anchor.getY()+1.04,
                anchor.getZ()+.5);
        if(!clear(level,player,landing)){
            player.sendSystemMessage(Component.literal(
                    "Grapple: the top of that ledge is obstructed."));
            return false;
        }
        Direction preferred=preferredSide(player,anchor,hit.getDirection());
        Direction[] options={preferred,preferred.getClockWise(),
                preferred.getCounterClockWise(),preferred.getOpposite()};
        for(Direction side:options){
            Vec3 outside=new Vec3(anchor.getX()+.5+side.getStepX(),
                    0,anchor.getZ()+.5+side.getStepZ());
            Vec3 approach=new Vec3(outside.x,player.getY(),outside.z);
            Vec3 column=new Vec3(outside.x,landing.y+.32,outside.z);
            if(!clear(level,player,approach)
                    ||!clear(level,player,column))continue;
            boolean shaftOpen=true;
            double low=Math.min(approach.y,column.y);
            double high=Math.max(approach.y,column.y);
            for(double y=low;y<=high;y+=.75)
                if(!clear(level,player,new Vec3(outside.x,y,outside.z))){
                    shaftOpen=false;break;
                }
            if(!shaftOpen)continue;
            Rope rope=new Rope(level,anchor,approach,column,landing);
            rope.visibleLine=GrappleLineVisuals.attach(level,player.getUUID(),
                    anchor,BlockPos.containing(approach),
                    approach.y,anchor.getY()+1.0);
            active.put(player.getUUID(),rope);
            player.sendSystemMessage(Component.literal(
                    "Grapple attached: reeling to "+anchor.toShortString()
                    +". Right-click again to retract."));
            return true;
        }
        player.sendSystemMessage(Component.literal(
                "Grapple: no clear rope column beside that ledge."));
        return false;
    }

    private static void tick(ServerPlayer player){
        Rope rope=active.get(player.getUUID());
        if(rope==null)return;
        if(!(player.level() instanceof ServerLevel level)
                ||!player.isAlive()||!rope.dimension.equals(level.dimension())
                ||!player.getMainHandItem().is(WorksiteContent.GRAPPLE_HOOK)
                    &&!player.getOffhandItem().is(WorksiteContent.GRAPPLE_HOOK)
                ||!level.hasChunkAt(rope.anchor)
                ||!level.getBlockState(rope.anchor).isFaceSturdy(
                        level,rope.anchor,Direction.UP)
                ||level.getGameTime()-rope.started>MAX_TICKS
                ||player.position().distanceToSqr(rope.landing)>26*26){
            release(player,false);
            return;
        }
        Vec3 target=rope.target();
        double distance=player.position().distanceTo(target);
        if(distance<.32){
            if(rope.phase==Phase.LAND){
                release(player,false);
                return;
            }
            rope.advance(level.getGameTime());
            target=rope.target();
            distance=player.position().distanceTo(target);
        }
        if(distance+.035<rope.bestDistance){
            rope.bestDistance=distance;
            rope.lastProgress=level.getGameTime();
        }
        if(level.getGameTime()-rope.lastProgress>STALL_TICKS){
            release(player,false);
            player.sendSystemMessage(Component.literal(
                    "Grapple released: route blocked."));
            return;
        }
        Vec3 offset=target.subtract(player.position());
        double horizontal=Math.hypot(offset.x,offset.z);
        double horizontalSpeed=Math.min(.43,horizontal*.22);
        double vx=horizontal<.001?0:offset.x/horizontal*horizontalSpeed;
        double vz=horizontal<.001?0:offset.z/horizontal*horizontalSpeed;
        double vy=Mth.clamp(offset.y*.25+(player.onGround()?0:.08),-.38,.48);
        player.setDeltaMovement(vx,vy,vz);
        player.hurtMarked=true;
        player.fallDistance=0;
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        // The real hook, bottom coil and vertical rope are world-visible
        // item displays. Sparkle particles no longer stand in for equipment.
        if(rope.visibleLine!=null)rope.visibleLine.touch();
    }
}
