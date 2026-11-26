package nl.netonic.browser.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import nl.netonic.browser.BrowserStore;
import nl.netonic.browser.BrowserTabs;
import nl.netonic.browser.PdfEditorProvider;
import nl.netonic.browser.core.Urls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Open In › Browser Tab (project view, editor and editor tab right-click, the HTML editor's floating browser bar):
 * the selected HTML file as a page, from disk ({@code file:} URL, so relative links, CSS and scripts work), or a
 * PDF in Chromium's PDF viewer.
 */
public final class OpenFileAction extends DumbAwareAction {
  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
    if (project == null || !applies(file)) return;
    if (PdfEditorProvider.applies(file) && BrowserStore.getInstance().isOpenPdfFiles()) {
      // the PDF's own tab, as opening it does
      new OpenFileDescriptor(project, file).navigateInEditor(project, true);
      return;
    }
    // Chromium reads the file from disk: unsaved edits in the editor would be missing
    FileDocumentManager documents = FileDocumentManager.getInstance();
    Document document = documents.getCachedDocument(file);
    if (document != null && documents.isDocumentUnsaved(document)) documents.saveDocument(document);
    BrowserTabs.openFile(project, Urls.fileUrl(file.toNioPath()));
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    boolean visible = e.getProject() != null && applies(e.getData(CommonDataKeys.VIRTUAL_FILE));
    e.getPresentation().setEnabledAndVisible(visible);
    // inside the "Open In" submenu the item reads "Browser Tab", next to "Browser", "Finder", "Terminal"
    if (e.isFromContextMenu()) e.getPresentation().setText("Browser Tab");
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  private static boolean applies(@Nullable VirtualFile file) {
    return file != null && !file.isDirectory() && file.isInLocalFileSystem()
        && (Urls.isHtmlFile(file.getName()) || Urls.isPdfFile(file.getName()));
  }
}
