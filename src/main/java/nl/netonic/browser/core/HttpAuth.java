package nl.netonic.browser.core;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HTTP basic authentication: reading a 401's challenge and writing the answer. Pure JDK, see CoreTests. */
public final class HttpAuth {
  /** A {@code Basic} challenge: at the start or after a comma (several challenges can share the header). */
  private static final Pattern BASIC = Pattern.compile("(?:^|,)\\s*basic(?:\\s+|$|,)", Pattern.CASE_INSENSITIVE);
  private static final Pattern REALM = Pattern.compile("^\\s*realm\\s*=\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^\\s,]+))",
      Pattern.CASE_INSENSITIVE);

  private HttpAuth() {}

  /**
   * The realm of the {@code Basic} challenge in a {@code WWW-Authenticate} header ({@code ""} when it names
   * none), or null when the header has no Basic challenge (e.g. only Digest or Negotiate, or no header).
   */
  public static String basicRealm(String wwwAuthenticate) {
    if (wwwAuthenticate == null) return null;
    Matcher m = BASIC.matcher(wwwAuthenticate);
    if (!m.find()) return null;
    Matcher realm = REALM.matcher(wwwAuthenticate.substring(m.end()));
    if (!realm.find()) return "";
    return realm.group(1) != null ? realm.group(1).replaceAll("\\\\(.)", "$1") : realm.group(2);
  }

  /** The {@code Authorization} header value for a username and password (UTF-8, as browsers send it). */
  public static String basicHeader(String user, String password) {
    String pair = (user == null ? "" : user) + ":" + (password == null ? "" : password);
    return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
  }

  /** True for an {@code Authorization} value of the Basic scheme. */
  public static boolean isBasic(String authorization) {
    return authorization != null && authorization.strip().toLowerCase(Locale.ROOT).startsWith("basic ");
  }
}
