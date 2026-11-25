package nl.netonic.browser.core;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/**
 * What a failed or unusual page load means, in words: Chromium's network error names ({@code ERR_CONNECTION_REFUSED},
 * from {@code CefLoadHandler.ErrorCode.name()}) and HTTP statuses. Pure JDK, so the names come in as strings.
 */
public final class LoadErrors {
  private LoadErrors() {}

  private static final Map<Integer, String> REASONS = Map.ofEntries(
      Map.entry(400, "Bad Request"), Map.entry(401, "Unauthorized"), Map.entry(402, "Payment Required"),
      Map.entry(403, "Forbidden"), Map.entry(404, "Not Found"), Map.entry(405, "Method Not Allowed"),
      Map.entry(406, "Not Acceptable"), Map.entry(408, "Request Timeout"), Map.entry(409, "Conflict"),
      Map.entry(410, "Gone"), Map.entry(413, "Content Too Large"), Map.entry(415, "Unsupported Media Type"),
      Map.entry(419, "Page Expired"), Map.entry(422, "Unprocessable Content"), Map.entry(429, "Too Many Requests"),
      Map.entry(500, "Internal Server Error"), Map.entry(501, "Not Implemented"), Map.entry(502, "Bad Gateway"),
      Map.entry(503, "Service Unavailable"), Map.entry(504, "Gateway Timeout"));

  /**
   * True when a main-frame load error is worth showing: not {@code ERR_ABORTED} (Stop, a new navigation before the
   * old one finished, a download) and not {@code ERR_INVALID_AUTH_CREDENTIALS} (a cancelled Sign In dialog, which
   * {@code BasicAuth} explains on the page itself).
   */
  public static boolean shown(String error) {
    return error != null && !error.isBlank()
        && !error.equals("ERR_NONE") && !error.equals("ERR_ABORTED") && !error.equals("ERR_INVALID_AUTH_CREDENTIALS");
  }

  /**
   * True when the tab can wait for the server to come up and reload by itself: a local address ({@link Urls#isLocal})
   * where nothing listens yet, e.g. a dev server that is (re)starting.
   */
  public static boolean waitsForServer(String error, String url) {
    return "ERR_CONNECTION_REFUSED".equals(error) && Urls.isLocal(url);
  }

  /** One sentence on why {@code url} didn't load, ending with a hint where one helps; the error name is not in it. */
  public static String describe(String error, String url) {
    String site = site(url);
    boolean local = Urls.isLocal(url);
    String e = error == null ? "" : error;
    if (e.equals("ERR_CONNECTION_REFUSED")) {
      return "Nothing is answering on " + site + "." + (local ? " Is the server running?" : "");
    }
    if (e.equals("ERR_NAME_NOT_RESOLVED") || e.equals("ERR_NAME_RESOLUTION_FAILED")) {
      return "Can't find " + hostOf(url) + (local ? ": is it in /etc/hosts or your local DNS?" : ": check the address.");
    }
    if (e.equals("ERR_INTERNET_DISCONNECTED")) return "No internet connection.";
    if (e.equals("ERR_TIMED_OUT") || e.equals("ERR_CONNECTION_TIMED_OUT")) return site + " took too long to answer.";
    if (e.equals("ERR_EMPTY_RESPONSE")) {
      return site + " closed the connection without an answer." + (local ? " Did the server crash? Check its log." : "");
    }
    if (e.equals("ERR_CONNECTION_RESET") || e.equals("ERR_CONNECTION_CLOSED") || e.equals("ERR_CONNECTION_ABORTED")
        || e.equals("ERR_CONNECTION_FAILED")) {
      return "The connection to " + site + " broke off.";
    }
    if (e.equals("ERR_SSL_PROTOCOL_ERROR") || e.equals("ERR_SSL_VERSION_OR_CIPHER_MISMATCH")
        || e.equals("ERR_WRONG_VERSION_ON_EARLY_DATA")) {
      return site + " can't make a secure connection." + (local && isHttps(url) ? " Does it only speak http://?" : "");
    }
    if (e.startsWith("ERR_CERT_")) return site + "'s certificate isn't trusted.";
    if (e.equals("ERR_TOO_MANY_REDIRECTS")) return site + " redirects in a loop. Clearing its cookies may help.";
    if (e.equals("ERR_ADDRESS_UNREACHABLE") || e.equals("ERR_ADDRESS_INVALID")) return "Can't reach " + site + ".";
    if (e.equals("ERR_UNSAFE_PORT")) return "Chromium blocks port " + portOf(url) + ": use another port.";
    if (e.equals("ERR_FILE_NOT_FOUND")) return "The file doesn't exist.";
    if (e.equals("ERR_INVALID_RESPONSE") || e.equals("ERR_INVALID_HTTP_RESPONSE")) {
      return site + " sent an answer Chromium can't read.";
    }
    if (e.equals("ERR_PROXY_CONNECTION_FAILED") || e.equals("ERR_TUNNEL_CONNECTION_FAILED")) {
      return "Can't reach the proxy server.";
    }
    return "The page couldn't load.";
  }

  /** The banner title for a failed load. */
  public static String title(String url) {
    return "Can't open " + site(url);
  }

  /** {@code "404 Not Found"}, or just the number for a status without a well-known reason. */
  public static String httpStatus(int status) {
    String reason = REASONS.get(status);
    return reason == null ? Integer.toString(status) : status + " " + reason;
  }

  /** True for a status worth a badge: the server answered with an error (4xx, 5xx). */
  public static boolean isHttpError(int status) {
    return status >= 400 && status < 600;
  }

  /** {@code host:port} as shown to the user (the port only when the URL has one), or the URL itself. */
  public static String site(String url) {
    try {
      URI uri = URI.create(url == null ? "" : url.strip());
      String host = uri.getHost();
      if (host == null) return url == null || url.isBlank() ? "the page" : Urls.shorten(url, 60);
      return host.toLowerCase(Locale.ROOT) + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
    } catch (IllegalArgumentException e) {
      return Urls.shorten(url, 60);
    }
  }

  /** The host to probe for {@link #waitsForServer} (brackets of an IPv6 literal removed), or null. */
  public static String hostOf(String url) {
    try {
      String host = URI.create(url.strip()).getHost();
      if (host == null) return null;
      return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    } catch (IllegalArgumentException | NullPointerException e) {
      return null;
    }
  }

  /** The port {@code url} connects to (its scheme's default when it has none), or -1. */
  public static int portOf(String url) {
    try {
      URI uri = URI.create(url.strip());
      if (uri.getPort() >= 0) return uri.getPort();
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      return scheme.equals("https") ? 443 : scheme.equals("http") ? 80 : -1;
    } catch (IllegalArgumentException | NullPointerException e) {
      return -1;
    }
  }

  private static boolean isHttps(String url) {
    return url != null && url.strip().regionMatches(true, 0, "https://", 0, 8);
  }
}
