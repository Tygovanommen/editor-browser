package nl.netonic.browser;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Starts the app-wide {@link DevServerPoller} once a project is open (starting it again is a no-op). */
final class DevServerPollerStarter implements ProjectActivity {
  @Override
  public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
    DevServerPoller.getInstance().start();
    return Unit.INSTANCE;
  }
}
