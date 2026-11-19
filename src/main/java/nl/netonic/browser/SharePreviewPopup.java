package nl.netonic.browser;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import nl.netonic.browser.core.SharePreview;
import nl.netonic.browser.core.Urls;
import org.jetbrains.annotations.Nullable;

/**
 * The popover of the browser tab's Share Preview button: the page's link card as chat and social apps show it
 * (image, site, title, description), and what is missing from its meta tags. The image is downloaded here, as
 * those apps' crawlers do, so one that only loads with the browser's cookies shows as missing.
 */
final class SharePreviewPopup {
  /** The card's width (unscaled px). Not "WIDTH": inside a component that is ImageObserver.WIDTH (= 1). */
  private static final int CARD_WIDTH = 360;
  private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
  private static final Color CARD_TEXT_BACKGROUND = new JBColor(0xF2F3F5, 0x2B2D30);

  private SharePreviewPopup() {}

  static JBPopup create(SharePreview.Card card) {
    JPanel content = new JPanel();
    content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
    content.setBorder(JBUI.Borders.empty(10));

    ImageBox image = card.image() == null ? null : new ImageBox(card.imageAlt());
    content.add(left(cardPanel(card, image)));
    content.add(Box.createVerticalStrut(JBUI.scale(10)));
    JPanel notes = new JPanel();
    notes.setLayout(new BoxLayout(notes, BoxLayout.Y_AXIS));
    JBLabel allSet = card.warnings().isEmpty() ? note(AllIcons.General.InspectionsOK, "All the common share tags are set") : null;
    if (allSet != null) notes.add(left(allSet));
    for (String w : card.warnings()) notes.add(left(note(AllIcons.General.Warning, w)));
    content.add(left(notes));

    JBPopup popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, null)
        .setTitle("Share Preview")
        .setRequestFocus(true)
        .setCancelOnClickOutside(true)
        .setCancelKeyEnabled(true)
        .setResizable(false)
        .setMovable(true)
        .createPopup();
    if (image != null) loadImage(card.image(), image, notes, allSet, popup);
    return popup;
  }

  /** The card: image on top (when the page has one), then site, title and description. */
  private static JComponent cardPanel(SharePreview.Card card, @Nullable ImageBox image) {
    JPanel panel = new JPanel(new BorderLayout());
    panel.setBorder(JBUI.Borders.customLine(JBColor.border(), 1));
    if (image != null) panel.add(image, BorderLayout.NORTH);

    JPanel text = new JPanel();
    text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
    text.setBackground(CARD_TEXT_BACKGROUND);
    text.setBorder(JBUI.Borders.empty(8, 10));
    JBLabel site = new JBLabel(card.site().toUpperCase(Locale.ROOT));
    site.setFont(JBUI.Fonts.smallFont());
    site.setForeground(UIUtil.getContextHelpForeground());
    text.add(left(site));
    text.add(Box.createVerticalStrut(JBUI.scale(2)));
    JBLabel title = wrapped(card.title() == null ? "(no title)" : Urls.shorten(card.title(), 120), true);
    text.add(left(title));
    if (card.description() != null) {
      text.add(Box.createVerticalStrut(JBUI.scale(2)));
      JBLabel description = wrapped(Urls.shorten(card.description(), 220), false);
      description.setForeground(UIUtil.getContextHelpForeground());
      text.add(left(description));
    }
    panel.add(text, BorderLayout.CENTER);
    panel.setToolTipText(card.url());
    return panel;
  }

  private static JBLabel note(javax.swing.Icon icon, String text) {
    JBLabel label = wrapped(text, false);
    label.setIcon(icon);
    label.setVerticalTextPosition(SwingConstants.TOP);
    label.setIconTextGap(JBUI.scale(6));
    label.setBorder(JBUI.Borders.emptyBottom(4));
    return label;
  }

  /** A label that wraps within the card's width. */
  private static JBLabel wrapped(String text, boolean bold) {
    String html = StringUtil.escapeXmlEntities(text);
    // the HTML width is in unscaled px: the label scales its font, and the text with it
    return new JBLabel("<html><div style='width: " + (CARD_WIDTH - 40) + "px'>" + (bold ? "<b>" + html + "</b>" : html)
        + "</div></html>");
  }

  private static <T extends JComponent> T left(T c) {
    c.setAlignmentX(0f);
    return c;
  }

  /** Downloads the image on a background thread; shows it in the card, or says why it couldn't. */
  private static void loadImage(String url, ImageBox box, JPanel notes, @Nullable JBLabel allSet, JBPopup popup) {
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      BufferedImage img = null;
      String error;
      try {
        img = download(url);
        error = img == null ? "This image format can't be shown here (PNG, JPEG and GIF can)" : null;
      } catch (Exception e) {
        error = "The image couldn't be downloaded: " + Urls.shorten(String.valueOf(e.getMessage()), 120);
      }
      BufferedImage loaded = img;
      String failure = error;
      ApplicationManager.getApplication().invokeLater(() -> {
        if (popup.isDisposed()) return;
        box.show(loaded, failure);
        String note = failure != null ? failure
            : SharePreview.imageWarning(loaded.getWidth(), loaded.getHeight());
        if (note != null) {
          if (allSet != null) notes.remove(allSet);
          notes.add(left(note(AllIcons.General.Warning, note)));
          notes.revalidate();
          popup.pack(false, true);
        }
      });
    });
  }

  private static @Nullable BufferedImage download(String url) throws Exception {
    URI uri = new URI(url);
    String scheme = String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("file")) {
      throw new IllegalArgumentException("not an http(s) address");
    }
    URLConnection connection = uri.toURL().openConnection();
    connection.setConnectTimeout(5000);
    connection.setReadTimeout(10000);
    // some image hosts refuse Java's default user agent
    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; EditorBrowser share preview)");
    if (connection.getContentLengthLong() > MAX_IMAGE_BYTES) throw new IllegalArgumentException("larger than 10 MB");
    try (InputStream in = connection.getInputStream()) {
      return ImageIO.read(in);
    }
  }

  /** The card's image area (1.91 : 1, as the apps crop it): loading, the image, or why there is none. */
  private static final class ImageBox extends JComponent {
    private @Nullable BufferedImage image;
    private String message = "Loading image…";

    ImageBox(@Nullable String alt) {
      int w = JBUI.scale(CARD_WIDTH - 22);
      setPreferredSize(new Dimension(w, w * SharePreview.IMAGE_HEIGHT / SharePreview.IMAGE_WIDTH));
      if (alt != null) setToolTipText(alt);
    }

    void show(@Nullable BufferedImage loaded, @Nullable String failure) {
      image = loaded;
      message = failure != null ? "No image" : "";
      repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setColor(JBColor.namedColor("Panel.background", JBColor.PanelBackground).darker());
      g2.fillRect(0, 0, getWidth(), getHeight());
      if (image != null) {
        int[] crop = SharePreview.coverCrop(image.getWidth(), image.getHeight());
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.drawImage(image, 0, 0, getWidth(), getHeight(), crop[0], crop[1], crop[0] + crop[2], crop[1] + crop[3], null);
      } else if (!message.isEmpty()) {
        g2.setColor(UIUtil.getContextHelpForeground());
        g2.setFont(JBUI.Fonts.label());
        int tw = g2.getFontMetrics().stringWidth(message);
        g2.drawString(message, (getWidth() - tw) / 2, getHeight() / 2 + g2.getFontMetrics().getAscent() / 2);
      }
      g2.dispose();
    }
  }
}
