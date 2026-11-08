package dev.twme.editpreview.render;

import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.regions.ConvexPolyhedralRegion;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.CylinderRegion;
import com.sk89q.worldedit.regions.EllipsoidRegion;
import com.sk89q.worldedit.regions.Polygonal2DRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.regions.polyhedron.Triangle;
import dev.twme.editpreview.render.Geo.Color;
import dev.twme.editpreview.render.Geo.Cube;
import dev.twme.editpreview.render.Geo.Seg;
import dev.twme.editpreview.render.Geo.Shape;
import dev.twme.editpreview.render.Geo.Text;
import dev.twme.editpreview.render.Geo.V;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** WorldEdit 選區 → 邊框。認不得的選區畫包圍盒（灰色）。 */
public final class Shapes {
  private Shapes() {}

  private static final int MAX_SEGS = 600;

  public static Shape region(Region r) {
    List<Seg> segs;
    List<Cube> cubes = new ArrayList<>();
    try {
      if (r instanceof CuboidRegion c) {
        segs = cuboid(c.getMinimumPoint(), c.getMaximumPoint(), Color.WHITE);
        cubes.add(new Cube(center(c.getPos1()), 0.3, Color.ORANGE));
        cubes.add(new Cube(center(c.getPos2()), 0.3, Color.BLUE));
      } else if (r instanceof Polygonal2DRegion p) {
        segs = polygon(p.getPoints(), p.getMinimumY(), p.getMaximumY());
      } else if (r instanceof CylinderRegion c) {
        segs = cylinder(c);
      } else if (r instanceof EllipsoidRegion e) {
        segs = ellipsoid(e);
      } else if (r instanceof ConvexPolyhedralRegion cv) {
        segs = convex(cv);
      } else {
        segs = cuboid(r.getMinimumPoint(), r.getMaximumPoint(), Color.GRAY);
      }
    } catch (Throwable t) {
      // 不同版本的 WE／FAWE 方法對不上時退回包圍盒
      segs = cuboid(r.getMinimumPoint(), r.getMaximumPoint(), Color.GRAY);
    }
    if (segs.size() > MAX_SEGS) segs = segs.subList(0, MAX_SEGS);
    return new Shape(segs, cubes, List.of(label(r)));
  }

  /** 只有第一點時（選區還沒完成）。 */
  public static Shape point(BlockVector3 p) {
    return new Shape(List.of(), List.of(new Cube(center(p), 0.3, Color.ORANGE)), List.of());
  }

  public static List<Seg> cuboid(BlockVector3 lo, BlockVector3 hi, Color c) {
    return Geo.box(lo.x(), lo.y(), lo.z(), hi.x(), hi.y(), hi.z(), c);
  }

  private static Text label(Region r) {
    BlockVector3 lo = r.getMinimumPoint(), hi = r.getMaximumPoint();
    int w = hi.x() - lo.x() + 1, h = hi.y() - lo.y() + 1, l = hi.z() - lo.z() + 1;
    long vol;
    try {
      vol = r.getVolume();
    } catch (Throwable t) {
      vol = (long) w * h * l;
    }
    return new Text(new V((lo.x() + hi.x() + 1) / 2.0, hi.y() + 1.5, (lo.z() + hi.z() + 1) / 2.0),
        w + " × " + h + " × " + l + "（" + String.format("%,d", vol) + " 格）");
  }

  private static V center(BlockVector3 p) {
    return new V(p.x() + 0.5, p.y() + 0.5, p.z() + 0.5);
  }

  private static List<Seg> polygon(List<BlockVector2> pts, int y0, int y1) {
    List<Seg> out = new ArrayList<>();
    double lo = y0, hi = y1 + 1.0;
    int n = Math.min(pts.size(), 200);
    for (int i = 0; i < n; i++) {
      BlockVector2 a = pts.get(i), b = pts.get((i + 1) % n);
      double ax = a.x() + 0.5, az = a.z() + 0.5, bx = b.x() + 0.5, bz = b.z() + 0.5;
      out.add(new Seg(new V(ax, lo, az), new V(bx, lo, bz), Color.WHITE, Geo.EDGE));
      out.add(new Seg(new V(ax, hi, az), new V(bx, hi, bz), Color.WHITE, Geo.EDGE));
      out.add(new Seg(new V(ax, lo, az), new V(ax, hi, az), Color.WHITE, Geo.EDGE));
    }
    return out;
  }

  private static List<Seg> cylinder(CylinderRegion c) {
    Vector3 ctr = c.getCenter();
    double rx = c.getRadius().x() + 0.5, rz = c.getRadius().z() + 0.5;
    double lo = c.getMinimumY(), hi = c.getMaximumY() + 1.0;
    double cx = ctr.x() + 0.5, cz = ctr.z() + 0.5;
    int n = Geo.segments(Math.max(rx, rz));
    V u = new V(rx, 0, 0), v = new V(0, 0, rz);
    List<Seg> out = new ArrayList<>(Geo.ellipse(new V(cx, lo, cz), u, v, n, Color.WHITE));
    out.addAll(Geo.ellipse(new V(cx, hi, cz), u, v, n, Color.WHITE));
    for (int i = 0; i < 4; i++) {
      double a = Math.PI / 2 * i;
      double x = cx + rx * Math.cos(a), z = cz + rz * Math.sin(a);
      out.add(new Seg(new V(x, lo, z), new V(x, hi, z), Color.WHITE, Geo.EDGE));
    }
    return out;
  }

  private static List<Seg> ellipsoid(EllipsoidRegion e) {
    Vector3 ctr = e.getCenter(), rad = e.getRadius();
    V c = new V(ctr.x() + 0.5, ctr.y() + 0.5, ctr.z() + 0.5);
    double rx = rad.x() + 0.5, ry = rad.y() + 0.5, rz = rad.z() + 0.5;
    int n = Geo.segments(Math.max(rx, Math.max(ry, rz)));
    V ux = new V(rx, 0, 0), uy = new V(0, ry, 0), uz = new V(0, 0, rz);
    List<Seg> out = new ArrayList<>(Geo.ellipse(c, ux, uz, n, Color.WHITE));
    out.addAll(Geo.ellipse(c, ux, uy, n, Color.WHITE));
    out.addAll(Geo.ellipse(c, uz, uy, n, Color.WHITE));
    return out;
  }

  private static List<Seg> convex(ConvexPolyhedralRegion cv) {
    List<Seg> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Triangle t : cv.getTriangles()) {
      for (int i = 0; i < 3; i++) {
        Vector3 a = t.getVertex(i), b = t.getVertex((i + 1) % 3);
        String k1 = a + "|" + b, k2 = b + "|" + a;
        if (!seen.add(k1) || seen.contains(k2)) continue;
        out.add(new Seg(new V(a.x() + 0.5, a.y() + 0.5, a.z() + 0.5), new V(b.x() + 0.5, b.y() + 0.5, b.z() + 0.5),
            Color.WHITE, Geo.EDGE));
      }
    }
    return out;
  }
}
