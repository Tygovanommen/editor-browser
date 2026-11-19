package nl.netonic.browser;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;

/**
 * Saving a project file reloads the browser tabs on a local page, or on a {@code file:} page in the same folder
 * ({@link BrowserEditor#reloadAfterSave}).
 * Only saves made in the IDE count, not files changed on disk by other tools: frameworks write caches and
 * compiled templates on every request, which would reload the page in a loop. Saves within {@value #DELAY_MS} ms
 * (Save All, autosave) reload once. Toggled by {@link BrowserStore#isReloadOnSave()}.
 */
final class ReloadOnSave implements FileDocumentManagerListener {
  private static final long DELAY_MS = 300;
  /** Saved files waiting for the reload; guarded by itself. */
  private static final Set<VirtualFile> pending = new LinkedHashSet<>();

  @Override
  public void afterDocumentSaved(@NotNull Document document) {
    if (!BrowserStore.getInstance().isReloadOnSave()) return;
    VirtualFile file = FileDocumentManager.getInstance().getFile(document);
    if (file == null || !file.isInLocalFileSystem()) return;
    boolean first;
    synchronized (pending) {
      first = pending.isEmpty();
      pending.add(file);
    }
    if (first) {
      AppExecutorUtil.getAppScheduledExecutorService().schedule(
          () -> ApplicationManager.getApplication().invokeLater(ReloadOnSave::flush), DELAY_MS, TimeUnit.MILLISECONDS);
    }
  }

  // on the UI thread
  private static void flush() {
    List<VirtualFile> files;
    synchronized (pending) {
      files = new ArrayList<>(pending);
      pending.clear();
    }
    for (Project project : ProjectManager.getInstance().getOpenProjects()) {
      if (project.isDisposed()) continue;
      ProjectFileIndex index = ProjectFileIndex.getInstance(project);
      List<Path> saved = ApplicationManager.getApplication().runReadAction((Computable<List<Path>>) () -> files.stream()
          .filter(f -> f.isValid() && index.isInContent(f))
          .map(VirtualFile::toNioPath)
          .toList());
      if (saved.isEmpty()) continue;
      for (FileEditor editor : FileEditorManager.getInstance(project).getAllEditors()) {
        if (editor instanceof BrowserEditor browser) browser.reloadAfterSave(saved);
      }
    }
  }
}
