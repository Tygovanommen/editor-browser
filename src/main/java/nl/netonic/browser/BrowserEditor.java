package nl.netonic.browser;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.KeepPopupOnPerform;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.intellij.util.ui.JBUI;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeListener;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import nl.netonic.browser.core.ColorScheme;
import nl.netonic.browser.core.ConsoleLog;
import nl.netonic.browser.core.DeviceWidths;
import nl.netonic.browser.core.Js;
import nl.netonic.browser.core.LiveReload;
import nl.netonic.browser.core.LoadErrors;
import nl.netonic.browser.core.SharePreview;
import nl.netonic.browser.core.ToolbarLayout;
import nl.netonic.browser.core.ToolbarLayout.Item;
import nl.netonic.browser.core.ToolbarLayout.Place;
import nl.netonic.browser.core.Urls;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefDevToolsClient;
import org.cef.browser.CefFrame;
import org.cef.callback.CefContextMenuParams;
import org.cef.callback.CefMenuModel;
import org.cef.handler.CefContextMenuHandlerAdapter;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefRequestHandler.TerminationStatus;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.misc.BoolRef;
import org.cef.network.CefRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** One browser tab: toolbar with back / forward / reload, an address bar, and the page (embedded Chromium). */
public final class BrowserEditor extends UserDataHolderBase implements FileEditor {
  public static final String TOOLBAR_PLACE = "EditorBrowser";
  private static final int[] ZOOM_STEPS = {25, 33, 50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200, 250, 300};
  /** Minimum time the progress line and the tab's spinner show after a load starts, see {@link #spinUntil}. */
  private static final int MIN_SPIN_MS = 500;
  /** A second click on Reload within this time reloads again instead of stopping the load it just started. */
  private static final int STOP_GUARD_MS = 700;
  /** No answer from the server after this long: the notice bar says the tab is waiting for it. */
  private static final int WAITING_NOTICE_MS = 3000;
  /** How long a tab waits for its local server to come up (see {@link #startWaitingForServer}). */
  private static final long WAIT_FOR_SERVER_MS = 10 * 60 * 1000;
  /** Reloads by the wait for the server that failed again in a row before the tab stops waiting. */
  private static final int MAX_AUTO_RELOADS = 3;
  private static final Icon DEVICE_WIDTH_ICON = IconLoader.getIcon("/icons/deviceWidth.svg", BrowserEditor.class);
  private static final Icon COLOR_SCHEME_ICON = IconLoader.getIcon("/icons/colorScheme.svg", BrowserEditor.class);
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
  /** The tab: a {@link BrowserFile}, or a local PDF file shown in Chromium's PDF viewer ({@link PdfEditorProvider}). */
  private final VirtualFile file;
  /** The tab's key in {@link BrowserStore} (URL, zoom, …) and {@link BrowserFavicons}. */
  private final String id;
  private final BrowserStore store = BrowserStore.getInstance();
  private final JPanel root = new JPanel(new BorderLayout());
  private final JBTextField address = new JBTextField();
  /** "80%" left of the zoom buttons when the zoom isn't 100%; click to reset. */
  private final JBLabel zoomLabel = new JBLabel();
  /** "390 px" left of the zoom label when a device width is set; click for full width. */
  private final JBLabel widthLabel = new JBLabel();
  /** "Dark" / "Light" left of the width label when the color scheme is forced; click for the system's. */
  private final JBLabel schemeLabel = new JBLabel();
  /** Holds the page: the full tab, or a column of the device width (at the zoom) centred in it. */
  private final JPanel viewport = new Viewport();
  /** Red error icon + count when the page logged console errors; click for DevTools. */
  private final JBLabel errorLabel = new JBLabel();
  /** "500" / "404" when the server answered the page with an HTTP error status. */
  private final JBLabel statusLabel = new JBLabel();
  /**
   * The notice bar under the toolbar: the page failed to load / crashed (with Try Again), or the server hasn't
   * answered yet; hidden when there is nothing to say. UI thread only, like the state behind it.
   */
  private final JPanel notice = new JPanel(new BorderLayout(JBUI.scale(8), 0));
  private final JBLabel noticeText = new JBLabel();
  private final JPanel noticeLinks = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(12), 0));
  /** The toolbar, whose bottom edge shows the progress line ({@link #paintProgress}). */
  private @Nullable JComponent toolbarBar;
  private final ConsoleLog console = new ConsoleLog();
  /** Find in page (⌘F), between the toolbar and the page; hidden until opened. */
  private final JPanel findBar = new JPanel(new BorderLayout());
  private final JBTextField findField = new JBTextField(24);
  private final JBLabel findCount = new JBLabel();
  private final @Nullable JBCefBrowser browser;
  private @Nullable JBCefJSQuery findCountQuery;
  private @Nullable JBCefJSQuery shareQuery;
  private @Nullable JBCefJSQuery canvasQuery;
  /** Share Preview: the button it was opened from (null: from a shortcut / the sandbox), and the popover itself. */
  private @Nullable Component shareAnchor;
  private @Nullable JBPopup sharePopup;
  /** Numbers the searches, so a late match count for an older search text is ignored; UI thread only. */
  private int findSeq;

  /**
   * Showing a page's source: Chromium reports a {@code view-source:} page's address without the prefix, which
   * would put the plain URL in the address bar and reopen the page instead of its source after a restart.
   */
  private volatile boolean sourceView;
  private volatile boolean loading;
  /**
   * The progress line and the tab's spinner show at least until this time ({@link System#currentTimeMillis()}), so
   * a page that loads in a few milliseconds (a local dev server) still shows that it reloaded.
   */
  private volatile long spinUntil;
  /** When the current load started (Chromium's loading state went on). */
  private volatile long loadStartedAt;
  /** The server answered the current load: the main frame started loading the new page ({@code onLoadStart}). */
  private volatile boolean committed;
  /** The HTTP status of the page shown (0: none / not http, or the load failed). */
  private volatile int httpStatus;
  /** When Reload was clicked (see {@link #STOP_GUARD_MS}). UI thread only. */
  private long reloadClickedAt;
  /** The progress line: 0..1, or -1 when hidden; animated by {@link #progressTimer}. UI thread only. */
  private double progress = -1;
  private @Nullable Timer progressTimer;
  /** The last failed load (Chromium's error name and the URL), until a page loads again. UI thread only. */
  private @Nullable String failedError;
  private @Nullable String failedUrl;
  /** How many times in a row that same load failed: "Still can't open …" from the second time. */
  private int failedTimes;
  /** Chromium's page process ended: how ("crashed", "was stopped", "ran out of memory"), else null. UI thread only. */
  private @Nullable String crashed;
  /** Waiting for the failed page's local server to accept connections, to reload then. UI thread only. */
  private @Nullable Timer serverWait;
  private long serverWaitSince;
  private boolean probing;
  /** The user clicked Stop Waiting for this failure. */
  private boolean waitDeclined;
  /** Reloads by the server wait in a row that failed again (see {@link #MAX_AUTO_RELOADS}). */
  private int autoReloads;
  /** The toolbar with the reload button: refreshed when the loading state changes; set once in the constructor. */
  private @Nullable ActionToolbar navToolbar;
  /** The toolbar right of the address bar (optional items + ⋮); set once in the constructor. */
  private @Nullable ActionToolbar sideToolbar;
  private volatile boolean canGoBack;
  private volatile boolean canGoForward;
  /**
   * The page is a PDF in Chromium's PDF viewer (from its extension frame starting to load, see {@code onLoadStart}).
   * Find in page still works there, but the match count can't be read ({@link #runFind}).
   */
  private volatile boolean pdfPage;
  /** The page has a dev-server client that reloads it (see {@link #LIVE_RELOAD_JS}). */
  private volatile boolean pageReloadsItself;
  /** A project file was saved while this tab was in the background: reload when it is shown; UI thread only. */
  private boolean reloadWhenShown;

  BrowserEditor(@NotNull Project project, @NotNull BrowserFile file) {
    this(project, file, file.id());
  }

  /** A tab for {@code file} that keeps its state under {@code id}; {@link BrowserStore} has its URL to load. */
  BrowserEditor(@NotNull Project project, @NotNull VirtualFile file, @NotNull String id) {
    this.project = project;
    this.file = file;
    this.id = id;
    String url = url();
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
    // Not browser.setPageBackgroundColor("white"): that sets body's inline style on every load, which beats the
    // page's own CSS (a dark site got a white body). See setCanvas instead.
    setCanvas(Color.WHITE);
    CefBrowser cef = browser.getCefBrowser();
    browser.getJBCefClient().addDisplayHandler(new CefDisplayHandlerAdapter() {
      @Override
      public void onAddressChange(CefBrowser b, CefFrame frame, String newUrl) {
        if (frame != null && !frame.isMain()) return;
        String shown = sourceView && Urls.viewSource(newUrl) != null ? Urls.viewSource(newUrl) : newUrl;
        store.setUrl(id, shown);
        onEdt(() -> {
          if (!address.hasFocus()) address.setText(shown);
          refreshTab();
        });
      }

      @Override
      public void onTitleChange(CefBrowser b, String title) {
        // Chromium reports "about:blank" itself as the title; keep showing "New Tab" (Urls.label) instead
        if (!Urls.BLANK.equalsIgnoreCase(title)) store.setTitle(id, title);
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
      BrowserFavicons.fetch(id, result, this::refreshTab);
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
    shareQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
    shareQuery.addHandler(result -> {
      onEdt(() -> showSharePreview(result));
      return null;
    });
    Disposer.register(this, shareQuery);
    canvasQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
    canvasQuery.addHandler(result -> {
      int rgb = Js.parseRgb(result);
      if (rgb >= 0) onEdt(() -> setCanvas(new Color(rgb)));
      return null;
    });
    Disposer.register(this, canvasQuery);
    browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
      @Override
      public void onLoadingStateChange(CefBrowser b, boolean isLoading, boolean back, boolean forward) {
        if (isLoading && !loading) {
          long now = System.currentTimeMillis();
          spinUntil = Math.max(spinUntil, now + MIN_SPIN_MS);
          loadStartedAt = now;
          committed = false;
        }
        loading = isLoading;
        canGoBack = back;
        canGoForward = forward;
        if (!isLoading) applyZoom();
        onEdt(BrowserEditor.this::refreshLoading);
      }

      @Override
      public void onLoadStart(CefBrowser b, CefFrame frame, CefRequest.TransitionType transitionType) {
        if (frame == null) return;
        if (!frame.isMain()) {
          // Chromium's PDF viewer: an extension frame inside the page
          if (Urls.isPdfViewer(frame.getURL())) {
            pdfPage = true;
            onEdt(BrowserEditor.this::updateZoomLabel);
          }
          return;
        }
        // the server answered: a new page (Chromium's error page for a failed load doesn't get here)
        committed = true;
        httpStatus = 0;
        pdfPage = false;
        // a new page: its own console errors and dev-server client
        console.clear();
        pageReloadsItself = false;
        onEdt(() -> {
          updateErrorLabel();
          updateZoomLabel();
          pageLoaded();
        });
        // the emulation outlives navigations; set again in case Chromium started a new renderer for this page
        if (colorScheme() != ColorScheme.SYSTEM) applyColorScheme();
      }

      @Override
      public void onLoadEnd(CefBrowser b, CefFrame frame, int httpStatusCode) {
        if (frame == null || !frame.isMain()) return;
        // 0 for a failed load (onLoadError), negative (an error code) for a stopped one
        httpStatus = Math.max(0, httpStatusCode);
        onEdt(BrowserEditor.this::updateStatusLabel);
        b.executeJavaScript(faviconQuery.inject(FAVICON_JS), b.getURL(), 0);
        b.executeJavaScript(liveReloadQuery.inject(LIVE_RELOAD_JS), b.getURL(), 0);
        readCanvas();
        onEdt(() -> { if (findBar.isVisible()) runFind(true, false); });
      }

      @Override
      public void onLoadError(CefBrowser b, CefFrame frame, ErrorCode errorCode, String errorText, String failedUrl) {
        if (frame == null || !frame.isMain() || errorCode == null || !LoadErrors.shown(errorCode.name())) return;
        String error = errorCode.name();
        onEdt(() -> loadFailed(error, failedUrl));
      }
    }, cef);
    browser.getJBCefClient().addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
      @Override
      public void onAfterCreated(CefBrowser b) {
        // the DevTools protocol is there from now on (see devTools)
        if (colorScheme() != ColorScheme.SYSTEM) applyColorScheme();
      }

      @Override
      public boolean onBeforePopup(CefBrowser b, CefFrame frame, String targetUrl, String targetFrameName) {
        // target="_blank" links and window.open(): a new editor tab instead of a separate window
        if (targetUrl != null && !targetUrl.isBlank()) onEdt(() -> BrowserTabs.open(project, targetUrl, true));
        return true;
      }
    }, cef);
    browser.getJBCefClient().addRequestHandler(new CefRequestHandlerAdapter() {
      @Override
      public void onRenderProcessTerminated(CefBrowser b, TerminationStatus status, int errorCode, String errorString) {
        onEdt(() -> pageCrashed(status));
      }

      @Override
      public CefResourceRequestHandler getResourceRequestHandler(CefBrowser b, CefFrame frame, CefRequest request,
          boolean isNavigation, boolean isDownload, String requestInitiator, BoolRef disableDefaultHandling) {
        // HTTP basic auth (e.g. a staging server): without this the page just shows the site's 401
        return BasicAuth.handlerFor(project, frame, request, isNavigation, new BasicAuth.Tab() {
          @Override
          public void reload() {
            withCef(CefBrowser::reload);
          }

          @Override
          public void signInCancelled(String site, String realm) {
            // now, and again in case Chromium shows its error page a moment later (the script only acts on it)
            String script = Js.signInCancelledPage(site, realm);
            Runnable show = () -> withCef(c -> c.executeJavaScript(script, c.getURL(), 0));
            show.run();
            for (int delay : new int[] {500, 1500}) {
              Timer timer = new Timer(delay, e -> show.run());
              timer.setRepeats(false);
              timer.start();
            }
          }
        });
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
    north.add(createNotice(), BorderLayout.CENTER);
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
    store.setUrl(id, url);
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
    String url = url();
    Path page = Urls.localPath(Urls.isViewSource(url) ? url.substring(Urls.VIEW_SOURCE.length()) : url);
    boolean reload = page != null
        ? LiveReload.changesFilePage(page, saved)
        : Urls.isLocal(url) && LiveReload.shouldReload(
            saved.stream().map(p -> String.valueOf(p.getFileName())).toList(), pageReloadsItself);
    if (!reload) return;
    if (root.isShowing()) reload();
    else reloadWhenShown = true;
  }

  /**
   * Files changed on disk ({@link PdfFileWatcher}): reloads the page when it shows one of them from disk, or when
   * it is shown again if the tab is in the background.
   */
  void reloadForChangedFiles(Set<Path> changed) {
    if (browser == null) return;
    Path page = Urls.localPath(url());
    if (page == null || !changed.contains(page.toAbsolutePath().normalize())) return;
    if (root.isShowing()) reloadNow();
    else reloadWhenShown = true;
  }

  /** The page's current URL (as the address bar shows it), {@code about:blank} when unknown. */
  private @NotNull String url() {
    String url = store.url(id);
    return url != null ? url : Urls.BLANK;
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
    return store.zoom(id);
  }

  private void setZoom(int percent) {
    if (pdfPage) {
      pdfZoomHint();
      return;
    }
    store.setZoom(id, percent);
    applyZoom();
    updateZoomLabel();
    // a device width is in CSS px, so its column grows and shrinks with the zoom
    if (deviceWidth() != DeviceWidths.FULL) relayoutViewport();
  }

  private void updateZoomLabel() {
    int z = zoom();
    zoomLabel.setText(z + "%");
    zoomLabel.setVisible(z != 100 && !pdfPage);
  }

  /**
   * Chromium's PDF viewer has a zoom of its own, which Chromium's zoom level doesn't change (nor do keys sent to it):
   * the tab's zoom is left alone on a PDF, and its zoom actions point to the viewer's buttons.
   */
  private void pdfZoomHint() {
    status("Zoom a PDF with the − / + buttons in the viewer's toolbar");
  }

  private void applyZoom() {
    if (pdfPage) return;
    double level = Math.log(zoom() / 100.0) / Math.log(1.2);
    withCef(c -> c.setZoomLevel(level));
  }

  private void zoomStep(int direction) {
    if (pdfPage) {
      pdfZoomHint();
      return;
    }
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
    return store.width(id);
  }

  private void setDeviceWidth(int cssWidth) {
    store.setWidth(id, cssWidth);
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

  /**
   * Dropdown (on the toolbar) or submenu (in ⋮): Full Width and the {@link DeviceWidths#PRESETS}, the current one
   * ticked; shown only where the {@link ToolbarLayout} puts it.
   */
  private DefaultActionGroup deviceWidthGroup(Place where) {
    DefaultActionGroup group = placedGroup("Device Width", Item.DEVICE_WIDTH, where);
    group.getTemplatePresentation().setIcon(DEVICE_WIDTH_ICON);
    group.add(option("Full Width", () -> deviceWidth() == DeviceWidths.FULL, () -> setDeviceWidth(DeviceWidths.FULL)));
    group.addSeparator();
    for (DeviceWidths.Preset p : DeviceWidths.PRESETS) {
      group.add(option(p.text(), () -> deviceWidth() == p.width(), () -> setDeviceWidth(p.width())));
    }
    return group;
  }

  /** A dropdown / submenu item of the toolbar, shown only where the {@link ToolbarLayout} puts it. */
  private DefaultActionGroup placedGroup(String text, Item item, Place where) {
    return new DefaultActionGroup(text, true) {
      @Override
      public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setVisible(isPlaced(item, where));
      }

      @Override
      public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
      }
    };
  }

  /** One choice of such a dropdown, ticked while {@code selected}. */
  private static DumbAwareToggleAction option(String text, BooleanSupplier selected, Runnable select) {
    DumbAwareToggleAction option = new DumbAwareToggleAction(text) {
      @Override
      public boolean isSelected(@NotNull AnActionEvent e) {
        return selected.getAsBoolean();
      }

      @Override
      public void setSelected(@NotNull AnActionEvent e, boolean state) {
        select.run();
      }

      @Override
      public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
      }
    };
    // a toggle keeps its popup open by default; a choice is picked once, so close the menu like a plain item
    option.getTemplatePresentation().setKeepPopupOnPerform(KeepPopupOnPerform.Never);
    return option;
  }

  // --- light / dark mode (what the page sees as prefers-color-scheme)

  private ColorScheme colorScheme() {
    return store.colorScheme(id);
  }

  private void setColorScheme(ColorScheme scheme) {
    store.setColorScheme(id, scheme);
    applyColorScheme();
    updateSchemeLabel();
  }

  private void applyColorScheme() {
    // the page's canvas follows the scheme when it supports both (color-scheme: light dark)
    devTools("Emulation.setEmulatedMedia", colorScheme().emulatedMediaParams()).thenRun(this::readCanvas);
  }

  /**
   * The colour behind the page. Off-screen, Chromium leaves the canvas transparent (and ignores the DevTools
   * protocol's background override), so where a page has no background of its own the Swing component shows: set
   * to the page's CSS {@code Canvas} colour ({@link Js#CANVAS_COLOR}, read after each load and color scheme
   * change), white for most pages and dark for a page in a dark color scheme, as Chrome draws it.
   */
  private void setCanvas(Color color) {
    if (browser == null) return;
    for (Component c : new Component[] {browser.getComponent(), browser.getCefBrowser().getUIComponent()}) {
      if (c == null || color.equals(c.getBackground())) continue;
      c.setBackground(color);
      c.repaint();
    }
  }

  private void readCanvas() {
    JBCefJSQuery query = canvasQuery;
    if (query != null) withCef(c -> c.executeJavaScript(query.inject(Js.CANVAS_COLOR), c.getURL(), 0));
  }

  private void updateSchemeLabel() {
    ColorScheme scheme = colorScheme();
    schemeLabel.setText(scheme.text);
    schemeLabel.setVisible(scheme != ColorScheme.SYSTEM);
  }

  /** Dropdown (on the toolbar) or submenu (in ⋮): System, Light, Dark, the current one ticked. */
  private DefaultActionGroup colorSchemeGroup(Place where) {
    DefaultActionGroup group = placedGroup("Color Scheme", Item.COLOR_SCHEME, where);
    group.getTemplatePresentation().setIcon(COLOR_SCHEME_ICON);
    group.add(option("System", () -> colorScheme() == ColorScheme.SYSTEM, () -> setColorScheme(ColorScheme.SYSTEM)));
    group.addSeparator();
    group.add(option("Light", () -> colorScheme() == ColorScheme.LIGHT, () -> setColorScheme(ColorScheme.LIGHT)));
    group.add(option("Dark", () -> colorScheme() == ColorScheme.DARK, () -> setColorScheme(ColorScheme.DARK)));
    return group;
  }

  // --- clear site data

  /**
   * Deletes the page's site data (cookies, local / session storage, IndexedDB, service workers, Cache Storage) and
   * empties the HTTP cache, then reloads: a fresh first visit, e.g. logged out. Only for http(s) pages.
   */
  private void clearSiteData() {
    String origin = Urls.origin(url());
    if (origin == null) return;
    devTools("Storage.clearDataForOrigin", "{\"origin\":\"" + origin + "\",\"storageTypes\":\"all\"}")
        .thenCompose(r -> devTools("Network.clearBrowserCache", null))
        .whenComplete((r, error) -> onEdt(() -> {
          if (error != null) {
            status("Couldn't clear the site data of " + origin + ": " + error.getMessage());
            return;
          }
          spinUntil = System.currentTimeMillis() + MIN_SPIN_MS;
          withCef(CefBrowser::reloadIgnoreCache);
          refreshLoading();
          status("Cleared the cookies, storage and cache of " + origin);
        }));
  }

  private void status(String text) {
    if (!project.isDisposed()) StatusBar.Info.set(text, project);
  }

  /**
   * Runs a DevTools protocol method on this tab's page; fails while Chromium hasn't created the browser yet (see
   * {@code onAfterCreated}).
   */
  private CompletableFuture<String> devTools(String method, @Nullable String paramsJson) {
    CefDevToolsClient client = browser == null || browser.isDisposed() ? null : browser.getCefBrowser().getDevToolsClient();
    if (client == null) return CompletableFuture.failedFuture(new IllegalStateException("the page isn't ready"));
    return paramsJson == null ? client.executeDevToolsMethod(method) : client.executeDevToolsMethod(method, paramsJson);
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
    if (pdfPage) {
      // the viewer's text isn't in the page's DOM, so no count; Chromium still highlights and steps
      findCount.setText("");
      return;
    }
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

  // --- share preview (the link card chat / social apps build from the page's og: and twitter: meta tags)

  /** Reads the page's meta tags; the popover opens when they come back ({@link #showSharePreview}). */
  private void openSharePreview() {
    JBCefJSQuery query = shareQuery;
    if (query == null) return;
    withCef(c -> c.executeJavaScript(query.inject(SharePreview.SCRIPT), c.getURL(), 0));
  }

  private void showSharePreview(String result) {
    if (browser == null || browser.isDisposed()) return;
    if (sharePopup != null && !sharePopup.isDisposed()) sharePopup.cancel();
    JBPopup popup = SharePreviewPopup.create(SharePreview.card(SharePreview.parse(result)));
    sharePopup = popup;
    Disposer.register(this, popup);
    int width = popup.getContent().getPreferredSize().width;
    Component anchor = shareAnchor;
    if (anchor != null && anchor.isShowing()) {
      // right-aligned under the button: it sits towards the right end of the toolbar
      popup.show(new RelativePoint(anchor, new Point(anchor.getWidth() - width, anchor.getHeight())));
    } else if (root.isShowing()) {
      // under the toolbar, at the right, where the button / ⋮ is
      int y = viewport.isShowing() ? viewport.getY() : 0;
      popup.show(new RelativePoint(root, new Point(Math.max(0, root.getWidth() - width - JBUI.scale(8)), y)));
    }
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
        optional(Item.NEW_TAB, Place.TOOLBAR));
    navToolbar = toolbar(left);
    DefaultActionGroup right = new DefaultActionGroup();
    right.add(deviceWidthGroup(Place.TOOLBAR));
    right.add(colorSchemeGroup(Place.TOOLBAR));
    for (Item item : Item.values()) {
      if (isGroupOrNewTab(item)) continue;
      right.addAll(optionals(item, Place.TOOLBAR));
    }
    right.add(moreGroup());
    sideToolbar = toolbar(right);

    address.getEmptyText().setText("Enter a URL, e.g. localhost:3000");
    address.addKeyListener(new KeyAdapter() {
      @Override
      public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ENTER) {
          navigate(address.getText());
          e.consume();
        } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
          address.setText(url());
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
    schemeLabel.setToolTipText("Pages see this color scheme (prefers-color-scheme); click for the system's");
    schemeLabel.setForeground(JBColor.GRAY);
    schemeLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    schemeLabel.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        setColorScheme(ColorScheme.SYSTEM);
      }
    });
    updateSchemeLabel();
    errorLabel.setIcon(AllIcons.General.Error);
    errorLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    errorLabel.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        if (browser != null) browser.openDevtools();
      }
    });
    updateErrorLabel();
    statusLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    statusLabel.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        if (browser != null) browser.openDevtools();
      }
    });
    statusLabel.setVisible(false);
    JPanel labels = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0));
    labels.setOpaque(false);
    labels.add(statusLabel);
    labels.add(errorLabel);
    labels.add(schemeLabel);
    labels.add(widthLabel);
    labels.add(zoomLabel);
    JPanel east = new JPanel(new BorderLayout(JBUI.scale(2), 0));
    east.setOpaque(false);
    east.add(labels, BorderLayout.WEST);
    east.add(sideToolbar.getComponent(), BorderLayout.CENTER);

    JPanel bar = new JPanel(new BorderLayout(JBUI.scale(4), 0)) {
      @Override
      protected void paintChildren(Graphics g) {
        super.paintChildren(g);
        paintProgress(g, this);
      }
    };
    toolbarBar = bar;
    bar.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(2, 4)));
    bar.add(navToolbar.getComponent(), BorderLayout.WEST);
    bar.add(address, BorderLayout.CENTER);
    bar.add(east, BorderLayout.EAST);
    return bar;
  }

  /**
   * The ⋮ menu: the items the {@link ToolbarLayout} puts there, and Customize Toolbar… (always there, so the
   * settings are one click away however the items are placed).
   */
  private DefaultActionGroup moreGroup() {
    DefaultActionGroup more = new DefaultActionGroup("More", true);
    more.getTemplatePresentation().setIcon(AllIcons.Actions.More);
    more.add(optional(Item.NEW_TAB, Place.MENU));
    more.addSeparator();
    more.add(deviceWidthGroup(Place.MENU));
    more.add(colorSchemeGroup(Place.MENU));
    for (Item item : Item.values()) {
      if (isGroupOrNewTab(item)) continue;
      more.addAll(optionals(item, Place.MENU));
      if (item == Item.ZOOM) more.addSeparator();
    }
    more.add(Separator.getInstance());
    more.add(action("Customize Toolbar…", null, () -> true,
        () -> ShowSettingsUtil.getInstance().showSettingsDialog(project, BrowserConfigurable.class)));
    return more;
  }

  /** New Tab sits left of the address bar; Device Width and Color Scheme are dropdowns, added first. */
  private static boolean isGroupOrNewTab(Item item) {
    return item == Item.NEW_TAB || item == Item.DEVICE_WIDTH || item == Item.COLOR_SCHEME;
  }

  private boolean isPlaced(Item item, Place where) {
    return store.toolbarLayout().place(item) == where;
  }

  private DumbAwareAction optional(Item item, Place where) {
    List<DumbAwareAction> actions = optionals(item, where);
    if (actions.size() != 1) throw new IllegalArgumentException(item + " has " + actions.size() + " actions");
    return actions.get(0);
  }

  /**
   * The action(s) of an optional toolbar item, shown only while the {@link ToolbarLayout} puts the item at
   * {@code where}. Their shortcuts (registered in {@link #registerShortcuts}) work wherever the item is; the
   * shortcut set here is only for the tooltip / menu text.
   */
  private List<DumbAwareAction> optionals(Item item, Place where) {
    String mod = SystemInfo.isMac ? "meta" : "control";
    BooleanSupplier shown = () -> isPlaced(item, where);
    Runnable devTools = () -> { if (browser != null) browser.openDevtools(); };
    return switch (item) {
      case NEW_TAB -> {
        DumbAwareAction a = action("New Browser Tab", AllIcons.General.Add, shown, () -> true,
            () -> BrowserTabs.openBlank(project));
        a.copyShortcutFrom(ActionManager.getInstance().getAction("EditorBrowser.NewTab"));
        yield List.of(a);
      }
      case ZOOM -> where == Place.MENU
          ? List.of(
              withKeys(action("Zoom In", AllIcons.General.ZoomIn, shown, this::canZoomIn, () -> zoomStep(1)), mod + " EQUALS"),
              withKeys(action("Zoom Out", AllIcons.General.ZoomOut, shown, this::canZoomOut, () -> zoomStep(-1)), mod + " MINUS"),
              withKeys(action("Actual Size", AllIcons.General.ActualZoom, shown, () -> zoom() != 100 && !pdfPage, () -> setZoom(100)), mod + " 0"))
          : List.of(
              withKeys(action("Zoom Out", AllIcons.General.ZoomOut, shown, this::canZoomOut, () -> zoomStep(-1)), mod + " MINUS"),
              withKeys(action("Zoom In", AllIcons.General.ZoomIn, shown, this::canZoomIn, () -> zoomStep(1)), mod + " EQUALS"));
      case FIND -> List.of(withKeys(
          action("Find in Page", AllIcons.Actions.Find, shown, () -> browser != null, this::openFind), mod + " F"));
      case SHARE_PREVIEW -> List.of(new DumbAwareAction("Share Preview",
          "How a link to this page looks in chat and social apps", AllIcons.Actions.Share) {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
          // from the toolbar the popover opens under the button; from ⋮ (its list is gone by then) at the right
          shareAnchor = e.getInputEvent() == null ? null : e.getInputEvent().getComponent();
          openSharePreview();
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
          boolean visible = shown.getAsBoolean();
          e.getPresentation().setVisible(visible);
          e.getPresentation().setEnabled(visible && browser != null && !Urls.BLANK.equalsIgnoreCase(url()));
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
          return ActionUpdateThread.EDT;
        }
      });
      case CLEAR_SITE_DATA -> {
        DumbAwareAction a = action("Clear Site Data", AllIcons.Actions.ClearCash, shown,
            () -> browser != null && Urls.origin(url()) != null, this::clearSiteData);
        a.getTemplatePresentation().setDescription(
            "Deletes this site's cookies and storage, empties the browser cache and reloads");
        yield List.of(a);
      }
      case OPEN_EXTERNAL -> List.of(action("Open in Default Browser", AllIcons.Ide.External_link_arrow, shown, () -> true,
          () -> BrowserUtil.browse(url())));
      case DEVTOOLS -> List.of(withKeys(
          action("Open DevTools", AllIcons.Toolwindows.ToolWindowInspection, shown, () -> browser != null, devTools), "F12"));
      case DEVICE_WIDTH, COLOR_SCHEME -> throw new IllegalArgumentException("a group, see deviceWidthGroup / colorSchemeGroup");
    };
  }

  private boolean canZoomIn() {
    return !pdfPage && zoom() < ZOOM_STEPS[ZOOM_STEPS.length - 1];
  }

  private boolean canZoomOut() {
    return !pdfPage && zoom() > ZOOM_STEPS[0];
  }

  /** Shows the keystroke in the tooltip / menu; no component, so this doesn't bind it (see registerShortcuts). */
  private static DumbAwareAction withKeys(DumbAwareAction action, String keystroke) {
    action.registerCustomShortcutSet(CustomShortcutSet.fromString(keystroke), null);
    return action;
  }

  /** The toolbar layout changed in the settings: show / hide the items now rather than on the next periodic update. */
  public void toolbarLayoutChanged() {
    if (navToolbar != null) navToolbar.updateActionsAsync();
    if (sideToolbar != null) sideToolbar.updateActionsAsync();
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
    shortcut(mod + " R", this::userReload);
    shortcut("F5", this::userReload);
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

  private static DumbAwareAction action(String text, @Nullable Icon icon, BooleanSupplier enabled, Runnable run) {
    return action(text, icon, () -> true, enabled, run);
  }

  private static DumbAwareAction action(String text, @Nullable Icon icon, BooleanSupplier visible, BooleanSupplier enabled,
      Runnable run) {
    return new DumbAwareAction(text, null, icon) {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        run.run();
      }

      @Override
      public void update(@NotNull AnActionEvent e) {
        boolean shown = visible.getAsBoolean();
        e.getPresentation().setVisible(shown);
        e.getPresentation().setEnabled(shown && enabled.getAsBoolean());
      }

      @Override
      public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
      }
    };
  }

  // --- loading state: progress line, tab icon, notice bar

  /** True while the progress line and the tab's spinner show: loading, or within {@link #MIN_SPIN_MS} of a load start. */
  private boolean spinning() {
    return loading || System.currentTimeMillis() < spinUntil;
  }

  /**
   * The loading state changed: updates the Reload / Stop button now (the toolbar's own refresh is periodic and would
   * miss a quick load), the tab icon, and runs the progress line while loading. UI thread only.
   */
  private void refreshLoading() {
    if (navToolbar == null || browser == null || browser.isDisposed()) return;
    navToolbar.updateActionsAsync();
    updateTabStatus();
    if (spinning() || progress >= 0) startProgress();
  }

  private void startProgress() {
    if (progressTimer != null) return;
    progressTimer = new Timer(30, e -> tickProgress());
    progressTimer.start();
  }

  /**
   * One frame of the progress line. Chromium doesn't report how far a load is, so it creeps up like a browser's:
   * towards 30% while waiting for the server, 90% once the page is coming in, then runs to the end and fades out.
   */
  private void tickProgress() {
    long now = System.currentTimeMillis();
    if (spinning()) {
      if (progress < 0) progress = 0.02;
      double target = committed ? 0.9 : 0.3;
      if (progress < target) progress += Math.max(0.0015, (target - progress) * 0.04);
      if (loading && !committed && now - loadStartedAt > WAITING_NOTICE_MS && failedError == null && crashed == null) {
        showWaitingNotice((now - loadStartedAt) / 1000);
      }
    } else if (progress >= 0) {
      progress = progress >= 1 ? -1 : Math.min(1, progress + 0.2);
    }
    if (!loading && "waiting".equals(notice.getName())) hideNotice();
    if (toolbarBar != null) {
      int h = JBUI.scale(2);
      toolbarBar.repaint(0, toolbarBar.getHeight() - h, toolbarBar.getWidth(), h);
    }
    if (progress < 0 && !spinning()) {
      if (progressTimer != null) progressTimer.stop();
      progressTimer = null;
      // the minimum spin time is over: the tab icon and the button follow
      updateTabStatus();
      if (navToolbar != null) navToolbar.updateActionsAsync();
    }
  }

  /** The progress line, over the toolbar's bottom border. */
  private void paintProgress(Graphics g, JComponent bar) {
    if (progress < 0) return;
    int h = JBUI.scale(2);
    g.setColor(JBColor.namedColor("ProgressBar.progressColor", new JBColor(0x3574F0, 0x548AF7)));
    g.fillRect(0, bar.getHeight() - h, (int) Math.round(bar.getWidth() * Math.min(1, progress)), h);
  }

  /** The tab icon: a spinner while loading, an error icon for a failed / crashed page or a server error. */
  private void updateTabStatus() {
    BrowserFavicons.Status status = spinning() ? BrowserFavicons.Status.LOADING
        : failedError != null || crashed != null || httpStatus >= 500 ? BrowserFavicons.Status.FAILED
        : BrowserFavicons.Status.NORMAL;
    if (BrowserFavicons.setStatus(id, status)) refreshTab();
  }

  private void updateStatusLabel() {
    int code = httpStatus;
    boolean shown = LoadErrors.isHttpError(code);
    statusLabel.setVisible(shown);
    if (shown) {
      statusLabel.setText(Integer.toString(code));
      statusLabel.setIcon(code >= 500 ? AllIcons.General.Error : AllIcons.General.Warning);
      statusLabel.setToolTipText("<html>The server answered <b>" + StringUtil.escapeXmlEntities(LoadErrors.httpStatus(code))
          + "</b>; click to open DevTools</html>");
    }
    updateTabStatus();
  }

  /** A page loaded (the main frame got an answer): whatever failed before is over. UI thread only. */
  private void pageLoaded() {
    failedError = null;
    failedUrl = null;
    failedTimes = 0;
    crashed = null;
    autoReloads = 0;
    waitDeclined = false;
    stopWaitingForServer();
    hideNotice();
    updateStatusLabel();
  }

  /** The main frame failed to load {@code url} (Chromium shows its error page). UI thread only. */
  private void loadFailed(String error, String url) {
    if (browser == null || browser.isDisposed()) return;
    boolean again = error.equals(failedError) && url.equals(failedUrl);
    failedTimes = again ? failedTimes + 1 : 1;
    if (!again) {
      autoReloads = 0;
      waitDeclined = false;
    }
    failedError = error;
    failedUrl = url;
    httpStatus = 0;
    stopWaitingForServer();
    if (LoadErrors.waitsForServer(error, url) && !waitDeclined && autoReloads < MAX_AUTO_RELOADS) {
      startWaitingForServer();
    }
    showFailureNotice();
    updateStatusLabel();
  }

  private void showFailureNotice() {
    String url = failedUrl;
    String error = failedError;
    if (url == null || error == null) return;
    String title = (failedTimes > 1 ? "Still can't open " + LoadErrors.site(url) : LoadErrors.title(url));
    String text = LoadErrors.describe(error, url);
    if (serverWait != null) text += " The page reloads when it is up.";
    else if (LoadErrors.waitsForServer(error, url) && autoReloads >= MAX_AUTO_RELOADS) {
      text += " It accepts connections but the page still doesn't load.";
    }
    List<Runnable> actions = new java.util.ArrayList<>();
    List<String> labels = new java.util.ArrayList<>();
    labels.add("Try Again");
    actions.add(this::retry);
    if (serverWait != null) {
      labels.add("Stop Waiting");
      actions.add(() -> {
        waitDeclined = true;
        stopWaitingForServer();
        showFailureNotice();
      });
    }
    showNotice("failed", JBUI.CurrentTheme.Banner.ERROR_BACKGROUND, JBUI.CurrentTheme.Banner.ERROR_BORDER_COLOR,
        serverWait != null ? AnimatedIcon.Default.INSTANCE : AllIcons.General.Error,
        title, text + " (" + error + ")", labels, actions);
  }

  private void showWaitingNotice(long seconds) {
    String text = "No answer yet" + (seconds >= 5 ? " after " + seconds + " s" : "") + "."
        + (Urls.isLocal(url()) ? " Paused at a breakpoint, or a slow request?" : "");
    showNotice("waiting", JBUI.CurrentTheme.Banner.INFO_BACKGROUND, JBUI.CurrentTheme.Banner.INFO_BORDER_COLOR,
        AnimatedIcon.Default.INSTANCE, "Waiting for " + LoadErrors.site(currentLoadUrl()), text,
        List.of("Stop"), List.of(this::stopLoading));
  }

  /** The URL being loaded: what the address bar shows unless the user is typing there, else the tab's URL. */
  private String currentLoadUrl() {
    String cef = browser == null ? null : browser.getCefBrowser().getURL();
    return cef != null && !cef.isBlank() ? cef : url();
  }

  /** Chromium's page process ended: the page is gone (blank or a sad face) until it is reloaded. UI thread only. */
  private void pageCrashed(TerminationStatus status) {
    if (browser == null || browser.isDisposed()) return;
    String why = switch (status) {
      case TS_PROCESS_OOM -> "ran out of memory";
      case TS_PROCESS_WAS_KILLED -> "was stopped";
      default -> "crashed";
    };
    crashed = why;
    stopWaitingForServer();
    showNotice("crashed", JBUI.CurrentTheme.Banner.ERROR_BACKGROUND, JBUI.CurrentTheme.Banner.ERROR_BORDER_COLOR,
        AllIcons.General.Error, "The page " + why, "Chromium's process for this page ended: reload to show it again.",
        List.of("Reload"), List.of(this::retry));
    updateTabStatus();
  }

  /** Try Again in the notice bar: loads the page again, and lets the wait for its server start over. */
  private void retry() {
    autoReloads = 0;
    waitDeclined = false;
    stopWaitingForServer();
    reloadNow();
  }

  /** Reloads with immediate feedback (Chromium reports the load start a moment later). */
  private void reloadNow() {
    spinUntil = System.currentTimeMillis() + MIN_SPIN_MS;
    String url = crashed != null ? currentLoadUrl() : null;
    // after a crash a plain reload may find no page to reload: load the URL again
    if (url != null && browser != null) browser.getCefBrowser().loadURL(url);
    else withCef(CefBrowser::reload);
    refreshLoading();
  }

  /** Reload by the user (button, ⌘R / F5): after a failure also a fresh start for the wait for the server. */
  private void userReload() {
    if (failedError != null || crashed != null) retry();
    else reloadNow();
  }

  private void stopLoading() {
    withCef(CefBrowser::stopLoad);
    spinUntil = 0;
    refreshLoading();
  }

  /**
   * The local server refused the connection: checks every second whether it accepts connections now (a dev server
   * starting or restarting), and reloads the page when it does. Gives up after {@link #WAIT_FOR_SERVER_MS}.
   */
  private void startWaitingForServer() {
    if (serverWait != null) return;
    serverWaitSince = System.currentTimeMillis();
    serverWait = new Timer(1000, e -> probeServer());
    serverWait.start();
  }

  private void stopWaitingForServer() {
    if (serverWait != null) serverWait.stop();
    serverWait = null;
  }

  private void probeServer() {
    String url = failedUrl;
    String host = url == null ? null : LoadErrors.hostOf(url);
    int port = url == null ? -1 : LoadErrors.portOf(url);
    if (host == null || port < 0 || browser == null || browser.isDisposed()) {
      stopWaitingForServer();
      return;
    }
    if (System.currentTimeMillis() - serverWaitSince > WAIT_FOR_SERVER_MS) {
      stopWaitingForServer();
      showFailureNotice();
      return;
    }
    if (probing) return;
    probing = true;
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      boolean up = ServerProbe.accepts(host, port);
      onEdt(() -> {
        probing = false;
        if (!up || serverWait == null || !url.equals(failedUrl)) return;
        stopWaitingForServer();
        autoReloads++;
        reloadNow();
      });
    });
  }

  private JComponent createNotice() {
    notice.setBorder(JBUI.Borders.empty(5, 10));
    noticeText.setIconTextGap(JBUI.scale(8));
    noticeLinks.setOpaque(false);
    notice.add(noticeText, BorderLayout.CENTER);
    notice.add(noticeLinks, BorderLayout.EAST);
    notice.setVisible(false);
    return notice;
  }

  /**
   * Shows the notice bar: {@code kind} names what it says (for {@link #hideNotice} by kind and the sandbox's
   * checks), a bold title, a sentence, and links. UI thread only.
   */
  private void showNotice(String kind, Color background, Color border, Icon icon, String title, String text,
      List<String> labels, List<Runnable> actions) {
    String html = "<html><b>" + StringUtil.escapeXmlEntities(title) + "</b>&nbsp;&nbsp;"
        + StringUtil.escapeXmlEntities(text) + "</html>";
    boolean same = kind.equals(notice.getName()) && html.equals(noticeText.getText()) && notice.isVisible();
    notice.setName(kind);
    notice.setBackground(background);
    notice.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(border), JBUI.Borders.empty(5, 10)));
    noticeText.setIcon(icon);
    noticeText.setText(html);
    noticeText.setToolTipText(title + ". " + text);
    if (same) return;
    noticeLinks.removeAll();
    for (int i = 0; i < labels.size(); i++) {
      Runnable action = actions.get(i);
      ActionLink link = new ActionLink(labels.get(i));
      link.addActionListener(e -> action.run());
      noticeLinks.add(link);
    }
    notice.setVisible(true);
    notice.revalidate();
    notice.repaint();
  }

  private void hideNotice() {
    if (!notice.isVisible()) return;
    notice.setVisible(false);
    notice.setName(null);
    noticeLinks.removeAll();
    notice.revalidate();
  }

  /**
   * Reload, or Stop (an X) while the page loads, as in a browser. A second click right after Reload reloads again
   * instead of stopping (a double click shouldn't cancel the load it started).
   */
  private final class ReloadAction extends DumbAwareAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      long now = System.currentTimeMillis();
      if (loading && now - reloadClickedAt > STOP_GUARD_MS) {
        stopLoading();
      } else {
        reloadClickedAt = now;
        userReload();
      }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      boolean stop = loading;
      e.getPresentation().setText(stop ? "Stop" : "Reload");
      e.getPresentation().setIcon(stop ? AllIcons.Actions.Close : AllIcons.Actions.Refresh);
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
    if (browser == null || Urls.BLANK.equalsIgnoreCase(url())) return address;
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
  public void deselectNotify() {
    // another tab: the popover is about this page
    if (sharePopup != null && !sharePopup.isDisposed()) sharePopup.cancel();
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
    stopWaitingForServer();
    if (progressTimer != null) progressTimer.stop();
    progressTimer = null;
    BrowserFavicons.setStatus(id, BrowserFavicons.Status.NORMAL);
  }
}
