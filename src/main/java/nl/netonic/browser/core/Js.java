package nl.netonic.browser.core;

/** Building small JavaScript snippets to run in a page. Pure JDK, see CoreTests. */
public final class Js {
  private Js() {}

  /** {@code text} as a JavaScript string literal, double-quoted and escaped. */
  public static String string(String text) {
    String s = text == null ? "" : text;
    StringBuilder b = new StringBuilder(s.length() + 2).append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        case '<' -> b.append("\\u003c");
        case '\u2028' -> b.append("\\u2028");
        case '\u2029' -> b.append("\\u2029");
        default -> {
          if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
          else b.append(c);
        }
      }
    }
    return b.append('"').toString();
  }

  /**
   * An expression giving {@code "<seq>:<n>"}, n = how often {@code needle} occurs (case-insensitively) in the
   * page's visible text, as Chromium's find in page counts it (near enough: text inside iframes isn't counted).
   */
  public static String countMatches(String needle, int seq) {
    return "(function (n) {"
        + " var t = ((document.body && document.body.innerText) || '').toLowerCase(); n = n.toLowerCase();"
        + " var c = 0, i = 0; if (n) while ((i = t.indexOf(n, i)) >= 0) { c++; i += n.length; }"
        + " return '" + seq + ":' + c; })(" + string(needle) + ")";
  }
}
