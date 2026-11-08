package dev.twme.editpreview;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

/**
 * 打字預覽：恆預覽開著時，聊天欄裡打到一半的 //set、//replace 直接用 WorldEdit 的解析器解析，
 * 在選區裡算出結果（不執行指令，所以打錯也不會出現錯誤訊息）。
 * <ul>
 *   <li>//set &lt;pattern&gt;：選區（扣掉 gmask 不符合的）換成 pattern 的樣子。
 *   <li>//replace &lt;遮罩&gt;：還在打遮罩時，符合的格子顯示綠色玻璃。
 *   <li>//replace &lt;遮罩&gt; &lt;pattern&gt;：開始打 pattern 後，符合的格子顯示換完的樣子。
 * </ul>
 */
final class Live {
  private Live() {}

  static final Set<String> SET = Set.of("/set");
  static final Set<String> REPLACE = Set.of("/replace", "/rep", "/re");

  /** 解析失敗回傳 null（保留上一次的預覽）；不是這兩個指令回傳 {@link #NOT_LIVE}。 */
  record Out(Map<Long, BlockData> writes, Capture.Style style, String note) {}

  /** 最後一次解析／計算失敗的原因（/pev debug 用） */
  static volatile String lastError;

  static final Out NOT_LIVE = new Out(Map.of(), Capture.Style.BLOCKS, null);

  static Out compute(Player p, String buffer, boolean fawe, int maxVolume) {
    var actor = BukkitAdapter.adapt(p);
    LocalSession session = WorldEdit.getInstance().getSessionManager().getIfPresent(actor);
    return WeRequest.with(BukkitAdapter.adapt(p.getWorld()), session, () -> compute0(p, buffer, fawe, maxVolume));
  }

  private static Out compute0(Player p, String buffer, boolean fawe, int maxVolume) {
    String b = buffer.startsWith("/") ? buffer.substring(1) : buffer;
    String[] parts = b.split(" ", -1);
    String label = EditPreviewPlugin.baseLabel(parts[0]);
    boolean set = SET.contains(label), replace = REPLACE.contains(label);
    if (!set && !replace) return NOT_LIVE;
    List<String> args = new ArrayList<>();
    for (int i = 1; i < parts.length; i++) {
      String a = parts[i];
      if (a.isEmpty() || a.equalsIgnoreCase("-pev")) continue;
      if (a.length() == 2 && a.charAt(0) == '-' && Character.isLetter(a.charAt(1))) continue; // 旗標
      args.add(a);
    }
    if (args.isEmpty()) return NOT_LIVE;
    String maskText = null, patternText = null;
    if (set) patternText = args.get(0);
    else if (args.size() == 1) maskText = args.get(0);
    else {
      maskText = args.get(0);
      patternText = args.get(1);
    }

    var actor = BukkitAdapter.adapt(p);
    LocalSession session = WorldEdit.getInstance().getSessionManager().getIfPresent(actor);
    if (session == null) return NOT_LIVE;
    World world = session.getSelectionWorld();
    if (world == null || !world.getName().equals(p.getWorld().getName())) return new Out(Map.of(), Capture.Style.BLOCKS, "沒有選區");
    Region region;
    try {
      region = session.getRegionSelector(world).getRegion();
    } catch (IncompleteRegionException e) {
      return new Out(Map.of(), Capture.Style.BLOCKS, "沒有選區");
    }
    long volume;
    try {
      volume = region.getVolume();
    } catch (Throwable t) {
      volume = Long.MAX_VALUE;
    }
    if (volume > maxVolume) return new Out(Map.of(), Capture.Style.BLOCKS, "選區超過 " + String.format("%,d", maxVolume) + " 格，打字預覽暫停（按 Enter 仍會試跑）");

    ParserContext ctx = new ParserContext();
    ctx.setActor(actor);
    ctx.setWorld(world);
    ctx.setSession(session);
    ctx.setExtent(world);
    ctx.setRestricted(true);
    ctx.setPreferringWildcard(false);
    Mask mask;
    Pattern pattern;
    try {
      mask = maskText == null ? null : WorldEdit.getInstance().getMaskFactory().parseFromInput(maskText, ctx);
      pattern = patternText == null ? null : WorldEdit.getInstance().getPatternFactory().parseFromInput(patternText, ctx);
    } catch (Exception e) {
      lastError = e.toString();
      return null;
    }
    Mask gmask = session.getMask();
    if (fawe) {
      try {
        dev.twme.editpreview.fawe.FaweSupport.resetMask(gmask, world);
        dev.twme.editpreview.fawe.FaweSupport.resetMask(mask, world);
      } catch (Throwable ignored) {
        // 舊版 FAWE
      }
    }
    BlockData glass = Bukkit.createBlockData("minecraft:lime_stained_glass");
    Map<Long, BlockData> out = new HashMap<>();
    Map<Object, BlockData> conv = new HashMap<>();
    try {
      for (BlockVector3 v : region) {
        if (gmask != null && !gmask.test(v)) continue;
        if (mask != null && !mask.test(v)) continue;
        BlockData d = glass;
        if (pattern != null) {
          var st = pattern.applyBlock(v).toImmutableState();
          d = conv.computeIfAbsent(st, s -> BukkitAdapter.adapt((com.sk89q.worldedit.world.block.BlockState) s));
        }
        out.put(Keys.key(v.x(), v.y(), v.z()), d);
      }
    } catch (Exception e) {
      lastError = e.toString();
      return null;
    }
    return new Out(out, pattern == null ? Capture.Style.GLASS : Capture.Style.BLOCKS,
        label.toLowerCase(Locale.ROOT).equals("/set") ? null : (pattern == null ? "符合遮罩" : "取代後"));
  }
}
