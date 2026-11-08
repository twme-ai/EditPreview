package dev.twme.editpreview;

import dev.twme.editpreview.render.FakeView;
import org.bukkit.entity.Player;

/** Preview state of one player. */
final class PlayerState {
  enum Source {
    NONE,
    /** Command dry-run (-pev or always-preview) */
    COMMAND,
    /** Typing preview (//set, //replace, or any command with -pev) */
    LIVE,
    /** Always-preview: aiming a brush */
    BRUSH,
    /** Brief lime-glass flash of a newly made selection */
    SELECTION
  }

  enum StyleMode {
    AUTO,
    BLOCKS,
    GLASS
  }

  final Player player;
  final FakeView fake;

  boolean always;
  boolean brush = true;
  /** Flash new selections as lime glass (independent of always-preview) */
  boolean selectFlash = true;
  /** Carve the preview around the player's line of sight and body */
  boolean carve = true;
  StyleMode style = StyleMode.AUTO;

  /** Selection flash: identity of the last seen selection (null = none) and when the flash ends (tick) */
  String selKey;
  boolean selSeen;
  long flashUntil;
  /** Dry-run preview (COMMAND) expires at this tick (0 = never) */
  long commandUntil;

  /** Armed dry-run (read from WorldEdit threads) */
  volatile Capture armed;
  /** Large FAWE edits that need //confirm: no EditSession arrived while armed, so keep it; //confirm within 60 s dry-runs it */
  Capture pendingConfirm;
  long pendingUntil;

  /** Last command dry-run (re-run on apply) */
  String lastCommand;
  Source source = Source.NONE;
  /** A real command sent while a typing dry-run was still running: sent after it finishes so it isn't swallowed as a preview */
  String queuedChat;

  /** Typing preview: the command in the chat box (written by the async completion event) */
  volatile String typed;
  volatile long typedAt;
  String typedDone;

  /** Brush preview: target and settings of the last computation */
  String brushKey;
  int brushCooldown;

  PlayerState(Player player) {
    this.player = player;
    fake = new FakeView(player);
  }

  void clearPreview() {
    fake.clear();
    source = Source.NONE;
  }

  void dispose() {
    fake.forget();
  }
}
