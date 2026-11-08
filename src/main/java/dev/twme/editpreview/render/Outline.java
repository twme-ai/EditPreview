package dev.twme.editpreview.render;

import dev.twme.editpreview.render.Geo.Cube;
import dev.twme.editpreview.render.Geo.Seg;
import dev.twme.editpreview.render.Geo.Shape;
import dev.twme.editpreview.render.Geo.Text;
import dev.twme.editpreview.render.Geo.V;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 一位玩家的邊框：一組只有他看得到、不存檔的 BlockDisplay（發光，透過方塊也看得到）。
 * <p>
 * 所有細條的實體都放在玩家附近的同一個錨點，用變形的平移畫到真正的位置——長條或遠處的選取框不會因為
 * 實體離玩家太遠而不被追蹤；玩家走遠了才把錨點搬過去。文字標示是面向鏡頭的，平移會跟著轉，
 * 所以文字實體直接放在標示的位置。
 */
public final class Outline {
  private static final double REANCHOR = 24;

  private final Plugin plugin;
  private final Player player;
  private final List<BlockDisplay> bars = new ArrayList<>();
  private final List<TextDisplay> labels = new ArrayList<>();
  private Shape shape = Shape.empty();
  private Location anchor;

  public Outline(Plugin plugin, Player player) {
    this.plugin = plugin;
    this.player = player;
  }

  /** 每 tick 呼叫：形狀沒變、玩家沒走遠就什麼都不送。 */
  public void show(Shape s) {
    Location pl = player.getLocation();
    boolean moved = anchor == null || anchor.getWorld() != pl.getWorld() || anchor.distanceSquared(pl) > REANCHOR * REANCHOR;
    boolean dead = false;
    for (BlockDisplay b : bars) dead |= !b.isValid();
    for (TextDisplay t : labels) dead |= !t.isValid();
    if (!moved && !dead && s.equals(shape)) return;
    if (dead || (anchor != null && anchor.getWorld() != pl.getWorld())) clear();
    shape = s;
    if (moved || anchor == null) anchor = new Location(pl.getWorld(), Math.floor(pl.getX()), Math.floor(pl.getY()), Math.floor(pl.getZ()));
    draw();
  }

  /** 目前的實體數（細條與方塊標記） */
  public int count() {
    return bars.size();
  }

  public void clear() {
    for (BlockDisplay b : bars) b.remove();
    for (TextDisplay t : labels) t.remove();
    bars.clear();
    labels.clear();
    shape = Shape.empty();
    anchor = null;
  }

  private void draw() {
    World w = anchor.getWorld();
    int need = shape.segs().size() + shape.cubes().size();
    while (bars.size() < need) bars.add(spawnBar(w));
    while (bars.size() > need) bars.remove(bars.size() - 1).remove();
    int i = 0;
    for (Seg s : shape.segs()) place(bars.get(i++), s.color(), bar(s.a(), s.b(), s.t()));
    for (Cube c : shape.cubes()) {
      double h = c.size() / 2;
      V lo = c.c().add(new V(-h, -h, -h));
      place(bars.get(i++), c.color(), box(lo, new V(c.size(), c.size(), c.size())));
    }
    for (BlockDisplay b : bars) if (!b.getLocation().toVector().equals(anchor.toVector())) b.teleport(anchor);

    List<Text> texts = shape.texts();
    while (labels.size() < texts.size()) labels.add(spawnLabel(w));
    while (labels.size() > texts.size()) labels.remove(labels.size() - 1).remove();
    for (int k = 0; k < texts.size(); k++) {
      Text t = texts.get(k);
      TextDisplay d = labels.get(k);
      d.text(Component.text(t.text()));
      Location at = new Location(w, t.at().x(), t.at().y(), t.at().z());
      if (!d.getLocation().equals(at)) d.teleport(at);
    }
  }

  private record Tf(Vector3f translation, Quaternionf rotation, Vector3f scale) {}

  private Tf box(V lo, V size) {
    return new Tf(rel(lo), new Quaternionf(), new Vector3f((float) size.x(), (float) size.y(), (float) size.z()));
  }

  /** a 到 b 的方形截面長條（粗 t）：單位方塊先縮放成 (長, t, t)，再把 +X 轉到 a→b。 */
  private Tf bar(V a, V b, double t) {
    V d = b.sub(a);
    double len = d.length();
    if (len < 1e-6) return box(a, new V(0, 0, 0));
    Vector3f dir = new Vector3f((float) d.x(), (float) d.y(), (float) d.z()).normalize();
    Quaternionf q = new Quaternionf().rotationTo(new Vector3f(1, 0, 0), dir);
    Vector3f off = q.transform(new Vector3f(0, (float) -t / 2, (float) -t / 2));
    Vector3f tr = rel(a).add(off);
    return new Tf(tr, q, new Vector3f((float) len, (float) t, (float) t));
  }

  private Vector3f rel(V p) {
    return new Vector3f((float) (p.x() - anchor.getX()), (float) (p.y() - anchor.getY()), (float) (p.z() - anchor.getZ()));
  }

  private void place(BlockDisplay d, Geo.Color c, Tf tf) {
    BlockData data = Bukkit.createBlockData("minecraft:" + c.block);
    if (!d.getBlock().equals(data)) d.setBlock(data);
    org.bukkit.Color glow = org.bukkit.Color.fromRGB(c.glow);
    if (!glow.equals(d.getGlowColorOverride())) d.setGlowColorOverride(glow);
    Transformation t = new Transformation(tf.translation, tf.rotation, tf.scale, new Quaternionf());
    if (!t.equals(d.getTransformation())) d.setTransformation(t);
  }

  private BlockDisplay spawnBar(World w) {
    BlockDisplay d = w.spawn(anchor, BlockDisplay.class, e -> {
      e.setPersistent(false);
      e.setVisibleByDefault(false);
      e.setGlowing(true);
      e.setBrightness(new Display.Brightness(15, 15));
      e.setViewRange(4f);
      e.setInterpolationDuration(0);
      e.setTeleportDuration(0);
      e.setShadowRadius(0);
    });
    player.showEntity(plugin, d);
    return d;
  }

  private TextDisplay spawnLabel(World w) {
    TextDisplay d = w.spawn(anchor, TextDisplay.class, e -> {
      e.setPersistent(false);
      e.setVisibleByDefault(false);
      e.setBillboard(Display.Billboard.CENTER);
      e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.6f, 1.6f, 1.6f), new Quaternionf()));
      e.setSeeThrough(true);
      e.setShadowed(true);
      e.setBrightness(new Display.Brightness(15, 15));
      e.setViewRange(2f);
    });
    player.showEntity(plugin, d);
    return d;
  }
}
