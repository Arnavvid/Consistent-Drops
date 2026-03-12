package me.misclik.consistentDrops;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.stream.Collectors;

public final class ConsistentDrops extends JavaPlugin implements Listener {

    // Hard-coded defaults (no YAML)
    private static final double DOWNWARD_VELOCITY = -0.05;
    private static final int PICKUP_DELAY_TICKS = 10;
    private static final int MAINTAIN_TICKS = 4; // keep zero lateral velocity for this many ticks

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("ConsistentDrops enabled (chain-proof, no config).");
    }

    @Override
    public void onDisable() {
        getLogger().info("ConsistentDrops disabled.");
    }

    /**
     * Primary handler: catches all drops produced by blocks (includes chain breaks like scaffolding).
     * BlockDropItemEvent gives the actual Item entities that will spawn.
     */
    @EventHandler
    public void onBlockDropItem(BlockDropItemEvent event) {
        if (event.isCancelled()) return;

        // event.getItems() returns a List<Item>
        List<Item> items = event.getItems();
        for (Item item : items) {
            if (item == null) continue;
            applyStraightBehavior(item);
        }
    }

    /**
     * Explosions often spawn drops differently. Wait one tick, then normalize items near each destroyed block.
     */
    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        if (event.isCancelled()) return;

        List<Location> blockLocations = event.blockList().stream()
                .map(b -> b.getLocation().add(0.5, 0.5, 0.5))
                .collect(Collectors.toList());

        new BukkitRunnable() {
            @Override
            public void run() {
                for (Location loc : blockLocations) {
                    // radius 1.2 should catch near-spawned items
                    loc.getWorld().getNearbyEntities(loc, 1.2, 1.2, 1.2).stream()
                            .filter(e -> e instanceof Item)
                            .map(e -> (Item) e)
                            .forEach(ConsistentDrops.this::applyStraightBehavior);
                }
            }
        }.runTaskLater(this, 1L);
    }

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent event) {
        if (event.isCancelled()) return;
        Item item = event.getEntity();

        if (item.getPickupDelay() == 40) return;

        Vector v = item.getVelocity();
        if (v != null && (Math.abs(v.getX()) > 0.2 || Math.abs(v.getZ()) > 0.2 || v.getY() > 0.2)) {
            return;
        }
        applyStraightBehavior(item);
    }


    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        // noop; kept for future extensions
    }

    /**
     * Central function: zero X/Z velocity, nudge Y to DOWNWARD_VELOCITY, set pickup delay,
     * and keep lateral velocity zero for a few ticks to avoid physics jitter.
     */
    private void applyStraightBehavior(Item item) {
        if (item == null || item.isDead()) return;

        Vector v = item.getVelocity();
        if (v == null) v = new Vector(0, DOWNWARD_VELOCITY, 0);

        v.setX(0);
        v.setZ(0);
        v.setY(DOWNWARD_VELOCITY);
        item.setVelocity(v);

        try {
            item.setPickupDelay(PICKUP_DELAY_TICKS);
        } catch (NoSuchMethodError | UnsupportedOperationException ignored) {
            // older API compatibility: ignore if not present
        }

        new BukkitRunnable() {
            int runs = 0;
            @Override
            public void run() {
                if (item == null || item.isDead() || runs++ > MAINTAIN_TICKS) {
                    cancel();
                    return;
                }
                Vector cur = item.getVelocity();
                if (cur == null) cur = new Vector(0, DOWNWARD_VELOCITY, 0);
                cur.setX(0);
                cur.setZ(0);
                if (cur.getY() > DOWNWARD_VELOCITY) cur.setY(DOWNWARD_VELOCITY);
                item.setVelocity(cur);
            }
        }.runTaskTimer(this, 0L, 1L);
    }
}
