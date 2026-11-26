package nl.netonic.browser;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import java.awt.BorderLayout;
import java.util.EnumMap;
import java.util.Map;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import nl.netonic.browser.core.ToolbarLayout;
import nl.netonic.browser.core.ToolbarLayout.Item;
import nl.netonic.browser.core.ToolbarLayout.Place;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Settings › Tools › Editor Browser: automatic dev-server tabs, reloading local tabs on save, PDF files in a
 * browser tab, and where each optional toolbar item goes ({@link ToolbarLayout}: toolbar, ⋮ menu or hidden).
 */
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
  private final JBCheckBox openPdf = new JBCheckBox(
      "<html>Open PDF files in a browser tab<br>"
          + "<small>Chromium's PDF viewer, next to your code: find in the PDF, zoom, page thumbnails, print. "
          + "Off: PDF files open in the system's PDF app</small></html>");
  private final Map<Item, ComboBox<Place>> places = new EnumMap<>(Item.class);

  @Override
  public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
    return "Editor Browser";
  }

  @Override
  public @Nullable JComponent createComponent() {
    FormBuilder form = FormBuilder.createFormBuilder().addComponent(autoOpen).addComponent(reloadOnSave)
        .addComponent(openPdf)
        .addComponent(new TitledSeparator("Browser Tab Toolbar"));
    JBLabel hint = new JBLabel("Back, forward, reload and the address bar are always shown. Shortcuts keep working "
        + "for hidden items.");
    hint.setComponentStyle(UIUtil.ComponentStyle.SMALL);
    hint.setForeground(UIUtil.getContextHelpForeground());
    form.addComponent(hint);
    for (Item item : Item.values()) {
      ComboBox<Place> combo = new ComboBox<>(Place.values());
      places.put(item, combo);
      form.addLabeledComponent(new JBLabel(item.text + ":", icon(item), JBLabel.LEFT), combo);
    }
    form.addComponent(new ActionLink("Reset to defaults", (java.awt.event.ActionListener) e -> show(ToolbarLayout.DEFAULT)));
    JPanel panel = new JPanel(new BorderLayout());
    panel.add(form.getPanel(), BorderLayout.NORTH);
    return panel;
  }

  @Override
  public boolean isModified() {
    BrowserStore store = BrowserStore.getInstance();
    return autoOpen.isSelected() != store.isAutoOpenDevServer() || reloadOnSave.isSelected() != store.isReloadOnSave()
        || openPdf.isSelected() != store.isOpenPdfFiles()
        || !layout().equals(store.toolbarLayout());
  }

  @Override
  public void apply() {
    BrowserStore store = BrowserStore.getInstance();
    store.setAutoOpenDevServer(autoOpen.isSelected());
    store.setReloadOnSave(reloadOnSave.isSelected());
    store.setOpenPdfFiles(openPdf.isSelected());
    ToolbarLayout layout = layout();
    if (!layout.equals(store.toolbarLayout())) {
      store.setToolbarLayout(layout);
      for (Project project : ProjectManager.getInstance().getOpenProjects()) {
        if (project.isDisposed()) continue;
        for (FileEditor editor : FileEditorManager.getInstance(project).getAllEditors()) {
          if (editor instanceof BrowserEditor browser) browser.toolbarLayoutChanged();
        }
      }
    }
  }

  @Override
  public void reset() {
    BrowserStore store = BrowserStore.getInstance();
    autoOpen.setSelected(store.isAutoOpenDevServer());
    reloadOnSave.setSelected(store.isReloadOnSave());
    openPdf.setSelected(store.isOpenPdfFiles());
    show(store.toolbarLayout());
  }

  private void show(ToolbarLayout layout) {
    places.forEach((item, combo) -> combo.setSelectedItem(layout.place(item)));
  }

  /** The layout as chosen in the form (not applied yet). */
  private ToolbarLayout layout() {
    ToolbarLayout layout = ToolbarLayout.DEFAULT;
    for (Map.Entry<Item, ComboBox<Place>> e : places.entrySet()) {
      if (e.getValue().getSelectedItem() instanceof Place p) layout = layout.with(e.getKey(), p);
    }
    return layout;
  }

  /** The icon the item has on the toolbar, so the rows are easy to match with the buttons. */
  private static Icon icon(Item item) {
    return switch (item) {
      case NEW_TAB -> AllIcons.General.Add;
      case DEVICE_WIDTH -> IconLoader.getIcon("/icons/deviceWidth.svg", BrowserConfigurable.class);
      case COLOR_SCHEME -> IconLoader.getIcon("/icons/colorScheme.svg", BrowserConfigurable.class);
      case ZOOM -> AllIcons.General.ZoomIn;
      case FIND -> AllIcons.Actions.Find;
      case SHARE_PREVIEW -> AllIcons.Actions.Share;
      case CLEAR_SITE_DATA -> AllIcons.Actions.ClearCash;
      case OPEN_EXTERNAL -> AllIcons.Ide.External_link_arrow;
      case DEVTOOLS -> AllIcons.Toolwindows.ToolWindowInspection;
    };
  }
}
