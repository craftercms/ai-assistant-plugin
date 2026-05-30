package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal

import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Minimal RGB PNG encoder without {@code java.awt} / {@code ImageIO} (Studio Groovy sandbox blocks
 * {@code System.nanoTime()} inside those stacks).
 */
final class CmsSandboxSafePng {

  private CmsSandboxSafePng() {}

  /** Solid-fill PNG (8-bit RGB, filter type 0 per scanline). */
  static byte[] solidColorPng(int width, int height, int rgb) {
    if (width < 1 || height < 1) {
      throw new IllegalArgumentException('width and height must be positive')
    }
    int r = (rgb >> 16) & 0xFF
    int g = (rgb >> 8) & 0xFF
    int b = rgb & 0xFF
    int rowLen = 1 + width * 3
    byte[] raw = new byte[rowLen * height]
    for (int y = 0; y < height; y++) {
      int rowStart = y * rowLen
      raw[rowStart] = 0
      for (int x = 0; x < width; x++) {
        int i = rowStart + 1 + x * 3
        raw[i] = (byte) r
        raw[i + 1] = (byte) g
        raw[i + 2] = (byte) b
      }
    }
    byte[] compressed = deflate(raw)
    ByteArrayOutputStream png = new ByteArrayOutputStream(64 + compressed.length)
    png.write([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A] as byte[])
    writeChunk(png, 'IHDR', ihdrBytes(width, height))
    writeChunk(png, 'IDAT', compressed)
    writeChunk(png, 'IEND', new byte[0])
    return png.toByteArray()
  }

  private static byte[] ihdrBytes(int width, int height) {
    byte[] b = new byte[13]
    putInt32(b, 0, width)
    putInt32(b, 4, height)
    b[8] = 8
    b[9] = 2
    b[10] = 0
    b[11] = 0
    b[12] = 0
    return b
  }

  private static byte[] deflate(byte[] raw) {
    Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION)
    try {
      deflater.setInput(raw)
      deflater.finish()
      ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length / 4 + 32)
      byte[] buf = new byte[8192]
      while (!deflater.finished()) {
        int n = deflater.deflate(buf)
        if (n > 0) {
          bos.write(buf, 0, n)
        }
      }
      return bos.toByteArray()
    } finally {
      deflater.end()
    }
  }

  private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
    byte[] typeBytes = type.getBytes('US-ASCII')
    writeInt32(out, data.length)
    out.write(typeBytes)
    if (data.length) {
      out.write(data)
    }
    CRC32 crc = new CRC32()
    crc.update(typeBytes)
    if (data.length) {
      crc.update(data)
    }
    writeInt32(out, (int) crc.getValue())
  }

  private static void putInt32(byte[] dest, int offset, int value) {
    dest[offset] = (byte) ((value >> 24) & 0xFF)
    dest[offset + 1] = (byte) ((value >> 16) & 0xFF)
    dest[offset + 2] = (byte) ((value >> 8) & 0xFF)
    dest[offset + 3] = (byte) (value & 0xFF)
  }

  private static void writeInt32(ByteArrayOutputStream out, int value) {
    out.write((byte) ((value >> 24) & 0xFF))
    out.write((byte) ((value >> 16) & 0xFF))
    out.write((byte) ((value >> 8) & 0xFF))
    out.write((byte) (value & 0xFF))
  }
}
