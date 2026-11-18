package nl.netonic.browser;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.intellij.util.ui.JBUI;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeListener;
import java.util.function.Consumer;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import nl.netonic.browser.core.Urls;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** One browser tab: toolbar with back / forward / reload, an address bar, and the page (embedded Chromium). */
public final class BrowserEditor extends UserDataHolderBase implements FileEditor {
  private static final int[] ZOOM_STEPS = {25, 33, 50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200, 250, 300};
  /** The reload icon, spinning in place, shown instead of a static icon while the page loads. */
  private static final Icon RELOAD_SPINNER = reloadSpinner();
  /** Evaluates to the page's best favicon URL, or {@code ""} for a page with no real origin (e.g. about:blank). */
  private static final String FAVICON_JS = """
      (function () {
        if (!/^https?:$/.test(location.protocol)) return '';
        var links = Array.prototype.slice.call(document.querySelectorAll("link[rel~='icon']"));
        var best = links.sort(function (a, b) {
          var sa = parseInt((a.getAttribute('sizes') || '0').split('x')[0], 10) || 0;
          var sb = parseInt((b.getAttribute('sizes') || '0').split('x')[0], 10) || 0;
          return sb - sa;
        })[0];
        return best ? best.href : (location.origin + '/favicon.ico');
      })()""";

  private final Project project;
  private final BrowserFile file;
  private final BrowserStore store = BrowserStore.getInstance();
  private final JPanel root = new JPanel(new BorderLayout());
  private final JBTextField address = new JBTextField();
  /** "80%" left of the zoom buttons when the zoom isn't 100%; click to reset. */
  private final JBLabel zoomLabel = new JBLabel();
  private final @Nullable JBCefBrowser browser;

  private volatile boolean loading;
  private volatile boolean canGoBack;
  private volatile boolean canGoForward;

