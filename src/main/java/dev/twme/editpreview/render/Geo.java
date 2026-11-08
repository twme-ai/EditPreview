package dev.twme.editpreview.render;

import java.util.ArrayList;
import java.util.List;

/** 邊框的幾何：線段（細長條）、方塊標記與文字。 */
public final class Geo {
  private Geo() {}

  public record V(double x, double y, double z) {
    public V add(V o) {
      return new V(x + o.x, y + o.y, z + o.z);
    }

    public V sub(V o) {
      return new V(x - o.x, y - o.y, z - o.z);
    }

    public V mul(double k) {
      return new V(x * k, y * k, z * k);
    }

    public double length() {
      return Math.sqrt(x * x + y * y + z * z);
    }
  }

  /** a→b 的發光細條。color 決定方塊材質與發光色，見 {@link Color}。 */
  public record Seg(V a, V b, Color color, double t) {}

  /** 以 c 為中心、邊長 size 的發光小方塊。 */
  public record Cube(V c, double size, Color color) {}

  public record Text(V at, String text) {}

  public enum Color {
    WHITE("white_concrete", 0xFFFFFF),
    ORANGE("orange_concrete", 0xFF9933),
    BLUE("light_blue_concrete", 0x55CCFF),
    YELLOW("yellow_concrete", 0xFFDD33),
    GRAY("light_gray_concrete", 0xAAAAAA);

    public final String block;
    public final int glow;

    Color(String block, int glow) {
      this.block = block;
      this.glow = glow;
    }
  }

  public record Shape(List<Seg> segs, List<Cube> cubes, List<Text> texts) {
    public static Shape empty() {
      return new Shape(List.of(), List.of(), List.of());
    }

    public Shape plus(Shape o) {
      var s = new ArrayList<>(segs);
      s.addAll(o.segs);
      var c = new ArrayList<>(cubes);
      c.addAll(o.cubes);
      var t = new ArrayList<>(texts);
      t.addAll(o.texts);
      return new Shape(s, c, t);
    }
  }

  public static final double EDGE = 0.045;

  /** 方塊座標 [lo, hi]（含）外框的 12 條邊。 */
  public static List<Seg> box(int x0, int y0, int z0, int x1, int y1, int z1, Color c) {
    double[] xs = {x0, x1 + 1.0}, ys = {y0, y1 + 1.0}, zs = {z0, z1 + 1.0};
    List<Seg> out = new ArrayList<>(12);
    for (int i = 0; i < 4; i++) {
      double a = (i & 1) == 0 ? ys[0] : ys[1], b = (i & 2) == 0 ? zs[0] : zs[1];
      out.add(new Seg(new V(xs[0], a, b), new V(xs[1], a, b), c, EDGE));
      double p = (i & 1) == 0 ? xs[0] : xs[1], q = (i & 2) == 0 ? zs[0] : zs[1];
      out.add(new Seg(new V(p, ys[0], q), new V(p, ys[1], q), c, EDGE));
      double r = (i & 1) == 0 ? xs[0] : xs[1], s = (i & 2) == 0 ? ys[0] : ys[1];
      out.add(new Seg(new V(r, s, zs[0]), new V(r, s, zs[1]), c, EDGE));
    }
    return out;
  }

  /** 在平面 (u, v) 上、以 c 為圓心的橢圓（n 段）。 */
  public static List<Seg> ellipse(V c, V u, V v, int n, Color color) {
    List<Seg> out = new ArrayList<>(n);
    V prev = c.add(u);
    for (int i = 1; i <= n; i++) {
      double a = 2 * Math.PI * i / n;
      V p = c.add(u.mul(Math.cos(a))).add(v.mul(Math.sin(a)));
      out.add(new Seg(prev, p, color, EDGE));
      prev = p;
    }
    return out;
  }

  public static int segments(double radius) {
    return (int) Math.max(16, Math.min(96, Math.round(radius * 3)));
  }
}
