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
 * FAWE 專用（只在 FAWE 存在時載入）。
 * <p>
 * FAWE 的寫入大多是整批交給佇列，不經過 extent 的 setBlock；所以在佇列上掛一個 CUSTOM 等級的批次處理器。
 * 處理器依等級排序：遮罩（REMOVING_BLOCKS）之後、歷史紀錄（READING_BLOCKS）之前，
 * 這裡看到的就是遮罩後的最終結果；記下來之後把這批寫入整個清空，世界和 //undo 都不受影響。
 */
public final class FaweSupport implements IBatchProcessor {
  private final Capture cap;

  private FaweSupport(Capture cap) {
    this.cap = cap;
  }

  /** 在 extent 鏈裡找到佇列並掛上處理器；找不到回傳 false（改用 CaptureExtent）。 */
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

  /** addProcessor 定義在 FAWE 版的 Extent 上（編譯時解析到的是原版 Extent），所以用反射呼叫。 */
  private static boolean add(Object queue, IBatchProcessor p) {
    try {
      queue.getClass().getMethod("addProcessor", IBatchProcessor.class).invoke(queue, p);
      return true;
    } catch (ReflectiveOperationException e) {
      return false;
    }
  }

  /** FAWE 只接受 allowed-plugins 裡的 extent 類別（以套件名稱子字串比對）。 */
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
    log.info("已將 " + type.getPackageName() + " 加入 FAWE 的 allowed-plugins（只在記憶體中）");
  }

  /** FAWE 的遮罩綁著當初解析時的 extent（多半是已關閉的 EditSession），換成現在要讀的。 */
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
        if (c == 0) continue; // 0＝這格沒寫
        cap.put(bx + (i & 15), by + (i >> 8), bz + ((i >> 4) & 15), c);
      }
    }
    cap.touch();
    // 什麼都不寫：方塊、方塊實體、生物群系、實體、光照全部清掉
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
