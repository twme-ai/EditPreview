package dev.twme.editpreview;

import dev.twme.editpreview.render.Geo;
import dev.twme.editpreview.render.Geo.Shape;
import dev.twme.editpreview.render.Geo.Text;
import dev.twme.editpreview.render.Geo.V;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;

/**
 * 寫入結果 → 要送給玩家的假方塊。
 * <ul>
 *   <li>GLASS：受影響的格子顯示成綠色玻璃，只留露在外面的（六面有一面不在集合裡）。
 *   <li>BLOCKS：顯示寫入後的方塊；被不透光方塊完全包住的格子看不到，不送。
 * </ul>
 * 超過上限時留離玩家最近的。
 */
final class Build {
  private Build() {}

  static final int[][] N6 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

  record Result(Map<Long, BlockData> fake, Shape box, int changed, boolean truncated) {}

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
    return new Result(fake, bounds(writes), writes.size(), truncated);
  }

  private static double dist(long k, double px, double py, double pz) {
    double dx = Keys.x(k) + 0.5 - px, dy = Keys.y(k) + 0.5 - py, dz = Keys.z(k) + 0.5 - pz;
    return dx * dx + dy * dy + dz * dz;
  }

  /** 受影響範圍的黃色外框。 */
  static Shape bounds(Map<Long, ?> writes) {
    if (writes.isEmpty()) return Shape.empty();
    int x0 = Integer.MAX_VALUE, y0 = x0, z0 = x0, x1 = Integer.MIN_VALUE, y1 = x1, z1 = x1;
    for (long k : writes.keySet()) {
      int x = Keys.x(k), y = Keys.y(k), z = Keys.z(k);
      x0 = Math.min(x0, x);
      y0 = Math.min(y0, y);
      z0 = Math.min(z0, z);
      x1 = Math.max(x1, x);
      y1 = Math.max(y1, y);
      z1 = Math.max(z1, z);
    }
    return new Shape(Geo.box(x0, y0, z0, x1, y1, z1, Geo.Color.YELLOW), List.of(),
        List.of(new Text(new V((x0 + x1 + 1) / 2.0, y1 + 2.4, (z0 + z1 + 1) / 2.0), "預覽：" + String.format("%,d", writes.size()) + " 格")));
  }
}
