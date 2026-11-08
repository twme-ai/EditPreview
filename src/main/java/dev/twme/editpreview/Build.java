package dev.twme.editpreview;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;

/**
 * Captured writes → fake blocks to send to the player.
 * <ul>
 *   <li>GLASS: affected blocks show as lime glass; only exposed ones are kept (at least one of six neighbours not in the set).
 *   <li>BLOCKS: show the resulting blocks; blocks fully enclosed by occluding blocks are invisible and not sent.
 * </ul>
 * Over the limit, the blocks nearest to the player are kept.
 */
final class Build {
  private Build() {}

  static final int[][] N6 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

  record Result(Map<Long, BlockData> fake, int changed, boolean truncated) {}

  static Result build(Map<Long, BlockData> writes, Capture.Style style, double px, double py, double pz, int max) {
    BlockData glass = Bukkit.createBlockData("minecraft:lime_stained_glass");
    List<Long> visible = new ArrayList<>();
    for (var e : writes.entrySet()) {
      long k = e.getKey();
      boolean exposed = false;
      for (int[] o : N6) {
        BlockData n = writes.get(Keys.offset(k, o[0], o[1], o[2]));
        if (n == null || (style == Capture.Style.BLOCKS && !n.getMaterial().isOccluding())) {
          exposed = true;
          break;
        }
      }
      if (exposed) visible.add(k);
    }
    boolean truncated = false;
    if (visible.size() > max) {
      visible.sort((a, b) -> Double.compare(dist(a, px, py, pz), dist(b, px, py, pz)));
      visible = visible.subList(0, max);
      truncated = true;
    }
    Map<Long, BlockData> fake = new HashMap<>(visible.size() * 2);
    for (long k : visible) fake.put(k, style == Capture.Style.GLASS ? glass : writes.get(k));
    return new Result(fake, writes.size(), truncated);
  }

  private static double dist(long k, double px, double py, double pz) {
    double dx = Keys.x(k) + 0.5 - px, dy = Keys.y(k) + 0.5 - py, dz = Keys.z(k) + 0.5 - pz;
    return dx * dx + dy * dy + dz * dz;
  }
}
