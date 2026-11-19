package nl.netonic.browser.core;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The link preview ("share card") a page gets when its URL is pasted into Slack, X, LinkedIn, WhatsApp, …: built
 * from its Open Graph ({@code og:*}) and Twitter ({@code twitter:*}) meta tags, with the fallbacks those apps use
 * ({@code <title>}, {@code <meta name="description">}), plus notes on what is missing. Pure JDK, see CoreTests.
 */
public final class SharePreview {
  /** Longer titles / descriptions are usually cut off in the card. */
  public static final int TITLE_MAX = 60;
  public static final int DESCRIPTION_MAX = 160;
  /** The image size most apps want for a large card (1.91 : 1); smaller ones show as a thumbnail or not at all. */
  public static final int IMAGE_WIDTH = 1200;
  public static final int IMAGE_HEIGHT = 630;
  private static final int IMAGE_MIN_WIDTH = 600;
  private static final int IMAGE_MIN_HEIGHT = 315;

  private SharePreview() {}

  /**
   * Evaluates in the page to {@code key<TAB>value} lines: every {@code <meta property|name>} (first one per key,
   * keys lower-cased), plus {@code @title} (the {@code <title>}), {@code @canonical} and {@code @url} (the page's
   * address, the base for relative URLs). Tabs and line breaks in values become spaces.
   */
  public static final String SCRIPT = """
      (function () {
        var out = [], seen = {};
        function add(k, v) {
          if (!k || v == null || seen[k]) return;
          seen[k] = true;
          out.push(k + '\\t' + String(v).replace(/[\\t\\r\\n]+/g, ' ').trim());
        }
        add('@url', document.baseURI || location.href);
        add('@title', document.title);
        var canonical = document.querySelector("link[rel~='canonical']");
        if (canonical) add('@canonical', canonical.getAttribute('href'));
        Array.prototype.forEach.call(document.querySelectorAll('meta[property], meta[name]'), function (m) {
          var k = (m.getAttribute('property') || m.getAttribute('name') || '').trim().toLowerCase();
          var v = m.getAttribute('content');
          if (k && v != null && v.trim() !== '') add(k, v);
        });
        return out.join('\\n');
      })()""";

  /** What the share card shows; {@code image} is absolute (or null), {@code warnings} in order of importance. */
  public record Card(String title, String description, String image, String imageAlt, String site, String url,
      String cardType, List<String> warnings, Map<String, String> tags) {}

  /** {@link #SCRIPT}'s result to its tags. */
  public static Map<String, String> parse(String result) {
    Map<String, String> tags = new LinkedHashMap<>();
    if (result == null) return tags;
    for (String line : result.split("\n")) {
      int tab = line.indexOf('\t');
      if (tab <= 0) continue;
      String value = line.substring(tab + 1).strip();
      if (!value.isEmpty()) tags.putIfAbsent(line.substring(0, tab), value);
    }
    return tags;
  }

  public static Card card(Map<String, String> tags) {
    String pageUrl = tags.get("@url");
    String ogTitle = tags.get("og:title");
    String ogDescription = tags.get("og:description");
    String rawImage = first(tags, "og:image", "og:image:url", "og:image:secure_url", "twitter:image", "twitter:image:src");
    String title = first(tags, "og:title", "twitter:title", "@title");
    String description = first(tags, "og:description", "twitter:description", "description");
    String image = resolve(pageUrl, rawImage);
    String url = first(tags, "og:url", "@canonical");
    String shownUrl = url != null ? resolve(pageUrl, url) : pageUrl;
    String site = first(tags, "og:site_name");
    if (site == null) site = shownUrl == null ? "" : Urls.label(shownUrl);
    String cardType = first(tags, "twitter:card");

    List<String> warnings = new ArrayList<>();
    if (title == null) warnings.add("No title: add og:title (or a <title>)");
    else if (ogTitle == null) warnings.add("No og:title: apps fall back to " + (tags.containsKey("twitter:title") ? "twitter:title" : "<title>"));
    if (description == null) warnings.add("No description: add og:description");
    else if (ogDescription == null) warnings.add("No og:description: apps fall back to "
        + (tags.containsKey("twitter:description") ? "twitter:description" : "the meta description"));
    if (rawImage == null) warnings.add("No og:image: most apps show a text-only link");
    else if (!isAbsolute(rawImage)) warnings.add("og:image is a relative URL: crawlers need an absolute one");
    if (image != null && Urls.isLocal(image) && !Urls.isLocal(pageUrl == null ? "" : pageUrl)) {
      warnings.add("og:image points at a local server that other sites can't reach");
    }
    if (title != null && title.length() > TITLE_MAX) {
      warnings.add("Title is " + title.length() + " characters: most apps cut it off after about " + TITLE_MAX);
    }
    if (description != null && description.length() > DESCRIPTION_MAX) {
      warnings.add("Description is " + description.length() + " characters: most apps cut it off after about "
          + DESCRIPTION_MAX);
    }
    if (url == null) warnings.add("No og:url or canonical link: shares of this page under other URLs aren't combined");
    if (cardType == null) warnings.add("No twitter:card: X shows a small card (summary_large_image for a big image)");
    return new Card(title, description, image, tags.get("og:image:alt"), site, shownUrl, cardType, warnings, tags);
  }

  /** A note when the downloaded image is too small for a large card, else null. */
  public static String imageWarning(int width, int height) {
    if (width <= 0 || height <= 0) return null;
    if (width >= IMAGE_MIN_WIDTH && height >= IMAGE_MIN_HEIGHT) return null;
    return "Image is " + width + " × " + height + " px: " + IMAGE_WIDTH + " × " + IMAGE_HEIGHT
        + " is recommended, smaller ones show as a thumbnail or not at all";
  }

  /** The source rectangle {x, y, w, h} that crops an image to the card's 1.91 : 1 frame, centred ("cover"). */
  public static int[] coverCrop(int width, int height) {
    if (width <= 0 || height <= 0) return new int[] {0, 0, Math.max(width, 0), Math.max(height, 0)};
    // width / height > 1200 / 630: too wide, cut the sides; else cut top and bottom
    if ((long) width * IMAGE_HEIGHT > (long) height * IMAGE_WIDTH) {
      int w = (int) Math.round((double) height * IMAGE_WIDTH / IMAGE_HEIGHT);
      return new int[] {(width - w) / 2, 0, w, height};
    }
    int h = (int) Math.round((double) width * IMAGE_HEIGHT / IMAGE_WIDTH);
    return new int[] {0, (height - h) / 2, width, h};
  }

  /** {@code ref} against the page's URL; null when there is none or it can't be resolved. */
  static String resolve(String base, String ref) {
    if (ref == null) return null;
    try {
      URI r = new URI(ref.replace(" ", "%20"));
      if (r.isAbsolute()) return r.toString();
      if (base == null) return null;
      return new URI(base.replace(" ", "%20")).resolve(r).toString();
    } catch (Exception e) {
      return null;
    }
  }

  private static boolean isAbsolute(String ref) {
    String s = ref.toLowerCase(Locale.ROOT);
    return s.startsWith("http://") || s.startsWith("https://");
  }

  private static String first(Map<String, String> tags, String... keys) {
    for (String k : keys) {
      String v = tags.get(k);
      if (v != null && !v.isBlank()) return v;
    }
    return null;
  }
}
