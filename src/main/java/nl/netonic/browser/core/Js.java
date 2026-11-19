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

  /**
   * Evaluates to the page's canvas colour (CSS {@code Canvas}, e.g. {@code "rgb(255, 255, 255)"}), which follows
   * the color scheme the page uses: what Chromium shows where the page has no background of its own.
   */
  public static final String CANVAS_COLOR = """
      (function () {
        var root = document.body || document.documentElement;
        if (!root) return '';
        var d = document.createElement('div');
        d.style.cssText = 'position:absolute;width:0;height:0;background-color:Canvas';
        root.appendChild(d);
        var c = getComputedStyle(d).backgroundColor;
        d.remove();
        return c;
      })()""";

  /** {@code rgb(r, g, b)} / {@code rgba(r, g, b, a)} (as {@code getComputedStyle} writes it) as 0xRRGGBB, else -1. */
  public static int parseRgb(String css) {
    if (css == null) return -1;
    java.util.regex.Matcher m = java.util.regex.Pattern
        .compile("rgba?\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*(?:,\\s*([\\d.]+)\\s*)?\\)")
        .matcher(css.strip());
    if (!m.matches()) return -1;
    if (m.group(4) != null && Double.parseDouble(m.group(4)) == 0) return -1; // transparent: not a colour
    int r = Integer.parseInt(m.group(1)), g = Integer.parseInt(m.group(2)), b = Integer.parseInt(m.group(3));
    if (r > 255 || g > 255 || b > 255) return -1;
    return (r << 16) | (g << 8) | b;
  }

  /**
   * A script that turns Chromium's empty error page (a 401 whose sign-in was cancelled) into a short notice with a
   * Sign In button; reloading an error page loads the failed address again, which asks again. Does nothing on any
   * other page, so it can safely run again (the error page may show up a moment after the sign-in was cancelled).
   */
  public static String signInCancelledPage(String site, String realm) {
    return "(function (site, realm) {"
        + " if (location.protocol !== 'chrome-error:') return;"
        + " document.title = 'Sign-in needed';"
        + " var b = document.body || document.documentElement.appendChild(document.createElement('body'));"
        + " b.innerHTML = '';"
        + " b.style.cssText = 'font:14px -apple-system,BlinkMacSystemFont,system-ui,sans-serif;margin:48px;color:#222;background:#fff';"
        + " var h = document.createElement('h2'); h.textContent = 'Sign-in needed';"
        + " var p = document.createElement('p');"
        + " p.textContent = site + ' asks for a username and password' + (realm ? ' (\u201c' + realm + '\u201d)' : '') + '.';"
        + " var s = document.createElement('button'); s.textContent = 'Sign In\u2026';"
        + " s.onclick = function () { location.reload(); };"
        + " b.append(h, p, s); })(" + string(site) + ", " + string(realm) + ")";
  }
}
