package com.example.longerdays;

import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;

public class LongerDaysPlugin extends JavaPlugin {

    private int multiplier;

    // How much excess time to "undo" per tick, accumulates until a whole tick
    // can be subtracted to keep drift-free on non-integer multipliers.
    private final Map<String, Double> corrections = new HashMap<>();

    // The time we last left the world at — used to detect large jumps caused
    // by sleeping, /time set, or another plugin so we don't fight them.
    private final Map<String, Long> lastSetTime = new HashMap<>();

    private BukkitRunnable timeTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        multiplier = Math.max(1, getConfig().getInt("day-length-multiplier", 3));
        startTimeTask();
        getLogger().info("LongerDays enabled — day length multiplier: " + multiplier + "x");
    }

    @Override
    public void onDisable() {
        if (timeTask != null) {
            timeTask.cancel();
            timeTask = null;
        }
        corrections.clear();
        lastSetTime.clear();
        getLogger().info("LongerDays disabled.");
    }

    private boolean shouldManageWorld(World world) {
        // Only manage Overworld environments — Nether and End have no day cycle.
        return world.getEnvironment() == World.Environment.NORMAL;
    }

    private void startTimeTask() {
        // How much of each natural +1 tick to "give back" per real tick.
        // multiplier=3 → subtract 2/3 per tick so net advancement is 1/3.
        final double correctionPerTick = 1.0 - (1.0 / multiplier);

        timeTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (World world : getServer().getWorlds()) {
                    if (!shouldManageWorld(world)) continue;

                    String name = world.getName();
                    // Use full time (ever-increasing) rather than time-of-day.
                    // setTime() secretly jumps fullTime forward by up to 23999 ticks
                    // when we subtract even 1 tick of day-time, which makes moon
                    // phases race ahead. setFullTime() controls the absolute counter
                    // directly so both time-of-day and moon phase slow down together.
                    long currentTime = world.getFullTime();

                    // First time we see this world — record and skip.
                    if (!lastSetTime.containsKey(name)) {
                        lastSetTime.put(name, currentTime);
                        corrections.put(name, 0.0);
                        continue;
                    }

                    // Compare actual time to what we'd expect after one natural tick.
                    // fullTime is always increasing so no modular wrap needed.
                    long expected = lastSetTime.get(name) + 1;
                    long diff = Math.abs(currentTime - expected);

                    if (diff > 2) {
                        // Large jump — sleep skip, /time set, or another plugin.
                        // Accept the new time and reset so we don't fight it.
                        lastSetTime.put(name, currentTime);
                        corrections.put(name, 0.0);
                        continue;
                    }

                    // Normal tick — accumulate and apply correction as whole ticks.
                    double acc = corrections.get(name) + correctionPerTick;
                    long subtract = (long) acc;
                    corrections.put(name, acc - subtract);

                    long newTime = currentTime - subtract;
                    if (subtract > 0) {
                        world.setFullTime(newTime);
                    }
                    lastSetTime.put(name, newTime);
                }
            }
        };

        // Run every server tick.
        timeTask.runTaskTimer(this, 0L, 1L);
    }
}
