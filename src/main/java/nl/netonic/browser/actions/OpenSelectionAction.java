package nl.netonic.browser.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import nl.netonic.browser.BrowserTabs;
import nl.netonic.browser.core.Urls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Editor right-click › Open in Editor Tab: the selected address, shown only when the selection looks like one. */
public final class OpenSelectionAction extends DumbAwareAction {
  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    String text = unquote(selection(e));
    if (project != null && Urls.looksLikeUrl(text)) BrowserTabs.openTyped(project, text);
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    e.getPresentation().setEnabledAndVisible(e.getProject() != null && Urls.looksLikeUrl(unquote(selection(e))));
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  private static @Nullable String selection(AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    return editor != null ? editor.getSelectionModel().getSelectedText() : null;
  }

  /** {@code 'https://x'} or {@code "https://x"} as selected from code → {@code https://x}. */
  private static @Nullable String unquote(@Nullable String text) {
    if (text == null) return null;
    String s = text.strip();
    if (s.length() >= 2 && "'\"`".indexOf(s.charAt(0)) >= 0 && s.charAt(s.length() - 1) == s.charAt(0)) {
      s = s.substring(1, s.length() - 1);
    }
    return s;
  }
}
