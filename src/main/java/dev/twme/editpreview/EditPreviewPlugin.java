package dev.twme.editpreview;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.block.BlockState;
import java.io.File;
import java.io.IOException;
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
import org.bukkit.configuration.file.YamlConfiguration;
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
 * Block previews for WorldEdit / FAWE:
 * <ul>
 *   <li>Add -pev (or --pev) to a command: the command only dry-runs, the result is shown with fake blocks, and nothing is
 *       written until [Apply]. Masks and replace show lime glass. Without the flag, commands always run for real.
 *   <li>A newly made selection (any shape) briefly flashes as lime glass (/pev select).
 *   <li>Fake blocks are carved around the player's line of sight and body, so the preview never blocks clicking (/pev carve).
 *   <li>Typing -pev in chat (before pressing Enter) already previews; in always-preview (/pev always), //set and //replace
 *       preview while typing (Enter still executes) and holding a brush previews the stroke at the aimed block.
 * </ul>
 */
public final class EditPreviewPlugin extends JavaPlugin implements Listener, TabExecutor {
  private static final Set<String> NEVER = Set.of("/undo", "undo", "/redo", "redo", "/clearhistory", "clearhistory",
      "/cancel", "fcancel", "worldedit", "we", "fawe", "/fawe",
      // entity removal does not go through block writes, so it cannot be intercepted
      "/butcher", "butcher", "/remove", "remove", "/rem", "/rement");
  /** Commands that don't write to the world: never arm (otherwise a real brush stroke during the arm window would be swallowed). */
  private static final Set<String> NON_EDIT = Set.of("/br", "br", "brush", "/brush", "tool", "/tool", "/sel", "sel", "/desel", "desel",
      "/pos1", "/pos2", "/hpos1", "/hpos2", "/chunk", "/wand", "/gmask", "gmask", "mask", "/mask", "material", "mat", "size", "range",
      "tracemask", "none", "/none", "/expand", "/contract", "/shift", "/outset", "/inset", "/size", "/count", "/distr", "/copy",
      "/schematic", "/schem", "schematic", "schem", "/clipboard", "/rotate", "/flip", "/clearclipboard", "/fast", "/perf", "/limit",
      "/timeout", "/toggleplace", "/searchitem", "/help", "/listchunks", "/calc", "/eval", "/drawsel", "/watchdog", "/reorder",
      "/sp", "superpickaxe", "/", ",", "/;", "/inspect", "/history", "/lazycopy",
      // player movement
      "/ascend", "asc", "/descend", "desc", "/ceil", "ceil", "/thru", "thru", "/jumpto", "j", "/up", "up", "/unstuck", "!");
  /** Not dry-run while typing: changes the clipboard. */
  private static final Set<String> TYPED_UNSAFE = Set.of("/cut", "/lazycut");
  /** Not dry-run while typing when -s is given: moves the selection. */
  private static final Set<String> SELECTION_FLAG = Set.of("/move", "/stack", "/paste");
  private static final Set<String> CONFIRM = Set.of("/confirm", "confirm");
  private static final Set<String> REPLACE_LIKE = Set.of("/replace", "/rep", "/re", "/replacenear", "replacenear", "/rn");

