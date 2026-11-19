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
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.intellij.util.ui.JBUI;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeListener;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import nl.netonic.browser.core.ConsoleLog;
import nl.netonic.browser.core.DeviceWidths;
import nl.netonic.browser.core.Js;
import nl.netonic.browser.core.LiveReload;
import nl.netonic.browser.core.Urls;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefContextMenuParams;
import org.cef.callback.CefMenuModel;
import org.cef.handler.CefContextMenuHandlerAdapter;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.network.CefRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** One browser tab: toolbar with back / forward / reload, an address bar, and the page (embedded Chromium). */
public final class BrowserEditor extends UserDataHolderBase implements FileEditor {
  public static final String TOOLBAR_PLACE = "EditorBrowser";
  private static final int[] ZOOM_STEPS = {25, 33, 50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200, 250, 300};
  /** The reload icon, spinning in place, shown instead of a static icon while the page loads. */
  private static final Icon RELOAD_SPINNER = reloadSpinner();
  /** Minimum time the reload button spins after a load starts, see {@link #spinUntil}. */
  private static final int MIN_SPIN_MS = 500;
  private static final Icon DEVICE_WIDTH_ICON = IconLoader.getIcon("/icons/deviceWidth.svg", BrowserEditor.class);
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
  /**
   * Evaluates to {@code "true"} when the page has a dev-server client that reloads it by itself (Vite, webpack
   * HMR, LiveReload, Browsersync), see {@link LiveReload}.
   */
  private static final String LIVE_RELOAD_JS = """
      (function () {
        var srcs = Array.prototype.map.call(document.scripts, function (s) { return s.src || ''; }).join(' ');
        if (/\\/@vite\\/client|livereload|browser-sync|webpack-dev-server/i.test(srcs)) return 'true';
        if (document.getElementById('__bs_script__')) return 'true';
        for (var k in window) if (k.indexOf('webpackHotUpdate') === 0) return 'true';
        return 'false';
      })()""";

  private final Project project;
  private final BrowserFile file;
  private final BrowserStore store = BrowserStore.getInstance();
  private final JPanel root = new JPanel(new BorderLayout());
  private final JBTextField address = new JBTextField();
  /** "80%" left of the zoom buttons when the zoom isn't 100%; click to reset. */
  private final JBLabel zoomLabel = new JBLabel();
  /** "390 px" left of the zoom label when a device width is set; click for full width. */
  private final JBLabel widthLabel = new JBLabel();
  /** Holds the page: the full tab, or a column of the device width (at the zoom) centred in it. */
  private final JPanel viewport = new Viewport();
  /** Red error icon + count when the page logged console errors; click for DevTools. */
  private final JBLabel errorLabel = new JBLabel();
  private final ConsoleLog console = new ConsoleLog();
  /** Find in page (⌘F), between the toolbar and the page; hidden until opened. */
  private final JPanel findBar = new JPanel(new BorderLayout());
  private final JBTextField findField = new JBTextField(24);
  private final JBLabel findCount = new JBLabel();
  private final @Nullable JBCefBrowser browser;
  private @Nullable JBCefJSQuery findCountQuery;
  /** Numbers the searches, so a late match count for an older search text is ignored; UI thread only. */
  private int findSeq;

  /**
   * Showing a page's source: Chromium reports a {@code view-source:} page's address without the prefix, which
   * would put the plain URL in the address bar and reopen the page instead of its source after a restart.
   */
  private volatile boolean sourceView;
  private volatile boolean loading;
  /**
   * The reload button spins at least until this time ({@link System#currentTimeMillis()}), so a page that loads in
   * a few milliseconds (a local dev server) still shows that it reloaded.
   */
  private volatile long spinUntil;
  /** The toolbar with the reload button: refreshed when the loading state changes; set once in the constructor. */
  private @Nullable ActionToolbar navToolbar;
  private volatile boolean canGoBack;
  private volatile boolean canGoForward;
  /** The page has a dev-server client that reloads it (see {@link #LIVE_RELOAD_JS}). */
  private volatile boolean pageReloadsItself;
  /** A project file was saved while this tab was in the background: reload when it is shown; UI thread only. */
  private boolean reloadWhenShown;

