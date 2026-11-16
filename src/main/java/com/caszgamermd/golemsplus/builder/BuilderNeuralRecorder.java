package com.caszgamermd.golemsplus.builder;

import com.google.gson.Gson;
import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import net.minecraft.core.BlockPos;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;

/** Experimental observation only. No route requests, claims or movement writes. */
final class BuilderNeuralRecorder {
    private BuilderNeuralRecorder() {}
    static final boolean ENABLED = Boolean.parseBoolean(System.getProperty(
            "golems.nn.record", System.getenv().getOrDefault("GOLEMS_NN_RECORD", "false")));
    private static final String RUN = UUID.randomUUID().toString();
    private static final Gson JSON = new Gson();
    private static final int LIMIT = 100_000;
    private static int emitted;
    private record Start(long tick, Vec3 position, String controller, double[] features,
            ServerLevel level) {}
    private record Row(int schema, String run, String worker, long tick,
            String controller, double[] features, double distance, int moved) {}
    private static final Map<BuilderGolem, Start> pending = new WeakHashMap<>();

    private static final Map<BuilderGolem, Long> lastSeen = new WeakHashMap<>();

    // One accepted physical action, with a terminal label independent of movement windows.
    private static final Map<BuilderGolem, FlightStart> activeFlights = new WeakHashMap<>();
    private static long actionSequence;
    private static final int ACTION_LIMIT = 100_000;
    private record FlightStart(long id, long tick, ServerLevel level, Vec3 from,
            Vec3 destination, double[] features, double[] knowledge, long lastTick) {}
    private record FlightRow(int schema, String run, String worker, long id,
            long tick, long endTick, String action, double[] features,
            String outcome, double remainingDistance, double[] knowledge, String blockage) {}

    static void beginFlight(ServerLevel level, BuilderGolem worker, List<Vec3> path, Vec3 destination) {
        if (!ENABLED || actionSequence >= ACTION_LIMIT) return;
        finishFlight(level, worker, "REPLACED");
        Vec3 from = worker.position(), at = from;
        double route = 0;
        for (Vec3 waypoint : path) {
            route += at.distanceTo(waypoint);
            at = waypoint;
        }
        Vec3 delta = destination.subtract(from);
        double[] x = {
            Math.clamp(delta.x / 2, -1, 1),
            Math.clamp(delta.y / 32, -1, 1),
            Math.clamp(delta.z / 2, -1, 1),
            Math.min(1, route / 64),
            Math.min(1, path.size() / 3.0),
            worker.onGround() ? 1 : 0,
            Math.min(1, from.distanceTo(destination) / 64)
        };
        long now = level.getGameTime();
        activeFlights.put(worker, new FlightStart(++actionSequence, now, level,
                from, destination, x, BuilderFlight.observedKnowledge(level,worker,path,destination), now));
    }

    static void observeFlightTick(ServerLevel level, BuilderGolem worker) {
        if (!ENABLED) return;
        FlightStart f = activeFlights.get(worker);
        if (f == null) return;
        long now = level.getGameTime();
        if (f.level() != level || now < f.lastTick() || now - f.lastTick() > 1) {
            finishFlight(level, worker, "OBSERVATION_GAP");
            return;
        }
        activeFlights.put(worker, new FlightStart(f.id(), f.tick(), f.level(),
                f.from(), f.destination(), f.features(), f.knowledge(), now));
    }

    static void finishFlight(ServerLevel level, BuilderGolem worker, String outcome) {
        finishFlight(level, worker, outcome, "NONE");
    }

    static void finishFlight(ServerLevel level, BuilderGolem worker, String outcome, String blockage) {
        if (!ENABLED) return;
        FlightStart f = activeFlights.remove(worker);
        if (f == null) return;
        long now = level.getGameTime();
        if (f.level() != level || now < f.lastTick() || now - f.lastTick() > 1) {
            outcome = "OBSERVATION_GAP";
            blockage = "NONE";
            now = f.lastTick();
        }
        double remaining = worker.position().distanceTo(f.destination());
        if (!Double.isFinite(remaining)) return;
        System.out.println("[NNGOLEMS_ACTION] " + JSON.toJson(new FlightRow(1, RUN,
                worker.getUUID().toString(), f.id(), f.tick(), now, "FLIGHT",
                f.features(), outcome, remaining, f.knowledge(), blockage)));
    }

    private static long placementSequence;
    record PlacementStart(long id,long tick,BlockPos pos,String desired,String existing,
            String support,String category,double[] features) {}
    private record PlacementRow(int schema,String run,String worker,long id,long tick,
            String desired,String existing,String support,String category,double[] features,
            String outcome,String actual,boolean stateMatches) {}

