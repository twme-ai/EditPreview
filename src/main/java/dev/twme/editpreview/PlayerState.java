package dev.twme.editpreview;

import dev.twme.editpreview.render.FakeView;
import dev.twme.editpreview.render.Geo.Shape;
import dev.twme.editpreview.render.Outline;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** 一位玩家的預覽狀態。 */
final class PlayerState {
  enum Source {
    NONE,
    /** 帶 -pev 或恆預覽下的指令試跑 */
    COMMAND,
    /** 恆預覽：打字中的 //set、//replace */
    LIVE,
    /** 恆預覽：拿著筆刷瞄準 */
    BRUSH
  }

  enum StyleMode {
    AUTO,
    BLOCKS,
    GLASS
  }

  final Player player;
  final Outline outline;
  final FakeView fake;

  boolean always;
  boolean showBox = true;
  boolean brush = true;
  StyleMode style = StyleMode.AUTO;

  /** 上膛中的試跑（WE 的執行緒會讀） */
  volatile Capture armed;
  /** FAWE 要 //confirm 的大編輯：上膛後沒等到 EditSession，先記著，60 秒內打 //confirm 就接著試跑 */
  Capture pendingConfirm;
  long pendingUntil;

  /** 最後一次指令試跑（套用時重下） */
  String lastCommand;
  Source source = Source.NONE;
  Shape previewBox = Shape.empty();

  /** 打字預覽：聊天欄裡的指令（非同步的補全事件寫入） */
  volatile String typed;
  volatile long typedAt;
  String typedDone;

  /** 筆刷預覽：上次算的目標與設定 */
  String brushKey;
  int brushCooldown;

  PlayerState(Plugin plugin, Player player) {
    this.player = player;
    outline = new Outline(plugin, player);
    fake = new FakeView(player);
  }

  void clearPreview() {
    fake.clear();
    previewBox = Shape.empty();
    source = Source.NONE;
  }

  void dispose() {
    outline.clear();
    fake.forget();
  }
}
