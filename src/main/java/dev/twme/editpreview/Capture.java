package dev.twme.editpreview;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 一次預覽：玩家下了帶 -pev（或恆預覽中）的指令後「上膛」，之後這個玩家的 EditSession 寫入都收進這裡，
 * 不寫進世界、也不進歷史紀錄。寫入可能來自 FAWE 的多條執行緒，所以同步。
 */
public final class Capture {
  public enum Style {
    /** 指令有遮罩或是 replace：綠色玻璃 */
    GLASS,
    /** 一般：顯示寫入後的方塊 */
    BLOCKS
  }

  final UUID player;
  /** 去掉 -pev 後的指令（含開頭的 /），套用時重下 */
  final String command;
  final Style style;
  /** false＝只看不套用（/pev mask） */
  final boolean applicable;
  /** true＝玩家明確要求（-pev、/pev ...）；恆預覽時沒有變更就不必提示 */
  final boolean explicit;
  final long created = System.currentTimeMillis();
  private final int max;

  /** 位置鍵 → 寫入的狀態（原版：WorldEdit 的 BlockState；FAWE：狀態序號 Character） */
  private final Map<Long, Object> writes = new HashMap<>();
  volatile long last = created;
  volatile boolean sessionSeen, overflow, committed;

  Capture(UUID player, String command, Style style, boolean applicable, boolean explicit, int max) {
    this.player = player;
    this.command = command;
    this.style = style;
    this.applicable = applicable;
    this.explicit = explicit;
    this.max = max;
  }

  public void put(int x, int y, int z, Object state) {
    long k = Keys.key(x, y, z);
    synchronized (writes) {
      if (writes.size() >= max && !writes.containsKey(k)) overflow = true;
      else writes.put(k, state);
    }
    last = System.currentTimeMillis();
  }

  public Object get(int x, int y, int z) {
    synchronized (writes) {
      return writes.get(Keys.key(x, y, z));
    }
  }

  public void touch() {
    last = System.currentTimeMillis();
  }

  public void sessionStarted() {
    sessionSeen = true;
    touch();
  }

  int size() {
    synchronized (writes) {
      return writes.size();
    }
  }

  Map<Long, Object> snapshot() {
    synchronized (writes) {
      return new HashMap<>(writes);
    }
  }
}
