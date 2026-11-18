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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Per tab id: the current URL, page title and zoom; plus the recently typed addresses. Saved in
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
    public List<String> recent = new ArrayList<>();
  }

  private Data data = new Data();

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

  public synchronized List<String> recent() {
    return new ArrayList<>(data.recent);
  }

  public synchronized void addRecent(String url) {
    data.recent.remove(url);
    data.recent.add(0, url);
    while (data.recent.size() > MAX_RECENT) data.recent.remove(data.recent.size() - 1);
  }

  private void prune() {
    Iterator<String> it = data.urls.keySet().iterator();
    while (data.urls.size() > MAX_TABS && it.hasNext()) {
      String id = it.next();
      it.remove();
      data.titles.remove(id);
      data.zoom.remove(id);
    }
  }
}
