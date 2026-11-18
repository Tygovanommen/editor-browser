package nl.netonic.browser.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import nl.netonic.browser.BrowserTabs;
import org.jetbrains.annotations.NotNull;

/** Tools › New Browser Tab: opens a blank tab, address bar focused, no dialog in the way. */
public final class NewTabAction extends DumbAwareAction {
  public NewTabAction() {
    super("New Browser Tab", "Open a new, blank browser tab", AllIcons.General.Add);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project != null) BrowserTabs.openBlank(project);
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
