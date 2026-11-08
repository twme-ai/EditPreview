package dev.twme.editpreview;

import java.util.ArrayList;
import java.util.List;

/** Parsing of the preview flag (-pev / --pev [seconds]) in commands and chat buffers; no server classes, unit-tested. */
final class PevFlag {
  private PevFlag() {}

  /** The preview flag: -pev or --pev. */
  static boolean isPev(String arg) {
    return arg.equalsIgnoreCase("-pev") || arg.equalsIgnoreCase("--pev");
  }

  /** A duration after the flag: -pev 30 (seconds; 0 = until cleared). */
  static boolean isSeconds(String arg) {
    return arg.matches("\\d{1,5}(\\.\\d+)?");
  }

  /** The command split into its words without the flag (and its duration); seconds = -1 when not given. */
  record Flag(boolean on, double seconds, List<String> kept) {}

  static Flag flag(String[] parts) {
    boolean on = false;
    double seconds = -1;
    List<String> kept = new ArrayList<>();
    for (int i = 0; i < parts.length; i++) {
      if (i > 0 && isPev(parts[i])) {
        on = true;
        if (i + 1 < parts.length && isSeconds(parts[i + 1])) seconds = Double.parseDouble(parts[++i]);
      } else kept.add(parts[i]);
    }
    return new Flag(on, seconds, kept);
  }

  /** The command (chat buffer) contains a standalone -pev / --pev. */
  static boolean hasPev(String buffer) {
    String[] parts = buffer.split(" ");
    for (int i = 1; i < parts.length; i++) if (isPev(parts[i])) return true;
    return false;
  }

  /**
   * The client only sends the text up to the cursor, and moving the cursor sends a request too, so the server cannot tell
   * "moved left" from "deleted". Once the line has a -pev, keep the rest of the line:
   * <ul>
   *   <li>the text sent is a prefix of the line: the cursor moved left (or characters before it were deleted); keep the line,
   *       a deleted argument is replaced by whatever is typed next;
   *   <li>otherwise something was typed at the cursor: the new text, plus the line from the first space after the point where
   *       they differ (the arguments after the one being edited, including -pev).
   * </ul>
   * Requests arriving out of order (an older, shorter buffer) are prefixes too, so they don't break the line.
   */
  static String withTail(String prev, String sent) {
    if (prev == null || !hasPev(prev)) return sent;
    String l1 = sent.split(" ", 2)[0], l2 = prev.split(" ", 2)[0];
    if (!sent.contains(" ") || !l1.equalsIgnoreCase(l2)) return sent; // still typing the command name, or another command
    if (prev.startsWith(sent)) return prev;
    int p = 0, n = Math.min(sent.length(), prev.length());
    while (p < n && sent.charAt(p) == prev.charAt(p)) p++;
    int i = prev.indexOf(' ', p);
    return i < 0 ? sent : sent + prev.substring(i);
  }
}
