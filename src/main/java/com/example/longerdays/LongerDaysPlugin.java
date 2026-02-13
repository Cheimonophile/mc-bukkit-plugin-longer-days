package com.example.longerdays;

import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;

public class LongerDaysPlugin extends JavaPlugin {

    // How many real ticks to wait before advancing game time by 1.
    // A normal day is 24000 ticks over 24000 real ticks (1:1).
    // At multiplier=3, we advance 1 game tick every 3 real ticks.
    private int multiplier;

    // Tracks fractional tick accumulation per world so no time is lost
    // when the multiplier doesn't divide evenly.
    private final Map<String, Double> accumulators = new HashMap<>();

    // Original doDayLightCycle state per world so we can restore on disable.
    private final Map<String, Boolean> originalDayLightCycle = new HashMap<>();

    private BukkitRunnable timeTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        multiplier = Math.max(1, getConfig().getInt("day-length-multiplier", 3));

        for (World world : getServer().getWorlds()) {
            if (shouldManageWorld(world)) {
                enableForWorld(world);
            }
        }

        startTimeTask();

        getLogger().info("LongerDays enabled — day length multiplier: " + multiplier + "x");
    }

    @Override
    public void onDisable() {
        if (timeTask != null) {
            timeTask.cancel();
            timeTask = null;
        }

        // Restore doDayLightCycle for every world we took over.
        for (Map.Entry<String, Boolean> entry : originalDayLightCycle.entrySet()) {
            World world = getServer().getWorld(entry.getKey());
            if (world != null) {
                world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, entry.getValue());
            }
        }

        originalDayLightCycle.clear();
        accumulators.clear();

        getLogger().info("LongerDays disabled — doDayLightCycle restored.");
    }

    private boolean shouldManageWorld(World world) {
        // Only manage normal overworld-type worlds (not the Nether or End,
        // which don't have a meaningful day/night cycle).
        return world.getEnvironment() == World.Environment.NORMAL;
    }

    private void enableForWorld(World world) {
        Boolean current = world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE);
        // Default to true if the gamerule hasn't been set yet.
        originalDayLightCycle.put(world.getName(), current != null ? current : true);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        accumulators.put(world.getName(), 0.0);
    }

    private void startTimeTask() {
        final double ticksPerRealTick = 1.0 / multiplier;

        timeTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (World world : getServer().getWorlds()) {
                    if (!shouldManageWorld(world)) continue;

                    // Lazily register worlds that joined after onEnable
                    // (e.g. worlds loaded by other plugins).
                    if (!accumulators.containsKey(world.getName())) {
                        enableForWorld(world);
                    }

                    double acc = accumulators.get(world.getName()) + ticksPerRealTick;
                    long advance = (long) acc;
                    accumulators.put(world.getName(), acc - advance);

                    if (advance > 0) {
                        world.setTime(world.getTime() + advance);
                    }
                }
            }
        };

        // Run every server tick (period = 1).
        timeTask.runTaskTimer(this, 0L, 1L);
    }
}
