package nl.netonic.browser.sandbox;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.jcef.JBCefBrowser;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import nl.netonic.browser.BrowserEditor;
import nl.netonic.browser.BrowserFile;
import nl.netonic.browser.BrowserTabs;

/**
 * The commands of tools/sandbox/cmd.sh, loaded fresh for every command by {@link ShotHelper}
 * ({@code demo.sh --probe} recompiles them without a restart). Called on the EDT.
 */
public final class Commands {
  private Commands() {}

  public static String run(String line) throws Exception {
    StringBuilder out = new StringBuilder();
    String[] p = line.split(" ", 2);
    String arg = p.length > 1 ? p[1] : "";
    switch (p[0]) {
      case "windows" -> {
        for (Window w : Window.getWindows()) {
          if (!w.isShowing()) continue;
          out.append(w.getClass().getSimpleName()).append(" '").append(title(w)).append("' ").append(w.getBounds()).append('\n');
        }
        for (Project pr : ProjectManager.getInstance().getOpenProjects()) out.append("project ").append(pr.getName()).append('\n');
      }
      // open <url>: a new browser tab, as Tools › Open URL in Editor Tab does after the dialog
      case "open" -> BrowserTabs.open(project(), arg, true);
      // devserver <url>: runs a throwaway process that prints <url>, firing ExecutionManager.EXECUTION_TOPIC
      // as a real run configuration would, to exercise DevServerWatcher end to end
      case "devserver" -> {
        Project project = project();
        com.intellij.execution.configurations.GeneralCommandLine cmd =
            new com.intellij.execution.configurations.GeneralCommandLine("sh", "-c", "echo " + arg);
        com.intellij.execution.process.OSProcessHandler handler =
            new com.intellij.execution.process.OSProcessHandler(cmd);
        com.intellij.execution.runners.ExecutionEnvironment env = new com.intellij.execution.runners.ExecutionEnvironmentBuilder(
            project, com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance())
            .runProfile(new com.intellij.execution.configurations.RunProfile() {
              @Override
              public com.intellij.execution.configurations.RunProfileState getState(
                  com.intellij.execution.Executor executor, com.intellij.execution.runners.ExecutionEnvironment environment) {
                return null;
              }

              @Override
              public String getName() {
                return "devserver test";
              }

              @Override
              public javax.swing.Icon getIcon() {
                return null;
              }
            })
            .build();
        project.getMessageBus().syncPublisher(com.intellij.execution.ExecutionManager.EXECUTION_TOPIC)
            .processStarted("Run", env, handler);
        handler.startNotify();
        out.append("started");
      }
      // tabs: every open file of the project; browser tabs with title, URL and how many editors (splits) show it
      case "tabs" -> {
        FileEditorManager fem = FileEditorManager.getInstance(project());
        VirtualFile selected = fem.getSelectedFiles().length > 0 ? fem.getSelectedFiles()[0] : null;
        for (VirtualFile f : fem.getOpenFiles()) {
          out.append(f == selected ? "* " : "  ");
          if (f instanceof BrowserFile b) {
            out.append("browser '").append(b.title()).append("' ").append(b.url()).append(" url=").append(f.getUrl())
                .append(" editors=").append(fem.getAllEditors(f).length);
          } else {
            out.append("file ").append(f.getName());
          }
          out.append('\n');
        }
        out.append("splits ").append(com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project()).getWindows().length);
      }
      // state: the selected browser tab's address bar, zoom label and what Chromium reports
      case "state" -> {
        BrowserEditor ed = selectedBrowser();
        JBCefBrowser browser = field(ed, "browser", JBCefBrowser.class);
        JBTextField address = field(ed, "address", JBTextField.class);
        JBLabel zoom = field(ed, "zoomLabel", JBLabel.class);
        out.append("address ").append(address.getText()).append('\n');
        out.append("zoomLabel ").append(zoom.isVisible() ? zoom.getText() : "(hidden)").append('\n');
        JBLabel width = field(ed, "widthLabel", JBLabel.class);
        out.append("widthLabel ").append(width.isVisible() ? width.getText() : "(hidden)").append('\n');
        if (browser != null) {
          out.append("canvas ").append(String.format("#%06x", browser.getComponent().getBackground().getRGB() & 0xffffff)).append('\n');
        }
        JBLabel scheme = field(ed, "schemeLabel", JBLabel.class);
        out.append("schemeLabel ").append(scheme.isVisible() ? scheme.getText() : "(hidden)").append('\n');
        // the page's width in screen px out of the tab's (device width × zoom, centred)
        javax.swing.JPanel viewport = field(ed, "viewport", javax.swing.JPanel.class);
        if (browser != null) {
          out.append("page ").append(browser.getComponent().getWidth()).append(" of ").append(viewport.getWidth())
              .append(" at x ").append(browser.getComponent().getX()).append('\n');
        }
        out.append("cef ").append(browser == null ? "none" : browser.getCefBrowser().getURL()).append('\n');
        out.append("cefZoomLevel ").append(browser == null ? "-" : browser.getCefBrowser().getZoomLevel()).append('\n');
        out.append("loading ").append(field(ed, "loading", Boolean.class)).append('\n');
        out.append("focus ").append(describe(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner())).append('\n');
        // find bar: shown / hidden, its match count, whether its field has focus; the console error badge
        javax.swing.JPanel findBar = field(ed, "findBar", javax.swing.JPanel.class);
        JBLabel findCount = field(ed, "findCount", JBLabel.class);
        JBTextField findField = field(ed, "findField", JBTextField.class);
        out.append("find ").append(findBar.isVisible() ? "shown" : "hidden").append(" '").append(findCount.getText())
            .append('\'').append(findField.hasFocus() ? " focused" : "").append('\n');
        JBLabel errors = field(ed, "errorLabel", JBLabel.class);
        out.append("errors ").append(errors.isVisible() ? errors.getText() : "0").append('\n');
        out.append("favicon ").append(describeIcon(favicon(ed.getFile()))).append('\n');
        // loading: the progress line, the tab icon's status, the HTTP status badge, the notice bar (kind: text | links)
        out.append("progress ").append(field(ed, "progress", Double.class) < 0 ? "hidden" : "shown").append('\n');
        java.lang.reflect.Method status = Class.forName("nl.netonic.browser.BrowserFavicons").getDeclaredMethod("status", String.class);
        status.setAccessible(true);
        out.append("tabicon ").append(status.invoke(null, ((BrowserFile) ed.getFile()).id())).append('\n');
        JBLabel httpStatus = field(ed, "statusLabel", JBLabel.class);
        out.append("http ").append(httpStatus.isVisible() ? httpStatus.getText() : "-").append('\n');
        javax.swing.JPanel notice = field(ed, "notice", javax.swing.JPanel.class);
        JBLabel noticeText = field(ed, "noticeText", JBLabel.class);
        javax.swing.JPanel noticeLinks = field(ed, "noticeLinks", javax.swing.JPanel.class);
        out.append("notice ");
        if (!notice.isVisible()) out.append("(hidden)");
        else {
          out.append(notice.getName()).append(": ").append(com.intellij.openapi.util.text.StringUtil.unescapeXmlEntities(noticeText.getText().replaceAll("<[^>]+>", "").replace("&nbsp;", " ").replace("&#39;", "'"))).append(" |");
          for (Component link : noticeLinks.getComponents()) {
            if (link instanceof com.intellij.ui.components.ActionLink a) out.append(" [").append(a.getText()).append(']');
          }
        }
      }
      // notice <link>: clicks a link of the selected browser tab's notice bar (Try Again, Stop Waiting, Stop)
      case "notice" -> {
        javax.swing.JPanel noticeLinks = field(selectedBrowser(), "noticeLinks", javax.swing.JPanel.class);
        boolean hit = false;
        for (Component link : noticeLinks.getComponents()) {
          if (link instanceof com.intellij.ui.components.ActionLink a && a.getText().equals(arg)) {
            a.doClick();
            hit = true;
          }
        }
        out.append(hit ? "clicked '" + arg + "'" : "no link '" + arg + "'");
      }
      // js <code>: runs JavaScript in the selected browser tab
      case "js" -> {
        JBCefBrowser browser = field(selectedBrowser(), "browser", JBCefBrowser.class);
        browser.getCefBrowser().executeJavaScript(arg, browser.getCefBrowser().getURL(), 0);
        out.append("ok");
      }
      // click <x> <y>: a real mouse click (user gesture) at page coordinates of the selected browser tab
      case "click" -> {
        JBCefBrowser browser = field(selectedBrowser(), "browser", JBCefBrowser.class);
        Component c = browser.getCefBrowser().getUIComponent();
        String[] a = arg.split(" ");
        int x = Integer.parseInt(a[0]), y = Integer.parseInt(a[1]);
        long t = System.currentTimeMillis();
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_MOVED, t, 0, x, y, 0, false, MouseEvent.NOBUTTON));
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, t, InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, t + 50, 0, x, y, 1, false, MouseEvent.BUTTON1));
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, t + 50, 0, x, y, 1, false, MouseEvent.BUTTON1));
        out.append("clicked ").append(describe(c)).append(' ').append(c.getBounds());
      }
      // rclick <x> <y>: a real right-click at page coordinates of the selected browser tab (Chromium's context menu)
      case "rclick" -> {
        JBCefBrowser browser = field(selectedBrowser(), "browser", JBCefBrowser.class);
        Component c = browser.getCefBrowser().getUIComponent();
        String[] a = arg.split(" ");
        int x = Integer.parseInt(a[0]), y = Integer.parseInt(a[1]);
        long t = System.currentTimeMillis();
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_MOVED, t, 0, x, y, 0, false, MouseEvent.NOBUTTON));
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, t, InputEvent.BUTTON3_DOWN_MASK, x, y, 1, true, MouseEvent.BUTTON3));
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, t + 50, 0, x, y, 1, false, MouseEvent.BUTTON3));
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, t + 50, 0, x, y, 1, false, MouseEvent.BUTTON3));
        out.append("right-clicked ").append(describe(c));
      }
      // menu [item label]: the items of the open list popup (Chromium's context menu is one), or picks one
      case "menu" -> {
        javax.swing.JList<?> list = null;
        for (Window w : Window.getWindows()) {
          if (!w.isShowing() || w instanceof JFrame) continue;
          for (Component c : all(w)) if (c instanceof javax.swing.JList<?> l && l.isShowing()) list = l;
        }
        if (list == null) throw new IllegalStateException("no popup list showing");
        int hit = -1;
        for (int i = 0; i < list.getModel().getSize(); i++) {
          // JCEF's items print as MenuItem(label=Back, commandId=100, …)
          String item = String.valueOf(list.getModel().getElementAt(i));
          java.util.regex.Matcher m = java.util.regex.Pattern.compile("label=([^,]*)").matcher(item);
          String label = m.find() ? m.group(1) : item;
          // an action popup's items (PopupFactoryImpl.ActionItem) have a getText()
          try {
            Object text = list.getModel().getElementAt(i).getClass().getMethod("getText").invoke(list.getModel().getElementAt(i));
            if (text != null) label = text.toString();
          } catch (ReflectiveOperationException ignored) {
            // a plain item
          }
          out.append("item '").append(label).append("'\n");
          if (label.equals(arg)) hit = i;
        }
        if (!arg.isEmpty()) {
          if (hit < 0) throw new IllegalArgumentException("no menu item '" + arg + "' in:\n" + out);
          // a mouse click on the item, as the popup only takes keys through the IDE's event queue
          java.awt.Rectangle r = list.getCellBounds(hit, hit);
          int x = r.x + r.width / 2, y = r.y + r.height / 2;
          long t = System.currentTimeMillis();
          list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_MOVED, t, 0, x, y, 0, false, MouseEvent.NOBUTTON));
          list.setSelectedIndex(hit);
          list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_PRESSED, t, InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
          list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_RELEASED, t + 50, 0, x, y, 1, false, MouseEvent.BUTTON1));
          list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_CLICKED, t + 50, 0, x, y, 1, false, MouseEvent.BUTTON1));
          out.setLength(0);
          out.append("picked '").append(arg).append('\'');
        }
      }
      // action <id>: runs an action with the selected editor (or focus owner) as context
      case "action" -> {
        AnAction a = ActionManager.getInstance().getAction(arg);
        if (a == null) throw new IllegalArgumentException("no action " + arg);
        Component c = focusOrEditor();
        later(() -> ActionManager.getInstance().tryToExecute(a, null, c, ActionPlaces.UNKNOWN, true));
        out.append("ok ").append(describe(c));
      }
      // address: focuses the selected tab's address bar
      case "address" -> {
        JBTextField address = field(selectedBrowser(), "address", JBTextField.class);
        IdeFocusManager.getInstance(project()).requestFocus(address, true);
        out.append("ok");
      }
      // type <text>: replaces the text of the focused text field (address bar or a dialog's field)
      case "type" -> {
        Component c = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (!(c instanceof JTextComponent t)) throw new IllegalStateException("focus owner is " + describe(c));
        t.setText(arg);
        out.append("typed into ").append(describe(c));
      }
      case "key" -> {
        Component c = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        int code = switch (arg) {
          case "esc" -> KeyEvent.VK_ESCAPE;
          case "enter" -> KeyEvent.VK_ENTER;
          default -> throw new IllegalArgumentException(arg);
        };
        c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, code, KeyEvent.CHAR_UNDEFINED));
        c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, code, KeyEvent.CHAR_UNDEFINED));
        out.append("key ").append(arg).append(" to ").append(describe(c));
      }
      // stroke <keystroke>: a key press through the IDE's event queue, so shortcuts fire ("meta MINUS", "meta L")
      case "stroke" -> {
        Component c = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        javax.swing.KeyStroke ks = javax.swing.KeyStroke.getKeyStroke(arg);
        if (ks == null) throw new IllegalArgumentException("bad keystroke " + arg);
        java.awt.EventQueue q = java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue();
        long t = System.currentTimeMillis();
        q.postEvent(new KeyEvent(c, KeyEvent.KEY_PRESSED, t, ks.getModifiers(), ks.getKeyCode(), KeyEvent.CHAR_UNDEFINED));
        q.postEvent(new KeyEvent(c, KeyEvent.KEY_RELEASED, t + 20, ks.getModifiers(), ks.getKeyCode(), KeyEvent.CHAR_UNDEFINED));
        out.append("stroke ").append(ks).append(" to ").append(describe(c));
      }
      // toolbar [click]: the New Browser Tab button in the IDE's main toolbar (its icon and tooltip), or clicks it
      // button <text>: clicks the selected browser tab's toolbar button with that text (e.g. "Device Width")
      case "button" -> {
        com.intellij.openapi.actionSystem.impl.ActionButton b = null;
        for (Component c : all(selectedBrowser().getComponent())) {
          if (c instanceof com.intellij.openapi.actionSystem.impl.ActionButton ab && ab.isShowing()
              && arg.equals(ab.getPresentation().getText())) b = ab;
        }
        if (b == null) throw new IllegalStateException("no button '" + arg + "' in the browser tab's toolbar");
        com.intellij.openapi.actionSystem.impl.ActionButton button = b;
        later(button::click);
        out.append("clicked '").append(arg).append('\'');
      }
      // buttons: the buttons showing in the selected browser tab's toolbars, one per line
      case "buttons" -> {
        for (Component c : all(selectedBrowser().getComponent())) {
          if (c instanceof com.intellij.openapi.actionSystem.impl.ActionButton ab && ab.isShowing()) {
            out.append("button '").append(ab.getPresentation().getText()).append("'\n");
          }
        }
      }
      // layout default | <item>=<toolbar|menu|hidden> …: sets the toolbar layout (as Settings › Apply does)
      case "layout" -> {
        nl.netonic.browser.core.ToolbarLayout layout = nl.netonic.browser.core.ToolbarLayout.DEFAULT;
        if (!arg.equals("default")) {
          java.util.Map<String, String> stored = new java.util.LinkedHashMap<>();
          for (String kv : arg.split(" ")) {
            String[] a = kv.split("=", 2);
            stored.put(a[0], a[1]);
          }
          layout = nl.netonic.browser.core.ToolbarLayout.parse(stored);
          if (!layout.toStored().equals(stored)) throw new IllegalArgumentException("unknown item or place in: " + arg);
        }
        nl.netonic.browser.BrowserStore.getInstance().setToolbarLayout(layout);
        for (com.intellij.openapi.project.Project pr : com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()) {
          for (com.intellij.openapi.fileEditor.FileEditor ed : com.intellij.openapi.fileEditor.FileEditorManager.getInstance(pr).getAllEditors()) {
            if (ed instanceof BrowserEditor b) b.toolbarLayoutChanged();
          }
        }
        out.append("layout ").append(layout.toStored());
      }
      case "toolbar" -> {
        AnAction newTab = ActionManager.getInstance().getAction("EditorBrowser.NewTab");
        com.intellij.openapi.actionSystem.impl.ActionButton b = null;
        for (Component c : all(frame())) {
          // topmost: the browser tabs' own toolbars show the same action lower down
          if (c instanceof com.intellij.openapi.actionSystem.impl.ActionButton ab && ab.getAction() == newTab && ab.isShowing()
              && (b == null || ab.getLocationOnScreen().y < b.getLocationOnScreen().y)) b = ab;
        }
        if (b == null) throw new IllegalStateException("no New Browser Tab button in the main toolbar");
        if (arg.equals("click")) {
          com.intellij.openapi.actionSystem.impl.ActionButton button = b;
          later(button::click);
          out.append("clicked");
        } else {
          out.append("button ").append(String.valueOf(b.getIcon()).endsWith("/web.svg)") ? "globe" : String.valueOf(b.getIcon()))
             .append(" '").append(b.getToolTipText()).append('\'');
        }
      }
      // shortcut <action id>: the action's first keyboard shortcut in the active keymap, as the IDE shows it
      case "shortcut" -> out.append("shortcut ").append(com.intellij.openapi.keymap.KeymapUtil.getFirstKeyboardShortcutText(arg));
      // select <text>: selects the first occurrence of text in the selected text editor
      case "select" -> {
        com.intellij.openapi.editor.Editor ed = FileEditorManager.getInstance(project()).getSelectedTextEditor();
        if (ed == null) throw new IllegalStateException("no text editor selected");
        int at = ed.getDocument().getText().indexOf(arg);
        if (at < 0) throw new IllegalArgumentException("not found: " + arg);
        ed.getSelectionModel().setSelection(at, at + arg.length());
        IdeFocusManager.getInstance(project()).requestFocus(ed.getContentComponent(), true);
        out.append("selected ").append(ed.getSelectionModel().getSelectedText());
      }
      // file <path>: opens a file from disk
      case "file" -> {
        VirtualFile f = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(arg);
        if (f == null) throw new IllegalArgumentException("no file " + arg);
        new com.intellij.openapi.fileEditor.OpenFileDescriptor(project(), f).navigate(true);
        out.append("opened ").append(f.getName());
      }
      // save <path>: adds a newline to a file from disk in its editor document and saves it, as ⌘S would
      case "save" -> {
        // the real path: /tmp is a symlink on macOS, and only /private/tmp/… is the project's own file
        VirtualFile f = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .refreshAndFindFileByNioFile(java.nio.file.Path.of(arg).toRealPath());
        if (f == null) throw new IllegalArgumentException("no file " + arg);
        com.intellij.openapi.fileEditor.FileDocumentManager fdm = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance();
        com.intellij.openapi.editor.Document d = fdm.getDocument(f);
        if (d == null) throw new IllegalStateException("no document for " + arg);
        // helper commands run in a plain invokeLater: a document change needs write-intent
        com.intellij.openapi.application.WriteIntentReadAction.run((Runnable) () -> {
          com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project(), () -> d.insertString(d.getTextLength(), "\n"));
          fdm.saveDocument(d);
        });
        out.append("saved ").append(f.getName());
      }
      // closedialogs: cancels the IDE dialogs left open (e.g. by a check that couldn't type into one without focus);
      // while a modal dialog shows, the plugin's invokeLater updates wait
      case "closedialogs" -> {
        for (Window w : Window.getWindows()) {
          if (!(w instanceof JDialog d) || !d.isShowing()) continue;
          com.intellij.openapi.ui.DialogWrapper dw = com.intellij.openapi.ui.DialogWrapper.findInstance(d.getContentPane());
          if (dw != null) dw.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE);
          else d.dispose();
          out.append("closed '").append(d.getTitle()).append("'\n");
        }
      }
      // call <method>: runs a no-argument method of the selected browser tab (e.g. openFind), no keyboard focus needed
      case "call" -> {
        BrowserEditor ed = selectedBrowser();
        java.lang.reflect.Method m = ed.getClass().getDeclaredMethod(arg);
        m.setAccessible(true);
        m.invoke(ed);
        out.append("called ").append(arg);
      }
      // popup [close | sizes | shot <file>]: the text of the selected browser tab's Share Preview popover, one label
      // per line; or closes it, lists its components' sizes, or paints it to a PNG (it is a window of its own)
      case "popup" -> {
        com.intellij.openapi.ui.popup.JBPopup popup =
            field(selectedBrowser(), "sharePopup", com.intellij.openapi.ui.popup.JBPopup.class);
        if (popup == null || popup.isDisposed() || !popup.isVisible()) {
          out.append("no popup");
        } else if (arg.equals("close")) {
          popup.cancel();
          out.append("closed");
        } else if (arg.equals("sizes")) {
          for (Component c : all(popup.getContent())) {
            out.append(c.getClass().getSimpleName()).append(' ').append(c.getWidth()).append('x').append(c.getHeight())
                .append(" pref ").append(c.getPreferredSize().width).append('x').append(c.getPreferredSize().height).append('\n');
          }
        } else if (arg.startsWith("shot ")) {
          javax.swing.JComponent content = popup.getContent();
          BufferedImage img = new BufferedImage(content.getWidth() * 2, content.getHeight() * 2, BufferedImage.TYPE_INT_ARGB);
          Graphics2D g = img.createGraphics();
          g.scale(2, 2);
          content.paint(g);
          g.dispose();
          ImageIO.write(img, "png", new File(arg.substring(5)));
          out.append("wrote ").append(arg.substring(5));
        } else {
          for (Component c : all(popup.getContent())) {
            if (c instanceof JBLabel l && l.isShowing()) {
              out.append("label ").append(l.getText().replaceAll("<[^>]*>", "").replace("&amp;", "&")
                  .replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'").replace("&quot;", "\"")).append('\n');
            }
          }
        }
      }
      // settext <field> <text>: sets the text of a text field of the selected browser tab (findField, address)
      case "settext" -> {
        String[] a = arg.split(" ", 2);
        JTextComponent t = field(selectedBrowser(), a[0], JTextComponent.class);
        t.setText(a.length > 1 ? a[1] : "");
        out.append("set ").append(a[0]);
      }
      // auth [<user> <password> | cancel]: the open Sign In dialog (HTTP basic auth) — its text, one label per line;
      // or fills it in and signs in, or cancels it. No keyboard focus needed.
      case "auth" -> {
        com.intellij.openapi.ui.DialogWrapper dialog = null;
        for (Window w : Window.getWindows()) {
          if (!(w instanceof JDialog d) || !d.isShowing()) continue;
          com.intellij.openapi.ui.DialogWrapper dw = com.intellij.openapi.ui.DialogWrapper.findInstance(d.getContentPane());
          if (dw != null && dw.getClass().getName().equals("nl.netonic.browser.BasicAuth$Dialog")) dialog = dw;
        }
        if (dialog == null) {
          out.append("no auth dialog");
        } else if (arg.equals("cancel")) {
          dialog.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE);
          out.append("cancelled");
        } else if (!arg.isEmpty()) {
          String[] a = arg.split(" ", 2);
          field(dialog, "user", JTextComponent.class).setText(a[0]);
          field(dialog, "password", JTextComponent.class).setText(a.length > 1 ? a[1] : "");
          java.lang.reflect.Method ok = com.intellij.openapi.ui.DialogWrapper.class.getDeclaredMethod("doOKAction");
          ok.setAccessible(true);
          ok.invoke(dialog);
          out.append("signed in as ").append(a[0]);
        } else {
          for (Component c : all(dialog.getContentPane())) {
            if (c instanceof JBLabel l && l.isShowing()) {
              out.append("label ").append(l.getText().replaceAll("<[^>]*>", " ").replace("&amp;", "&")
                  .replaceAll(" +", " ").strip()).append('\n');
            }
          }
        }
      }
      // cdp <method> [<params json>]: runs a DevTools protocol method on the selected browser tab, prints the reply
      case "cdp" -> {
        String[] a = arg.split(" ", 2);
        JBCefBrowser browser = field(selectedBrowser(), "browser", JBCefBrowser.class);
        org.cef.browser.CefDevToolsClient client = browser.getCefBrowser().getDevToolsClient();
        if (client == null) throw new IllegalStateException("no DevTools client");
        java.util.concurrent.CompletableFuture<String> f =
            a.length > 1 ? client.executeDevToolsMethod(a[0], a[1]) : client.executeDevToolsMethod(a[0]);
        try {
          out.append("reply ").append(f.get(5, java.util.concurrent.TimeUnit.SECONDS));
        } catch (Exception e) {
          out.append("error ").append(e);
        }
      }
      // loaderrors: from now on records the selected browser tab's load errors (Chromium's error code, URL); prints them
      case "loaderrors" -> {
        JBCefBrowser browser = field(selectedBrowser(), "browser", JBCefBrowser.class);
        if (System.getProperty("ebdemo.loaderr.hooked." + browser.hashCode()) == null) {
          System.setProperty("ebdemo.loaderr.hooked." + browser.hashCode(), "1");
          browser.getJBCefClient().addLoadHandler(new org.cef.handler.CefLoadHandlerAdapter() {
            @Override
            public void onLoadError(org.cef.browser.CefBrowser b, org.cef.browser.CefFrame f,
                org.cef.handler.CefLoadHandler.ErrorCode code, String text, String url) {
              System.setProperty("ebdemo.loaderr", System.getProperty("ebdemo.loaderr", "") + code + " " + text + " " + url + "\n");
            }
          }, browser.getCefBrowser());
        }
        out.append(System.getProperty("ebdemo.loaderr", "none"));
      }
      // loadevents [clear]: from now on records the selected browser tab's load callbacks, in order; prints them
      case "loadevents" -> {
        JBCefBrowser browser = field(selectedBrowser(), "browser", JBCefBrowser.class);
        String key = "ebdemo.loadev." + browser.hashCode();
        if ("clear".equals(arg)) System.setProperty(key, "");
        if (System.getProperty(key + ".hooked") == null) {
          System.setProperty(key + ".hooked", "1");
          System.setProperty(key, "");
          java.util.function.Consumer<String> log = s -> System.setProperty(key, System.getProperty(key, "") + s + "\n");
          browser.getJBCefClient().addLoadHandler(new org.cef.handler.CefLoadHandlerAdapter() {
            @Override
            public void onLoadingStateChange(org.cef.browser.CefBrowser b, boolean loading, boolean back, boolean fwd) {
              log.accept("state loading=" + loading + " url=" + b.getURL());
            }

            @Override
            public void onLoadStart(org.cef.browser.CefBrowser b, org.cef.browser.CefFrame f,
                org.cef.network.CefRequest.TransitionType t) {
              log.accept("start main=" + f.isMain() + " " + t + " url=" + f.getURL());
            }

            @Override
            public void onLoadEnd(org.cef.browser.CefBrowser b, org.cef.browser.CefFrame f, int status) {
              log.accept("end main=" + f.isMain() + " status=" + status + " url=" + f.getURL());
            }

            @Override
            public void onLoadError(org.cef.browser.CefBrowser b, org.cef.browser.CefFrame f,
                org.cef.handler.CefLoadHandler.ErrorCode code, String text, String url) {
              log.accept("error main=" + f.isMain() + " " + code + " url=" + url);
            }
          }, browser.getCefBrowser());
        }
        out.append(System.getProperty(key, ""));
      }
      // reloadinfo: what reload on save sees for the selected browser tab
      case "reloadinfo" -> {
        BrowserEditor ed = selectedBrowser();
        out.append("local ").append(nl.netonic.browser.core.Urls.isLocal(ed.getFile() instanceof BrowserFile b ? b.url() : ""))
            .append(" reloadsItself ").append(field(ed, "pageReloadsItself", Boolean.class))
            .append(" showing ").append(ed.getComponent().isShowing())
            .append(" listeners ").append(com.intellij.openapi.application.ApplicationManager.getApplication().getMessageBus()
                .syncPublisher(com.intellij.openapi.fileEditor.FileDocumentManagerListener.TOPIC).getClass().getSimpleName());
        Field pending = Class.forName("nl.netonic.browser.ReloadOnSave").getDeclaredField("pending");
        pending.setAccessible(true);
        out.append(" pending ").append(pending.get(null));
      }
      // focustab <title>: selects the open browser tab with that page title
      case "focustab" -> {
        FileEditorManager fem = FileEditorManager.getInstance(project());
        VirtualFile hit = null;
        for (VirtualFile f : fem.getOpenFiles()) if (f instanceof BrowserFile b && arg.equals(b.title())) hit = f;
        if (hit == null) throw new IllegalArgumentException("no browser tab '" + arg + "'");
        fem.openFile(hit, true);
        out.append("selected '").append(arg).append('\'');
      }
      case "closefiles" -> {
        FileEditorManager fem = FileEditorManager.getInstance(project());
        for (VirtualFile f : fem.getOpenFiles()) fem.closeFile(f);
      }
      // shot <file>: the project window painted from Swing (Chromium's own pixels may be missing)
      case "shot" -> {
        Window w = frame();
        BufferedImage img = new BufferedImage(w.getWidth() * 2, w.getHeight() * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.scale(2, 2);
        JRootPane root = SwingUtilities.getRootPane(w);
        (root != null ? root : w).paint(g);
        g.dispose();
        ImageIO.write(img, "png", new File(arg));
        out.append("wrote ").append(arg);
      }
      // dump: the component tree of the popup windows that are showing
      case "dump" -> {
        for (Window w : Window.getWindows()) {
          if (!w.isShowing() || w instanceof JFrame) continue;
          out.append(w.getClass().getSimpleName()).append('\n');
          for (Component c : all(w)) out.append("  ").append(c.getClass().getName()).append(' ').append(describe(c))
              .append(c instanceof javax.swing.JList<?> l ? " model=" + l.getModel().getSize() + " first=" + (l.getModel().getSize() > 0 ? l.getModel().getElementAt(0) : "") : "")
              .append('\n');
        }
      }
      case "size" -> {
        String[] a = arg.split(" ");
        frame().setBounds(Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
      }
      case "quit" -> {
        later(() -> ApplicationManager.getApplication().exit(true, true, false));
        out.append("quitting");
      }
      default -> out.append("unknown command ").append(line);
    }
    return out.toString();
  }

  private static Project project() {
    Project[] open = ProjectManager.getInstance().getOpenProjects();
    if (open.length == 0) throw new IllegalStateException("no open project");
    return open[0];
  }

  private static Window frame() {
    IdeFrame f = WindowManager.getInstance().getIdeFrame(project());
    return SwingUtilities.getWindowAncestor(f.getComponent());
  }

  private static BrowserEditor selectedBrowser() {
    FileEditor ed = FileEditorManager.getInstance(project()).getSelectedEditor();
    if (ed instanceof BrowserEditor b) return b;
    throw new IllegalStateException("selected editor is " + ed);
  }

  private static Component focusOrEditor() {
    Component c = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
    if (c != null) return c;
    FileEditor ed = FileEditorManager.getInstance(project()).getSelectedEditor();
    return ed != null ? ed.getComponent() : frame();
  }

  private static <T> T field(Object o, String name, Class<T> type) throws ReflectiveOperationException {
    Field f = o.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return type.cast(f.get(o));
  }

  private static void later(Runnable r) {
    ApplicationManager.getApplication().invokeLater(r);
  }

  private static String title(Window w) {
    return w instanceof JFrame f ? f.getTitle() : w instanceof JDialog d ? d.getTitle() : "";
  }

  private static javax.swing.Icon favicon(VirtualFile file) {
    // in a read action: other providers (PsiBasedFileIconProvider) need one
    return com.intellij.openapi.application.ReadAction.compute(() -> {
      for (com.intellij.ide.FileIconProvider p : com.intellij.ide.FileIconProvider.EP_NAME.getExtensionList()) {
        javax.swing.Icon icon = p.getIcon(file, 0, project());
        if (icon != null) return icon;
      }
      return null;
    });
  }

  private static String describeIcon(javax.swing.Icon icon) {
    return icon == null ? "none" : icon.getIconWidth() + "x" + icon.getIconHeight();
  }

  private static String describe(Component c) {
    if (c == null) return "null";
    String text = c instanceof JTextComponent t ? " text=" + t.getText() : "";
    return c.getClass().getSimpleName() + text;
  }

  @SuppressWarnings("unused")
  private static List<Component> all(Container root) {
    List<Component> r = new ArrayList<>();
    for (Component c : root.getComponents()) {
      r.add(c);
      if (c instanceof Container k) r.addAll(all(k));
    }
    return r;
  }
}
