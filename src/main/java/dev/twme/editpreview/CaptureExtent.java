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
 * An extent that swallows all writes: they are only recorded in the {@link Capture}, and reads see recorded values first (later steps of the same command see earlier results).
 * <p>
 * In vanilla WorldEdit it sits at BEFORE_HISTORY (after masks, before history), so history stays empty.
 * With FAWE it is used only when no queue is found; FAWE bulk writes (setBlocks, replaceBlocks…) go straight to the queue by default,
 * so they are overridden to record block by block. Only FAWE's Extent has these methods, hence no @Override.
 */
public final class CaptureExtent extends AbstractDelegateExtent {
  private final Capture cap;
  /**
   * Vanilla WorldEdit's extent chain, inside out: world → reorder → history → mask (MaskingExtent) → block limit → BEFORE_HISTORY,
   * so this layer sits outside the mask: writes must check the MaskingExtent below (gmask, brush mask; also read if set after the EditSession is built).
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

  // ---- FAWE bulk writes: record block by block ----

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