  BrowserEditor(@NotNull Project project, @NotNull BrowserFile file) {
    this.project = project;
    this.file = file;
    String url = file.url();
    sourceView = Urls.isViewSource(url);
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
        String shown = sourceView && Urls.viewSource(newUrl) != null ? Urls.viewSource(newUrl) : newUrl;
        store.setUrl(file.id(), shown);
        onEdt(() -> {
          if (!address.hasFocus()) address.setText(shown);
          refreshTab();
        });
      }

      @Override
      public void onTitleChange(CefBrowser b, String title) {
        // Chromium reports "about:blank" itself as the title; keep showing "New Tab" (Urls.label) instead
        if (!Urls.BLANK.equalsIgnoreCase(title)) store.setTitle(file.id(), title);
        onEdt(BrowserEditor.this::refreshTab);
      }

      @Override
      public boolean onConsoleMessage(CefBrowser b, CefSettings.LogSeverity level, String message, String source,
          int line) {
        if (level == CefSettings.LogSeverity.LOGSEVERITY_ERROR || level == CefSettings.LogSeverity.LOGSEVERITY_FATAL) {
          console.add(message, source, line);
          onEdt(BrowserEditor.this::updateErrorLabel);
        }
        return false;
      }
    }, cef);
    JBCefJSQuery faviconQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
    faviconQuery.addHandler(result -> {
      BrowserFavicons.fetch(file.id(), result, this::refreshTab);
      return null;
    });
    Disposer.register(this, faviconQuery);
    JBCefJSQuery liveReloadQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
    liveReloadQuery.addHandler(result -> {
      pageReloadsItself = "true".equals(result);
      return null;
    });
    Disposer.register(this, liveReloadQuery);
    findCountQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
    findCountQuery.addHandler(result -> {
      onEdt(() -> showFindCount(result));
      return null;
    });
    Disposer.register(this, findCountQuery);
    browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
      @Override
      public void onLoadingStateChange(CefBrowser b, boolean isLoading, boolean back, boolean forward) {
        if (isLoading && !loading) spinUntil = System.currentTimeMillis() + MIN_SPIN_MS;
        loading = isLoading;
        canGoBack = back;
        canGoForward = forward;
        if (!isLoading) applyZoom();
        onEdt(BrowserEditor.this::refreshReloadButton);
      }

      @Override
      public void onLoadStart(CefBrowser b, CefFrame frame, CefRequest.TransitionType transitionType) {
        if (frame == null || !frame.isMain()) return;
        // a new page: its own console errors and dev-server client
        console.clear();
        pageReloadsItself = false;
        onEdt(BrowserEditor.this::updateErrorLabel);
      }

      @Override
      public void onLoadEnd(CefBrowser b, CefFrame frame, int httpStatusCode) {
        if (frame == null || !frame.isMain()) return;
        b.executeJavaScript(faviconQuery.inject(FAVICON_JS), b.getURL(), 0);
        b.executeJavaScript(liveReloadQuery.inject(LIVE_RELOAD_JS), b.getURL(), 0);
        onEdt(() -> { if (findBar.isVisible()) runFind(true, false); });
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
    browser.getJBCefClient().addContextMenuHandler(new CefContextMenuHandlerAdapter() {
      @Override
      public boolean onContextMenuCommand(CefBrowser b, CefFrame frame, CefContextMenuParams params, int commandId,
          int eventFlags) {
        // Chromium's own "View page source" writes the source to a temp file for the OS to open, which does
        // nothing in the IDE: show it in a new tab instead, as Chrome does
        if (commandId != CefMenuModel.MenuId.MENU_ID_VIEW_SOURCE) return false;
        String pageUrl = params.getPageUrl();
        if (pageUrl == null || pageUrl.isBlank()) pageUrl = b.getURL();
        String source = Urls.viewSource(pageUrl);
        if (source != null) onEdt(() -> BrowserTabs.open(project, source, true));
        return true;
      }
    }, cef);

    JPanel north = new JPanel(new BorderLayout());
    north.add(createToolbar(), BorderLayout.NORTH);
    north.add(createFindBar(), BorderLayout.SOUTH);
    root.add(north, BorderLayout.NORTH);
    viewport.add(browser.getComponent());
    root.add(viewport, BorderLayout.CENTER);
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
    sourceView = Urls.isViewSource(url);
    store.setUrl(file.id(), url);
    address.setText(url);
    if (browser != null) {
      browser.loadURL(url);
      browser.getComponent().requestFocusInWindow();
    }
  }

  /** Reloads the current page, e.g. after its dev server was restarted. */
  public void reload() {
    withCef(CefBrowser::reload);
  }

  /**
   * Project files were saved ({@link ReloadOnSave}): reloads a local page, unless its dev server reloads it for
   * these files itself, or a page shown from disk ({@code file:}) when a saved file is in its folder. A tab in the
   * background reloads when it is shown again ({@link #selectNotify()}).
   */
  void reloadAfterSave(List<Path> saved) {
    if (browser == null) return;
    String url = file.url();
    Path page = Urls.localPath(Urls.isViewSource(url) ? url.substring(Urls.VIEW_SOURCE.length()) : url);
    boolean reload = page != null
        ? LiveReload.changesFilePage(page, saved)
        : Urls.isLocal(url) && LiveReload.shouldReload(
            saved.stream().map(p -> String.valueOf(p.getFileName())).toList(), pageReloadsItself);
    if (!reload) return;
    if (root.isShowing()) reload();
    else reloadWhenShown = true;
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
    // a device width is in CSS px, so its column grows and shrinks with the zoom
    if (deviceWidth() != DeviceWidths.FULL) relayoutViewport();
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

  // --- device width (the page laid out at a phone / tablet / laptop width, centred in the tab)

  private int deviceWidth() {
    return store.width(file.id());
  }

  private void setDeviceWidth(int cssWidth) {
    store.setWidth(file.id(), cssWidth);
    updateWidthLabel();
    relayoutViewport();
  }

  private void updateWidthLabel() {
    int w = deviceWidth();
    widthLabel.setText(w + " px");
    widthLabel.setVisible(w != DeviceWidths.FULL);
  }

  private void relayoutViewport() {
    viewport.revalidate();
    viewport.repaint();
  }

  /** Lays the page out at the device width (times the zoom), centred, with a line either side; else fills. */
  private final class Viewport extends JPanel {
    Viewport() {
      super(null);
      setBackground(JBColor.namedColor("EditorTabs.background", JBColor.PanelBackground));
    }

    @Override
    public void doLayout() {
      if (getComponentCount() == 0) return;
      int w = DeviceWidths.frameWidth(deviceWidth(), zoom(), getWidth());
      getComponent(0).setBounds((getWidth() - w) / 2, 0, w, getHeight());
    }

    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (getComponentCount() == 0) return;
      Component page = getComponent(0);
      if (page.getWidth() >= getWidth()) return;
      g.setColor(JBColor.border());
      g.drawLine(page.getX() - 1, 0, page.getX() - 1, getHeight());
      g.drawLine(page.getX() + page.getWidth(), 0, page.getX() + page.getWidth(), getHeight());
    }
  }

  /** Toolbar dropdown: Full Width and the {@link DeviceWidths#PRESETS}, the current one ticked. */
  private DefaultActionGroup deviceWidthGroup() {
    DefaultActionGroup group = new DefaultActionGroup("Device Width", true);
    group.getTemplatePresentation().setIcon(DEVICE_WIDTH_ICON);
    group.add(widthOption("Full Width", DeviceWidths.FULL));
    group.addSeparator();
    for (DeviceWidths.Preset p : DeviceWidths.PRESETS) group.add(widthOption(p.text(), p.width()));
    return group;
  }

  private DumbAwareToggleAction widthOption(String text, int cssWidth) {
    return new DumbAwareToggleAction(text) {
      @Override
      public boolean isSelected(@NotNull AnActionEvent e) {
        return deviceWidth() == cssWidth;
      }

      @Override
      public void setSelected(@NotNull AnActionEvent e, boolean state) {
        setDeviceWidth(cssWidth);
      }

      @Override
      public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
      }
    };
  }

  // --- find in page (Chromium highlights and steps through the matches; JCEF reports no count, see Js.countMatches)

  private void openFind() {
    if (browser == null) return;
    findBar.setVisible(true);
    root.revalidate();
    findField.requestFocusInWindow();
    findField.selectAll();
    if (!findField.getText().isEmpty()) runFind(true, false);
  }

  private void closeFind() {
    if (!findBar.isVisible()) return;
    findBar.setVisible(false);
    root.revalidate();
    findSeq++;
    findCount.setText("");
    withCef(c -> c.stopFinding(true));
    if (browser != null) browser.getComponent().requestFocusInWindow();
  }

  /** ⌘G / ⇧⌘G, F3 / ⇧F3, Enter / ⇧Enter in the find field: the next or previous match. */
  private void findAgain(boolean forward) {
    if (!findBar.isVisible()) openFind();
    else runFind(forward, true);
  }

  /** {@code next}: step to the next / previous match of the same text; otherwise a new search (text changed). */
  private void runFind(boolean forward, boolean next) {
    String text = findField.getText();
    int seq = ++findSeq;
    if (text.isEmpty()) {
      findCount.setText("");
      withCef(c -> c.stopFinding(true));
      return;
    }
    withCef(c -> c.find(text, forward, false, next));
    JBCefJSQuery query = findCountQuery;
    if (!next && query != null) withCef(c -> c.executeJavaScript(query.inject(Js.countMatches(text, seq)), c.getURL(), 0));
  }

  /** {@code "<seq>:<count>"} from {@link Js#countMatches}. */
  private void showFindCount(String result) {
    int colon = result == null ? -1 : result.indexOf(':');
    if (colon < 0) return;
    try {
      int seq = Integer.parseInt(result.substring(0, colon));
      int count = Integer.parseInt(result.substring(colon + 1));
      if (seq != findSeq || !findBar.isVisible()) return;
      findCount.setText(count == 0 ? "No matches" : count == 1 ? "1 match" : count + " matches");
      findCount.setForeground(count == 0 ? JBColor.RED : JBColor.GRAY);
    } catch (NumberFormatException ignored) {
      // not ours
    }
  }

  private JComponent createFindBar() {
    findField.getEmptyText().setText("Find in page");
    findField.getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull DocumentEvent e) {
        runFind(true, false);
      }
    });
    findField.addKeyListener(new KeyAdapter() {
      @Override
      public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ENTER) {
          runFind(!e.isShiftDown(), true);
          e.consume();
        } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
          closeFind();
          e.consume();
        }
      }
    });
    findField.addFocusListener(new FocusAdapter() {
      @Override
      public void focusGained(FocusEvent e) {
        SwingUtilities.invokeLater(findField::selectAll);
      }
    });
    String mod = SystemInfo.isMac ? "⌘" : "Ctrl+";
    DefaultActionGroup actions = new DefaultActionGroup(
        action("Previous Match (⇧" + mod + "G)", AllIcons.Actions.PreviousOccurence, () -> !findField.getText().isEmpty(),
            () -> runFind(false, true)),
        action("Next Match (" + mod + "G)", AllIcons.Actions.NextOccurence, () -> !findField.getText().isEmpty(),
            () -> runFind(true, true)),
        action("Close (Esc)", AllIcons.Actions.Close, () -> true, this::closeFind));
    findCount.setForeground(JBColor.GRAY);

    JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
    row.setOpaque(false);
    row.add(findField);
    row.add(toolbar(actions).getComponent());
    row.add(findCount);
    findBar.add(row, BorderLayout.CENTER);
    findBar.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(2, 0)));
    findBar.setVisible(false);
    return findBar;
  }

  // --- console errors

  private void updateErrorLabel() {
    int count = console.count();
    errorLabel.setVisible(count > 0);
    errorLabel.setText(Integer.toString(count));
    StringBuilder tip = new StringBuilder("<html><b>").append(count).append(count == 1 ? " console error" : " console errors")
        .append("</b> on this page; click to open DevTools");
    for (String message : console.recent()) tip.append("<br>").append(StringUtil.escapeXmlEntities(message));
    errorLabel.setToolTipText(tip.append("</html>").toString());
  }

  // --- UI

  private JComponent createToolbar() {
    DefaultActionGroup left = new DefaultActionGroup(
        action("Back", AllIcons.Actions.Back, () -> canGoBack, () -> withCef(CefBrowser::goBack)),
        action("Forward", AllIcons.Actions.Forward, () -> canGoForward, () -> withCef(CefBrowser::goForward)),
        new ReloadAction(),
        ActionManager.getInstance().getAction("EditorBrowser.NewTab"));
    navToolbar = toolbar(left);
    DefaultActionGroup right = new DefaultActionGroup(
        deviceWidthGroup(),
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
    widthLabel.setToolTipText("Device width; click for full width");
    widthLabel.setForeground(JBColor.GRAY);
    widthLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    widthLabel.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        setDeviceWidth(DeviceWidths.FULL);
      }
    });
    updateWidthLabel();
    errorLabel.setIcon(AllIcons.General.Error);
    errorLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    errorLabel.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        if (browser != null) browser.openDevtools();
      }
    });
    updateErrorLabel();
    JPanel labels = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0));
    labels.setOpaque(false);
    labels.add(errorLabel);
    labels.add(widthLabel);
    labels.add(zoomLabel);
    JPanel east = new JPanel(new BorderLayout(JBUI.scale(2), 0));
    east.setOpaque(false);
    east.add(labels, BorderLayout.WEST);
    east.add(toolbar(right).getComponent(), BorderLayout.CENTER);

    JPanel bar = new JPanel(new BorderLayout(JBUI.scale(4), 0));
    bar.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(2, 4)));
    bar.add(navToolbar.getComponent(), BorderLayout.WEST);
    bar.add(address, BorderLayout.CENTER);
    bar.add(east, BorderLayout.EAST);
    return bar;
  }

  private ActionToolbar toolbar(DefaultActionGroup group) {
    ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, group, true);
    toolbar.setTargetComponent(root);
    toolbar.getComponent().setOpaque(false);
    toolbar.getComponent().setBorder(JBUI.Borders.empty());
    return toolbar;
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
    shortcut(mod + " F", this::openFind);
    shortcut(mod + " G", () -> findAgain(true));
    shortcut(mod + " shift G", () -> findAgain(false));
    shortcut("F3", () -> findAgain(true));
    shortcut("shift F3", () -> findAgain(false));
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
    // Negative: Java2D rotates clockwise for positive angles (y points down), and the Refresh icon's arrows point
    // counter-clockwise, so the spin follows the arrows.
    for (int i = 0; i < frameCount; i++) frames[i] = rotated(base, -2 * Math.PI * i / frameCount);
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

  /** True while the reload button shows the spinner: loading, or within {@link #MIN_SPIN_MS} of a load start. */
  private boolean spinning() {
    return loading || System.currentTimeMillis() < spinUntil;
  }

  /**
   * Updates the reload button now (the toolbar's own refresh is periodic and would miss a quick load), and once
   * more when the minimum spin time is over. UI thread only.
   */
  private void refreshReloadButton() {
    if (navToolbar == null || (browser != null && browser.isDisposed())) return;
    navToolbar.updateActionsAsync();
    long left = spinUntil - System.currentTimeMillis();
    if (!loading && left > 0) {
      Timer timer = new Timer((int) left + 20, e -> refreshReloadButton());
      timer.setRepeats(false);
      timer.start();
    }
  }

  private final class ReloadAction extends DumbAwareAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      if (loading) {
        withCef(CefBrowser::stopLoad);
        spinUntil = 0;
        refreshReloadButton();
      } else {
        // spin right away: Chromium reports the load start a moment later
        spinUntil = System.currentTimeMillis() + MIN_SPIN_MS;
        withCef(CefBrowser::reload);
        refreshReloadButton();
      }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      boolean spinning = spinning();
      e.getPresentation().setText(loading ? "Stop" : "Reload");
      e.getPresentation().setIcon(spinning ? RELOAD_SPINNER : AllIcons.Actions.Refresh);
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
  public void selectNotify() {
    if (!reloadWhenShown) return;
    reloadWhenShown = false;
    reload();
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
