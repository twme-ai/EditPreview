package dev.twme.editpreview;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import dev.twme.editpreview.fawe.FaweSupport;
import java.util.function.Function;
import java.util.UUID;

/**
 * 玩家「上膛」時攔下他的 EditSession：
 * <ul>
 *   <li>原版 WorldEdit：BEFORE_HISTORY 階段包一層 {@link CaptureExtent}（在遮罩之後、歷史之前）。
 *   <li>FAWE：最靠近世界的 BEFORE_CHANGE 階段，在佇列掛 {@link FaweSupport} 批次處理器；找不到佇列才包 extent。
 * </ul>
 */
final class WeHook {
  /** 這條執行緒接下來建立的 EditSession 一律攔下（筆刷預覽：只攔自己建的那一個，不影響玩家真正的編輯） */
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
        // FAWE 內部類別對不上：退回 extent
      }
    }
    e.setExtent(new CaptureExtent(e.getExtent(), cap));
  }
}
