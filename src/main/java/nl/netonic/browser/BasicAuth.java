package nl.netonic.browser;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.JComponent;
import nl.netonic.browser.core.HttpAuth;
import nl.netonic.browser.core.Urls;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;
import org.jetbrains.annotations.Nullable;

/**
 * HTTP basic authentication, e.g. a staging server behind a password: a page answered with a 401 and a
 * {@code WWW-Authenticate: Basic} challenge gets a Sign In dialog; the login is then added (as an
 * {@code Authorization} header) to every request to that origin for the rest of the session, optionally
 * remembered in the IDE's password store for later sessions.
 * <p>
 * Done on the requests rather than through {@code CefRequestHandler.getAuthCredentials}: JCEF in its default
 * out-of-process mode never calls that. Basic only, no Digest. Proxy logins are left to the IDE.
 */
final class BasicAuth {
  private static final String SERVICE = "Editor Browser";

  /** Per origin ({@link Urls#origin}): the {@code Authorization} header to send, for this session. */
  private static final Map<String, String> logins = new ConcurrentHashMap<>();
  /** Origins with a Sign In dialog open (or about to open): a second 401 meanwhile doesn't open another. */
  private static final Set<String> asking = ConcurrentHashMap.newKeySet();

  private BasicAuth() {}

  /**
   * {@code CefRequestHandler.getResourceRequestHandler}, on CEF's IO thread: a handler for page loads (to notice
   * a 401) and for requests to an origin with a login (to send it); null for anything else.
   */
  static @Nullable CefResourceRequestHandler handlerFor(Project project, @Nullable CefFrame frame, CefRequest request,
      boolean isNavigation, Tab tab) {
    String origin = Urls.origin(request.getURL());
    if (origin == null) return null;
    boolean page = isNavigation && frame != null && frame.isMain();
    if (!page && !logins.containsKey(origin)) return null;
    return new CefResourceRequestHandlerAdapter() {
      /** Whether this request carried our login: a 401 for it means the site rejected that login. */
      private volatile boolean sentLogin;

      @Override
      public boolean onBeforeResourceLoad(CefBrowser browser, CefFrame f, CefRequest r) {
        String header = logins.get(origin);
        if (header != null && StringUtil.isEmpty(r.getHeaderByName("Authorization"))) {
          r.setHeaderByName("Authorization", header, true);
          sentLogin = true;
        }
        return false;
      }

      @Override
      public boolean onResourceResponse(CefBrowser browser, CefFrame f, CefRequest r, CefResponse response) {
        if (page && response.getStatus() == 401) {
          String realm = HttpAuth.basicRealm(response.getHeaderByName("WWW-Authenticate"));
          if (realm != null) challenged(project, origin, realm, sentLogin, tab);
        }
        return false;
      }
    };
  }

  /** A page of {@code origin} got a 401 asking for a Basic login: a remembered login, else the dialog. */
  private static void challenged(Project project, String origin, String realm, boolean rejected, Tab tab) {
    if (!asking.add(origin)) return;
    if (rejected) logins.remove(origin);
    // the password store may be the OS keychain: not on CEF's IO thread, nor on the UI thread
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      CredentialAttributes attributes = attributes(origin, realm);
      Credentials saved = PasswordSafe.getInstance().get(attributes);
      if (!rejected && saved != null && saved.getUserName() != null && saved.getPasswordAsString() != null) {
        logins.put(origin, HttpAuth.basicHeader(saved.getUserName(), saved.getPasswordAsString()));
        asking.remove(origin);
        tab.reload();
        return;
      }
      ApplicationManager.getApplication().invokeLater(() -> {
        try {
          if (project.isDisposed()) return;
          Dialog dialog = new Dialog(project, origin, realm, rejected, saved);
          if (!dialog.showAndGet()) {
            tab.signInCancelled(origin.substring(origin.indexOf("://") + 3), realm);
            return;
          }
          String user = dialog.user.getText();
          String password = new String(dialog.password.getPassword());
          boolean remember = dialog.remember.isSelected();
          logins.put(origin, HttpAuth.basicHeader(user, password));
          tab.reload();
          // unticked: also forget a login remembered before
          ApplicationManager.getApplication().executeOnPooledThread(() ->
              PasswordSafe.getInstance().set(attributes, remember ? new Credentials(user, password) : null));
        } finally {
          asking.remove(origin);
        }
      });
    });
  }

  /** What the browser tab does once the login is known, or the sign-in was cancelled. */
  interface Tab {
    void reload();

    /**
     * With a resource handler on the request, Chromium shows a 401 that wasn't signed in to as an empty error
     * page ({@code ERR_INVALID_AUTH_CREDENTIALS}) rather than the site's own 401 page: say what happened there.
     */
    void signInCancelled(String site, String realm);
  }

  private static CredentialAttributes attributes(String origin, String realm) {
    return new CredentialAttributes(CredentialAttributesKt.generateServiceName(SERVICE, origin + " " + realm));
  }

  private static final class Dialog extends DialogWrapper {
    final JBTextField user = new JBTextField(24);
    final JBPasswordField password = new JBPasswordField();
    final JBCheckBox remember = new JBCheckBox("Remember in the IDE's password store");
    private final String origin;
    private final String realm;
    private final boolean rejected;

    Dialog(Project project, String origin, String realm, boolean rejected, @Nullable Credentials saved) {
      super(project);
      this.origin = origin;
      this.realm = realm;
      this.rejected = rejected;
      if (saved != null) {
        user.setText(saved.getUserName());
        remember.setSelected(true);
      }
      setTitle("Sign In");
      setOKButtonText("Sign In");
      init();
    }

    @Override
    protected JComponent createCenterPanel() {
      String site = origin.substring(origin.indexOf("://") + 3);
      StringBuilder text = new StringBuilder("<html><b>").append(StringUtil.escapeXmlEntities(site))
          .append("</b> asks for a username and password");
      if (!realm.isBlank()) text.append("<br>“").append(StringUtil.escapeXmlEntities(realm)).append("”");
      FormBuilder form = FormBuilder.createFormBuilder().addComponent(new JBLabel(text.append("</html>").toString()));
      if (rejected) form.addComponent(note("The site didn't accept that username or password.", JBColor.RED));
      if (origin.startsWith("http:")) {
        form.addComponent(note("Not a secure (https) connection: the password is sent readable.", JBColor.GRAY));
      }
      return form
          .addVerticalGap(JBUI.scale(4))
          .addLabeledComponent("Username:", user)
          .addLabeledComponent("Password:", password)
          .addComponent(remember)
          .getPanel();
    }

    private static JBLabel note(String text, JBColor color) {
      JBLabel label = new JBLabel(text);
      label.setForeground(color);
      return label;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
      return user.getText().isEmpty() ? user : password;
    }
  }
}
