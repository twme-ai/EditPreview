package dev.twme.editpreview;

import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.MaskingExtent;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import java.util.Set;
import java.util.UUID;

/**
 * 吞下所有寫入的 extent：寫入只記進 {@link Capture}，讀取時先看記下來的（同一個指令後面的步驟看得到前面的結果）。
 * <p>
 * 原版 WorldEdit 掛在 BEFORE_HISTORY（遮罩之後、歷史之前），所以歷史裡什麼都沒有。
 * FAWE 找不到佇列時才用它；FAWE 的整批寫入（setBlocks、replaceBlocks…）預設會直接往下交給佇列，
 * 所以這裡把它們都改成逐格記錄。這些方法只有 FAWE 的 Extent 才有，故不加 @Override。
 */
public final class CaptureExtent extends AbstractDelegateExtent {
  private final Capture cap;
  /**
   * 原版 WorldEdit 的 extent 鏈由內到外是 世界 → 重排 → 歷史 → 遮罩（MaskingExtent）→ 數量上限 → BEFORE_HISTORY，
   * 所以這一層在遮罩「外面」：寫入要先問下面那層 MaskingExtent 的遮罩（gmask、筆刷遮罩；EditSession 建好後才設也讀得到）。
   */
  private final MaskingExtent masking;

  public CaptureExtent(Extent extent, Capture cap) {
    super(extent);
    this.cap = cap;
    MaskingExtent found = null;
    Extent e = extent;
    for (int depth = 0; e != null && depth < 32; depth++) {
      if (e instanceof MaskingExtent m) {
        found = m;
        break;
      }
      e = e instanceof AbstractDelegateExtent d ? d.getExtent() : null;
    }
    masking = found;
  }

  private void put(BlockVector3 p, BlockState s) {
    if (masking != null) {
      Mask m = masking.getMask();
      if (m != null && !m.test(p)) return;
    }
    cap.put(p.x(), p.y(), p.z(), s);
  }

  @Override
  public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 p, T block) throws WorldEditException {
    put(p, block.toImmutableState());
    return true;
  }

  // FAWE
  public <T extends BlockStateHolder<T>> boolean setBlock(int x, int y, int z, T block) {
    put(BlockVector3.at(x, y, z), block.toImmutableState());
    return true;
  }

  @Override
  public BlockState getBlock(BlockVector3 p) {
    return cap.get(p.x(), p.y(), p.z()) instanceof BlockState s ? s : super.getBlock(p);
  }

  // FAWE
  public BlockState getBlock(int x, int y, int z) {
    return cap.get(x, y, z) instanceof BlockState s ? s : getExtent().getBlock(BlockVector3.at(x, y, z));
  }

  @Override
  public BaseBlock getFullBlock(BlockVector3 p) {
    return cap.get(p.x(), p.y(), p.z()) instanceof BlockState s ? s.toBaseBlock() : super.getFullBlock(p);
  }

  // FAWE
  public BaseBlock getFullBlock(int x, int y, int z) {
    return cap.get(x, y, z) instanceof BlockState s ? s.toBaseBlock() : getExtent().getFullBlock(BlockVector3.at(x, y, z));
  }

  @Override
  public boolean setBiome(BlockVector3 position, BiomeType biome) {
    return false;
  }

  // FAWE
  public boolean setBiome(int x, int y, int z, BiomeType biome) {
    return false;
  }

  @Override
  public Entity createEntity(Location location, BaseEntity entity) {
    return null;
  }

  // FAWE
  public Entity createEntity(Location location, BaseEntity entity, UUID uuid) {
    return null;
  }

  @Override
  protected Operation commitBefore() {
    cap.committed = true;
    cap.touch();
    return null;
  }

  // ---- FAWE 的整批寫入：改成逐格 ----

  public <B extends BlockStateHolder<B>> int setBlocks(Region region, B block) {
    BlockState s = block.toImmutableState();
    int n = 0;
    for (BlockVector3 p : region) {
      put(p, s);
      n++;
    }
    return n;
  }

  public int setBlocks(Region region, Pattern pattern) {
    int n = 0;
    for (BlockVector3 p : region) {
      put(p, pattern.applyBlock(p).toImmutableState());
      n++;
    }
    return n;
  }

  public int setBlocks(Set<BlockVector3> vset, Pattern pattern) {
    int n = 0;
    for (BlockVector3 p : vset) {
      put(p, pattern.applyBlock(p).toImmutableState());
      n++;
    }
    return n;
  }

  public int replaceBlocks(Region region, Mask mask, Pattern pattern) {
    int n = 0;
    for (BlockVector3 p : region) {
      if (!mask.test(p)) continue;
      put(p, pattern.applyBlock(p).toImmutableState());
      n++;
    }
    return n;
  }

  public int replaceBlocks(Region region, Set<BaseBlock> filter, Pattern pattern) {
    int n = 0;
    for (BlockVector3 p : region) {
      if (filter != null && !filter.isEmpty() && !filter.contains(getFullBlock(p))) continue;
      put(p, pattern.applyBlock(p).toImmutableState());
      n++;
    }
    return n;
  }

  public <B extends BlockStateHolder<B>> int replaceBlocks(Region region, Set<BaseBlock> filter, B replacement) {
    BlockState s = replacement.toImmutableState();
    int n = 0;
    for (BlockVector3 p : region) {
      if (filter != null && !filter.isEmpty() && !filter.contains(getFullBlock(p))) continue;
      put(p, s);
      n++;
    }
    return n;
  }
}
