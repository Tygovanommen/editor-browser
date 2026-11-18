package nl.netonic.browser.core;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/** What the user typed in the address bar → a URL, and a URL → a short tab label. Pure JDK, see CoreTests. */
public final class Urls {
  public static final String BLANK = "about:blank";

  private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");
  private static final Pattern NO_SLASH_SCHEME = Pattern.compile("^(about|data|file|javascript|mailto|view-source):.*",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

  private Urls() {}

  /**
   * {@code localhost:3000} → {@code http://localhost:3000}, {@code example.com} → {@code https://example.com}.
   * Local hosts (localhost, IPs, {@code .test}, {@code .local}, {@code .localhost}) get http, like Herd and Valet
   * serve them; anything else https. Returns null for blank input.
   */
  public static String normalize(String input) {
    if (input == null) return null;
    String s = input.strip();
    if (s.isEmpty()) return null;
    if (SCHEME.matcher(s).matches() || NO_SLASH_SCHEME.matcher(s).matches()) return s;
    if (s.startsWith("//")) s = s.substring(2);
    return (isLocalHost(host(s)) ? "http://" : "https://") + s;
  }

  /** True when the text could be an address: a scheme, localhost, or a host with a dot and no spaces. */
  public static boolean looksLikeUrl(String text) {
    if (text == null) return false;
    String s = text.strip();
    if (s.isEmpty() || s.length() > 2048 || s.chars().anyMatch(Character::isWhitespace)) return false;
    if (SCHEME.matcher(s).matches()) return s.toLowerCase(Locale.ROOT).matches("^(https?|file)://.+");
    String host = host(s);
    if (host.isEmpty()) return false;
    if (isLocalHost(host)) return true;
    int dot = host.lastIndexOf('.');
    // a dot that isn't the first or last character, and a letters-only TLD ("app.vue" is not an address)
    return dot > 0 && dot < host.length() - 1 && host.substring(dot + 1).matches("[a-zA-Z]{2,24}")
        && !isLikelyFileName(host);
  }

  /** Short label for a tab before the page has a title: {@code localhost:3000}, {@code example.com}. */
  public static String label(String url) {
    if (url == null || url.isBlank() || url.equalsIgnoreCase(BLANK)) return "New Tab";
    try {
      URI uri = URI.create(url);
      String host = uri.getHost();
      if (host != null) {
        if (host.startsWith("www.")) host = host.substring(4);
        return uri.getPort() > 0 ? host + ":" + uri.getPort() : host;
      }
    } catch (IllegalArgumentException ignored) {
      // not a parseable URI, fall through
    }
    String rest = url.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*:(//)?", "");
    int cut = indexOfAny(rest, "/?#");
    String label = cut >= 0 ? rest.substring(0, cut) : rest;
    return label.isEmpty() ? url : label;
  }

  /** {@code text} cut to {@code max} characters with an ellipsis. */
  public static String shorten(String text, int max) {
    if (text == null) return "";
    String s = text.strip();
    return s.length() <= max ? s : s.substring(0, Math.max(1, max - 1)).stripTrailing() + "…";
  }

  /** The host part of something typed without a scheme: {@code localhost:3000/x} → {@code localhost}. */
  static String host(String noScheme) {
    String s = noScheme;
    int at = s.indexOf('@');
    int cut = indexOfAny(s, "/?#");
    if (at >= 0 && (cut < 0 || at < cut)) s = s.substring(at + 1);
    cut = indexOfAny(s, "/?#");
    if (cut >= 0) s = s.substring(0, cut);
    int colon = s.lastIndexOf(':');
    if (colon >= 0 && !s.startsWith("[")) s = s.substring(0, colon);
    return s.toLowerCase(Locale.ROOT);
  }

  static boolean isLocalHost(String host) {
    return host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".test")
        || host.endsWith(".local") || host.startsWith("[") || IPV4.matcher(host).matches();
  }

  private static boolean isLikelyFileName(String host) {
    String ext = host.substring(host.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    return switch (ext) {
      case "php", "js", "ts", "vue", "json", "md", "html", "css", "scss", "xml", "yml", "yaml", "txt", "java",
           "kt", "lock", "env", "sh", "sql", "log", "png", "jpg", "svg", "zip" -> true;
      default -> false;
    };
  }

  private static int indexOfAny(String s, String chars) {
    for (int i = 0; i < s.length(); i++) if (chars.indexOf(s.charAt(i)) >= 0) return i;
    return -1;
  }
}
