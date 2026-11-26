package nl.netonic.browser;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import nl.netonic.browser.core.Urls;
import org.jetbrains.annotations.NotNull;

/**
 * A PDF shown in a browser tab changed on disk (a report or invoice generated again, a LaTeX build): the tab reloads
 * it, a tab in the background when it is shown again ({@link BrowserEditor#reloadForChangedFiles}). Unlike
 * {@link ReloadOnSave} this follows changes on disk, by any tool: only the tabs showing that very PDF reload, so a
 * tool writing it on every request can't make a page reload in a loop.
 */
final class PdfFileWatcher implements BulkFileListener {
  @Override
  public void after(@NotNull List<? extends @NotNull VFileEvent> events) {
    Set<Path> changed = new HashSet<>();
    for (VFileEvent event : events) {
      if (!(event instanceof VFileContentChangeEvent || event instanceof VFileCreateEvent)) continue;
      if (!(event.getFileSystem() instanceof LocalFileSystem) || !Urls.isPdfFile(event.getPath())) continue;
      changed.add(Path.of(event.getPath()).toAbsolutePath().normalize());
    }
    if (changed.isEmpty()) return;
    ApplicationManager.getApplication().invokeLater(() -> {
      for (Project project : ProjectManager.getInstance().getOpenProjects()) {
        if (project.isDisposed()) continue;
        for (FileEditor editor : FileEditorManager.getInstance(project).getAllEditors()) {
          if (editor instanceof BrowserEditor browser) browser.reloadForChangedFiles(changed);
        }
      }
    });
  }
}
