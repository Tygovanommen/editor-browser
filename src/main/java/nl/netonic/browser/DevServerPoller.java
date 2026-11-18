package nl.netonic.browser;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import nl.netonic.browser.core.DevPorts;

/**
 * Opens a browser tab when a well-known dev-server port ({@link DevPorts#WATCHED}) starts listening, so
 * {@code npm run dev} typed in the Terminal works like a run configuration does through {@link DevServerWatcher}.
 * Looks at the OS's listening sockets every {@value #INTERVAL_SECONDS} s (never connects to the server); the tab
 * goes to the project whose window was focused last. Toggled by {@link BrowserStore#isAutoOpenDevServer()};
 * started by {@link DevServerPollerStarter}.
 */
final class DevServerPoller implements Disposable {
  private static final Logger LOG = Logger.getInstance(DevServerPoller.class);
  private static final int INTERVAL_SECONDS = 2;

  private final DevPorts.Watch watch = new DevPorts.Watch();
  private ScheduledFuture<?> task;
  private boolean failureLogged;

  static DevServerPoller getInstance() {
    return ApplicationManager.getApplication().getService(DevServerPoller.class);
  }

  synchronized void start() {
    if (task != null) return;
    ScheduledExecutorService executor = AppExecutorUtil.createBoundedScheduledExecutorService("Editor Browser dev-server ports", 1);
    task = executor.scheduleWithFixedDelay(this::poll, 0, INTERVAL_SECONDS, TimeUnit.SECONDS);
  }

  @Override
  public synchronized void dispose() {
    if (task != null) task.cancel(false);
    task = null;
  }

  // on the single poller thread
  private void poll() {
    if (!BrowserStore.getInstance().isAutoOpenDevServer()) {
      watch.reset();
      return;
    }
    Set<Integer> listening;
    try {
      listening = listeningPorts();
    } catch (Exception e) {
      if (!failureLogged) LOG.info("Editor Browser: can't list listening ports, Terminal dev servers won't open tabs", e);
      failureLogged = true;
      return;
    }
    for (int port : watch.update(listening)) {
      ApplicationManager.getApplication().invokeLater(() -> {
        IdeFrame frame = IdeFocusManager.getGlobalInstance().getLastFocusedFrame();
        Project project = frame == null ? null : frame.getProject();
        if (project != null && !project.isDisposed()) BrowserTabs.openDevServerPort(project, port);
      });
    }
  }

  private static Set<Integer> listeningPorts() throws IOException, InterruptedException {
    if (SystemInfo.isLinux) {
      Set<Integer> ports = new TreeSet<>();
      for (String name : new String[] {"/proc/net/tcp", "/proc/net/tcp6"}) {
        Path path = Path.of(name);
        if (Files.isReadable(path)) ports.addAll(DevPorts.parseProcNetTcp(Files.readString(path)));
      }
      return ports;
    }
    if (SystemInfo.isWindows) return DevPorts.parseWindowsNetstat(run("netstat", "-an"));
    return DevPorts.parseBsdNetstat(run(SystemInfo.isMac ? "/usr/sbin/netstat" : "netstat", "-an", "-p", "tcp"));
  }

  private static String run(String... command) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    try {
      // a few hundred lines; read before waiting so a full pipe can't block the process
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException(command[0] + " timed out");
      return output;
    } finally {
      process.destroy();
    }
  }
}
