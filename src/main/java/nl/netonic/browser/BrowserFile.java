package nl.netonic.browser;

import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import nl.netonic.browser.core.Urls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A browser tab as a (contentless) file; its path is the tab id, its URL lives in {@link BrowserStore}. */
public final class BrowserFile extends VirtualFile {
  private static final byte[] EMPTY = new byte[0];

  private final BrowserFileSystem fs;
  private final String id;

  BrowserFile(BrowserFileSystem fs, String id) {
    this.fs = fs;
    this.id = id;
  }

  public @NotNull String id() {
    return id;
  }

  /** The page's current URL, {@code about:blank} when unknown. */
  public @NotNull String url() {
    String url = BrowserStore.getInstance().url(id);
    return url != null ? url : Urls.BLANK;
  }

  /** Tab text: the page title once loaded, else the host. */
  public @NotNull String title() {
    String title = BrowserStore.getInstance().title(id);
    return Urls.shorten(title != null ? title : Urls.label(url()), 40);
  }

  @Override
  public @NotNull String getName() {
    return Urls.label(url()).replace('/', '_');
  }

  @Override
  public @NotNull String getPresentableName() {
    return title();
  }

  @Override
  public @NotNull FileType getFileType() {
    return BrowserFileType.INSTANCE;
  }

  @Override
  public @NotNull VirtualFileSystem getFileSystem() {
    return fs;
  }

  @Override
  public @NotNull String getPath() {
    return id;
  }

  @Override
  public boolean isWritable() {
    return false;
  }

  @Override
  public boolean isDirectory() {
    return false;
  }

  @Override
  public boolean isValid() {
    return true;
  }

  @Override
  public @Nullable VirtualFile getParent() {
    return null;
  }

  @Override
  public VirtualFile[] getChildren() {
    return EMPTY_ARRAY;
  }

  @Override
  public @NotNull OutputStream getOutputStream(Object requestor, long newModificationStamp, long newTimeStamp)
      throws IOException {
    throw new IOException("A browser tab can't be written to");
  }

  @Override
  public byte @NotNull [] contentsToByteArray() {
    return EMPTY;
  }

  @Override
  public long getTimeStamp() {
    return 0;
  }

  @Override
  public long getLength() {
    return 0;
  }

  @Override
  public void refresh(boolean asynchronous, boolean recursive, @Nullable Runnable postRunnable) {
    if (postRunnable != null) postRunnable.run();
  }

  @Override
  public @NotNull InputStream getInputStream() {
    return new ByteArrayInputStream(EMPTY);
  }
}
