package com.caszgamermd.golemsplus.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * World-visible, non-colliding equipment for an ACTUALLY attached grapple.
 * Item display entities replicate the same hook, coil and rope to all
 * clients. They never replace the real clearance or climbing physics.
 */
public final class GrappleLineVisuals {
    private static final String TAG="golems_plus_grapple_visual";
    private static final Set<UUID> activePieces=new HashSet<>();
    private static final Map<UUID,Line> lines=new HashMap<>();
    private static long serverTick;
    private GrappleLineVisuals(){}

    public static void initialize(){
        // Display entities save by default. Remove any orphaned decoration
        // when a world/chunk reloads after a server restart mid-grapple.
        ServerEntityEvents.ENTITY_LOAD.register((entity,world)->{
            if(entity instanceof Display.ItemDisplay
                    &&entity.getCustomName()!=null
                    &&TAG.equals(entity.getCustomName().getString())
                    &&!activePieces.contains(entity.getUUID()))
                entity.discard();
        });
        // Also handle abrupt player disconnects, despawned Builders, or
        // vanished ropes without leaving hundreds of saved display items.
        ServerTickEvents.END_SERVER_TICK.register(server->{
            serverTick++;
            for(Line line:new ArrayList<>(lines.values()))
                if(serverTick-line.lastTouched>45)line.close();
        });
    }

    public static final class Line {
        private final UUID owner;
        private final List<Display.ItemDisplay> pieces=new ArrayList<>();
        private long lastTouched;
        private boolean closed;
        private Line(UUID owner){
            this.owner=owner;
            lastTouched=serverTick;
        }
        public int pieceCount(){return pieces.size();}
        public boolean valid(){
            return !closed&&!pieces.isEmpty()
                    &&pieces.stream().allMatch(Display.ItemDisplay::isAlive);
        }
        public void touch(){lastTouched=serverTick;}
        public void close(){
            if(closed)return;
            closed=true;
            if(lines.get(owner)==this)lines.remove(owner);
            for(var piece:pieces){
                activePieces.remove(piece.getUUID());
                piece.discard();
            }
            pieces.clear();
        }
    }

    private static void add(ServerLevel level,Line line,Item item,
            double x,double y,double z,float yaw,float pitch){
        var type=BuiltInRegistries.ENTITY_TYPE.getValue(
                Identifier.fromNamespaceAndPath("minecraft","item_display"));
        if(type==null)return;
        Display.ItemDisplay model=new Display.ItemDisplay(type,level);
        model.setPos(x,y,z);
        model.setYRot(yaw);
        model.setXRot(pitch);
        model.setNoGravity(true);
        model.setInvulnerable(true);
        model.setCustomName(Component.literal(TAG));
        if(!model.getSlot(0).set(new ItemStack(item)))return;
        activePieces.add(model.getUUID());
        if(level.addFreshEntity(model))line.pieces.add(model);
        else activePieces.remove(model.getUUID());
    }

    /** Bottom Y is solid standing-height; top Y is the anchor's real
     * collision top (also valid for partial roof blocks). A thin, vertical
     * rope occupies the already-validated adjacent air column.
     */
    public static Line attach(ServerLevel level,UUID owner,
            BlockPos anchor,BlockPos column,double bottomY,double topY){
        Line previous=lines.get(owner);
        if(previous!=null)previous.close();
        if(!Double.isFinite(bottomY)||!Double.isFinite(topY)
                ||topY-bottomY<.3||topY-bottomY>22
                ||!level.hasChunkAt(anchor)||!level.hasChunkAt(column))
            return null;
        Line line=new Line(owner);
        lines.put(owner,line);
        // The reusable grapple hook sits on the UPPER block, not on the
        // Golem. The lead sprite is a coiled rope at the LOWER footing.
        add(level,line,WorksiteContent.GRAPPLE_HOOK,
                anchor.getX()+.5,topY+.12,anchor.getZ()+.5,0,70);
        add(level,line,Items.LEAD,
                column.getX()+.5,bottomY+.13,column.getZ()+.5,0,85);

        // One thin modeled rope length per vertical cell. Unlike sparks,
        // the full line stays visible while the Builder climbs/descends.
        int spans=(int)Math.ceil(topY-bottomY);
        for(int n=0;n<spans;n++){
            double center=Math.min(topY-.45,bottomY+.55+n);
            add(level,line,WorksiteContent.GRAPPLE_ROPE_SEGMENT,
                    column.getX()+.5,center,column.getZ()+.5,0,0);
        }
        // Bridge the short horizontal distance from the hook on the
        // block to the vertical rope hanging beside its outward face.
        double dx=column.getX()-anchor.getX();
        double dz=column.getZ()-anchor.getZ();
        if(Math.abs(dx)+Math.abs(dz)==1){
            add(level,line,WorksiteContent.GRAPPLE_ROPE_LINK,
                    anchor.getX()+.5+dx*.5,topY+.07,
                    anchor.getZ()+.5+dz*.5,
                    dz!=0?90:0,0);
        }
        return line;
    }
}
