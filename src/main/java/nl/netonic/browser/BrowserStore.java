package nl.netonic.browser;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import nl.netonic.browser.core.DeviceWidths;
import nl.netonic.browser.core.ToolbarLayout;
import nl.netonic.browser.core.Urls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Per tab id: the current URL, page title, zoom and device width; plus the recently typed addresses. Saved in
 * {@code editorBrowser.xml}, so tabs PhpStorm reopens after a restart load their last page.
 * Entries are not removed when a tab closes (closing a project closes its tabs too); the oldest are dropped past
 * {@link #MAX_TABS}.
 */
@State(name = "EditorBrowser", storages = @Storage("editorBrowser.xml"))
public final class BrowserStore implements PersistentStateComponent<BrowserStore.Data> {
  static final int MAX_TABS = 200;
  static final int MAX_RECENT = 15;

  public static final class Data {
    public Map<String, String> urls = new LinkedHashMap<>();
    public Map<String, String> titles = new LinkedHashMap<>();
    public Map<String, String> zoom = new LinkedHashMap<>();
    /** Device width preset in CSS px; absent = full width. */
    public Map<String, String> widths = new LinkedHashMap<>();
    public List<String> recent = new ArrayList<>();
    public boolean autoOpenDevServer = true;
    public boolean reloadOnSave = true;
    /** {@link ToolbarLayout#toStored()}: item → place, only the items moved from their default place. */
    public Map<String, String> toolbar = new LinkedHashMap<>();
  }

  private Data data = new Data();
  /** {@link Data#toolbar} parsed; read by every toolbar update, so kept rather than parsed each time. */
  private ToolbarLayout toolbarLayout = ToolbarLayout.DEFAULT;

  public static BrowserStore getInstance() {
    return ApplicationManager.getApplication().getService(BrowserStore.class);
  }

  @Override
  public synchronized @NotNull Data getState() {
    return data;
  }

  @Override
  public synchronized void loadState(@NotNull Data state) {
    data = state;
    toolbarLayout = ToolbarLayout.parse(state.toolbar);
  }

  public synchronized boolean has(String id) {
    return data.urls.containsKey(id);
  }

  public synchronized @Nullable String url(String id) {
    return data.urls.get(id);
  }

  /** Saves the tab's URL and marks it as most recently used. */
  public synchronized void setUrl(String id, String url) {
    data.urls.remove(id);
    data.urls.put(id, url);
    prune();
  }

  public synchronized @Nullable String title(String id) {
    return data.titles.get(id);
  }

  public synchronized void setTitle(String id, @Nullable String title) {
    if (title == null || title.isBlank()) data.titles.remove(id);
    else data.titles.put(id, title.strip());
  }

  /** Zoom in percent, 100 when never set. */
  public synchronized int zoom(String id) {
    try {
      return Integer.parseInt(data.zoom.getOrDefault(id, "100"));
    } catch (NumberFormatException e) {
      return 100;
    }
  }

  public synchronized void setZoom(String id, int percent) {
    if (percent == 100) data.zoom.remove(id);
    else data.zoom.put(id, Integer.toString(percent));
  }

  /** Device width in CSS px, {@link DeviceWidths#FULL} (0) when the page fills the tab. */
  public synchronized int width(String id) {
    return DeviceWidths.parse(data.widths.get(id));
  }

  public synchronized void setWidth(String id, int cssWidth) {
    if (cssWidth <= 0) data.widths.remove(id);
    else data.widths.put(id, Integer.toString(cssWidth));
  }

  public synchronized List<String> recent() {
    return new ArrayList<>(data.recent);
  }

  public synchronized void addRecent(String url) {
    data.recent.remove(url);
    data.recent.add(0, url);
    while (data.recent.size() > MAX_RECENT) data.recent.remove(data.recent.size() - 1);
  }

  public synchronized boolean isAutoOpenDevServer() {
    return data.autoOpenDevServer;
  }

  public synchronized void setAutoOpenDevServer(boolean value) {
    data.autoOpenDevServer = value;
  }

  /** Whether saving a project file reloads the tabs showing a local page ({@link ReloadOnSave}). */
  public synchronized boolean isReloadOnSave() {
    return data.reloadOnSave;
  }

  public synchronized void setReloadOnSave(boolean value) {
    data.reloadOnSave = value;
  }

  /** Where the optional toolbar items go (toolbar, ⋮ menu or hidden). */
  public synchronized ToolbarLayout toolbarLayout() {
    return toolbarLayout;
  }

  public synchronized void setToolbarLayout(ToolbarLayout layout) {
    toolbarLayout = layout;
    data.toolbar = new LinkedHashMap<>(layout.toStored());
  }

  /**
   * The id of the most recently active tab already pointed at the same host and port as {@code url} (e.g. a
   * dev server that was stopped and started again), or null when there isn't one.
   */
  public synchronized @Nullable String findTabByHost(String url) {
    String hostPort = Urls.hostPort(url);
    if (hostPort == null) return null;
    String found = null;
    for (Map.Entry<String, String> entry : data.urls.entrySet()) {
      if (hostPort.equals(Urls.hostPort(entry.getValue()))) found = entry.getKey();
    }
    return found;
  }

  /**
   * The id of the most recently active tab showing the same local file as the {@code file:} URL {@code url}
   * (query and fragment ignored), or null when there isn't one.
   */
  public synchronized @Nullable String findTabByFile(String url) {
    java.nio.file.Path path = Urls.localPath(url);
    if (path == null) return null;
    String found = null;
    for (Map.Entry<String, String> entry : data.urls.entrySet()) {
      if (path.equals(Urls.localPath(entry.getValue()))) found = entry.getKey();
    }
    return found;
  }

  private void prune() {
    Iterator<String> it = data.urls.keySet().iterator();
    while (data.urls.size() > MAX_TABS && it.hasNext()) {
      String id = it.next();
      it.remove();
      data.titles.remove(id);
      data.zoom.remove(id);
      data.widths.remove(id);
    }
  }
}
