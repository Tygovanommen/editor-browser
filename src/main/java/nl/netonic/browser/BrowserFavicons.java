package nl.netonic.browser;

import com.intellij.openapi.application.ApplicationManager;
import java.awt.Image;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import org.jetbrains.annotations.Nullable;

/**
 * In-memory favicon cache, keyed by tab id. Not persisted: favicons are re-fetched (from the browser's own page,
 * via {@link BrowserEditor}) each time a tab reloads or PhpStorm restarts.
 */
final class BrowserFavicons {
  private static final int SIZE = 16;
  private static final Map<String, Icon> icons = new ConcurrentHashMap<>();
  private static final Map<String, String> fetchedUrls = new ConcurrentHashMap<>();

  private BrowserFavicons() {}

  static @Nullable Icon get(String id) {
    return icons.get(id);
  }

  /** Downloads the favicon at {@code url} on a background thread and runs {@code onFetched} on the EDT once cached. */
  static void fetch(String id, @Nullable String url, Runnable onFetched) {
    if (url == null || url.isBlank()) return;
    if (url.equals(fetchedUrls.put(id, url))) return;
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      Icon icon = download(url);
      if (icon != null) {
        icons.put(id, icon);
        ApplicationManager.getApplication().invokeLater(onFetched);
      }
    });
  }

  private static @Nullable Icon download(String url) {
    try {
      URI uri = new URI(url);
      String scheme = uri.getScheme();
      if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;
      URLConnection connection = uri.toURL().openConnection();
      connection.setConnectTimeout(5000);
      connection.setReadTimeout(5000);
      try (InputStream in = connection.getInputStream()) {
        java.awt.image.BufferedImage image = ImageIO.read(in);
        if (image == null) return null;
        return new ImageIcon(image.getScaledInstance(SIZE, SIZE, Image.SCALE_SMOOTH));
      }
    } catch (Exception e) {
      return null;
    }
  }
}
