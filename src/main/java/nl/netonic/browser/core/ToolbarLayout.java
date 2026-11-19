package nl.netonic.browser.core;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where each optional item of the browser tab's toolbar goes: on the toolbar, in its ⋮ menu, or hidden (its
 * keyboard shortcut keeps working). Back / forward / reload and the address bar are always on the toolbar.
 * Saved as item key → place key, only the items that differ from their default, so a later change of a default
 * reaches everyone who never changed that item.
 */
public final class ToolbarLayout {
  public enum Place {
    TOOLBAR("toolbar", "Toolbar"),
    MENU("menu", "⋮ menu"),
    HIDDEN("hidden", "Hidden");

    public final String key;
    public final String text;

    Place(String key, String text) {
      this.key = key;
      this.text = text;
    }

    @Override
    public String toString() {
      return text;
    }
  }

  /** In toolbar / menu order. */
  public enum Item {
    NEW_TAB("newTab", "New tab", Place.TOOLBAR),
    DEVICE_WIDTH("deviceWidth", "Device width", Place.MENU),
    ZOOM("zoom", "Zoom in / out", Place.MENU),
    FIND("find", "Find in page", Place.MENU),
    SHARE_PREVIEW("sharePreview", "Share preview", Place.MENU),
    OPEN_EXTERNAL("openExternal", "Open in default browser", Place.MENU),
    DEVTOOLS("devTools", "DevTools", Place.TOOLBAR);

    public final String key;
    public final String text;
    public final Place defaultPlace;

    Item(String key, String text, Place defaultPlace) {
      this.key = key;
      this.text = text;
      this.defaultPlace = defaultPlace;
    }
  }

  public static final ToolbarLayout DEFAULT = new ToolbarLayout(new EnumMap<>(Item.class));

  /** Only the items not at their default place. */
  private final EnumMap<Item, Place> changed;

  private ToolbarLayout(EnumMap<Item, Place> changed) {
    this.changed = changed;
  }

  public Place place(Item item) {
    return changed.getOrDefault(item, item.defaultPlace);
  }

  public ToolbarLayout with(Item item, Place place) {
    EnumMap<Item, Place> copy = new EnumMap<>(Item.class);
    copy.putAll(changed);
    if (place == item.defaultPlace) copy.remove(item);
    else copy.put(item, place);
    return new ToolbarLayout(copy);
  }

  public boolean isDefault() {
    return changed.isEmpty();
  }

  /** The saved form back to a layout; unknown items and places (e.g. from a newer version) are ignored. */
  public static ToolbarLayout parse(Map<String, String> stored) {
    ToolbarLayout layout = DEFAULT;
    if (stored == null) return layout;
    for (Map.Entry<String, String> e : stored.entrySet()) {
      Item item = itemByKey(e.getKey());
      Place place = placeByKey(e.getValue());
      if (item != null && place != null) layout = layout.with(item, place);
    }
    return layout;
  }

  public Map<String, String> toStored() {
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<Item, Place> e : changed.entrySet()) out.put(e.getKey().key, e.getValue().key);
    return out;
  }

  private static Item itemByKey(String key) {
    for (Item i : Item.values()) if (i.key.equals(key)) return i;
    return null;
  }

  private static Place placeByKey(String key) {
    for (Place p : Place.values()) if (p.key.equals(key)) return p;
    return null;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ToolbarLayout other && changed.equals(other.changed);
  }

  @Override
  public int hashCode() {
    return changed.hashCode();
  }
}
