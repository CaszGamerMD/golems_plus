package com.caszgamermd.golemsplus.builder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Per-Y wall-clock timing of the real four-Builder dome construction.
 *
 * The ideal is a deliberately provisional throughput target, NOT a claim
 * about a proven minimum: one placement per Builder per 40 ticks, plus
 * 100 ticks of setup/travel per occupied layer. Change these constants
 * when repeated successful runs establish a better reference.
 */
final class BuilderDomeLayerTiming {
    private static final int TICKS_PER_BLOCK_PER_BUILDER = 40;
    private static final int LAYER_OVERHEAD_TICKS = 100;
    private static final int BUILDERS = 4;
    private final TreeMap<Integer, Layer> layers = new TreeMap<>();
    private final int originY;
    private long sampledAt;
    private int activeY;
    private boolean constructionFinished;

    private static final class Layer {
        final int y;
        int required;
        long elapsed;
        long unassignedBuilderTicks;
        long scaffoldPresentBuilderTicks;
        boolean completed;

        Layer(int y) { this.y = y; }

        long ideal() {
            return LAYER_OVERHEAD_TICKS
                    + ((long) required * TICKS_PER_BLOCK_PER_BUILDER
                            + BUILDERS - 1) / BUILDERS;
        }
    }

    BuilderDomeLayerTiming(List<BuilderConstructionPlan.Step> plan,
            int originY, long startTick, int firstActiveY) {
        this.originY = originY;
        this.sampledAt = startTick;
        this.activeY = firstActiveY;
        for (var step : plan) {
            if (!step.ignorePlant())
                layers.computeIfAbsent(step.y(), Layer::new).required++;
        }
        System.out.println("[DOME LAYER BASELINE] builders=" + BUILDERS
                + " ticksPerBlockPerBuilder=" + TICKS_PER_BLOCK_PER_BUILDER
                + " travelTicksPerLayer=" + LAYER_OVERHEAD_TICKS
                + " (provisional target, not measured optimum)");
    }

    /** Attribute each elapsed interval to the layer active at its start.
     * The dome samples every 20 ticks, so boundary times have <=20-tick
     * resolution. Cleanup is excluded once all real blocks are correct.
     */
    void sample(long tick, int nextActiveY, boolean allBlocksCorrect,
            int unassignedBuilders, int scaffoldPresentBuilders) {
        if (constructionFinished) return;
        Layer current = layers.get(activeY);
        long interval = Math.max(0, tick - sampledAt);
        if (current != null) {
            current.elapsed += interval;
            current.unassignedBuilderTicks += interval * unassignedBuilders;
            current.scaffoldPresentBuilderTicks += interval * scaffoldPresentBuilders;
        }
        sampledAt = tick;
        if (nextActiveY > activeY) {
            for (Layer layer : layers.values()) {
                if (layer.y >= activeY && layer.y < nextActiveY)
                    complete(layer);
            }
        }
        activeY = nextActiveY;
        if (allBlocksCorrect) {
            for (Layer layer : layers.values()) complete(layer);
            constructionFinished = true;
        }
    }

    private void complete(Layer layer) {
        if (layer.completed) return;
        layer.completed = true;
        System.out.println("[DOME LAYER COMPLETE] y=" + (layer.y - originY)
                + " blocks=" + layer.required
                + " idealTicks=" + layer.ideal()
                + " actualTicks=" + layer.elapsed
                + " slowdown=" + ratio(layer.elapsed, layer.ideal()));
    }

