package com.caszgamermd.golemsplus.world;

import com.caszgamermd.golemsplus.beekeeper.BeeToolboxBlockEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

/**
 * Server-owned, one-shot selection of an output inventory for a job block.
 * Pending clicks expire and never persist; the chosen target is saved by
 * WorkerStationBlockEntity. World use is consumed on selection.
 */
public final class OutputChestSelection {
    private static final long TIMEOUT_TICKS = 1200L;
    private record Pending(ResourceKey<Level> dimension, BlockPos station,
                           String kind, long expires) {}
    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private OutputChestSelection() {}

    public static void initialize() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!(player instanceof ServerPlayer server)
                    || !(level instanceof ServerLevel world)
                    || hand != InteractionHand.MAIN_HAND)
                return InteractionResult.PASS;
            Pending pending = PENDING.get(server.getUUID());
            if (pending == null) return InteractionResult.PASS;
            if (world.getGameTime() > pending.expires()
                    || !world.dimension().equals(pending.dimension())) {
                PENDING.remove(server.getUUID());
                tell(server, "Chest selection expired; use the job block again");
                return InteractionResult.PASS;
            }
            if (!world.hasChunkAt(pending.station())
                    || !(world.getBlockEntity(pending.station())
                            instanceof WorkerStationBlockEntity station)
                    || !station.canConfigure(server.getUUID())
                    || (pending.kind().equals("fisher") ? !station.isFishing()
                        :pending.kind().equals("builder")?!station.isBuilder()
                        :!station.isBeekeeper())) {
                PENDING.remove(server.getUUID());
                tell(server, "Job block is unavailable or no longer owned by you");
                return InteractionResult.SUCCESS;
            }
            BlockPos target = hit.getBlockPos();
            int maxRange = pending.kind().equals("builder") ? 256
                    :pending.kind().equals("fisher") ? 16 : 8;
            if ((pending.kind().equals("builder")
                        ? station.builderOrigin().distSqr(target)>128L*128L
                        :pending.station().distSqr(target)>maxRange*maxRange)
                    || !world.mayInteract(server, target)) {
                tell(server, "Choose an accessible chest within " + maxRange + " blocks");
                return InteractionResult.SUCCESS;
            }
            if (!world.hasChunkAt(target)
                    || !(world.getBlockEntity(target) instanceof Container box)
                    || box instanceof WorkerStationBlockEntity
                    || box instanceof BeeToolboxBlockEntity
                    || box instanceof AbstractFurnaceBlockEntity) {
                tell(server, "Right-click a chest, barrel, shulker box or storage container");
                return InteractionResult.SUCCESS;
            }
            boolean success = pending.kind().equals("builder")
                    ?station.setBuilderMaterialStorage(server.getUUID(),target)
                    :pending.kind().equals("fisher")
                        ?station.setFisherOutput(server.getUUID(),target)
                        :station.setBeeLink(server.getUUID(),false,target);
            if (success) {
                PENDING.remove(server.getUUID());
                tell(server, "Output linked at " + target.toShortString());
            } else {
                tell(server, "Could not link the selected storage container");
            }
            return InteractionResult.SUCCESS;
        });
    }

    public static void begin(ServerPlayer player, WorkerStationBlockEntity station,
                             boolean fisher) {
        if (!station.canConfigure(player.getUUID())
                || (fisher ? !station.isFishing() : !station.isBeekeeper()))
            return;
        PENDING.put(player.getUUID(), new Pending(player.level().dimension(),
                station.getBlockPos().immutable(), fisher?"fisher":"beekeeper",
                player.level().getGameTime() + TIMEOUT_TICKS));
        tell(player, "Right-click an output chest to link it ("
                + (fisher ? 16 : 8) + "-block range; expires in 60 seconds)");
    }

    public static void beginBuilder(ServerPlayer player,WorkerStationBlockEntity station){
        if(!station.isBuilder()||!station.canConfigure(player.getUUID())
                ||!station.hasPlacedSchematic())return;
        PENDING.put(player.getUUID(),new Pending(player.level().dimension(),
                station.getBlockPos().immutable(),"builder",
                player.level().getGameTime()+TIMEOUT_TICKS));
        tell(player,"Right-click a container within 128 blocks of the selected build "
                +"to stage the warehouse's physical material shulkers.");
    }
    private static void tell(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal("[Job Block] " + message));
    }
}
