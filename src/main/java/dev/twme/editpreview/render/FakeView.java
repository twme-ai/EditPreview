package dev.twme.editpreview.render;

import dev.twme.editpreview.Keys;
import io.papermc.paper.math.Position;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

/**
 * 一位玩家的假方塊（只送封包，世界不變）。大量時分批送，每 tick 最多 {@link #PER_TICK} 格，
 * 清掉時把真正的方塊送回去（只送已載入的 chunk，沒載入的客戶端也沒有）。
 */
public final class FakeView {
  private static final int PER_TICK = 16384;

  private final Player player;
  private World world;
  private Map<Long, BlockData> shown = new HashMap<>();
  /** 待送：值為 null 代表要送回真正的方塊 */
  private final ArrayDeque<Map.Entry<Long, BlockData>> queue = new ArrayDeque<>();

  public FakeView(Player player) {
    this.player = player;
  }

  public boolean isEmpty() {
    return shown.isEmpty();
  }

  public int size() {
    return shown.size();
  }

  /** 假方塊裡有幾格是這種材質（測試用） */
  public long count(org.bukkit.Material m) {
    return shown.values().stream().filter(d -> d.getMaterial() == m).count();
  }

  public World world() {
    return world;
  }

  /** 換成這組假方塊。 */
  public void show(World w, Map<Long, BlockData> blocks) {
    queue.clear();
    if (world != null && world != w) shown.clear(); // 換了世界：舊的客戶端早就沒了
    for (Long k : shown.keySet()) if (!blocks.containsKey(k)) queue.add(new java.util.AbstractMap.SimpleEntry<>(k, null));
    for (var e : blocks.entrySet()) if (!e.getValue().equals(shown.get(e.getKey()))) queue.add(Map.entry(e.getKey(), e.getValue()));
    world = w;
    shown = new HashMap<>(blocks);
    flush();
  }

  public void clear() {
    if (world == null) return;
    show(world, Map.of());
  }

  /** 換世界、離線：直接忘掉（客戶端已經沒有這些方塊）。 */
  public void forget() {
    queue.clear();
    shown.clear();
    world = null;
  }

  /** 每 tick 呼叫：送出下一批。 */
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

  /** 世界上這一格真的變了（例如套用之後）：不再當它是假的。 */
  public void resend() {
    if (world == null) return;
    for (var e : shown.entrySet()) queue.add(Map.entry(e.getKey(), e.getValue()));
    flush();
  }
}
