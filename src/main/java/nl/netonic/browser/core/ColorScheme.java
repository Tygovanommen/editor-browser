package nl.netonic.browser.core;

/**
 * The color scheme a tab's pages see as {@code prefers-color-scheme}: whatever Chromium reports by itself, or
 * forced to light or dark (DevTools protocol {@code Emulation.setEmulatedMedia}), to check a site's dark mode
 * without switching the OS.
 */
public enum ColorScheme {
  SYSTEM("", "System"),
  LIGHT("light", "Light"),
  DARK("dark", "Dark");

  /** Saved per tab in {@code editorBrowser.xml}; {@code ""} (not saved) for {@link #SYSTEM}. */
  public final String key;
  public final String text;

  ColorScheme(String key, String text) {
    this.key = key;
    this.text = text;
  }

  /** The saved key back to a scheme; null, empty or unknown is {@link #SYSTEM}. */
  public static ColorScheme parse(String key) {
    if (key != null) for (ColorScheme s : values()) if (s != SYSTEM && s.key.equals(key)) return s;
    return SYSTEM;
  }

  /** Parameters of {@code Emulation.setEmulatedMedia}; an empty value ends the override (the page's own again). */
  public String emulatedMediaParams() {
    return "{\"features\":[{\"name\":\"prefers-color-scheme\",\"value\":\"" + key + "\"}]}";
  }
}
