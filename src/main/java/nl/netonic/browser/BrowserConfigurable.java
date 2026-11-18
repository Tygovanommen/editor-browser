package nl.netonic.browser;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.components.JBCheckBox;
import java.awt.BorderLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Settings › Tools › Editor Browser: the one toggle for automatic dev-server tabs. */
public final class BrowserConfigurable implements Configurable {
  private final JBCheckBox autoOpen = new JBCheckBox(
      "<html>Open a browser tab automatically when a dev server starts<br>"
          + "<small>A run configuration printing a localhost URL, or a common dev-server port (5173, 3000, 8000, "
          + "8080, …) starting to listen, e.g. npm run dev in the Terminal</small></html>");

  @Override
  public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
    return "Editor Browser";
  }

  @Override
  public @Nullable JComponent createComponent() {
    JPanel panel = new JPanel(new BorderLayout());
    panel.add(autoOpen, BorderLayout.NORTH);
    return panel;
  }

  @Override
  public boolean isModified() {
    return autoOpen.isSelected() != BrowserStore.getInstance().isAutoOpenDevServer();
  }

  @Override
  public void apply() {
    BrowserStore.getInstance().setAutoOpenDevServer(autoOpen.isSelected());
  }

  @Override
  public void reset() {
    autoOpen.setSelected(BrowserStore.getInstance().isAutoOpenDevServer());
  }
}
