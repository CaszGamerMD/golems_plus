package com.caszgamermd.golemsplus.breakroom;

import java.util.*;
import com.caszgamermd.golemsplus.builder.BuilderGolem;
import com.caszgamermd.golemsplus.beekeeper.BeekeeperGolem;
import com.caszgamermd.golemsplus.trader.TraderGolem;
import com.caszgamermd.golemsplus.cooking.ChefGolem;
import com.caszgamermd.golemsplus.fishing.FisherGolem;
import com.caszgamermd.golemsplus.worker.*;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.core.*;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Server-owned Break Room routines. Golems physically path onto chair seats,
 * collect a transient snack from a vending machine, eat while seated, or play
 * an arcade with positional block sounds.
 */
public final class GolemBreakService {
    private static final int SYNC_SEATED = 1;
    private static final int SYNC_EATING = 2;
    private static final int SYNC_HAS_SNACK = 4;
    private static final int SYNC_ARCADE = 8;
    private static final int SYNC_SNACK_SHIFT = 4;

    private enum Visit { NONE, CHAIR, SNACK_MACHINE, EATING, ARCADE }

    private static final Map<PathfinderMob, Rest> workers = new WeakHashMap<>();

    private static final class Rest {
        BlockPos furniture;
        BlockPos stand;
        BlockPos reservedChair;
        BlockPos table;
        Visit visit = Visit.NONE;
        long chooseAt;
        long nextArcadeSound;
        int rest;
        int phaseTicks;
        int breakLength = 280;
        int arcadeNotes;
        int snackKind = -1;
        boolean ready;
        boolean working;
    }

    private record Seat(BlockPos chair, BlockPos table) {}
    private record Candidate(Visit visit, BlockPos furniture, BlockPos stand,
                             BlockPos reservedChair, BlockPos table) {}

    private GolemBreakService() {}

    private static BlockPos home(PathfinderMob mob) {
        if (mob instanceof BuilderGolem g) return g.home();
        if (mob instanceof BeekeeperGolem g) return g.home();
        if (mob instanceof TraderGolem g) return g.home();
        if (mob instanceof ChefGolem g) return g.home();
        if (mob instanceof FisherGolem g) return g.home();
        return null;
    }

    private static EntityDataAccessor<Integer> stateAccessor(PathfinderMob mob) {
        if (mob instanceof BuilderGolem) return BuilderGolem.BREAK_ROOM_STATE;
        if (mob instanceof BeekeeperGolem) return BeekeeperGolem.BREAK_ROOM_STATE;
        if (mob instanceof TraderGolem) return TraderGolem.BREAK_ROOM_STATE;
        if (mob instanceof ChefGolem) return ChefGolem.BREAK_ROOM_STATE;
        if (mob instanceof FisherGolem) return FisherGolem.BREAK_ROOM_STATE;
        return null;
    }

    private static int syncedState(PathfinderMob mob) {
        EntityDataAccessor<Integer> key = stateAccessor(mob);
        return key == null ? 0 : mob.getEntityData().get(key);
    }

    private static void setSyncedState(PathfinderMob mob, int value) {
        EntityDataAccessor<Integer> key = stateAccessor(mob);
        if (key != null && mob.getEntityData().get(key) != value)
            mob.getEntityData().set(key, value);
    }

    public static boolean breakRoomSeated(PathfinderMob mob) {
        return (syncedState(mob) & SYNC_SEATED) != 0;
    }

    public static boolean eating(PathfinderMob mob) {
        return (syncedState(mob) & SYNC_EATING) != 0;
    }

    public static boolean playingArcade(PathfinderMob mob) {
        return (syncedState(mob) & SYNC_ARCADE) != 0;
    }

    public static boolean hasSnack(PathfinderMob mob) {
        return (syncedState(mob) & SYNC_HAS_SNACK) != 0;
    }