    static PlacementStart beginPlacement(ServerLevel level,BuilderGolem worker,
            BuilderConstructionPlan.Step step,BlockPos pos,BlockState desired,
            boolean interaction,boolean repairDependency,boolean railJig,
            boolean railRepair,boolean partnerRepair) {
        if(!ENABLED||placementSequence>=100_000)return null;
        BlockState existing=level.getBlockState(pos),below=level.getBlockState(pos.below());
        boolean waterlog=BuilderWaterPass.isWaterlogPass(step.encodedState());
        boolean bubble=BuilderWaterPass.isBubbleColumn(step.encodedState());
        boolean rail=desired.getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock;
        boolean multi=step.companion()!=null;
        String category=repairDependency?"DEPENDENCY_REPAIR":waterlog?"WATERLOG":
                bubble?"BUBBLE":rail?"RAIL":multi?"MULTIBLOCK":
                interaction?"INTERACTION":BuilderWaterPass.isWaterStep(step.encodedState())?"WATER":"ORDINARY";
        double[] x={
            Math.clamp((pos.getX()+.5-worker.getX())/5,-1,1),
            Math.clamp((pos.getY()+.5-worker.getY())/5,-1,1),
            Math.clamp((pos.getZ()+.5-worker.getZ())/5,-1,1),
            worker.onGround()?1:0,BuilderFlight.active(worker)?1:0,
            existing.isAir()?1:0,existing.getFluidState().isEmpty()?0:1,
            below.isAir()?1:0,below.isFaceSturdy(level,pos.below(),net.minecraft.core.Direction.UP)?1:0,
            desired.canSurvive(level,pos)?1:0,multi?1:0,waterlog?1:0,bubble?1:0,
            rail?1:0,interaction?1:0,railJig?1:0,railRepair?1:0,
            partnerRepair?1:0,repairDependency?1:0
        };
        return new PlacementStart(++placementSequence,level.getGameTime(),pos.immutable(),
                desired.toString(),existing.toString(),below.toString(),category,x);
    }

    static void finishPlacement(ServerLevel level,BuilderGolem worker,PlacementStart p,String outcome) {
        if(p==null)return;
        String actual=level.getBlockState(p.pos()).toString();
        System.out.println("[NNGOLEMS_PLACEMENT] "+JSON.toJson(new PlacementRow(1,RUN,
                worker.getUUID().toString(),p.id(),p.tick(),p.desired(),p.existing(),
                p.support(),p.category(),p.features(),outcome,actual,actual.equals(p.desired()))));
    }

    static void sample(ServerLevel level, BuilderGolem worker, String controller) {
        if (!ENABLED || emitted >= LIMIT) return;
        long now = level.getGameTime();
        Start previous = pending.get(worker);
        Long last = lastSeen.put(worker, now);
        if (last == null || now - last != 1 || previous != null && previous.level() != level)
            previous = null;
        double previousDistance = 0;
        boolean hasHistory = false;
        // Controller changes and tick gaps are censored, never labelled failures.
        if (previous != null && previous.controller().equals(controller)) {
            long elapsed = now - previous.tick();
            if (elapsed > 0 && elapsed < 20) return;
            if (elapsed == 20) {
                double distance = worker.position().distanceTo(previous.position());
                // Exclude implausible discontinuities/teleports from training.
                if (Double.isFinite(distance) && distance <= 8) {
                    previousDistance = distance;
                    hasHistory = true;
                    System.out.println("[NNGOLEMS] " + JSON.toJson(new Row(3, RUN,
                            worker.getUUID().toString(), previous.tick(), controller,
                            previous.features(), distance, distance >= .25 ? 1 : 0)));
                    emitted++;
                }
            }
        }
        Vec3 velocity = worker.getDeltaMovement();
        double[] features = new double[30];
        features[0] = Math.clamp(velocity.x, -1, 1);
        features[1] = Math.clamp(velocity.y, -1, 1);
        features[2] = Math.clamp(velocity.z, -1, 1);
        features[3] = worker.onGround() ? 1 : 0;
        String[] controllers = {"FLIGHT", "GRAPPLE", "SCAFFOLD", "YIELD", "CENTER", "IDLE", "WALK"};
        for (int i = 0; i < controllers.length; i++)
            features[4 + i] = controllers[i].equals(controller) ? 1 : 0;
        BlockPos goal = controller.equals("FLIGHT") ? BuilderFlight.observedDestination(worker)
                : controller.equals("WALK") ? WorkerNavigation.observedDestination(worker) : null;
        if (goal != null) {
            features[11] = 1;
            features[12] = Math.clamp((goal.getX() + .5 - worker.getX()) / 32, -1, 1);
            features[13] = Math.clamp((goal.getY() - worker.getY()) / 32, -1, 1);
            features[14] = Math.clamp((goal.getZ() + .5 - worker.getZ()) / 32, -1, 1);
        }
        // Occupancy probes only, not executable route/standability claims.
        // clear() rejects unloaded chunks before inspecting collisions.
        int[][] directions = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int i=0; i<directions.length; i++) {
            int[] d=directions[i];
            features[15+i] = BuilderFlight.clear(level,worker,
                    worker.position().add(d[0]*.5,d[1]*.5,d[2]*.5)) ? 1 : 0;
        }
        if (controller.equals("FLIGHT")) {
            double[] execution = BuilderFlight.observedExecution(worker, now);
            System.arraycopy(execution, 0, features, 21, execution.length);
        }
        features[28] = hasHistory ? 1 : 0;
        features[29] = Math.min(1, previousDistance / 8);
        pending.put(worker, new Start(now, worker.position(), controller, features, level));
    }
}