    /** Emit CSV on both passes and failures; the CI dome artifact uploads
     * route-survey/, so a stalled layer remains inspectable afterwards.
     */
    void report(ServerLevel world, List<BuilderConstructionPlan.Step> plan,
            long tick, String outcome) {
        if (!constructionFinished) sample(tick, activeY, false, 0, 0);
        TreeMap<Integer, Integer> correct = new TreeMap<>();
        for (var step : plan) {
            if (step.ignorePlant()) continue;
            var expected = BuilderBlockResolver.resolve(step.encodedState(),
                    BuildSite.Rotation.NONE, BuildSite.Mirror.NONE);
            if (expected != null && BuilderPlacementState.matches(
                    world.getBlockState(new BlockPos(step.x(), step.y(), step.z())),
                    expected.state()))
                correct.merge(step.y(), 1, Integer::sum);
        }
        StringBuilder csv = new StringBuilder("layer_y,required_blocks,correct_blocks,"
                + "ideal_ticks,actual_ticks,overrun_ticks,slowdown,"
                + "unassigned_builder_ticks,scaffold_present_builder_ticks,status\n");
        long idealTotal = 0, measuredTotal = 0;
        List<Layer> started = new ArrayList<>();
        for (Layer layer : layers.values()) {
            String status = layer.completed ? "completed"
                    : layer.y == activeY ? "active" : "not_started";
            int matched = correct.getOrDefault(layer.y, 0);
            long ideal = layer.ideal();
            idealTotal += ideal;
            if (!status.equals("not_started")) {
                started.add(layer);
                measuredTotal += layer.elapsed;
            }
            csv.append(layer.y - originY).append(',')
                    .append(layer.required).append(',').append(matched).append(',')
                    .append(ideal).append(',')
                    .append(status.equals("not_started") ? "" : layer.elapsed).append(',')
                    .append(status.equals("not_started") ? "" : layer.elapsed - ideal)
                    .append(',')
                    .append(status.equals("not_started") ? "" : ratio(layer.elapsed, ideal))
                    .append(',').append(status.equals("not_started") ? ""
                            : layer.unassignedBuilderTicks)
                    .append(',').append(status.equals("not_started") ? ""
                            : layer.scaffoldPresentBuilderTicks)
                    .append(',').append(status).append('\n');
        }
        started.sort(Comparator.comparingLong(
                (Layer layer) -> layer.elapsed - layer.ideal()).reversed());
        StringBuilder summary = new StringBuilder("[DOME LAYER SUMMARY] outcome=")
                .append(outcome).append(" tick=").append(tick)
                .append(" idealAllLayers=").append(idealTotal)
                .append(" measuredStartedLayers=").append(measuredTotal)
                .append(" resolution=20ticks")
                .append(" baseline=40ticks/block/builder+100ticks/layer")
                .append(" (unassigned/scaffold columns are sampled builder-ticks,"
                        + " not mutually exclusive causes)\n");
        for (int i = 0; i < Math.min(8, started.size()); i++) {
            Layer layer = started.get(i);
            summary.append("[DOME LAYER BOTTLENECK] y=")
                    .append(layer.y - originY)
                    .append(" required=").append(layer.required)
                    .append(" correct=").append(correct.getOrDefault(layer.y, 0))
                    .append(" idealTicks=").append(layer.ideal())
                    .append(" actualTicks=").append(layer.elapsed)
                    .append(" overrunTicks=").append(layer.elapsed - layer.ideal())
                    .append(" slowdown=").append(ratio(layer.elapsed, layer.ideal()))
                    .append(" unassignedBuilderTicks=")
                    .append(layer.unassignedBuilderTicks)
                    .append(" scaffoldPresentBuilderTicks=")
                    .append(layer.scaffoldPresentBuilderTicks)
                    .append(" status=").append(layer.completed ? "completed" : "active")
                    .append('\n');
        }
        System.out.print(summary);
        try {
            Path folder = Path.of("route-survey");
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("dome_layer_timing.csv"), csv);
            Files.writeString(folder.resolve("dome_layer_bottlenecks.txt"), summary);
        } catch (Exception e) {
            System.err.println("[DOME LAYER REPORT] unable to save: " + e);
        }
    }

    private static String ratio(long actual, long ideal) {
        return String.format(Locale.ROOT, "%.2f", (double) actual / ideal);
    }
}
