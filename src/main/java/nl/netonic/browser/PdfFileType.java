package nl.netonic.browser;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.INativeFileType;
import com.intellij.openapi.fileTypes.NativeFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import javax.swing.Icon;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

/**
 * {@code *.pdf}. PhpStorm has PDFs as a "Native" file type, which opens them in the system's app without asking any
 * editor; this type takes {@code pdf} over, so opening a PDF (project view, Go to File, a link) shows it in a
 * browser tab ({@link PdfEditorProvider}), or, with {@link BrowserStore#isOpenPdfFiles()} off, in the system's app
 * as before.
 */
public final class PdfFileType implements FileType, INativeFileType {
  public static final PdfFileType INSTANCE = new PdfFileType();

  private PdfFileType() {}

  @Override
  public boolean openFileInAssociatedApplication(Project project, @NotNull VirtualFile file) {
    if (project != null && !project.isDisposed() && BrowserStore.getInstance().isOpenPdfFiles()
        && PdfEditorProvider.applies(file)) {
      return new OpenFileDescriptor(project, file).navigateInEditor(project, true);
    }
    return NativeFileType.openAssociatedApplication(file);
  }

  @Override
  public boolean useNativeIcon() {
    // the system's PDF icon in the project view, as before
    return true;
  }

  @Override
  public @NonNls @NotNull String getName() {
    return "PDF";
  }

  @Override
  public @NotNull String getDescription() {
    return "PDF (shown in a browser tab by Editor Browser)";
  }

  @Override
  public @NotNull String getDefaultExtension() {
    return "pdf";
  }

  @Override
  public Icon getIcon() {
    return AllIcons.FileTypes.Any_type;
  }

  @Override
  public boolean isBinary() {
    return true;
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }
}
