package nl.netonic.browser.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The console errors of the page a tab shows: how many, and the last {@value #KEEP} messages for the toolbar
 * badge's tooltip. Thread-safe (Chromium reports them on its own thread). Pure JDK, see CoreTests.
 */
public final class ConsoleLog {
  public static final int KEEP = 5;
  private static final int MAX_MESSAGE = 200;

  private int count;
  private final Deque<String> recent = new ArrayDeque<>();

  public synchronized void add(String message, String source, int line) {
    count++;
    recent.addLast(format(message, source, line));
    while (recent.size() > KEEP) recent.removeFirst();
  }

  /** A new page loaded. */
  public synchronized void clear() {
    count = 0;
    recent.clear();
  }

  public synchronized int count() {
    return count;
  }

  /** The last messages, oldest first: {@code Uncaught ReferenceError: x is not defined (app.js:12)}. */
  public synchronized List<String> recent() {
    return new ArrayList<>(recent);
  }

  static String format(String message, String source, int line) {
    String text = Urls.shorten(message == null ? "" : message.replaceAll("\\s+", " "), MAX_MESSAGE);
    String file = fileName(source);
    if (file.isEmpty()) return text;
    return text + " (" + file + (line > 0 ? ":" + line : "") + ")";
  }

  /** {@code http://localhost:5173/src/app.js?v=3} → {@code app.js}; {@code ""} when there is no file name. */
  static String fileName(String source) {
    if (source == null) return "";
    String s = source.strip();
    int cut = s.length();
    for (char c : new char[] {'?', '#'}) {
      int i = s.indexOf(c);
      if (i >= 0 && i < cut) cut = i;
    }
    s = s.substring(0, cut);
    while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
    int slash = s.lastIndexOf('/');
    String name = slash >= 0 ? s.substring(slash + 1) : s;
    // a bare origin (http://localhost:3000) has no file name
    return name.contains(":") ? "" : name;
  }
}
