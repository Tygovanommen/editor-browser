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
        out.append("cef ").append(browser == null ? "none" : browser.getCefBrowser().getURL()).append('\n');
        out.append("cefZoomLevel ").append(browser == null ? "-" : browser.getCefBrowser().getZoomLevel()).append('\n');
        out.append("loading ").append(field(ed, "loading", Boolean.class)).append('\n');
        out.append("focus ").append(describe(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner())).append('\n');
        out.append("favicon ").append(describeIcon(favicon(ed.getFile())));
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
    for (com.intellij.ide.FileIconProvider p : com.intellij.ide.FileIconProvider.EP_NAME.getExtensionList()) {
      javax.swing.Icon icon = p.getIcon(file, 0, project());
      if (icon != null) return icon;
    }
    return null;
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
