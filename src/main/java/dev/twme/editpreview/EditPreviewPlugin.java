package dev.twme.editpreview;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.world.block.BlockState;
import dev.twme.editpreview.render.Geo.Shape;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * WorldEdit／FAWE 預覽：
 * <ul>
 *   <li>選區邊框（發光的邊、第一點橘、第二點藍、尺寸標示）。
 *   <li>指令後加 -pev，或開著恆預覽（/pev always）：指令只試跑，結果用假方塊顯示，按 [套用] 才真的寫入。
 *       有遮罩或是 replace 時顯示綠色玻璃。
 *   <li>恆預覽中：打字時即時預覽 //set、//replace；拿著筆刷時預覽瞄準處刷下去的樣子。
 * </ul>
 */
public final class EditPreviewPlugin extends JavaPlugin implements Listener, TabExecutor {
  private static final Set<String> NEVER = Set.of("/undo", "undo", "/redo", "redo", "/clearhistory", "clearhistory",
      "/cancel", "fcancel", "worldedit", "we", "fawe", "/fawe",
      // 移除實體不經過方塊寫入，攔不下來
      "/butcher", "butcher", "/remove", "remove", "/rem", "/rement");
  /** 不寫入世界的指令：不上膛（否則上膛期間玩家真的刷下去的筆刷會被當成預覽吞掉） */
  private static final Set<String> NON_EDIT = Set.of("/br", "br", "brush", "/brush", "tool", "/tool", "/sel", "sel", "/desel", "desel",
      "/pos1", "/pos2", "/hpos1", "/hpos2", "/chunk", "/wand", "/gmask", "gmask", "mask", "/mask", "material", "mat", "size", "range",
      "tracemask", "none", "/none", "/expand", "/contract", "/shift", "/outset", "/inset", "/size", "/count", "/distr", "/copy",
      "/schematic", "/schem", "schematic", "schem", "/clipboard", "/rotate", "/flip", "/clearclipboard", "/fast", "/perf", "/limit",
      "/timeout", "/toggleplace", "/searchitem", "/help", "/listchunks", "/calc", "/eval", "/drawsel", "/watchdog", "/reorder",
      "/sp", "superpickaxe", "/", ",", "/;", "/inspect", "/history", "/lazycopy",
      // 移動玩家
      "/ascend", "asc", "/descend", "desc", "/ceil", "ceil", "/thru", "thru", "/jumpto", "j", "/up", "up", "/unstuck", "!");
  /** 打字中不自動試跑：會改剪貼簿 */
  private static final Set<String> TYPED_UNSAFE = Set.of("/cut", "/lazycut");
  /** 打字中不自動試跑：帶 -s 會移動選區 */
  private static final Set<String> SELECTION_FLAG = Set.of("/move", "/stack", "/paste");
  private static final Set<String> CONFIRM = Set.of("/confirm", "confirm");
  private static final Set<String> REPLACE_LIKE = Set.of("/replace", "/rep", "/re", "/replacenear", "replacenear", "/rn");

