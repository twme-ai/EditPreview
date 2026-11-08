package dev.twme.editpreview;

/** Packs a block position into a long: 26 bits each for x and z, 12 bits for y (-2048..2047). */
public final class Keys {
  private Keys() {}

  public static long key(int x, int y, int z) {
    return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
  }

  public static int x(long k) {
    return (int) (k >> 38);
  }

  public static int z(long k) {
    return (int) ((k << 26) >> 38);
  }

  public static int y(long k) {
    return (int) ((k << 52) >> 52);
  }

  public static long offset(long k, int dx, int dy, int dz) {
    return key(x(k) + dx, y(k) + dy, z(k) + dz);
  }
}