    /** Render-only food item, encoded in the same synced state as the pose. */
    public static ItemStack snackForRender(PathfinderMob mob) {
        int state = syncedState(mob);
        if ((state & SYNC_HAS_SNACK) == 0) return ItemStack.EMPTY;
        return snackStack((state >>> SYNC_SNACK_SHIFT) & 7);
    }

    private static ItemStack snackStack(int kind) {
        return new ItemStack(switch (kind) {
            case 1 -> Items.APPLE;
            case 2 -> Items.BREAD;
            case 3 -> Items.BAKED_POTATO;
            case 4 -> Items.PUMPKIN_PIE;
            default -> Items.COOKIE;
        });
    }

    private static void publish(PathfinderMob mob, Rest rest, boolean atTarget) {
        if (rest == null || rest.visit == Visit.NONE) {
            setSyncedState(mob, 0);
            return;
        }
        int value = 0;
        if (atTarget && (rest.visit == Visit.CHAIR || rest.visit == Visit.EATING))
            value |= SYNC_SEATED;
        if (atTarget && rest.visit == Visit.EATING && rest.snackKind >= 0
                && rest.phaseTicks < 100)
            value |= SYNC_EATING;
        if (rest.snackKind >= 0) {
            value |= SYNC_HAS_SNACK;
            value |= (rest.snackKind & 7) << SYNC_SNACK_SHIFT;
        }
        if (atTarget && rest.visit == Visit.ARCADE)
            value |= SYNC_ARCADE;
        setSyncedState(mob, value);
    }

    public static boolean rested(PathfinderMob mob) {
        Rest rest = workers.get(mob);
        return rest != null && rest.ready;
    }

    public static void saveRest(PathfinderMob mob,
                                net.minecraft.world.level.storage.ValueOutput out) {
        out.putBoolean("golems_plus_rested", rested(mob));
    }

    public static void loadRest(PathfinderMob mob,
                                net.minecraft.world.level.storage.ValueInput in) {
        workers.computeIfAbsent(mob, ignored -> new Rest()).ready =
                in.getBooleanOr("golems_plus_rested", false);
        setSyncedState(mob, 0);
    }

    public static boolean takingBreak(PathfinderMob mob) {
        Rest rest = workers.get(mob);
        return rest != null && rest.visit != Visit.NONE && !rest.working;
    }

