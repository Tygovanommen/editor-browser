package nl.netonic.browser.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.InputValidator;
import com.intellij.openapi.ui.Messages;
import java.util.List;
import nl.netonic.browser.BrowserStore;
import nl.netonic.browser.BrowserTabs;
import nl.netonic.browser.core.Urls;
import org.jetbrains.annotations.NotNull;

/** Tools › Open URL in Editor Tab…: asks for an address (recent ones in the dropdown) and opens it as a tab. */
public final class OpenUrlAction extends DumbAwareAction {
  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) return;
    List<String> recent = BrowserStore.getInstance().recent();
    String input = Messages.showEditableChooseDialog(
        "Address (localhost:3000, backend.test/admin, https://…):", "Open URL in Editor Tab",
        AllIcons.General.Web, recent.toArray(String[]::new), recent.isEmpty() ? "" : recent.get(0),
        new InputValidator() {
          @Override
          public boolean checkInput(String text) {
            return Urls.normalize(text) != null;
          }

          @Override
          public boolean canClose(String text) {
            return checkInput(text);
          }
        });
    if (input != null) BrowserTabs.openTyped(project, input);
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    e.getPresentation().setEnabled(e.getProject() != null);
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }
}
