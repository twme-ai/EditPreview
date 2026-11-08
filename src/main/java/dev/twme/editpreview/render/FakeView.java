package dev.twme.editpreview.render;

import dev.twme.editpreview.Keys;
import io.papermc.paper.math.Position;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * One player's fake blocks (packets only, the world is unchanged). Large sets are sent in batches of at most {@link #PER_TICK} per tick;
 * clearing sends the real blocks back (loaded chunks only; the client doesn't have unloaded ones anyway).
 * <p>
 * {@link #carve} keeps a hole in the preview so it never gets in the way: a thin tube along the line of sight up to the real block the
 * player is aiming at, and a column around the player's body (so the client never stands in or on a fake block).
 */
public final class FakeView {
  private static final int PER_TICK = 16384;
  /** Line-of-sight tube radius (squared), as in CreativeKit's VoxelPreview. */
  private static final double TUBE_R2 = 0.9 * 0.9;
  private static final double REACH = 64;
  /** Horizontal radius of the body column (squared). */
  private static final double BODY_R2 = 1.5 * 1.5;

  private final Player player;
  private World world;
  /** The full preview, before carving. */
  private Map<Long, BlockData> full = new HashMap<>();
  /** Positions carved out of the preview. */
  private Set<Long> cut = new HashSet<>();
  /** What the client currently has (full minus cut). */
  private final Map<Long, BlockData> shown = new HashMap<>();
  /** Pending: a null value means send the real block back */
  private final ArrayDeque<Map.Entry<Long, BlockData>> queue = new ArrayDeque<>();
  private boolean carving = true;

  public FakeView(Player player) {
    this.player = player;
  }

  public boolean isEmpty() {
    return full.isEmpty();
  }

  /** Size of the full preview (carved positions included). */
  public int size() {
    return full.size();
  }

  /** Number of positions currently carved out. */
  public int carved() {
    return full.size() - shown.size();
  }

  /** How many fake blocks of the full preview are of this material (for tests) */
  public long count(org.bukkit.Material m) {
    return full.values().stream().filter(d -> d.getMaterial() == m).count();
  }

  public World world() {
    return world;
  }

  public void carving(boolean on) {
    if (carving == on) return;
    carving = on;
    if (!on) setCut(new HashSet<>());
  }

  /** Replace the preview with this set. */
  public void show(World w, Map<Long, BlockData> blocks) {
    queue.clear();
    if (world != null && world != w) shown.clear(); // world changed: the client no longer has the old ones
    world = w;
    full = new HashMap<>(blocks);
    if (carving) cut = computeCut();
    else cut = new HashSet<>();
    for (var it = shown.entrySet().iterator(); it.hasNext(); ) {
      var e = it.next();
      if (!full.containsKey(e.getKey()) || cut.contains(e.getKey())) {
        queue.add(new java.util.AbstractMap.SimpleEntry<>(e.getKey(), null));
        it.remove();
      }
    }
    for (var e : full.entrySet()) {
      if (cut.contains(e.getKey())) continue;
      if (!e.getValue().equals(shown.get(e.getKey()))) {
        queue.add(Map.entry(e.getKey(), e.getValue()));
        shown.put(e.getKey(), e.getValue());
      }
    }
    flush();
  }

  public void clear() {
    if (world == null) return;
    show(world, Map.of());
  }

  /** World change or quit: just forget them (the client no longer has these blocks). */
  public void forget() {
    queue.clear();
    shown.clear();
    full = new HashMap<>();
    cut = new HashSet<>();
    world = null;
  }

  /** Recompute the carved hole for the player's current position and view; only changes are sent. */
  public void carve() {
    if (!carving || full.isEmpty() || world == null || player.getWorld() != world) return;
    setCut(computeCut());
  }

  private void setCut(Set<Long> c) {
    if (c.equals(cut)) return;
    for (Long k : cut) {
      if (c.contains(k)) continue;
      BlockData d = full.get(k);
      if (d != null) { // uncovered again: show the fake block
        shown.put(k, d);
        queue.add(Map.entry(k, d));
      }
    }
    for (Long k : c) {
      if (cut.contains(k)) continue;
      if (shown.remove(k) != null) queue.add(new java.util.AbstractMap.SimpleEntry<>(k, null)); // carved: real block
    }
    cut = c;
    flush();
  }

  private Set<Long> computeCut() {
    Set<Long> c = new HashSet<>();
    if (full.isEmpty() || player.getWorld() != world) return c;
    // body column
    Location feet = player.getLocation();
    double px = feet.getX(), pz = feet.getZ();
    int fy = feet.getBlockY();
    int bx = feet.getBlockX(), bz = feet.getBlockZ();
    for (int x = bx - 2; x <= bx + 2; x++)
      for (int z = bz - 2; z <= bz + 2; z++) {
        double dx = x + 0.5 - px, dz = z + 0.5 - pz;
        if (dx * dx + dz * dz > BODY_R2) continue;
        for (int y = fy - 1; y <= fy + 2; y++) {
          long k = Keys.key(x, y, z);
          if (full.containsKey(k)) c.add(k);
        }
      }
    // line of sight up to the real block being aimed at
    Location eye = player.getEyeLocation();
    Vector dir = eye.getDirection();
    double len = REACH;
    RayTraceResult hit = world.rayTraceBlocks(eye, dir, REACH, FluidCollisionMode.NEVER, true);
    if (hit != null) len = hit.getHitPosition().distance(eye.toVector());
    double ox = eye.getX(), oy = eye.getY(), oz = eye.getZ();
    double ux = dir.getX(), uy = dir.getY(), uz = dir.getZ();
    long lastCell = Long.MIN_VALUE;
    for (double t = 0; t <= len; t += 0.3) {
      int cx = (int) Math.floor(ox + ux * t), cy = (int) Math.floor(oy + uy * t), cz = (int) Math.floor(oz + uz * t);
      long cell = Keys.key(cx, cy, cz);
      if (cell == lastCell) continue;
      lastCell = cell;
      for (int dx = -1; dx <= 1; dx++)
        for (int dy = -1; dy <= 1; dy++)
          for (int dz = -1; dz <= 1; dz++) {
            int x = cx + dx, y = cy + dy, z = cz + dz;
            long k = Keys.key(x, y, z);
            if (!full.containsKey(k) || c.contains(k)) continue;
            // distance from the block center to the line of sight
            double vx = x + 0.5 - ox, vy = y + 0.5 - oy, vz = z + 0.5 - oz;
            double along = vx * ux + vy * uy + vz * uz;
            if (along < 0 || along > len + 0.9) continue;
            if (vx * vx + vy * vy + vz * vz - along * along <= TUBE_R2) c.add(k);
          }
    }
    return c;
  }

  /** Called every tick: send the next batch. */
  public void flush() {
    if (queue.isEmpty() || world == null) return;
    if (player.getWorld() != world) {
      forget();
      return;
    }
    Map<Position, BlockData> batch = new HashMap<>();
    Iterator<Map.Entry<Long, BlockData>> it = queue.iterator();
    while (it.hasNext() && batch.size() < PER_TICK) {
      var e = it.next();
      it.remove();
      long k = e.getKey();
      int x = Keys.x(k), y = Keys.y(k), z = Keys.z(k);
      BlockData d = e.getValue();
      if (d == null) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
        d = world.getBlockData(x, y, z);
      }
      batch.put(Position.block(x, y, z), d);
    }
    if (!batch.isEmpty()) player.sendMultiBlockChange(batch);
  }

  /** This block really changed in the world (e.g. after apply): stop treating it as fake. */
  public void resend() {
    if (world == null) return;
    for (var e : shown.entrySet()) queue.add(Map.entry(e.getKey(), e.getValue()));
    flush();
  }
}
