package dev.twme.editpreview;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import dev.twme.editpreview.fawe.FaweSupport;
import java.util.function.Function;
import java.util.UUID;

/**
 * Intercepts a player's EditSession while a capture is armed:
 * <ul>
 *   <li>Vanilla WorldEdit: wrap a {@link CaptureExtent} at BEFORE_HISTORY (after masks, before history).
 *   <li>FAWE: at BEFORE_CHANGE (closest to the world), add a {@link FaweSupport} batch processor to the queue; wrap the extent only if no queue is found.
 * </ul>
 */
final class WeHook {
  /** EditSessions created next on this thread are always captured (brush preview: only our own session, never the player's real edits) */
  static final ThreadLocal<Capture> FORCED = new ThreadLocal<>();

  private final boolean fawe;
  private final Function<UUID, Capture> armed;

  WeHook(boolean fawe, Function<UUID, Capture> armed) {
    this.fawe = fawe;
    this.armed = armed;
  }

  void register() {
    WorldEdit.getInstance().getEventBus().register(this);
  }

  void unregister() {
    WorldEdit.getInstance().getEventBus().unregister(this);
  }

  @Subscribe
  public void onEditSession(EditSessionEvent e) {
    EditSession.Stage want = fawe ? EditSession.Stage.BEFORE_CHANGE : EditSession.Stage.BEFORE_HISTORY;
    if (e.getStage() != want) return;
    Capture cap = FORCED.get();
    if (cap == null) {
      var actor = e.getActor();
      if (actor == null || !actor.isPlayer()) return;
      cap = armed.apply(actor.getUniqueId());
    }
    if (cap == null) return;
    cap.sessionStarted();
    if (fawe) {
      try {
        if (FaweSupport.attach(e.getExtent(), cap)) return;
      } catch (LinkageError err) {
        // FAWE internals don't match: fall back to the extent
      }
    }
    e.setExtent(new CaptureExtent(e.getExtent(), cap));
  }
}
