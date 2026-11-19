package nl.netonic.browser;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.ui.FormBuilder;
import java.awt.BorderLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Settings › Tools › Editor Browser: automatic dev-server tabs, and reloading local tabs on save. */
public final class BrowserConfigurable implements Configurable {
  private final JBCheckBox autoOpen = new JBCheckBox(
      "<html>Open a browser tab automatically when a dev server starts<br>"
          + "<small>A run configuration printing a localhost URL, or a common dev-server port (5173, 3000, 8000, "
          + "8080, …) starting to listen, e.g. npm run dev in the Terminal</small></html>");
  private final JBCheckBox reloadOnSave = new JBCheckBox(
      "<html>Reload local pages when a project file is saved<br>"
          + "<small>Tabs on localhost, an IP, .test, .local or .localhost; a tab in the background reloads when you "
          + "switch to it. Pages that reload themselves (Vite, webpack HMR, LiveReload, Browsersync) only reload "
          + "for server-side files such as PHP, Twig or Blade</small></html>");

  @Override
  public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
    return "Editor Browser";
  }

  @Override
  public @Nullable JComponent createComponent() {
    JPanel panel = new JPanel(new BorderLayout());
    panel.add(FormBuilder.createFormBuilder().addComponent(autoOpen).addComponent(reloadOnSave).getPanel(),
        BorderLayout.NORTH);
    return panel;
  }

  @Override
  public boolean isModified() {
    BrowserStore store = BrowserStore.getInstance();
    return autoOpen.isSelected() != store.isAutoOpenDevServer() || reloadOnSave.isSelected() != store.isReloadOnSave();
  }

  @Override
  public void apply() {
    BrowserStore store = BrowserStore.getInstance();
    store.setAutoOpenDevServer(autoOpen.isSelected());
    store.setReloadOnSave(reloadOnSave.isSelected());
  }

  @Override
  public void reset() {
    BrowserStore store = BrowserStore.getInstance();
    autoOpen.setSelected(store.isAutoOpenDevServer());
    reloadOnSave.setSelected(store.isReloadOnSave());
  }
}
