package nl.netonic.browser;

import com.intellij.ide.FileIconProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The tab icon once a tab's favicon has been fetched; falls back to {@link BrowserFileType}'s globe icon. */
public final class BrowserFaviconProvider implements FileIconProvider {
  @Override
  public @Nullable Icon getIcon(@NotNull VirtualFile file, int flags, @Nullable Project project) {
    return file instanceof BrowserFile page ? BrowserFavicons.get(page.id()) : null;
  }
}
