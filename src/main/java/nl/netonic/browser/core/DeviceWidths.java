package nl.netonic.browser.core;

import java.util.List;

/** Device width presets: the page laid out at a fixed viewport width (CSS px), centred in the tab. */
public final class DeviceWidths {
  /** No preset: the page fills the tab. */
  public static final int FULL = 0;

  public record Preset(String name, int width) {
    public String text() {
      return name + " (" + width + " px)";
    }
  }

  public static final List<Preset> PRESETS = List.of(
      new Preset("Phone", 390),
      new Preset("Tablet", 768),
      new Preset("Laptop", 1280));

  private DeviceWidths() {}

  /**
   * Width in screen px of the page area for a viewport of {@code cssWidth} CSS px at {@code zoomPercent}: Chromium's
   * zoom makes one CSS px take zoom/100 screen px, so 390 px at 50% takes 195 px. Never wider than the tab
   * ({@code available}); {@link #FULL} (or any width ≤ 0) fills it.
   */
  public static int frameWidth(int cssWidth, int zoomPercent, int available) {
    if (cssWidth <= 0 || zoomPercent <= 0) return Math.max(0, available);
    return Math.max(0, Math.min(available, (int) Math.round(cssWidth * zoomPercent / 100.0)));
  }

  /** A stored width back to a number, {@link #FULL} for anything missing or not a positive number. */
  public static int parse(String stored) {
    if (stored == null) return FULL;
    try {
      int w = Integer.parseInt(stored.strip());
      return w > 0 ? w : FULL;
    } catch (NumberFormatException e) {
      return FULL;
    }
  }
}
