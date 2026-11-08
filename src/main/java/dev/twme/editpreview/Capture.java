package dev.twme.editpreview;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One preview: armed when a player sends a command with -pev (or in always-preview); that player's EditSession writes are then collected here
 * instead of reaching the world or history. Writes may come from several FAWE threads, hence the synchronization.
 */
public final class Capture {
  public enum Style {
    /** The command has a mask or is a replace: lime glass */
    GLASS,
    /** Default: show the resulting blocks */
    BLOCKS
  }

  final UUID player;
  /** The command without -pev (with its leading /), re-run on apply */
  final String command;
  final Style style;
  /** false = view only, cannot be applied (/pev mask) */
  final boolean applicable;
  /** true = explicitly requested (-pev, /pev ...); in always-preview an empty result needs no message */
  final boolean explicit;
  final long created = System.currentTimeMillis();
  private final int max;

  /** Position key → written state (vanilla: WorldEdit BlockState; FAWE: state ordinal as Character) */
  private final Map<Long, Object> writes = new HashMap<>();
  volatile long last = created;
  volatile boolean sessionSeen, overflow, committed;
  /** Auto dry-run while typing (with -pev): shown as a typing preview, no chat message */
  volatile boolean live;

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
