package dev.twme.editpreview.fawe;

import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.extent.PassthroughExtent;
import com.fastasyncworldedit.core.extent.processor.ProcessorScope;
import com.fastasyncworldedit.core.queue.IBatchProcessor;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.fastasyncworldedit.core.queue.IQueueExtent;
import com.fastasyncworldedit.core.queue.implementation.ParallelQueueExtent;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.world.block.BlockTypesCache;
import dev.twme.editpreview.Capture;
import java.util.ArrayList;
import java.util.Locale;
import java.util.logging.Logger;
import org.bukkit.block.data.BlockData;

/**
 * FAWE only (loaded only when FAWE is present).
 * <p>
 * Most FAWE writes go to the queue in bulk, bypassing the extent's setBlock, so a CUSTOM-scope batch processor is added to the queue.
 * Processors are ordered by scope: after masks (REMOVING_BLOCKS) and before history (READING_BLOCKS),
 * so this sees the final, masked result; after recording it the whole batch is cleared, leaving the world and //undo untouched.
 */
public final class FaweSupport implements IBatchProcessor {
  private final Capture cap;

  private FaweSupport(Capture cap) {
    this.cap = cap;
  }

  /** Finds the queue in the extent chain and adds the processor; returns false if none is found (use CaptureExtent instead). */
  public static boolean attach(Extent extent, Capture cap) {
    FaweSupport p = new FaweSupport(cap);
    for (int depth = 0; extent != null && depth < 32; depth++) {
      if (extent instanceof ParallelQueueExtent parallel) return add(parallel.getExtent(), p);
      if (extent instanceof IQueueExtent<?> queue) return add(queue, p);
      if (extent instanceof PassthroughExtent pe) extent = pe.getExtent();
      else extent = extent instanceof AbstractDelegateExtent d ? d.getExtent() : null;
    }
    return false;
  }

  /** addProcessor is defined on FAWE's Extent (we compile against vanilla Extent), so it is called reflectively. */
  private static boolean add(Object queue, IBatchProcessor p) {
    try {
      queue.getClass().getMethod("addProcessor", IBatchProcessor.class).invoke(queue, p);
      return true;
    } catch (ReflectiveOperationException e) {
      return false;
    }
  }

  /** FAWE only accepts extent classes listed in allowed-plugins (matched by package-name substring). */
  public static void allowExtent(Class<?> type, Logger log) {
    String name = type.getPackageName().toLowerCase(Locale.ROOT);
    var settings = Settings.settings().EXTENT;
    for (String allowed : settings.ALLOWED_PLUGINS) if (name.contains(allowed.toLowerCase(Locale.ROOT))) return;
    try {
      settings.ALLOWED_PLUGINS.add(type.getPackageName());
    } catch (UnsupportedOperationException e) {
      var copy = new ArrayList<>(settings.ALLOWED_PLUGINS);
      copy.add(type.getPackageName());
      settings.ALLOWED_PLUGINS = copy;
    }
    log.info("Added " + type.getPackageName() + " to FAWE allowed-plugins (in memory only)");
  }

  /** FAWE masks stay bound to the extent they were parsed with (often a closed EditSession); rebind to the one we read now. */
  public static void resetMask(com.sk89q.worldedit.function.mask.Mask m, Extent e) {
    if (m != null) new com.fastasyncworldedit.core.util.MaskTraverser(m).reset(e);
  }

  public static BlockData adapt(char c) {
    return BukkitAdapter.adapt(BlockTypesCache.states[c]);
  }

  @Override
  public IChunkSet processSet(IChunk chunk, IChunkGet get, IChunkSet set) {
    int bx = chunk.getX() << 4, bz = chunk.getZ() << 4;
    for (int layer = set.getMinSectionPosition(); layer <= set.getMaxSectionPosition(); layer++) {
      char[] blocks = set.loadIfPresent(layer);
      if (blocks == null) continue;
      int by = layer << 4;
      for (int i = 0; i < blocks.length; i++) {
        char c = blocks[i];
        if (c == 0) continue; // 0 = not written
        cap.put(bx + (i & 15), by + (i >> 8), bz + ((i >> 4) & 15), c);
      }
    }
    cap.touch();
    // write nothing: clear blocks, block entities, biomes, entities and light
    set.reset();
    return set;
  }

  @Override
  public Extent construct(Extent child) {
    return child;
  }

  @Override
  public ProcessorScope getScope() {
    return ProcessorScope.CUSTOM;
  }
}