  private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
  private WeHook hook;
  private boolean fawe;
  private int maxCapture, maxDisplay, liveMax, flashTicks, flashMax, dryRunTicks;
  private double brushMax;
  private long tick;
  private NamespacedKey kAlways, kBrush, kStyle, kSelect, kCarve, kLiveMask, kLivePattern;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    maxCapture = getConfig().getInt("max-capture", 1_000_000);
    maxDisplay = getConfig().getInt("max-display", 150_000);
    liveMax = getConfig().getInt("live-max-volume", 262_144);
    brushMax = getConfig().getDouble("brush-max-size", 32);
    flashTicks = getConfig().getInt("selection-flash.ticks", 60);
    dryRunTicks = (int) Math.round(getConfig().getDouble("dry-run-timeout", 15) * 20);
    flashMax = getConfig().getInt("selection-flash.max-volume", 262_144);
    kAlways = new NamespacedKey(this, "always");
    kBrush = new NamespacedKey(this, "brush");
    kStyle = new NamespacedKey(this, "style");
    kSelect = new NamespacedKey(this, "select");
    kCarve = new NamespacedKey(this, "carve");
    kLiveMask = new NamespacedKey(this, "live_mask");
    kLivePattern = new NamespacedKey(this, "live_pattern");
    fawe = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") != null;
    if (fawe) {
      try {
        dev.twme.editpreview.fawe.FaweSupport.allowExtent(CaptureExtent.class, getLogger());
      } catch (LinkageError e) {
        getLogger().warning("Incompatible FAWE version: " + e);
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
    getLogger().info("Preview enabled (" + (fawe ? "FastAsyncWorldEdit" : "WorldEdit") + ")");
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

  // ---------------- players ----------------

  /**
   * Player settings live in plugins/EditPreview/players/&lt;uuid&gt;.yml and are written immediately on every change, so they
   * survive crashes and world resets. Values from older versions (player PDC) are migrated on first join.
   */
  private File settingsFile(java.util.UUID id) {
    return new File(new File(getDataFolder(), "players"), id + ".yml");
  }

  private PlayerState join(Player p) {
    PlayerState st = new PlayerState(p);
    File f = settingsFile(p.getUniqueId());
    YamlConfiguration y = f.isFile() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
    var pdc = p.getPersistentDataContainer();
    st.always = setting(y, pdc, "always", kAlways, "defaults.always", false);
    st.brush = setting(y, pdc, "brush", kBrush, "defaults.brush", true);
    st.selectFlash = setting(y, pdc, "selection-flash", kSelect, "defaults.selection-flash", true);
    st.carve = setting(y, pdc, "carve", kCarve, "defaults.carve", true);
    st.liveMask = setting(y, pdc, "live-mask", kLiveMask, "defaults.live-mask", true);
    st.livePattern = setting(y, pdc, "live-pattern", kLivePattern, "defaults.live-pattern", true);
    st.fake.carving(st.carve);
    String style = y.getString("style", pdc.getOrDefault(kStyle, PersistentDataType.STRING, "AUTO"));
    try {
      st.style = PlayerState.StyleMode.valueOf(style.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      st.style = PlayerState.StyleMode.AUTO;
    }
    states.put(p.getUniqueId(), st);
    if (!f.isFile() && hasOldSettings(pdc)) save(st); // migrate from the old per-player PDC storage
    return st;
  }

  /** File value, else the old PDC value, else the config default. */
  private boolean setting(YamlConfiguration y, org.bukkit.persistence.PersistentDataContainer pdc, String path, NamespacedKey old,
      String def, boolean fallback) {
    if (y.isBoolean(path)) return y.getBoolean(path);
    Byte b = pdc.get(old, PersistentDataType.BYTE);
    return b != null ? b == 1 : getConfig().getBoolean(def, fallback);
  }

  private boolean hasOldSettings(org.bukkit.persistence.PersistentDataContainer pdc) {
    for (NamespacedKey k : List.of(kAlways, kBrush, kStyle, kSelect, kCarve, kLiveMask, kLivePattern)) if (pdc.has(k)) return true;
    return false;
  }

  private void save(PlayerState st) {
    YamlConfiguration y = new YamlConfiguration();
    y.set("name", st.player.getName());
    y.set("always", st.always);
    y.set("brush", st.brush);
    y.set("selection-flash", st.selectFlash);
    y.set("carve", st.carve);
    y.set("live-mask", st.liveMask);
    y.set("live-pattern", st.livePattern);
    y.set("style", st.style.name().toLowerCase(Locale.ROOT));
    File f = settingsFile(st.player.getUniqueId());
    try {
      f.getParentFile().mkdirs();
      File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
      y.save(tmp);
      java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException | RuntimeException e) {
      getLogger().warning("Could not save settings of " + st.player.getName() + ": " + e);
    }
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
    st.source = PlayerState.Source.NONE;
  }

  // ---------------- command interception ----------------

  /** Command label without namespace (worldedit:/set → /set), lower-cased. */
  static String baseLabel(String raw) {
    String l = raw.toLowerCase(Locale.ROOT);
    int c = l.indexOf(':');
    return c >= 0 ? l.substring(c + 1) : l;
  }

  /** The preview flag: -pev or --pev. */
  static boolean isPev(String arg) {
    return arg.equalsIgnoreCase("-pev") || arg.equalsIgnoreCase("--pev");
  }

  /** The command (chat buffer) contains a standalone -pev / --pev. */
  static boolean hasPev(String buffer) {
    String[] parts = buffer.split(" ");
    for (int i = 1; i < parts.length; i++) if (isPev(parts[i])) return true;
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
      // A typing dry-run is still running (FAWE is async): a command sent now would be captured by it.
      // Queue it and send it once the dry-run has finished.
      e.setCancelled(true);
      st.queuedChat = msg;
      return;
    }

    boolean flag = false;
    List<String> kept = new ArrayList<>();
    for (int i = 0; i < parts.length; i++) {
      if (i > 0 && isPev(parts[i])) flag = true;
      else kept.add(parts[i]);
    }
    String clean = "/" + String.join(" ", kept).stripTrailing();
    if (flag) e.setMessage(clean);
    st.typed = null; // sent: typing preview is over
    st.typedDone = null; // the command may change the gmask or selection: recompute even for the same text

    if (CONFIRM.contains(label)) {
      // FAWE asks for //confirm on large edits: dry-run the pending command
      if (st.pendingConfirm != null && System.currentTimeMillis() < st.pendingUntil) {
        Capture c = st.pendingConfirm;
        st.pendingConfirm = null;
        st.armed = new Capture(p.getUniqueId(), c.command, c.style, c.applicable, c.explicit, maxCapture);
      }
      return;
    }
    if (!flag) return; // without -pev, commands run for real (even in always-preview; its live preview is cleared once sent)
    if (NON_EDIT.contains(label) || label.startsWith("/sel") || label.startsWith("/br")) return;
    if (NEVER.contains(label)) {
      if (flag) {
        e.setCancelled(true);
        info(p, Component.text("This command cannot be previewed", NamedTextColor.RED));
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
   * The client asks for completions on every keystroke: record the chat buffer and compute on the main thread once typing pauses.
   * Paper handles completions on several threads in parallel; at MONITOR we would wait for WorldEdit's completions, and a slow
   * older buffer could overwrite a newer one, so record it first (LOWEST).
   */
  @EventHandler(priority = EventPriority.LOWEST)
  public void onTab(AsyncTabCompleteEvent e) {
    if (!(e.getSender() instanceof Player p) || !e.isCommand()) return;
    PlayerState st = states.get(p.getUniqueId());
    if (st == null) return;
    // Start with always-preview or once -pev is typed (keep recording after -pev is deleted, so the preview can be cleared)
    if (!st.always && st.typed == null && !hasPev(e.getBuffer())) return;
    st.typed = e.getBuffer();
    st.typedAt = System.currentTimeMillis();
  }

  // ---------------- every tick ----------------

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
        if (tick % 4 == 0) selection(st);
        if (st.source == PlayerState.Source.SELECTION && tick >= st.flashUntil) st.clearPreview();
        if (st.source == PlayerState.Source.COMMAND && st.commandUntil > 0 && tick >= st.commandUntil) {
          st.clearPreview();
          st.lastCommand = null;
          st.player.sendActionBar(Component.text("Preview expired (nothing was written)", NamedTextColor.GRAY));
        }
        if (tick % 2 == 0) st.fake.carve();
        st.fake.flush();
      } catch (Throwable t) {
        getLogger().warning("Preview update failed (" + st.player.getName() + "): " + t);
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
      // No EditSession: not an edit command, or FAWE is waiting for //confirm.
      // Vanilla WorldEdit runs synchronously, so no EditSession in that tick means none at all. FAWE is async (a second or
      // two right after startup), so allow 4 s: too short and the dry-run would really write to the world; too long only
      // means a real edit in the meantime (e.g. a brush stroke) also ends up in the preview, so err on the long side.
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
      // Typing dry-run: shown as a typing preview; skip it if the player already sent the command or closed chat
      if (writes.isEmpty() || st.typed == null || (st.source != PlayerState.Source.LIVE && st.source != PlayerState.Source.NONE
          && st.source != PlayerState.Source.COMMAND)) {
        if (st.source == PlayerState.Source.LIVE) st.clearPreview();
        if (st.typed != null) p.sendActionBar(Component.text(c.command + ": no blocks would change", NamedTextColor.GRAY));
        return;
      }
      show(st, writes, c.style, PlayerState.Source.LIVE);
      st.lastCommand = null;
      p.sendActionBar(Component.text("Preview " + c.command + ": " + String.format("%,d", writes.size()) + " blocks (Enter to dry-run)",
          NamedTextColor.AQUA));
      return;
    }
    if (writes.isEmpty()) {
      if (c.explicit) info(p, Component.text("No blocks would change (nothing written)", NamedTextColor.GRAY));
      return;
    }
    show(st, writes, c.style, PlayerState.Source.COMMAND);
    st.lastCommand = c.applicable ? c.command : null;
    Component m = Component.text("Dry-run ", NamedTextColor.GRAY)
        .append(Component.text(c.command, NamedTextColor.WHITE))
        .append(Component.text(": " + String.format("%,d", writes.size()) + " blocks (not written yet)", NamedTextColor.GRAY));
    if (c.overflow) m = m.append(Component.text(" Limit exceeded, only the first " + String.format("%,d", maxCapture) + " blocks were captured", NamedTextColor.GOLD));
    m = m.append(Component.text("  "));
    if (c.applicable) m = m.append(button("[Apply]", NamedTextColor.GREEN, "/pev apply", "Run the command for real, as previewed")).append(Component.text(" "));
    m = m.append(button("[Cancel]", NamedTextColor.RED, "/pev clear", "Clear the preview"));
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
    st.source = src;
    st.commandUntil = src == PlayerState.Source.COMMAND && dryRunTicks > 0 ? tick + dryRunTicks : 0;
    if (r.truncated()) p.sendActionBar(Component.text("Preview too large: showing only the " + String.format("%,d", maxDisplay) + " blocks nearest to you", NamedTextColor.GOLD));
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
      // Not in always-preview and -pev was deleted: clear the typing preview
      st.typed = null;
      st.typedDone = null;
      if (st.source == PlayerState.Source.LIVE) st.clearPreview();
      return;
    }
    if (t.equals(st.typedDone) || now - st.typedAt < 250) return;
    if (st.source == PlayerState.Source.COMMAND && !pev) { // don't replace an unapplied dry-run (unless -pev was typed)
      st.typedDone = t;
      return;
    }
    // with -pev typed, every stage is previewed; otherwise follow /pev live mask|pattern
    Live.Out out = Live.compute(st.player, t, fawe, liveMax, pev || st.liveMask, pev || st.livePattern);
    if (out == Live.NOT_LIVE && pev) {
      typedRun(st, t, now); // other commands: dry-run once typing pauses
      return;
    }
    st.typedDone = t;
    if (out == null) return; // half-typed and unparsable: keep the previous preview
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
    st.player.sendActionBar(Component.text("Preview" + (out.note() == null ? "" : " (" + out.note() + ")") + ": "
        + String.format("%,d", out.writes().size()) + " blocks", NamedTextColor.AQUA));
  }

  /**
   * Any other WorldEdit command typed with -pev (//walls, //stack, //smooth…): once typing pauses for 600 ms, run it once in
   * capture mode. Like pressing Enter with -pev, nothing is written; the result is shown as a typing preview.
   */
  private void typedRun(PlayerState st, String t, long now) {
    if (now - st.typedAt < 600 || st.armed != null) return; // still typing, or the previous dry-run hasn't finished
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
    for (int i = 0; i < parts.length; i++) if (i == 0 || !isPev(parts[i])) kept.add(parts[i]);
    if (NEVER.contains(label)) {
      p.sendActionBar(Component.text("This command cannot be previewed", NamedTextColor.RED));
      return;
    }
    if (TYPED_UNSAFE.contains(label) || (SELECTION_FLAG.contains(label) && hasFlag(kept, 's'))) {
      p.sendActionBar(Component.text("This command changes the clipboard or selection: press Enter to dry-run", NamedTextColor.GOLD));
      return;
    }
    String clean = "/" + String.join(" ", kept).strip();
    Capture c = new Capture(p.getUniqueId(), clean, style(st, label, kept, p), true, false, maxCapture);
    c.live = true;
    st.armed = c;
    // performCommand skips PlayerCommandPreprocessEvent; every write is captured by st.armed
    p.performCommand(clean.substring(1));
  }

  /** Selection flash: when the selection changes to a complete region, briefly show it as lime glass (like CreativeKit's magic wand). */
  private void selection(PlayerState st) {
    Player p = st.player;
    LocalSession s = WorldEdit.getInstance().getSessionManager().getIfPresent(BukkitAdapter.adapt(p));
    String key = null;
    Region r = null;
    if (s != null) {
      var w = s.getSelectionWorld();
      // only ask for the selector of the selection's own world: getRegionSelector(other world) clears the selection
      if (w != null && w.getName().equals(p.getWorld().getName())) {
        try {
          var sel = s.getRegionSelector(w);
          if (sel.isDefined()) {
            r = sel.getRegion();
            key = r.getClass().getName() + r.getMinimumPoint() + r.getMaximumPoint() + volume(r);
          }
        } catch (IncompleteRegionException ignored) {
          // not complete yet
        }
      }
    }
    if (!st.selSeen) { // no flash for the selection a player already had on join
      st.selSeen = true;
      st.selKey = key;
      return;
    }
    if (java.util.Objects.equals(key, st.selKey)) return;
    st.selKey = key;
    if (r == null || !st.selectFlash) return;
    if (st.source != PlayerState.Source.NONE && st.source != PlayerState.Source.SELECTION) return; // don't hide a real preview
    if (volume(r) > flashMax) return;
    BlockData lime = Bukkit.createBlockData(org.bukkit.Material.LIME_STAINED_GLASS);
    Map<Long, BlockData> m = new HashMap<>();
    for (BlockVector3 v : r) m.put(Keys.key(v.x(), v.y(), v.z()), lime);
    if (m.isEmpty()) return;
    show(st, m, Capture.Style.GLASS, PlayerState.Source.SELECTION);
    st.flashUntil = tick + flashTicks;
  }

  private static long volume(Region r) {
    try {
      return r.getVolume();
    } catch (Throwable t) {
      return Long.MAX_VALUE;
    }
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
      // console: pev debug <player> (for tests)
      if (args.length == 2 && args[0].equalsIgnoreCase("debug") && Bukkit.getPlayerExact(args[1]) != null) {
        PlayerState st = state(Bukkit.getPlayerExact(args[1]));
        sender.sendMessage("PEV_DEBUG source=" + st.source + " fake=" + st.fake.size()
            + " glass=" + st.fake.count(org.bukkit.Material.LIME_STAINED_GLASS) + " armed=" + (st.armed != null)
            + " always=" + st.always + " brush=" + st.brush + " select=" + st.selectFlash + " carve=" + st.carve
            + " livemask=" + st.liveMask + " livepattern=" + st.livePattern + " style=" + st.style + " last=" + st.lastCommand
            + " entities=" + displayCount(st.player) + " cut=" + st.fake.carved() + " typed=" + st.typed + " liveError=" + Live.lastError);
        return true;
      }
      sender.sendMessage("Players only");
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
        info(p, Component.text("Always-preview: " + (st.always
                ? "on (typing //set, //replace and aiming brushes preview live; Enter still executes)" : "off"),
            st.always ? NamedTextColor.GREEN : NamedTextColor.GRAY));
      }
      case "live" -> {
        String stage = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        String[] rest = args.length > 1 ? java.util.Arrays.copyOfRange(args, 1, args.length) : new String[0];
        switch (stage) {
          case "mask" -> st.liveMask = toggle(rest, st.liveMask);
          case "pattern" -> st.livePattern = toggle(rest, st.livePattern);
          case "" -> { }
          default -> {
            info(p, Component.text("Usage: /pev live <mask|pattern> [on|off]", NamedTextColor.RED));
            return true;
          }
        }
        save(st);
        st.typedDone = null; // recompute the typing preview with the new setting
        info(p, Component.text("Typing preview in always-preview: mask (lime glass) " + onOff(st.liveMask) + ", pattern (real blocks) "
            + onOff(st.livePattern) + (st.always ? "" : " — takes effect with /pev always on"), NamedTextColor.GRAY));
      }
      case "select" -> {
        st.selectFlash = toggle(args, st.selectFlash);
        save(st);
        if (!st.selectFlash && st.source == PlayerState.Source.SELECTION) st.clearPreview();
        info(p, Component.text("Selection flash: " + onOff(st.selectFlash), NamedTextColor.GRAY));
      }
      case "carve" -> {
        st.carve = toggle(args, st.carve);
        st.fake.carving(st.carve);
        save(st);
        info(p, Component.text("Carve the preview around your view and body: " + onOff(st.carve), NamedTextColor.GRAY));
      }
      case "brush" -> {
        st.brush = toggle(args, st.brush);
        save(st);
        info(p, Component.text("Brush preview (in always-preview): " + onOff(st.brush), NamedTextColor.GRAY));
      }
      case "style" -> {
        if (args.length < 2) {
          info(p, Component.text("Usage: /pev style auto|blocks|glass (current: " + st.style.name().toLowerCase(Locale.ROOT) + ")", NamedTextColor.GRAY));
          return true;
        }
        try {
          st.style = PlayerState.StyleMode.valueOf(args[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
          info(p, Component.text("Expected auto, blocks or glass", NamedTextColor.RED));
          return true;
        }
        save(st);
        info(p, Component.text("Dry-run display style: " + args[1].toLowerCase(Locale.ROOT), NamedTextColor.GRAY));
      }
      case "apply" -> {
        String c = st.lastCommand;
        if (c == null || st.source != PlayerState.Source.COMMAND) {
          info(p, Component.text("Nothing to apply", NamedTextColor.RED));
          return true;
        }
        st.lastCommand = null;
        st.clearPreview();
        // performCommand skips PlayerCommandPreprocessEvent, so it won't be dry-run again
        p.performCommand(c.substring(1));
      }
      case "clear", "cancel" -> {
        st.armed = null;
        st.lastCommand = null;
        st.typed = null;
        st.clearPreview();
        info(p, Component.text("Preview cleared", NamedTextColor.GRAY));
      }
      case "mask", "pattern" -> {
        if (args.length < 2) {
          info(p, Component.text("Usage: /pev " + sub + " <" + sub + ">", NamedTextColor.GRAY));
          return true;
        }
        String text = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        String buffer = sub.equals("mask") ? "//replace " + text : "//set " + text;
        Live.Out out = Live.compute(p, buffer, fawe, maxCapture, true, true);
        if (out == null) {
          info(p, Component.text("Could not parse: " + text, NamedTextColor.RED));
          return true;
        }
        if (out.writes().isEmpty()) {
          info(p, Component.text(out.note() != null ? out.note() : "No matching blocks in the selection", NamedTextColor.GRAY));
          st.clearPreview();
          return true;
        }
        show(st, out.writes(), out.style(), PlayerState.Source.COMMAND);
        st.lastCommand = sub.equals("pattern") ? "//set " + text : null;
        Component m = Component.text(sub + " " + text + ": " + String.format("%,d", out.writes().size()) + " blocks  ", NamedTextColor.GRAY);
        if (st.lastCommand != null) m = m.append(button("[Apply //set]", NamedTextColor.GREEN, "/pev apply", "Run //set " + text)).append(Component.text(" "));
        info(p, m.append(button("[Cancel]", NamedTextColor.RED, "/pev clear", "Clear the preview")));
      }
      default -> help(p, st);
    }
    return true;
  }

  /** Display entities near the player (for tests: the plugin must not spawn any). */
  private static long displayCount(Player p) {
    return p.getNearbyEntities(64, 64, 64).stream().filter(e -> e instanceof org.bukkit.entity.Display).count();
  }

  private static String onOff(boolean b) {
    return b ? "on" : "off";
  }

  private static boolean toggle(String[] args, boolean cur) {
    if (args.length < 2) return !cur;
    return switch (args[1].toLowerCase(Locale.ROOT)) {
      case "on", "true" -> true;
      case "off", "false" -> false;
      default -> !cur;
    };
  }

  private void help(Player p, PlayerState st) {
    String[][] lines = {
        {"/pev always [on|off]", "Always-preview: //set, //replace and brushes preview live while typing / aiming; Enter still executes (now "
            + onOff(st.always) + ")"},
        {"<WorldEdit command> -pev", "Dry-run just this command (--pev works too; previews while typing)"},
        {"/pev apply", "Run the last dry-run for real"},
        {"/pev clear", "Clear the preview"},
        {"/pev mask <mask>", "Show blocks in the selection matching the mask as lime glass"},
        {"/pev pattern <pattern>", "Preview the selection filled with the pattern"},
        {"/pev style auto|blocks|glass", "Dry-run display style (auto: lime glass for masks and replace)"},
        {"/pev brush [on|off]", "Brush preview in always-preview (now " + onOff(st.brush) + ")"},
        {"/pev live <mask|pattern> [on|off]", "Typing preview stages in always-preview: //replace mask as lime glass (now "
            + onOff(st.liveMask) + "), //set / //replace pattern as real blocks (now " + onOff(st.livePattern) + ")"},
        {"/pev select [on|off]", "Briefly flash new selections as lime glass (now " + onOff(st.selectFlash) + ")"},
        {"/pev carve [on|off]", "Carve the preview around your line of sight and body (now " + onOff(st.carve) + ")"},
    };
    info(p, Component.text("WorldEdit preview", NamedTextColor.AQUA));
    for (String[] l : lines)
      p.sendMessage(Component.text(" " + l[0], NamedTextColor.WHITE).append(Component.text("  " + l[1], NamedTextColor.GRAY))
          .clickEvent(ClickEvent.suggestCommand(l[0].replaceAll(" *[\\[<].*", "") + " ")));
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
    if (args.length == 1) {
      List<String> out = new ArrayList<>();
      for (String s : List.of("always", "apply", "clear", "mask", "pattern", "style", "brush", "live", "select", "carve", "help"))
        if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
      return out;
    }
    if (args.length == 2) {
      return switch (args[0].toLowerCase(Locale.ROOT)) {
        case "always", "brush", "select", "carve" -> List.of("on", "off");
        case "style" -> List.of("auto", "blocks", "glass");
        case "live" -> List.of("mask", "pattern");
        default -> List.of();
      };
    }
    if (args.length == 3 && args[0].equalsIgnoreCase("live")) return List.of("on", "off");
    return List.of();
  }

  private static Component button(String text, NamedTextColor color, String cmd, String hover) {
    return Component.text(text, color).clickEvent(ClickEvent.runCommand(cmd)).hoverEvent(HoverEvent.showText(Component.text(hover)));
  }

  private static void info(Player p, Component c) {
    p.sendMessage(Component.text("[Preview] ", NamedTextColor.AQUA).append(c));
  }
}
