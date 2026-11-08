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
 * Typing preview: a half-typed //set or //replace in the chat box is parsed directly with WorldEdit's parsers and evaluated
 * over the selection (the command is not executed, so typos produce no error messages).
 * <ul>
 *   <li>//set &lt;pattern&gt;: the selection (minus blocks failing the gmask) filled with the pattern.
 *   <li>//replace &lt;mask&gt;: while the mask is being typed, matching blocks show as lime glass.
 *   <li>//replace &lt;mask&gt; &lt;pattern&gt;: once the pattern is typed, matching blocks show the replaced result.
 * </ul>
 */
final class Live {
  private Live() {}

  static final Set<String> SET = Set.of("/set");
  static final Set<String> REPLACE = Set.of("/replace", "/rep", "/re");

  /** compute returns null when parsing fails (keep the previous preview), or {@link #NOT_LIVE} for other commands. */
  record Out(Map<Long, BlockData> writes, Capture.Style style, String note) {}

  /** Reason for the last parse/compute failure (for pev debug). */
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
      if (a.isEmpty() || EditPreviewPlugin.isPev(a)) continue;
      if (a.length() == 2 && a.charAt(0) == '-' && Character.isLetter(a.charAt(1))) continue; // flag
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
    if (world == null || !world.getName().equals(p.getWorld().getName())) return new Out(Map.of(), Capture.Style.BLOCKS, "No selection");
    Region region;
    try {
      region = session.getRegionSelector(world).getRegion();
    } catch (IncompleteRegionException e) {
      return new Out(Map.of(), Capture.Style.BLOCKS, "No selection");
    }
    long volume;
    try {
      volume = region.getVolume();
    } catch (Throwable t) {
      volume = Long.MAX_VALUE;
    }
    if (volume > maxVolume) return new Out(Map.of(), Capture.Style.BLOCKS, "Selection is larger than " + String.format("%,d", maxVolume) + " blocks; typing preview paused (Enter still dry-runs)");

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
        // older FAWE
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
        label.toLowerCase(Locale.ROOT).equals("/set") ? null : (pattern == null ? "matching mask" : "after replace"));
  }
}
