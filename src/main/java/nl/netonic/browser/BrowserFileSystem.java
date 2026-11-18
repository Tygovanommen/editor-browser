package nl.netonic.browser;

import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code editor-browser://<tab id>}: one virtual file per browser tab. Being a real file system is what lets
 * PhpStorm reopen the tabs after a restart (it saves open files by URL and looks them up here again).
 */
public final class BrowserFileSystem extends DeprecatedVirtualFileSystem {
  public static final @NonNls String PROTOCOL = "editor-browser";

  private final Map<String, BrowserFile> files = new ConcurrentHashMap<>();

  public static BrowserFileSystem getInstance() {
    return (BrowserFileSystem) VirtualFileManager.getInstance().getFileSystem(PROTOCOL);
  }

  /** The file for a tab id; the same instance every time, so PhpStorm sees one tab per id. */
  public @NotNull BrowserFile file(@NotNull String id) {
    return files.computeIfAbsent(id, i -> new BrowserFile(this, i));
  }

  @Override
  public @NotNull String getProtocol() {
    return PROTOCOL;
  }

  @Override
  public @Nullable VirtualFile findFileByPath(@NotNull String path) {
    String id = path.replaceAll("^/+|/+$", "");
    // only tabs we know a URL for: a forgotten id would open an empty tab
    return !id.isEmpty() && BrowserStore.getInstance().has(id) ? file(id) : null;
  }

  @Override
  public void refresh(boolean asynchronous) {
    // nothing on disk
  }

  @Override
  public @Nullable VirtualFile refreshAndFindFileByPath(@NotNull String path) {
    return findFileByPath(path);
  }
}
