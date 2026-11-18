package nl.netonic.browser;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import java.util.UUID;
import nl.netonic.browser.core.Urls;
import org.jetbrains.annotations.NotNull;

/** Opening browser tabs. Call on the UI thread. */
public final class BrowserTabs {
  private BrowserTabs() {}

  /** Opens what the user typed ({@code localhost:3000}) in a new tab and remembers it as a recent address. */
  public static void openTyped(@NotNull Project project, String input) {
    String url = Urls.normalize(input);
    if (url == null) return;
    BrowserStore.getInstance().addRecent(url);
    open(project, url, true);
  }

  /** Opens a new, blank tab with its address bar focused, ready to type into. */
  public static void openBlank(@NotNull Project project) {
    open(project, Urls.BLANK, true);
  }

  /** Opens {@code url} in a new tab in the current editor split. */
  public static void open(@NotNull Project project, @NotNull String url, boolean focus) {
    if (project.isDisposed()) return;
    String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    BrowserStore.getInstance().setUrl(id, url);
    BrowserFile file = BrowserFileSystem.getInstance().file(id);
    new OpenFileDescriptor(project, file).navigate(focus);
  }

  /**
   * Opens {@code url} in a new tab, or, when a tab already points at the same host and port (a dev server that
   * was restarted), focuses and updates that tab instead. Used for the automatic dev-server detection, so a tab
   * doesn't pile up on every restart.
   */
  public static void openOrReuse(@NotNull Project project, @NotNull String url) {
    if (project.isDisposed()) return;
    String existingId = BrowserStore.getInstance().findTabByHost(url);
    if (existingId == null) {
      open(project, url, true);
      return;
    }
    BrowserFile file = BrowserFileSystem.getInstance().file(existingId);
    String previousUrl = file.url();
    FileEditor[] openEditors = FileEditorManager.getInstance(project).getEditors(file);
    BrowserStore.getInstance().setUrl(existingId, url);
    new OpenFileDescriptor(project, file).navigate(true);
    if (!url.equals(previousUrl)) {
      for (FileEditor editor : openEditors) if (editor instanceof BrowserEditor be) be.loadUrl(url);
    }
  }
}
