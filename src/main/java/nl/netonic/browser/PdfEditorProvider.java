package nl.netonic.browser;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import nl.netonic.browser.core.Urls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

/**
 * A local PDF file opens as a browser tab showing it in Chromium's PDF viewer (find, zoom, thumbnails, print),
 * instead of in the system's PDF app; toggled by {@link BrowserStore#isOpenPdfFiles()}. The tab is the PDF file
 * itself, so PhpStorm reopens it after a restart, and it splits, moves and closes like any file.
 */
public final class PdfEditorProvider implements FileEditorProvider, DumbAware {
  /** Starts the {@link BrowserStore} ids of PDF file tabs, which are not {@link BrowserFile}s. */
  static final String ID_PREFIX = "pdf-";

  @Override
  public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
    return BrowserStore.getInstance().isOpenPdfFiles() && applies(file);
  }

  /** A PDF on disk ({@code file:} URL, so Chromium reads it itself). */
  public static boolean applies(VirtualFile file) {
    return file != null && !file.isDirectory() && file.isInLocalFileSystem() && Urls.isPdfFile(file.getName());
  }

  @Override
  public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
    String id = tabId(file);
    // always the PDF when the tab opens, also where it was navigated away from (a link in the PDF) before
    BrowserStore.getInstance().setUrl(id, Urls.fileUrl(file.toNioPath()));
    return new BrowserEditor(project, file, id);
  }

  /** The PDF tab's id: the same for the same path, so its zoom and color scheme are kept. */
  static String tabId(VirtualFile file) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-1").digest(file.getPath().getBytes(StandardCharsets.UTF_8));
      return ID_PREFIX + HexFormat.of().formatHex(hash, 0, 6);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public @NotNull @NonNls String getEditorTypeId() {
    return "editor-browser-pdf";
  }

  @Override
  public @NotNull FileEditorPolicy getPolicy() {
    return FileEditorPolicy.HIDE_DEFAULT_EDITOR;
  }
}