    public static void initialize() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof PathfinderMob mob && home(mob) != null)
                workers.computeIfAbsent(mob, ignored -> new Rest());
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (var row : new ArrayList<>(workers.entrySet())) {
                if (row.getKey().level() instanceof ServerLevel world
                        && world.getGameTime() % 5 == 0)
                    tick(world, row.getKey(), row.getValue());
            }
        });
    }

    private static void tick(ServerLevel world, PathfinderMob mob, Rest rest) {
        BlockPos home = home(mob);
        if (home == null || !mob.isAlive() || mob.isPassenger()
                || !world.hasChunkAt(home)) return;
        if (!(world.getBlockEntity(home) instanceof WorkerStationBlockEntity station)) return;

        boolean working = (station.activeOrder() != null || station.chefBusy()
                || station.isFishing())
                && station.phase() != WorkerPhase.PAUSED
                && station.phase() != WorkerPhase.COMPLETE
                && station.phase() != WorkerPhase.IDLE;
        if (mob instanceof BeekeeperGolem bee)
            working = bee.job() != 0 && station.phase() != WorkerPhase.PAUSED;
        if (mob instanceof TraderGolem trader)
            working = trader.busy() && station.phase() != WorkerPhase.PAUSED;

        long now = world.getGameTime();
        if (working) {
            if (!rest.working && rest.ready) {
                mob.addEffect(new MobEffectInstance(MobEffects.SPEED, 3600, 0));
                rest.ready = false;
            }
            if (rest.visit != Visit.NONE) WorkerNavigation.stop(mob);
            clearVisit(rest);
            rest.working = true;
            publish(mob, rest, false);
            return;
        }
        rest.working = false;

        if (rest.visit == Visit.NONE) {
            publish(mob, rest, false);
            if (now < rest.chooseAt) return;
            rest.chooseAt = now + 80 + mob.getRandom().nextInt(160);
            chooseVisit(world, mob, rest, home);
            publish(mob, rest, false);
            return;
        }

        if (!validVisit(world, rest)) {
            abandon(world, mob, rest);
            return;
        }

        if (!atTarget(world, mob, rest.stand)) {
            if (!WorkerNavigation.routeToExact(world, mob, rest.stand, .65)) {
                abandon(world, mob, rest);
                return;
            }
            publish(mob, rest, false);
            return;
        }

        WorkerNavigation.stop(mob);
        switch (rest.visit) {
            case CHAIR -> {
                faceChair(world, mob, rest.furniture, rest.table);
                rest.rest += 5;
            }
            case EATING -> {
                faceChair(world, mob, rest.furniture, rest.table);
                rest.phaseTicks += 5;
                if (rest.snackKind >= 0 && rest.phaseTicks >= 100)
                    rest.snackKind = -1;
                else if (rest.snackKind >= 0 && rest.phaseTicks % 30 == 0)
                    mob.swing(InteractionHand.MAIN_HAND);
                rest.rest += 5;
            }
            case SNACK_MACHINE -> {
                faceToward(mob, rest.furniture);
                if (rest.phaseTicks == 0) mob.swing(InteractionHand.MAIN_HAND);
                rest.phaseTicks += 5;
                if (rest.phaseTicks >= 30) {
                    world.playSound(null, rest.furniture.getX() + .5,
                            rest.furniture.getY() + .65, rest.furniture.getZ() + .5,
                            SoundEvents.NOTE_BLOCK_HAT, SoundSource.BLOCKS, .55f, .95f);
                    rest.snackKind = mob.getRandom().nextInt(5);
                    rest.visit = Visit.EATING;
                    rest.furniture = rest.reservedChair;
                    rest.stand = rest.reservedChair;
                    rest.reservedChair = null;
                    rest.rest = 0;
                    rest.phaseTicks = 0;
                    WorkerNavigation.stop(mob);
                }
            }
            case ARCADE -> {
                faceToward(mob, rest.furniture);
                if (now >= rest.nextArcadeSound) {
                    playArcadeSound(world, rest.furniture, rest.arcadeNotes++);
                    rest.nextArcadeSound = now + 14 + mob.getRandom().nextInt(15);
                }
                if (rest.rest % 40 == 0) mob.swing(InteractionHand.MAIN_HAND);
                rest.rest += 5;
            }
            case NONE -> { }
        }

        if (rest.visit == Visit.CHAIR || rest.visit == Visit.EATING
                || rest.visit == Visit.ARCADE) {
            if (rest.rest >= 200) rest.ready = true;
            if (rest.rest >= rest.breakLength) {
                finishVisit(world, mob, rest);
                return;
            }
        }
        publish(mob, rest, atTarget(world, mob, rest.stand));
    }

    private static void chooseVisit(ServerLevel world, PathfinderMob mob,
                                    Rest rest, BlockPos home) {
        List<Seat> seats = new ArrayList<>();
        List<BlockPos> machines = new ArrayList<>();
        List<BlockPos> arcades = new ArrayList<>();
        BlockPos min = home.offset(-12, -2, -12);
        BlockPos max = home.offset(12, 3, 12);
        for (BlockPos raw : BlockPos.betweenClosed(min, max)) {
            BlockPos pos = raw.immutable();
            if (!world.hasChunkAt(pos)) continue;
            BlockState state = world.getBlockState(pos);
            if (state.is(BreakRoomContent.CHAIR)) {
                BlockPos tablePos = pos.relative(state.getValue(HorizontalDirectionalBlock.FACING));
                BlockPos table = world.getBlockState(tablePos).is(BreakRoomContent.TABLE)
                        ? tablePos.immutable() : null;
                seats.add(new Seat(pos, table));
            } else if (state.is(BreakRoomContent.SNACK_MACHINE)) {
                machines.add(pos);
            } else if (state.is(BreakRoomContent.ARCADE)) {
                arcades.add(pos);
            }
        }

        List<Candidate> snackChoices = new ArrayList<>();
        for (BlockPos machine : machines) {
            if (reserved(rest, machine)) continue;
            BlockPos approach = front(world, machine);
            if (!reachable(world, mob, approach)) continue;
            for (Seat seat : seats) {
                if (seat.table() == null || reserved(rest, seat.chair())
                        || reserved(rest, seat.table())
                        || !reachable(world, mob, seat.chair())) continue;
                snackChoices.add(new Candidate(Visit.SNACK_MACHINE, machine,
                        approach, seat.chair(), seat.table()));
            }
        }

        List<Candidate> arcadeChoices = new ArrayList<>();
        for (BlockPos arcade : arcades) {
            if (reserved(rest, arcade)) continue;
            BlockPos approach = front(world, arcade);
            if (reachable(world, mob, approach))
                arcadeChoices.add(new Candidate(Visit.ARCADE, arcade, approach, null, null));
        }

        List<Candidate> chairChoices = new ArrayList<>();
        if (snackChoices.isEmpty()) {
            for (Seat seat : seats) {
                if (reserved(rest, seat.chair()) || !reachable(world, mob, seat.chair())) continue;
                chairChoices.add(new Candidate(Visit.CHAIR, seat.chair(),
                        seat.chair(), null, seat.table()));
            }
        }

        List<Candidate> choices = new ArrayList<>();
        choices.addAll(snackChoices);
        choices.addAll(chairChoices);
        choices.addAll(arcadeChoices);
        if (choices.isEmpty()) return;

        Collections.shuffle(choices, new Random(mob.getRandom().nextLong()));
        Candidate selected = choices.get(0);
        rest.visit = selected.visit();
        rest.furniture = selected.furniture();
        rest.stand = selected.stand();
        rest.reservedChair = selected.reservedChair();
        rest.table = selected.table();
        rest.rest = 0;
        rest.phaseTicks = 0;
        rest.breakLength = 240 + mob.getRandom().nextInt(160);
        rest.nextArcadeSound = 0;
        rest.arcadeNotes = 0;
        rest.snackKind = -1;
    }

    private static boolean reachable(ServerLevel world, PathfinderMob mob, BlockPos target) {
        return target != null && world.hasChunkAt(target)
                && WorkerNavigation.canStandOn(world, mob, target)
                && WorkerNavigation.canReachExact(world, mob, target);
    }

    private static boolean reserved(Rest current, BlockPos pos) {
        if (pos == null) return false;
        for (Rest other : workers.values()) {
            if (other == current || other.visit == Visit.NONE) continue;
            if (pos.equals(other.furniture) || pos.equals(other.stand)
                    || pos.equals(other.reservedChair) || pos.equals(other.table))
                return true;
        }
        return false;
    }

    private static BlockPos front(ServerLevel world, BlockPos block) {
        return block.relative(world.getBlockState(block)
                .getValue(HorizontalDirectionalBlock.FACING));
    }

    private static boolean validVisit(ServerLevel world, Rest rest) {
        if (rest.furniture == null || rest.stand == null
                || !world.hasChunkAt(rest.furniture) || !world.hasChunkAt(rest.stand))
            return false;
        return switch (rest.visit) {
            case CHAIR -> world.getBlockState(rest.furniture).is(BreakRoomContent.CHAIR);
            case EATING -> world.getBlockState(rest.furniture).is(BreakRoomContent.CHAIR)
                    && rest.table != null && world.hasChunkAt(rest.table)
                    && world.getBlockState(rest.table).is(BreakRoomContent.TABLE);
            case SNACK_MACHINE -> world.getBlockState(rest.furniture)
                    .is(BreakRoomContent.SNACK_MACHINE)
                    && rest.reservedChair != null
                    && world.getBlockState(rest.reservedChair).is(BreakRoomContent.CHAIR)
                    && rest.table != null && world.hasChunkAt(rest.table)
                    && world.getBlockState(rest.table).is(BreakRoomContent.TABLE);
            case ARCADE -> world.getBlockState(rest.furniture).is(BreakRoomContent.ARCADE);
            case NONE -> false;
        };
    }

    private static boolean atTarget(ServerLevel world, PathfinderMob mob, BlockPos target) {
        if (target == null || !world.hasChunkAt(target)) return false;
        double floor = WorkerNavigation.surveyFloorHeight(world, target);
        return Double.isFinite(floor) && mob.onGround()
                && Math.abs(mob.getY() - floor) < .09
                && mob.distanceToSqr(target.getX() + .5, floor, target.getZ() + .5) < .30;
    }

    private static void faceChair(ServerLevel world, PathfinderMob mob,
                                  BlockPos chair, BlockPos table) {
        Direction facing = world.getBlockState(chair)
                .getValue(HorizontalDirectionalBlock.FACING);
        float yaw = facing.toYRot();
        mob.setYRot(yaw);
        mob.setYHeadRot(yaw);
        mob.yBodyRot = yaw;
        BlockPos look = table != null ? table : chair.relative(facing);
        mob.getLookControl().setLookAt(look.getX() + .5,
                look.getY() + .75, look.getZ() + .5);
    }

    private static void faceToward(PathfinderMob mob, BlockPos target) {
        double dx = target.getX() + .5 - mob.getX();
        double dz = target.getZ() + .5 - mob.getZ();
        float yaw = (float)(Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        mob.setYRot(yaw);
        mob.setYHeadRot(yaw);
        mob.yBodyRot = yaw;
        mob.getLookControl().setLookAt(target.getX() + .5,
                target.getY() + .75, target.getZ() + .5);
    }

    private static void playArcadeSound(ServerLevel world, BlockPos arcade, int note) {
        double x = arcade.getX() + .5;
        double y = arcade.getY() + .72;
        double z = arcade.getZ() + .5;
        var sound = switch (Math.floorMod(note, 5)) {
            case 0 -> SoundEvents.NOTE_BLOCK_PLING;
            case 1 -> SoundEvents.NOTE_BLOCK_CHIME;
            case 2 -> SoundEvents.NOTE_BLOCK_BIT;
            case 3 -> SoundEvents.NOTE_BLOCK_BELL;
            default -> SoundEvents.NOTE_BLOCK_HAT;
        };
        float pitch = switch (Math.floorMod(note, 5)) {
            case 0 -> 1.12f;
            case 1 -> 1.34f;
            case 2 -> .96f;
            case 3 -> 1.48f;
            default -> .82f;
        };
        world.playSound(null, x, y, z, sound, SoundSource.BLOCKS, .82f, pitch);
        if (Math.floorMod(note, 4) == 3)
            world.playSound(null, x, y, z, SoundEvents.NOTE_BLOCK_BASEDRUM,
                    SoundSource.BLOCKS, .42f, .88f);
    }

    private static void finishVisit(ServerLevel world, PathfinderMob mob, Rest rest) {
        WorkerNavigation.stop(mob);
        clearVisit(rest);
        rest.chooseAt = world.getGameTime() + 120 + mob.getRandom().nextInt(160);
        publish(mob, rest, false);
    }

    private static void abandon(ServerLevel world, PathfinderMob mob, Rest rest) {
        WorkerNavigation.stop(mob);
        clearVisit(rest);
        rest.chooseAt = world.getGameTime() + 100;
        publish(mob, rest, false);
    }

    private static void clearVisit(Rest rest) {
        rest.visit = Visit.NONE;
        rest.furniture = null;
        rest.stand = null;
        rest.reservedChair = null;
        rest.table = null;
        rest.rest = 0;
        rest.phaseTicks = 0;
        rest.snackKind = -1;
        rest.nextArcadeSound = 0;
        rest.arcadeNotes = 0;
    }
}
