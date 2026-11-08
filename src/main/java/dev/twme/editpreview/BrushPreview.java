package dev.twme.editpreview;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.command.tool.BrushTool;
import com.sk89q.worldedit.command.tool.Tool;
import com.sk89q.worldedit.command.tool.brush.Brush;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.mask.MaskIntersection;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.Location;
import java.util.Map;
import org.bukkit.entity.Player;

/**
 * 筆刷預覽：恆預覽開著、手上拿著 WorldEdit 筆刷時，照 BrushTool 的流程在瞄準的位置「刷一次」，
 * 但那個 EditSession 是自己建的，建立時用 {@link WeHook#FORCED} 只攔它——玩家真正刷下去的那一筆照常寫入。
 */
final class BrushPreview {
  private BrushPreview() {}

  record Out(Capture capture, String key) {}

  /** 回傳 null＝手上不是筆刷或沒瞄準任何方塊。key 沒變時不重算（capture 為 null）。 */
  static Out compute(Player p, String lastKey, int maxCapture, double maxSize) {
    LocalSession s = WorldEdit.getInstance().getSessionManager().getIfPresent(BukkitAdapter.adapt(p));
    return WeRequest.with(BukkitAdapter.adapt(p.getWorld()), s, () -> compute0(p, lastKey, maxCapture, maxSize));
  }

  private static Out compute0(Player p, String lastKey, int maxCapture, double maxSize) {
    var actor = BukkitAdapter.adapt(p);
    LocalSession session = WorldEdit.getInstance().getSessionManager().getIfPresent(actor);
    if (session == null) return null;
    var item = p.getInventory().getItemInMainHand();
    if (item.getType().isAir()) return null;
    Tool tool;
    try {
      tool = session.getTool(BukkitAdapter.asItemType(item.getType()));
    } catch (Throwable t) {
      return null;
    }
    if (!(tool instanceof BrushTool bt)) return null;
    Brush brush = bt.getBrush();
    if (brush == null) return null;
    double size = bt.getSize();
    Location target = actor.getBlockTrace(bt.getRange(), true, bt.getTraceMask());
    if (target == null) return null;
    BlockVector3 pos = target.toVector().toBlockPoint();
    Mask mask = bt.getMask();
    String key = pos + "|" + size + "|" + System.identityHashCode(brush) + "|" + System.identityHashCode(bt.getMaterial())
        + "|" + System.identityHashCode(mask) + "|" + System.identityHashCode(session.getMask());
    if (key.equals(lastKey)) return new Out(null, key);
    if (size > maxSize) return new Out(null, key);

    boolean masked = mask != null || session.getMask() != null;
    Capture cap = new Capture(p.getUniqueId(), null, masked ? Capture.Style.GLASS : Capture.Style.BLOCKS, false, false, maxCapture);
    EditSession es;
    WeHook.FORCED.set(cap);
    try {
      es = session.createEditSession(actor);
    } finally {
      WeHook.FORCED.remove();
    }
    try (es) {
      if (mask != null) {
        Mask existing = es.getMask();
        if (existing == null) es.setMask(mask);
        else {
          MaskIntersection both = new MaskIntersection(existing);
          both.add(mask);
          es.setMask(both);
        }
      }
      brush.build(es, pos, bt.getMaterial(), size);
    } catch (Throwable t) {
      // 筆刷自己的錯誤（超過上限等）：有收到多少就顯示多少
    }
    return new Out(cap, key);
  }

  static Map<Long, Object> writes(Capture c) {
    return c.snapshot();
  }
}
