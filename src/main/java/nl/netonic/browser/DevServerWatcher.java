package nl.netonic.browser;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import nl.netonic.browser.core.Urls;
import org.jetbrains.annotations.NotNull;

/**
 * Watches every run configuration's console output for a local URL (npm run dev, a PHP dev server, …) and opens
 * it as a browser tab, reusing an existing tab for the same host and port. Toggled by {@link
 * BrowserStore#isAutoOpenDevServer()}. Registered as a project listener in {@code plugin.xml}.
 */
final class DevServerWatcher implements ExecutionListener {
  @Override
  public void processStarted(@NotNull String executorId, @NotNull ExecutionEnvironment env,
      @NotNull ProcessHandler handler) {
    if (!BrowserStore.getInstance().isAutoOpenDevServer()) return;
    handler.addProcessListener(new ProcessListener() {
      private volatile boolean opened;

      @Override
      @SuppressWarnings("rawtypes")
      public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
        if (opened) return;
        String url = Urls.findDevServerUrl(event.getText());
        if (url == null) return;
        opened = true;
        Project project = env.getProject();
        ApplicationManager.getApplication().invokeLater(() -> {
          if (!project.isDisposed()) BrowserTabs.openOrReuse(project, url);
        });
      }
    });
  }
}
