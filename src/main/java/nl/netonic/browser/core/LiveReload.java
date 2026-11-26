package nl.netonic.browser.core;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/**
 * Which saved files reload a local browser tab. A page whose own dev server already reloads it (Vite, webpack
 * HMR, LiveReload, Browsersync) handles its front-end files itself; a full reload on top would throw away the
 * state HMR keeps. Server-side files (PHP, Twig, Blade, …) still reload it: a front-end dev server doesn't watch
 * those unless it was set up to. Pure JDK, see CoreTests.
 */
public final class LiveReload {
  private static final Set<String> FRONT_END = Set.of(
      "js", "mjs", "cjs", "ts", "mts", "cts", "jsx", "tsx", "vue", "svelte", "astro",
      "css", "scss", "sass", "less", "styl", "pcss", "postcss", "html", "htm", "json");

  private LiveReload() {}

  /** True for a front-end file a dev server with hot reload picks up by itself ({@code app.ts}, {@code site.scss}). */
  public static boolean handledByDevServer(String fileName) {
    if (fileName == null) return false;
    int dot = fileName.lastIndexOf('.');
    return dot > 0 && FRONT_END.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
  }

  /**
   * True when a saved file can change a page shown from disk ({@code file:///…/index.html}): it is in the page's
   * folder or below (the page itself, its CSS, JS, images). Never for a PDF: no file next to it changes it, and
   * the PDF itself isn't saved from an editor (a PDF that changes on disk reloads by {@code PdfFileWatcher}).
   */
  public static boolean changesFilePage(Path page, Collection<Path> saved) {
    if (page == null || saved == null) return false;
    if (page.getFileName() != null && Urls.isPdfFile(page.getFileName().toString())) return false;
    Path folder = page.toAbsolutePath().normalize().getParent();
    if (folder == null) return false;
    for (Path p : saved) if (p != null && p.toAbsolutePath().normalize().startsWith(folder)) return true;
    return false;
  }

  /**
   * True when saving {@code savedFileNames} should reload a page: always when the page doesn't reload itself,
   * otherwise only when one of them is a file its dev server doesn't handle.
   */
  public static boolean shouldReload(Collection<String> savedFileNames, boolean pageReloadsItself) {
    if (savedFileNames == null || savedFileNames.isEmpty()) return false;
    if (!pageReloadsItself) return true;
    for (String name : savedFileNames) if (!handledByDevServer(name)) return true;
    return false;
  }
}
