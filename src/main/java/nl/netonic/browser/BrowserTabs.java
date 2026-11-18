package nl.netonic.browser;

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

  /** Opens {@code url} in a new tab in the current editor split. */
  public static void open(@NotNull Project project, @NotNull String url, boolean focus) {
    if (project.isDisposed()) return;
    String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    BrowserStore.getInstance().setUrl(id, url);
    BrowserFile file = BrowserFileSystem.getInstance().file(id);
    new OpenFileDescriptor(project, file).navigate(focus);
  }
}
