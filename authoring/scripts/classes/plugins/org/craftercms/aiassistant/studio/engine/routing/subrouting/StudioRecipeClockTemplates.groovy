package plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Expands {@code {{studio.today}}} / {@code {{studio.now}}} placeholders in recipe phase hints.
 * <ul>
 *   <li>{@code {{studio.today}}} — calendar date on the Studio JVM (server zone)</li>
 *   <li>{@code {{studio.today-7D}}} — same, minus 7 days (units: {@code D}, {@code W}, {@code M})</li>
 *   <li>{@code {{studio.now}}} — date and time on the Studio JVM</li>
 *   <li>{@code {{studio.now-2H}}} — same, minus offset (units: {@code H}, {@code D}, {@code W}, {@code M})</li>
 * </ul>
 * Whitespace around {@code -} is optional ({@code today- 7D} works). Offsets always subtract from the anchor.
 */
final class StudioRecipeClockTemplates {

  private static final ZoneId SERVER_ZONE = ZoneId.systemDefault()
  private static final DateTimeFormatter DATE_FMT =
    DateTimeFormatter.ofPattern('MMMM d, yyyy', Locale.ENGLISH)
  private static final DateTimeFormatter DATE_TIME_FMT =
    DateTimeFormatter.ofPattern('MMMM d, yyyy · HH:mm z', Locale.ENGLISH)

  private static final Pattern STUDIO_CLOCK = Pattern.compile(
    '(?i)\\{\\{\\s*studio\\.(today|now)(?:\\s*-\\s*(\\d+)\\s*([DWMH]))?\\s*\\}\\}'
  )

  /**
   * Private constructor; not for direct use.
   */
private StudioRecipeClockTemplates() {}

  /**
   * Expand.
   * @param text Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  static String expand(String text) {
    return expand(text, null)
  }

  /** @param anchor optional fixed instant (tests); defaults to {@link Instant#now()} */
  static String expand(String text, Instant anchor) {
    if (!(text instanceof CharSequence) || !text.toString().contains('{{studio.')) {
      return text?.toString() ?: ''
    }
    Instant base = anchor != null ? anchor : Instant.now()
    Matcher m = STUDIO_CLOCK.matcher(text.toString())
    StringBuffer sb = new StringBuffer()
    while (m.find()) {
      String anchorKind = m.group(1)
      int amount = 0
      String amountStr = m.group(2)
      if (amountStr?.trim()) {
        try {
          amount = Integer.parseInt(amountStr.trim())
        } catch (NumberFormatException ignored) {
          amount = 0
        }
      }
      char unit = 'D'
      String unitStr = m.group(3)
      if (unitStr?.trim()) {
        unit = Character.toUpperCase(unitStr.trim().charAt(0))
      }
      String rep = formatClockToken(anchorKind, amount, unit, base)
      m.appendReplacement(sb, Matcher.quoteReplacement(rep))
    }
    m.appendTail(sb)
    return sb.toString()
  }

  /**
   * Format clock token.
   * @param anchorKind Caller-supplied input.
   * @param amount Caller-supplied input.
   * @param unit Caller-supplied input.
   * @param base Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String formatClockToken(String anchorKind, int amount, char unit, Instant base) {
    ZonedDateTime z = base.atZone(SERVER_ZONE)
    boolean todayAnchor = 'today'.equalsIgnoreCase((anchorKind ?: '').toString().trim())
    if (amount > 0) {
      z = subtract(z, amount, unit, todayAnchor)
    } else if (todayAnchor) {
      z = z.toLocalDate().atStartOfDay(SERVER_ZONE)
    }
    if (todayAnchor) {
      return DATE_FMT.format(z)
    }
    return DATE_TIME_FMT.format(z)
  }

  /**
   * Subtract.
   * @param z Caller-supplied input.
   * @param amount Caller-supplied input.
   * @param unit Caller-supplied input.
   * @param todayAnchor Caller-supplied input.
   * @return ZonedDateTime result.
   */
  private static ZonedDateTime subtract(ZonedDateTime z, int amount, char unit, boolean todayAnchor) {
    if (amount <= 0) {
      return todayAnchor ? z.toLocalDate().atStartOfDay(SERVER_ZONE) : z
    }
    switch (Character.toUpperCase(unit)) {
      case 'H':
        return z.minusHours(amount)
      case 'D':
        return z.minusDays(amount)
      case 'W':
        return z.minusWeeks(amount)
      case 'M':
        return z.minusMonths(amount)
      default:
        return todayAnchor ? z.toLocalDate().atStartOfDay(SERVER_ZONE) : z
    }
  }
}