  BrowserEditor(@NotNull Project project, @NotNull BrowserFile file) {
    this.project = project;
    this.file = file;
    String url = file.url();
    address.setText(url);

    if (!JBCefApp.isSupported()) {
      browser = null;
      root.add(new JBLabel("This tab needs PhpStorm's embedded browser (JCEF), which isn't available here.",
          SwingConstants.CENTER), BorderLayout.CENTER);
      return;
    }

    browser = JBCefBrowser.createBuilder().setUrl(url).build();
    Disposer.register(this, browser);
    // pages without a background of their own are white, as in any browser (not the IDE theme's dark grey)
    browser.setPageBackgroundColor("white");
    CefBrowser cef = browser.getCefBrowser();
    browser.getJBCefClient().addDisplayHandler(new CefDisplayHandlerAdapter() {
      @Override
      public void onAddressChange(CefBrowser b, CefFrame frame, String newUrl) {
        if (frame != null && !frame.isMain()) return;
        store.setUrl(file.id(), newUrl);
        onEdt(() -> {
          if (!address.hasFocus()) address.setText(newUrl);
          refreshTab();
        });
      }

      @Override
      public void onTitleChange(CefBrowser b, String title) {
        // Chromium reports "about:blank" itself as the title; keep showing "New Tab" (Urls.label) instead
        if (!Urls.BLANK.equalsIgnoreCase(title)) store.setTitle(file.id(), title);
        onEdt(BrowserEditor.this::refreshTab);
      }
    }, cef);
    JBCefJSQuery faviconQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
    faviconQuery.addHandler(result -> {
      BrowserFavicons.fetch(file.id(), result, this::refreshTab);
      return null;
    });
    Disposer.register(this, faviconQuery);
    browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
      @Override
      public void onLoadingStateChange(CefBrowser b, boolean isLoading, boolean back, boolean forward) {
        loading = isLoading;
        canGoBack = back;
        canGoForward = forward;
        if (!isLoading) applyZoom();
      }

      @Override
      public void onLoadEnd(CefBrowser b, CefFrame frame, int httpStatusCode) {
        if (frame != null && frame.isMain()) b.executeJavaScript(faviconQuery.inject(FAVICON_JS), b.getURL(), 0);
      }
    }, cef);
    browser.getJBCefClient().addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
      @Override
      public boolean onBeforePopup(CefBrowser b, CefFrame frame, String targetUrl, String targetFrameName) {
        // target="_blank" links and window.open(): a new editor tab instead of a separate window
        if (targetUrl != null && !targetUrl.isBlank()) onEdt(() -> BrowserTabs.open(project, targetUrl, true));
        return true;
      }
    }, cef);

    root.add(createToolbar(), BorderLayout.NORTH);
    root.add(browser.getComponent(), BorderLayout.CENTER);
    registerShortcuts();
  }

  // --- navigation

  void navigate(String input) {
    String url = Urls.normalize(input);
    if (url == null) return;
    store.addRecent(url);
    loadUrl(url);
  }

  /** Loads an already-normalized URL without touching the recent-addresses list, e.g. a detected dev server. */
  public void loadUrl(String url) {
    store.setUrl(file.id(), url);
    address.setText(url);
    if (browser != null) {
      browser.loadURL(url);
      browser.getComponent().requestFocusInWindow();
    }
  }

  private void refreshTab() {
    if (!project.isDisposed()) FileEditorManager.getInstance(project).updateFilePresentation(file);
  }

  private void withCef(Consumer<CefBrowser> action) {
    if (browser != null && !browser.isDisposed()) action.accept(browser.getCefBrowser());
  }

  private static void onEdt(Runnable r) {
    ApplicationManager.getApplication().invokeLater(r);
  }

  // --- zoom (Chromium zoom level: 0 = 100%, each step ×1.2)

  private int zoom() {
    return store.zoom(file.id());
  }

  private void setZoom(int percent) {
    store.setZoom(file.id(), percent);
    applyZoom();
    updateZoomLabel();
  }

  private void updateZoomLabel() {
    int z = zoom();
    zoomLabel.setText(z + "%");
    zoomLabel.setVisible(z != 100);
  }

  private void applyZoom() {
    double level = Math.log(zoom() / 100.0) / Math.log(1.2);
    withCef(c -> c.setZoomLevel(level));
  }

  private void zoomStep(int direction) {
    int current = zoom();
    int next = current;
    if (direction > 0) {
      for (int step : ZOOM_STEPS) if (step > current) { next = step; break; }
    } else {
      for (int i = ZOOM_STEPS.length - 1; i >= 0; i--) if (ZOOM_STEPS[i] < current) { next = ZOOM_STEPS[i]; break; }
    }
    setZoom(next);
  }

  // --- UI

  private JComponent createToolbar() {
    DefaultActionGroup left = new DefaultActionGroup(
        action("Back", AllIcons.Actions.Back, () -> canGoBack, () -> withCef(CefBrowser::goBack)),
        action("Forward", AllIcons.Actions.Forward, () -> canGoForward, () -> withCef(CefBrowser::goForward)),
        new ReloadAction(),
        ActionManager.getInstance().getAction("EditorBrowser.NewTab"));
    DefaultActionGroup right = new DefaultActionGroup(
        action("Zoom Out", AllIcons.General.ZoomOut, () -> zoom() > ZOOM_STEPS[0], () -> zoomStep(-1)),
        action("Zoom In", AllIcons.General.ZoomIn, () -> zoom() < ZOOM_STEPS[ZOOM_STEPS.length - 1], () -> zoomStep(1)),
        action("Open in Default Browser", AllIcons.Ide.External_link_arrow, () -> true,
            () -> BrowserUtil.browse(file.url())),
        action("Open DevTools", AllIcons.Toolwindows.ToolWindowInspection, () -> browser != null,
            () -> { if (browser != null) browser.openDevtools(); }));

    address.getEmptyText().setText("Enter a URL, e.g. localhost:3000");
    address.addKeyListener(new KeyAdapter() {
      @Override
      public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ENTER) {
          navigate(address.getText());
          e.consume();
        } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
          address.setText(file.url());
          if (browser != null) browser.getComponent().requestFocusInWindow();
          e.consume();
        }
      }
    });
    address.addFocusListener(new FocusAdapter() {
      @Override
      public void focusGained(FocusEvent e) {
        SwingUtilities.invokeLater(address::selectAll);
      }
    });

    zoomLabel.setToolTipText("Reset zoom to 100%");
    zoomLabel.setForeground(JBColor.GRAY);
    zoomLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    zoomLabel.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        setZoom(100);
      }
    });
    updateZoomLabel();
    JPanel east = new JPanel(new BorderLayout(JBUI.scale(2), 0));
    east.setOpaque(false);
    east.add(zoomLabel, BorderLayout.WEST);
    east.add(toolbar(right), BorderLayout.CENTER);

    JPanel bar = new JPanel(new BorderLayout(JBUI.scale(4), 0));
    bar.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(2, 4)));
    bar.add(toolbar(left), BorderLayout.WEST);
    bar.add(address, BorderLayout.CENTER);
    bar.add(east, BorderLayout.EAST);
    return bar;
  }

  private JComponent toolbar(DefaultActionGroup group) {
    ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("EditorBrowser", group, true);
    toolbar.setTargetComponent(root);
    toolbar.getComponent().setOpaque(false);
    toolbar.getComponent().setBorder(JBUI.Borders.empty());
    return toolbar.getComponent();
  }

  private void registerShortcuts() {
    String mod = SystemInfo.isMac ? "meta" : "control";
    shortcut(mod + " L", () -> address.requestFocusInWindow());
    shortcut(mod + " R", () -> withCef(CefBrowser::reload));
    shortcut("F5", () -> withCef(CefBrowser::reload));
    shortcut(mod + " shift R", () -> withCef(CefBrowser::reloadIgnoreCache));
    shortcut(mod + " OPEN_BRACKET", () -> withCef(CefBrowser::goBack));
    shortcut(mod + " CLOSE_BRACKET", () -> withCef(CefBrowser::goForward));
    shortcut(mod + " EQUALS", () -> zoomStep(1));
    shortcut(mod + " MINUS", () -> zoomStep(-1));
    shortcut(mod + " 0", () -> setZoom(100));
    shortcut("F12", () -> { if (browser != null) browser.openDevtools(); });
    shortcut(mod + " alt I", () -> { if (browser != null) browser.openDevtools(); });
  }

  private void shortcut(String keystroke, Runnable run) {
    DumbAwareAction.create(e -> run.run()).registerCustomShortcutSet(CustomShortcutSet.fromString(keystroke), root, this);
  }

  private static DumbAwareAction action(String text, Icon icon, java.util.function.BooleanSupplier enabled, Runnable run) {
    return new DumbAwareAction(text, null, icon) {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        run.run();
      }

      @Override
      public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(enabled.getAsBoolean());
      }

      @Override
      public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
      }
    };
  }

  private static Icon reloadSpinner() {
    Icon base = AllIcons.Actions.Refresh;
    int frameCount = 8;
    Icon[] frames = new Icon[frameCount];
    for (int i = 0; i < frameCount; i++) frames[i] = rotated(base, 2 * Math.PI * i / frameCount);
    return new AnimatedIcon(100, frames);
  }

  private static Icon rotated(Icon base, double radians) {
    return new Icon() {
      @Override
      public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y) {
        java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
        g2.rotate(radians, x + base.getIconWidth() / 2.0, y + base.getIconHeight() / 2.0);
        base.paintIcon(c, g2, x, y);
        g2.dispose();
      }

      @Override
      public int getIconWidth() {
        return base.getIconWidth();
      }

      @Override
      public int getIconHeight() {
        return base.getIconHeight();
      }
    };
  }

  private final class ReloadAction extends DumbAwareAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      withCef(c -> { if (loading) c.stopLoad(); else c.reload(); });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      e.getPresentation().setText(loading ? "Stop" : "Reload");
      e.getPresentation().setIcon(loading ? RELOAD_SPINNER : AllIcons.Actions.Refresh);
      e.getPresentation().setEnabled(browser != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.EDT;
    }
  }

  // --- FileEditor

  @Override
  public @NotNull JComponent getComponent() {
    return root;
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() {
    // a blank tab (just opened, nothing typed yet) starts with the address bar focused, ready to type into
    if (browser == null || Urls.BLANK.equalsIgnoreCase(file.url())) return address;
    return browser.getComponent();
  }

  @Override
  public @NotNull String getName() {
    return "Browser";
  }

  @Override
  public @NotNull VirtualFile getFile() {
    return file;
  }

  @Override
  public void setState(@NotNull FileEditorState state) {
    // the URL is kept in BrowserStore
  }

  @Override
  public boolean isModified() {
    return false;
  }

  @Override
  public boolean isValid() {
    return true;
  }

  @Override
  public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {}

  @Override
  public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {}

  @Override
  public void dispose() {
    // the browser is disposed with this editor (Disposer.register in the constructor)
  }
}
