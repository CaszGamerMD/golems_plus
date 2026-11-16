package com.caszgamermd.golemsplus.builder;

import com.google.gson.Gson;
import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import net.minecraft.core.BlockPos;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

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
