package nl.netonic.browser;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileTypes.FileType;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;

/** File type of a browser tab: gives the tab its globe icon. Has no extensions, only {@link BrowserFile} uses it. */
public final class BrowserFileType implements FileType {
  public static final BrowserFileType INSTANCE = new BrowserFileType();

  private BrowserFileType() {}

  @Override
  public @NotNull String getName() {
    return "EditorBrowserPage";
  }

  @Override
  public @NotNull String getDescription() {
    return "Web page (Editor Browser)";
  }

  @Override
  public @NotNull String getDefaultExtension() {
    return "";
  }

  @Override
  public Icon getIcon() {
    return AllIcons.General.Web;
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