  private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
  private WeHook hook;
  private boolean fawe;
  private int maxCapture, maxDisplay, liveMax;
  private double brushMax;
  private long tick;
  private NamespacedKey kAlways, kBox, kBrush, kStyle;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    maxCapture = getConfig().getInt("max-capture", 1_000_000);
    maxDisplay = getConfig().getInt("max-display", 150_000);
    liveMax = getConfig().getInt("live-max-volume", 262_144);
    brushMax = getConfig().getDouble("brush-max-size", 32);
    kAlways = new NamespacedKey(this, "always");
    kBox = new NamespacedKey(this, "box");
    kBrush = new NamespacedKey(this, "brush");
    kStyle = new NamespacedKey(this, "style");
    fawe = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") != null;
    if (fawe) {
      try {
        dev.twme.editpreview.fawe.FaweSupport.allowExtent(CaptureExtent.class, getLogger());
      } catch (LinkageError e) {
        getLogger().warning("FAWE 版本不相容：" + e);
      }
    }
    hook = new WeHook(fawe, id -> {
      PlayerState st = states.get(id);
      return st == null ? null : st.armed;
    });
    hook.register();
    Bukkit.getPluginManager().registerEvents(this, this);
    var cmd = getCommand("pev");
    if (cmd != null) {
      cmd.setExecutor(this);
      cmd.setTabCompleter(this);
    }
    for (Player p : Bukkit.getOnlinePlayers()) join(p);
    Bukkit.getScheduler().runTaskTimer(this, this::tick, 1, 1);
    getLogger().info("預覽已啟用（" + (fawe ? "FastAsyncWorldEdit" : "WorldEdit") + "）");
  }

  @Override
  public void onDisable() {
    if (hook != null) hook.unregister();
    for (PlayerState st : states.values()) {
      st.fake.clear();
      st.fake.flush();
      st.dispose();
    }
    states.clear();
  }

  // ---------------- 玩家 ----------------

  private PlayerState join(Player p) {
    PlayerState st = new PlayerState(this, p);
    var pdc = p.getPersistentDataContainer();
    st.always = pdc.getOrDefault(kAlways, PersistentDataType.BYTE, (byte) (getConfig().getBoolean("defaults.always", false) ? 1 : 0)) == 1;
    st.showBox = pdc.getOrDefault(kBox, PersistentDataType.BYTE, (byte) (getConfig().getBoolean("defaults.box", true) ? 1 : 0)) == 1;
    st.brush = pdc.getOrDefault(kBrush, PersistentDataType.BYTE, (byte) (getConfig().getBoolean("defaults.brush", true) ? 1 : 0)) == 1;
    try {
      st.style = PlayerState.StyleMode.valueOf(pdc.getOrDefault(kStyle, PersistentDataType.STRING, "AUTO"));
    } catch (IllegalArgumentException e) {
      st.style = PlayerState.StyleMode.AUTO;
    }
    states.put(p.getUniqueId(), st);
    return st;
  }

  private void save(PlayerState st) {
    var pdc = st.player.getPersistentDataContainer();
    pdc.set(kAlways, PersistentDataType.BYTE, (byte) (st.always ? 1 : 0));
    pdc.set(kBox, PersistentDataType.BYTE, (byte) (st.showBox ? 1 : 0));
    pdc.set(kBrush, PersistentDataType.BYTE, (byte) (st.brush ? 1 : 0));
    pdc.set(kStyle, PersistentDataType.STRING, st.style.name());
  }

  private PlayerState state(Player p) {
    PlayerState st = states.get(p.getUniqueId());
    return st != null && st.player == p ? st : join(p);
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent e) {
    join(e.getPlayer());
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent e) {
    PlayerState st = states.remove(e.getPlayer().getUniqueId());
    if (st != null) st.dispose();
  }

  @EventHandler
  public void onWorld(PlayerChangedWorldEvent e) {
    PlayerState st = states.get(e.getPlayer().getUniqueId());
    if (st == null) return;
    st.fake.forget();
    st.previewBox = Shape.empty();
    st.source = PlayerState.Source.NONE;
    st.outline.clear();
  }

  // ---------------- 指令攔截 ----------------

  /** 指令名稱：去掉命名空間（worldedit:/set → /set）、轉小寫。 */
  static String baseLabel(String raw) {
    String l = raw.toLowerCase(Locale.ROOT);
    int c = l.indexOf(':');
    return c >= 0 ? l.substring(c + 1) : l;
  }

  /** 指令（聊天欄內容）裡有獨立的 -pev */
  static boolean hasPev(String buffer) {
    String[] parts = buffer.split(" ");
    for (int i = 1; i < parts.length; i++) if (parts[i].equalsIgnoreCase("-pev")) return true;
    return false;
  }

  private static boolean hasFlag(List<String> args, char f) {
    for (int i = 1; i < args.size(); i++) {
      String a = args.get(i);
      if (a.length() >= 2 && a.charAt(0) == '-' && Character.isLetter(a.charAt(1)) && a.indexOf(f) > 0) return true;
    }
    return false;
  }

  private static boolean isWorldEdit(String rawLabel) {
    Command c = Bukkit.getCommandMap().getCommand(rawLabel.toLowerCase(Locale.ROOT));
    if (c instanceof PluginIdentifiableCommand pc) {
      String n = pc.getPlugin().getName();
      return n.equals("WorldEdit") || n.equals("FastAsyncWorldEdit");
    }
    return c == null && rawLabel.startsWith("/");
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onCommand(PlayerCommandPreprocessEvent e) {
    String msg = e.getMessage();
    if (msg.length() < 2) return;
    String[] parts = msg.substring(1).split(" ", -1);
    if (!isWorldEdit(parts[0])) return;
    String label = baseLabel(parts[0]);
    Player p = e.getPlayer();
    PlayerState st = state(p);
    Capture armedNow = st.armed;
    if (armedNow != null && armedNow.live) {
      // 打字試跑還在跑（FAWE 非同步）：這時送出的指令會被它收走，先排隊，試跑結束再送
      e.setCancelled(true);
      st.queuedChat = msg;
      return;
    }

    boolean flag = false;
    List<String> kept = new ArrayList<>();
    for (int i = 0; i < parts.length; i++) {
      if (i > 0 && parts[i].equalsIgnoreCase("-pev")) flag = true;
      else kept.add(parts[i]);
    }
    String clean = "/" + String.join(" ", kept).stripTrailing();
    if (flag) e.setMessage(clean);
    st.typed = null; // 送出了：打字預覽結束
    st.typedDone = null; // 指令可能改了 gmask、選區：下次打一樣的字也要重算

    if (CONFIRM.contains(label)) {
      // FAWE 的大編輯要 //confirm：接著試跑剛才那個
      if (st.pendingConfirm != null && System.currentTimeMillis() < st.pendingUntil) {
        Capture c = st.pendingConfirm;
        st.pendingConfirm = null;
        st.armed = new Capture(p.getUniqueId(), c.command, c.style, c.applicable, c.explicit, maxCapture);
      }
      return;
    }
    if (!flag && !st.always) return;
    if (NON_EDIT.contains(label) || label.startsWith("/sel") || label.startsWith("/br")) return;
    if (NEVER.contains(label)) {
      if (flag) {
        e.setCancelled(true);
        info(p, Component.text("這個指令不能預覽", NamedTextColor.RED));
      }
      return;
    }
    st.armed = new Capture(p.getUniqueId(), clean, style(st, label, kept, p), true, flag, maxCapture);
  }

  private Capture.Style style(PlayerState st, String label, List<String> args, Player p) {
    if (st.style == PlayerState.StyleMode.BLOCKS) return Capture.Style.BLOCKS;
    if (st.style == PlayerState.StyleMode.GLASS) return Capture.Style.GLASS;
    if (REPLACE_LIKE.contains(label)) return Capture.Style.GLASS;
    for (int i = 1; i < args.size(); i++) if (args.get(i).equals("-m")) return Capture.Style.GLASS;
    LocalSession s = WorldEdit.getInstance().getSessionManager().getIfPresent(BukkitAdapter.adapt(p));
    return s != null && s.getMask() != null ? Capture.Style.GLASS : Capture.Style.BLOCKS;
  }

  /**
   * 客戶端打字時會一直要補全：恆預覽中記下聊天欄的內容，主執行緒停手後才算。
   * Paper 用多條執行緒平行處理補全；放在 MONITOR 要等 WorldEdit 算完補全，算得慢的舊字串會蓋掉新的，
   * 所以在最前面（LOWEST）就記下來。
   */
  @EventHandler(priority = EventPriority.LOWEST)
  public void onTab(AsyncTabCompleteEvent e) {
    if (!(e.getSender() instanceof Player p) || !e.isCommand()) return;
    PlayerState st = states.get(p.getUniqueId());
    if (st == null) return;
    // 恆預覽、或指令裡打了 -pev 就開始打字預覽（之後刪掉 -pev 也要繼續記，才能把預覽收掉）
    if (!st.always && st.typed == null && !hasPev(e.getBuffer())) return;
    st.typed = e.getBuffer();
    st.typedAt = System.currentTimeMillis();
  }

  // ---------------- 每 tick ----------------

  private void tick() {
    tick++;
    long now = System.currentTimeMillis();
    for (PlayerState st : states.values()) {
      if (!st.player.isOnline()) continue;
      try {
        checkCapture(st, now);
        if (st.always || st.typed != null) live(st, now);
        if (st.always && st.brush && tick % 3 == 0) brush(st);
        else if ((!st.always || !st.brush) && st.source == PlayerState.Source.BRUSH) st.clearPreview();
        if (tick % 4 == 0) st.outline.show(st.showBox ? st.previewBox : Shape.empty());
        st.fake.flush();
      } catch (Throwable t) {
        getLogger().warning("預覽更新失敗（" + st.player.getName() + "）：" + t);
      }
    }
  }

  private void checkCapture(PlayerState st, long now) {
    Capture c = st.armed;
    if (c == null) return;
    long quiet = now - c.last;
    if (c.sessionSeen) {
      boolean done = (!fawe && c.committed) || (c.size() > 0 && quiet >= 500) || quiet >= 3000;
      if (!done) return;
      st.armed = null;
      finish(st, c);
    } else if (now - c.created > (fawe ? 4000 : 0)) {
      // 沒有 EditSession：不是編輯指令，或 FAWE 在等 //confirm。
      // 原版 WorldEdit 同步執行，指令那一 tick 沒建 EditSession 就是沒有；FAWE 非同步（剛開服時可能要一兩秒），給 4 秒——
      // 太短的話試跑的指令會真的寫進世界；太長只是期間玩家真正的編輯（例如刷筆刷）也被收進預覽，所以寧可長。
      st.armed = null;
      if (!c.live) {
        st.pendingConfirm = c;
        st.pendingUntil = now + 60_000;
      }
    }
    if (st.armed == null && st.queuedChat != null) {
      String q = st.queuedChat;
      st.queuedChat = null;
      st.player.chat(q);
    }
  }

  private void finish(PlayerState st, Capture c) {
    Player p = st.player;
    Map<Long, BlockData> writes = convert(c.snapshot());
    if (c.live) {
      // 打字試跑：當成打字預覽；玩家已經送出或關掉聊天欄就不顯示
      if (writes.isEmpty() || st.typed == null || (st.source != PlayerState.Source.LIVE && st.source != PlayerState.Source.NONE
          && st.source != PlayerState.Source.COMMAND)) {
        if (st.source == PlayerState.Source.LIVE) st.clearPreview();
        if (st.typed != null) p.sendActionBar(Component.text(c.command + "：沒有方塊會改變", NamedTextColor.GRAY));
        return;
      }
      show(st, writes, c.style, PlayerState.Source.LIVE);
      st.lastCommand = null;
      p.sendActionBar(Component.text("預覽 " + c.command + "：" + String.format("%,d", writes.size()) + " 格（按 Enter 試跑）",
          NamedTextColor.AQUA));
      return;
    }
    if (writes.isEmpty()) {
      if (c.explicit) info(p, Component.text("沒有方塊會改變（沒有寫入）", NamedTextColor.GRAY));
      return;
    }
    show(st, writes, c.style, PlayerState.Source.COMMAND);
    st.lastCommand = c.applicable ? c.command : null;
    Component m = Component.text("試跑 ", NamedTextColor.GRAY)
        .append(Component.text(c.command, NamedTextColor.WHITE))
        .append(Component.text("：" + String.format("%,d", writes.size()) + " 格（還沒寫入）", NamedTextColor.GRAY));
    if (c.overflow) m = m.append(Component.text(" 超過上限，只收到前 " + String.format("%,d", maxCapture) + " 格", NamedTextColor.GOLD));
    m = m.append(Component.text("  "));
    if (c.applicable) m = m.append(button("[套用]", NamedTextColor.GREEN, "/pev apply", "照這個預覽真的執行指令")).append(Component.text(" "));
    m = m.append(button("[取消]", NamedTextColor.RED, "/pev clear", "清掉預覽"));
    info(p, m);
  }

  private static Map<Long, BlockData> convert(Map<Long, Object> raw) {
    Map<Object, BlockData> cache = new HashMap<>();
    Map<Long, BlockData> out = new HashMap<>(raw.size() * 2);
    for (var e : raw.entrySet()) {
      BlockData d = cache.computeIfAbsent(e.getValue(), v -> v instanceof Character ch
          ? dev.twme.editpreview.fawe.FaweSupport.adapt(ch)
          : BukkitAdapter.adapt((BlockState) v));
      out.put(e.getKey(), d);
    }
    return out;
  }

  private void show(PlayerState st, Map<Long, BlockData> writes, Capture.Style style, PlayerState.Source src) {
    Player p = st.player;
    var loc = p.getEyeLocation();
    Build.Result r = Build.build(writes, style, loc.getX(), loc.getY(), loc.getZ(), maxDisplay);
    st.fake.show(p.getWorld(), r.fake());
    st.previewBox = r.box();
    st.source = src;
    if (r.truncated()) p.sendActionBar(Component.text("預覽太大：只顯示離你最近的 " + String.format("%,d", maxDisplay) + " 格", NamedTextColor.GOLD));
  }

  private void live(PlayerState st, long now) {
    String t = st.typed;
    if (t == null) {
      if (st.source == PlayerState.Source.LIVE && st.armed == null) st.clearPreview();
      return;
    }
    if (now - st.typedAt > 30_000) {
      st.typed = null;
      return;
    }
    boolean pev = hasPev(t);
    if (!st.always && !pev) {
      // 不是恆預覽，-pev 被刪掉了：收掉打字預覽
      st.typed = null;
      st.typedDone = null;
      if (st.source == PlayerState.Source.LIVE) st.clearPreview();
      return;
    }
    if (t.equals(st.typedDone) || now - st.typedAt < 250) return;
    if (st.source == PlayerState.Source.COMMAND && !pev) { // 不蓋掉還沒套用的試跑（明確打了 -pev 例外）
      st.typedDone = t;
      return;
    }
    Live.Out out = Live.compute(st.player, t, fawe, liveMax);
    if (out == Live.NOT_LIVE && pev) {
      typedRun(st, t, now); // 其他指令：停手後真的試跑一次
      return;
    }
    st.typedDone = t;
    if (out == null) return; // 打到一半解析不了：保留上一次
    if (out == Live.NOT_LIVE) {
      if (st.source == PlayerState.Source.LIVE) st.clearPreview();
      return;
    }
    if (out.writes().isEmpty()) {
      if (st.source == PlayerState.Source.LIVE) st.clearPreview();
      if (out.note() != null) st.player.sendActionBar(Component.text(out.note(), NamedTextColor.GRAY));
      return;
    }
    show(st, out.writes(), out.style(), PlayerState.Source.LIVE);
    st.player.sendActionBar(Component.text("預覽" + (out.note() == null ? "" : "（" + out.note() + "）") + "："
        + String.format("%,d", out.writes().size()) + " 格", NamedTextColor.AQUA));
  }

  /**
   * 打字中帶 -pev 的一般 WorldEdit 指令（//walls、//stack、//smooth…）：停手 600 ms 後在攔截模式下執行一次，
   * 和按 Enter 加 -pev 一樣不會寫入世界，結果當成打字預覽。
   */
  private void typedRun(PlayerState st, String t, long now) {
    if (now - st.typedAt < 600 || st.armed != null) return; // 還在打，或上一次試跑還沒結束
    st.typedDone = t;
    Player p = st.player;
    String[] parts = (t.startsWith("/") ? t.substring(1) : t).split(" ", -1);
    String label = baseLabel(parts[0]);
    if (!isWorldEdit(parts[0]) || NON_EDIT.contains(label) || label.startsWith("/sel") || label.startsWith("/br")
        || CONFIRM.contains(label)) {
      if (st.source == PlayerState.Source.LIVE) st.clearPreview();
      return;
    }
    List<String> kept = new ArrayList<>();
    for (int i = 0; i < parts.length; i++) if (i == 0 || !parts[i].equalsIgnoreCase("-pev")) kept.add(parts[i]);
    if (NEVER.contains(label)) {
      p.sendActionBar(Component.text("這個指令不能預覽", NamedTextColor.RED));
      return;
    }
    if (TYPED_UNSAFE.contains(label) || (SELECTION_FLAG.contains(label) && hasFlag(kept, 's'))) {
      p.sendActionBar(Component.text("這個指令會改剪貼簿或選區：按 Enter 才試跑", NamedTextColor.GOLD));
      return;
    }
    String clean = "/" + String.join(" ", kept).strip();
    Capture c = new Capture(p.getUniqueId(), clean, style(st, label, kept, p), true, false, maxCapture);
    c.live = true;
    st.armed = c;
    // performCommand 不經過 PlayerCommandPreprocessEvent；寫入都會被 st.armed 收走
    p.performCommand(clean.substring(1));
  }

  private void brush(PlayerState st) {
    if (st.armed != null || (st.source != PlayerState.Source.NONE && st.source != PlayerState.Source.BRUSH)) return;
    BrushPreview.Out out = BrushPreview.compute(st.player, st.brushKey, maxCapture, brushMax);
    if (out == null) {
      st.brushKey = null;
      if (st.source == PlayerState.Source.BRUSH) st.clearPreview();
      return;
    }
    st.brushKey = out.key();
    if (out.capture() == null) return;
    Map<Long, BlockData> writes = convert(out.capture().snapshot());
    if (writes.isEmpty()) {
      if (st.source == PlayerState.Source.BRUSH) st.clearPreview();
      return;
    }
    show(st, writes, out.capture().style, PlayerState.Source.BRUSH);
  }

  // ---------------- /pev ----------------

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!(sender instanceof Player p)) {
      // 主控台：pev debug <玩家>（測試用）
      if (args.length == 2 && args[0].equalsIgnoreCase("debug") && Bukkit.getPlayerExact(args[1]) != null) {
        PlayerState st = state(Bukkit.getPlayerExact(args[1]));
        sender.sendMessage("PEV_DEBUG source=" + st.source + " fake=" + st.fake.size()
            + " glass=" + st.fake.count(org.bukkit.Material.LIME_STAINED_GLASS) + " armed=" + (st.armed != null)
            + " always=" + st.always + " last=" + st.lastCommand + " box=" + st.previewBox.segs().size()
            + " outline=" + st.outline.count() + " typed=" + st.typed + " liveError=" + Live.lastError);
        return true;
      }
      sender.sendMessage("只有玩家可以用");
      return true;
    }
    PlayerState st = state(p);
    String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
    switch (sub) {
      case "always" -> {
        st.always = toggle(args, st.always);
        if (!st.always) {
          st.typed = null;
          if (st.source == PlayerState.Source.LIVE || st.source == PlayerState.Source.BRUSH) st.clearPreview();
        }
        save(st);
        info(p, Component.text("恆預覽：" + (st.always ? "開（WorldEdit 指令只試跑，按 [套用] 才寫入；打字與筆刷即時預覽）" : "關"),
            st.always ? NamedTextColor.GREEN : NamedTextColor.GRAY));
      }
      case "box" -> {
        st.showBox = toggle(args, st.showBox);
        save(st);
        info(p, Component.text("預覽範圍框：" + (st.showBox ? "開" : "關"), NamedTextColor.GRAY));
      }
      case "brush" -> {
        st.brush = toggle(args, st.brush);
        save(st);
        info(p, Component.text("筆刷預覽（恆預覽中）：" + (st.brush ? "開" : "關"), NamedTextColor.GRAY));
      }
      case "style" -> {
        if (args.length < 2) {
          info(p, Component.text("用法：/pev style auto|blocks|glass（目前 " + st.style.name().toLowerCase(Locale.ROOT) + "）", NamedTextColor.GRAY));
          return true;
        }
        try {
          st.style = PlayerState.StyleMode.valueOf(args[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
          info(p, Component.text("要 auto、blocks 或 glass", NamedTextColor.RED));
          return true;
        }
        save(st);
        info(p, Component.text("試跑的顯示方式：" + args[1].toLowerCase(Locale.ROOT), NamedTextColor.GRAY));
      }
      case "apply" -> {
        String c = st.lastCommand;
        if (c == null || st.source != PlayerState.Source.COMMAND) {
          info(p, Component.text("沒有可以套用的試跑", NamedTextColor.RED));
          return true;
        }
        st.lastCommand = null;
        st.clearPreview();
        // performCommand 不經過 PlayerCommandPreprocessEvent，所以不會再被試跑
        p.performCommand(c.substring(1));
      }
      case "clear", "cancel" -> {
        st.armed = null;
        st.lastCommand = null;
        st.typed = null;
        st.clearPreview();
        info(p, Component.text("已清掉預覽", NamedTextColor.GRAY));
      }
      case "mask", "pattern" -> {
        if (args.length < 2) {
          info(p, Component.text("用法：/pev " + sub + " <" + (sub.equals("mask") ? "遮罩" : "pattern") + ">", NamedTextColor.GRAY));
          return true;
        }
        String text = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        String buffer = sub.equals("mask") ? "//replace " + text : "//set " + text;
        Live.Out out = Live.compute(p, buffer, fawe, maxCapture);
        if (out == null) {
          info(p, Component.text("無法解析：" + text, NamedTextColor.RED));
          return true;
        }
        if (out.writes().isEmpty()) {
          info(p, Component.text(out.note() != null ? out.note() : "選區裡沒有符合的方塊", NamedTextColor.GRAY));
          st.clearPreview();
          return true;
        }
        show(st, out.writes(), out.style(), PlayerState.Source.COMMAND);
        st.lastCommand = sub.equals("pattern") ? "//set " + text : null;
        Component m = Component.text((sub.equals("mask") ? "遮罩 " : "pattern ") + text + "：" + String.format("%,d", out.writes().size()) + " 格  ", NamedTextColor.GRAY);
        if (st.lastCommand != null) m = m.append(button("[套用 //set]", NamedTextColor.GREEN, "/pev apply", "執行 //set " + text)).append(Component.text(" "));
        info(p, m.append(button("[取消]", NamedTextColor.RED, "/pev clear", "清掉預覽")));
      }
      default -> help(p, st);
    }
    return true;
  }

  private static boolean toggle(String[] args, boolean cur) {
    if (args.length < 2) return !cur;
    return switch (args[1].toLowerCase(Locale.ROOT)) {
      case "on", "true", "開" -> true;
      case "off", "false", "關" -> false;
      default -> !cur;
    };
  }

  private void help(Player p, PlayerState st) {
    String[][] lines = {
        {"/pev always [on|off]", "恆預覽：WorldEdit 指令只試跑；打字中的 //set、//replace 與筆刷即時預覽（目前 " + (st.always ? "開" : "關") + "）"},
        {"<WorldEdit 指令> -pev", "只試跑這一次"},
        {"/pev apply", "照最後一次試跑真的執行"},
        {"/pev clear", "清掉預覽"},
        {"/pev mask <遮罩>", "選區中符合遮罩的方塊顯示綠色玻璃"},
        {"/pev pattern <pattern>", "預覽選區換成 pattern"},
        {"/pev style auto|blocks|glass", "試跑的顯示方式（auto：有遮罩或 replace 用綠色玻璃）"},
        {"/pev box [on|off]", "預覽外圍的黃框與格數標籤（目前 " + (st.showBox ? "開" : "關") + "）"},
        {"/pev brush [on|off]", "恆預覽中的筆刷預覽（目前 " + (st.brush ? "開" : "關") + "）"},
    };
    info(p, Component.text("WorldEdit 預覽", NamedTextColor.AQUA));
    for (String[] l : lines)
      p.sendMessage(Component.text(" " + l[0], NamedTextColor.WHITE).append(Component.text("  " + l[1], NamedTextColor.GRAY))
          .clickEvent(ClickEvent.suggestCommand(l[0].replaceAll(" *[\\[<].*", "") + " ")));
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
    if (args.length == 1) {
      List<String> out = new ArrayList<>();
      for (String s : List.of("always", "apply", "clear", "mask", "pattern", "style", "box", "brush", "help"))
        if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
      return out;
    }
    if (args.length == 2) {
      return switch (args[0].toLowerCase(Locale.ROOT)) {
        case "always", "box", "brush" -> List.of("on", "off");
        case "style" -> List.of("auto", "blocks", "glass");
        default -> List.of();
      };
    }
    return List.of();
  }

  private static Component button(String text, NamedTextColor color, String cmd, String hover) {
    return Component.text(text, color).clickEvent(ClickEvent.runCommand(cmd)).hoverEvent(HoverEvent.showText(Component.text(hover)));
  }

  private static void info(Player p, Component c) {
    p.sendMessage(Component.text("[預覽] ", NamedTextColor.AQUA).append(c));
  }
}
