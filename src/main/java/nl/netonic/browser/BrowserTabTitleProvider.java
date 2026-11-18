package nl.netonic.browser;

import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.HtmlChunk;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Tab text = page title (or host), tooltip = the URL. */
public final class BrowserTabTitleProvider implements EditorTabTitleProvider {
  @Override
  public @Nullable String getEditorTabTitle(@NotNull Project project, @NotNull VirtualFile file) {
    return file instanceof BrowserFile page ? page.title() : null;
  }

  @Override
  public @Nullable HtmlChunk getEditorTabTooltipHtml(@NotNull Project project, @NotNull VirtualFile file) {
    return file instanceof BrowserFile page ? HtmlChunk.text(page.url()) : null;
  }
}
