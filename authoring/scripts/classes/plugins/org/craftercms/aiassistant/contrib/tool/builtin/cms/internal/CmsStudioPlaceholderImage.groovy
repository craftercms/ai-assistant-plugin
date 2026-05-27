package plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * Studio / Experience Builder sample image placeholder ({@code data:image/png;base64,...}) — same visual pattern as
 * studio-ui {@code generatePlaceholderImageDataUrl} (grey field, centered “Sample Image” label).
 * Required image-picker defaults are always at least {@link #DEFAULT_WIDTH}×{@link #DEFAULT_HEIGHT} — never 1×1.
 */
final class CmsStudioPlaceholderImage {

  private static final Logger log = LoggerFactory.getLogger(CmsStudioPlaceholderImage)

  static final int DEFAULT_WIDTH = 150
  static final int DEFAULT_HEIGHT = 150
  private static final int MIN_DIMENSION = 16
  private static final int MAX_DIMENSION = 4096
  private static final String SAMPLE_LABEL = 'Sample Image'

  /**
   * Exact {@code data:…} string models paste for required image-picker fields — replaced on write, never emitted.
   */
  private static final String MINIMAL_STUB_DATA_URL =
    'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=='

  /** 150×150 grey “Sample Image” PNG used when AWT/ImageIO cannot render (headless servers). */
  private static final String EMBEDDED_FALLBACK_150_PNG_B64 =
    'iVBORw0KGgoAAAANSUhEUgAAAJYAAACWEAAAAABJ+vJ9AAAABGdBTUEAALGPC/xhBQAAACBjSFJNAAB6JgAAgIQAAPoAAACA6AAAdTAAAOpgAAA6mAAAF3CculE8AAAAAmJLR0T//xSrMc0AAAAHdElNRQfqBRoRChUGmW/pAAAEsklEQVR42u3cb0zUBRzH8e9xHHMdgRAckieJfxBcAxVixwTRUcgIdblDWTqJgbYI5kZu0B9qgW651CnDjJYJ+QBE/gTDI/9AEqYhkMsgyEWYx+hcCu4MwxhcD1jLk/D4uKPT7fN6eOz3+d69x+4BD1DcuiU0RU6OfgOPE8YCMBaAsQCMBWAsAGMBGAvAWADGAjAWgLEAjAVgLABjARgLwFgAxgIwFoCxAIwFYCwAYwEYC8BYAMYCMBaAsQCMBWAsAGMBGAvAWADGAjAWgLEAjAVgLABjARgLwFgAxgIwFoCxAIwFYCwAYwEYC8BYAMYCMBaAsQCMBWAsAGMBGAvAWADGAjAWgLEAjAVgLABjARgLwFgAxgIwFoCxAIwFsFus7mT9LH/nkBWp4QOu+NMdH0elT/azqHTDJsfEuZ+dYlm0iSOrll660bowqGB9mEXr6I81PewUy5RiLEvePnOmy+E39M8ED46JFHkEJ2iTYmb8vEikO3nF5nVb/D1je7767vm7gSX7KkTKVVveWblyTnxcU88T/+5882ZkTkBDyvrBMev9/1qwviFSYQpOWLJkV0Rc04OWHoFYvvlBJYkjtYXmDkVfSYFnv9Hw7r5j/r+oQxIOLhcRuVy3Td2VaXF7vf64qurKh11/7hWpLczS946uWpYa/s/KgOvLJ/Z0dWdqk1774f4LExesb/x6+a3O6razZ0/Ot7X08JztNXTGpehuwda0utDe9z7V1cw60nnNq2VwzNn9ZpCIiKYy/iWFOSrtzl4PJ49dntk3QkVCXdZmSMYO3SHTtXPjG4bNy7x0NSLpuYvj//jSNe7e/YkL1jdqC9c1zzeJpIaX2lh6BGKpN2ZJlpgXfb7zxd2Xzs027n+/OtunUr1BIyIi7n4Ks4iy06tLdCLKTokWmRMvIqL8VpvzW656o4hIX9H3QxFHRSQgsHqo0fojTlxwsrrRn+vbIiLydL5UPHjJ4bFKo6oaj6tE3J7NKKtruvCK8kLztuZGz93H/E95T/aMsUyGRcYW97dphofKRUQ0p9d8fSBPZFTXPuLTbutm9Rf33vBuMW0XvYgpBV+aKjt9Z8VoWhcevnj7/KjutPeV5yKODjzp7ue23NxxpPgv/WTPtM+oX2PR7s/zPTB37vgrq3tObGrzGUn8QJG/2vZN6xsJh2oKr169fb64GF/6n2NpKhuG66OXPjXvzp5Tn130i0zqdeoL2rnhZmp4S2hdzmSBiwvmmeqji6sU5vFXZr96MCndsOCnVp+PIm3ftL4R0JB7Mq4ptueF39Vb0aWpUjjqX5yXq84Mf6K0396Pb5eG5KUpzNkW75Yduul5z3b7gne0wGqlKkYzEhs2nOku0xTLYb9ZRsP1lLDrjrn9sBwW63HEvzoAGAvAWADGAjAWgLEAjAVgLABjARgLwFgAxgIwFoCxAIwFYCwAYwEYC8BYAMYCMBaAsQCMBWAsAGMBGAvAWADGAjAWgLEAjAVgLABjARgLwFgAxgIwFoCxAIwFYCwAYwEYC8BYAMYCMBaAsQCMBWAsAGMBGAvAWADGAjAWgLEAjAVgLABjARgLwFgAxgIwFoCxAIwFYCwAYwEYC8BYgL8BPUqEm58dkBoAAAAldEVYdGRhdGU6Y3JlYXRlADIwMjYtMDUtMjZUMTc6MTA6MjErMDA6MDAC6nzMAAAAJXRFWHRkYXRlOm1vZGlmeQAyMDI2LTA1LTI2VDE3OjEwOjIxKzAwOjAwc7fEcAAAAABJRU5ErkJggg=='

  private static final byte[] FALLBACK_150_PNG_BYTES =
    Base64.getDecoder().decode(EMBEDDED_FALLBACK_150_PNG_B64)

  private static final String FALLBACK_150_DATA_URL =
    'data:image/png;base64,' + EMBEDDED_FALLBACK_150_PNG_B64

  private static final ConcurrentHashMap<String, String> DATA_URL_CACHE = new ConcurrentHashMap<>()

  static {
    DATA_URL_CACHE.put("${DEFAULT_WIDTH}x${DEFAULT_HEIGHT}" as String, FALLBACK_150_DATA_URL)
  }

  /**
   * Private constructor; not for direct use.
   */
private CmsStudioPlaceholderImage() {}

  /**
   * Tool entry: optional {@code width}/{@code height} (aliases {@code w}/{@code h}); defaults 150×150.
   */
  static Map generate(Map input) {
    int width = resolveDimension(input?.width != null ? input.width : input?.w, DEFAULT_WIDTH, 'width')
    int height = resolveDimension(input?.height != null ? input.height : input?.h, DEFAULT_HEIGHT, 'height')
    String dataUrl = dataUrlForDimensions(width, height)
    return [
      ok      : true,
      width   : width,
      height  : height,
      dataUrl : dataUrl,
      mimeType: 'image/png',
      label   : SAMPLE_LABEL
    ]
  }

  /** Default XB-style placeholder used when {@link CmsWriteContent} fills required empty image-picker fields. */
  static String defaultRequiredEmptyImagePickerDataUrl() {
    return dataUrlForDimensions(DEFAULT_WIDTH, DEFAULT_HEIGHT)
  }

  /** True when the value is the common 1×1 PNG data URL models paste instead of calling {@link #generate}. */
  static boolean isMinimalStubDataUrl(String value) {
    String v = (value ?: '').toString().trim()
    return v && v == MINIMAL_STUB_DATA_URL
  }

  /**
   * Data url for dimensions.
   * @param width Caller-supplied input.
   * @param height Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  static String dataUrlForDimensions(int width, int height) {
    int w = clampDimension(width, DEFAULT_WIDTH)
    int h = clampDimension(height, DEFAULT_HEIGHT)
    if (w == DEFAULT_WIDTH && h == DEFAULT_HEIGHT) {
      return FALLBACK_150_DATA_URL
    }
    String cacheKey = w + 'x' + h
    String cached = DATA_URL_CACHE.get(cacheKey)
    if (cached != null) {
      return cached
    }
    byte[] png = renderSampleImagePngBytes(w, h)
    String dataUrl = 'data:image/png;base64,' + Base64.getEncoder().encodeToString(png)
    DATA_URL_CACHE.put(cacheKey, dataUrl)
    return dataUrl
  }

  /**
   * Resolves dimension from request and plugin context.
   * @param raw Caller-supplied input.
   * @param defaultVal Caller-supplied input.
   * @param fieldName Caller-supplied input.
   * @return int result.
   */
  private static int resolveDimension(Object raw, int defaultVal, String fieldName) {
    if (raw == null) {
      return defaultVal
    }
    int v
    if (raw instanceof Number) {
      v = ((Number) raw).intValue()
    } else {
      String s = raw.toString().trim()
      if (!s) {
        return defaultVal
      }
      try {
        v = Integer.parseInt(s)
      } catch (NumberFormatException nfe) {
        throw new IllegalArgumentException("Invalid ${fieldName}: ${s} (expected positive integer pixels)")
      }
    }
    if (v < MIN_DIMENSION || v > MAX_DIMENSION) {
      throw new IllegalArgumentException(
        "${fieldName} must be between ${MIN_DIMENSION} and ${MAX_DIMENSION} pixels (got ${v})"
      )
    }
    return v
  }

  /**
   * Clamp dimension.
   * @param value Caller-supplied input.
   * @param fallback Caller-supplied input.
   * @return int result.
   */
  private static int clampDimension(int value, int fallback) {
    if (value >= MIN_DIMENSION && value <= MAX_DIMENSION) {
      return value
    }
    return fallback
  }

  /**
   * Render sample image png bytes.
   * @param w Caller-supplied input.
   * @param h Caller-supplied input.
   * @return byte[] result.
   */
  private static byte[] renderSampleImagePngBytes(int w, int h) {
    if (w == DEFAULT_WIDTH && h == DEFAULT_HEIGHT) {
      return FALLBACK_150_PNG_BYTES
    }
    try {
      BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
      Graphics2D g = img.createGraphics()
      try {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setColor(new Color(0xf0, 0xf0, 0xf0))
        g.fillRect(0, 0, w, h)
        int fontSize = Math.max(12, Math.min(48, (int) (Math.min(w, h) / 5d)))
        Font font = new Font('SansSerif', Font.PLAIN, fontSize)
        g.setFont(font)
        g.setColor(Color.BLACK)
        FontMetrics fm = g.getFontMetrics()
        int tw = fm.stringWidth(SAMPLE_LABEL)
        int x = Math.max(0, (w - tw) / 2)
        int y = (h + fm.getAscent() - fm.getDescent()) / 2
        g.drawString(SAMPLE_LABEL, x, y)
      } finally {
        g.dispose()
      }
      ByteArrayOutputStream bos = new ByteArrayOutputStream()
      ImageIO.write(img, 'png', bos)
      return bos.toByteArray()
    } catch (Throwable t) {
      log.warn(
        'renderSampleImagePngBytes: AWT/ImageIO failed ({}x{}): {}, using embedded {}x{} fallback',
        w, h, t.message, DEFAULT_WIDTH, DEFAULT_HEIGHT
      )
      return FALLBACK_150_PNG_BYTES
    }
  }
}
