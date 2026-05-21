package plugins.org.craftercms.aiassistant.tools.http

import java.net.InetAddress
import java.net.URI
import java.util.Locale

/** SSRF policy for outbound HTTP from built-in tools (FetchHttpUrl, PostHttpUrl, WebSearch, SerpApiWebSearch). */
final class OutboundHttpPolicy {

  private OutboundHttpPolicy() {}
  static boolean globallyEnabled() {
    !'false'.equalsIgnoreCase(System.getProperty('aiassistant.httpFetch.enabled', 'true')?.toString()?.trim())
  }

  /**
   * Coerces requested caps vs defaults.
   * Ensures positive ceilings for buffered reads.
   * Feeds streaming guards inside HTTP helpers.
   */
  static int maxChars(Integer toolRequested) {
    int cap = 400_000
    try {
      def p = System.getProperty('aiassistant.httpFetch.maxChars')?.toString()?.trim()
      if (p) {
        cap = Integer.parseInt(p)
      }
    } catch (Throwable ignored) {}
    if (cap < 4096) {
      cap = 4096
    }
    if (cap > 2_000_000) {
      cap = 2_000_000
    }
    if (toolRequested != null) {
      try {
        int tr = toolRequested.intValue()
        if (tr > 0) {
          cap = Math.min(cap, Math.min(tr, 2_000_000))
        }
      } catch (Throwable ignored) {}
    }
    return cap
  }

  /**
   * Detects loopback/link-local/multicast addresses.
   * Honors ipv6 localhost expansions.
   * Mitigates internal network probing from prompts.
   */
  static boolean inetBlocked(InetAddress ia) {
    if (ia == null) {
      return true
    }
    if (ia.isAnyLocalAddress() || ia.isLoopbackAddress()) {
      return true
    }
    if (ia.isLinkLocalAddress() || ia.isSiteLocalAddress()) {
      return true
    }
    if (ia.isMulticastAddress()) {
      return true
    }
    byte[] a = ia.getAddress()
    if (a.length == 4) {
      int b0 = a[0] & 0xff
      int b1 = a[1] & 0xff
      if (b0 == 0) {
        return true
      }
      if (b0 == 100 && b1 >= 64 && b1 <= 127) {
        return true
      }
      return false
    }
    if (a.length == 16) {
      if (isIpv4MappedIpv6(a)) {
        byte[] v4 = [a[12], a[13], a[14], a[15]] as byte[]
        try {
          InetAddress embedded = InetAddress.getByAddress(v4)
          if (embedded.isLoopbackAddress() || embedded.isAnyLocalAddress() ||
            embedded.isLinkLocalAddress() || embedded.isSiteLocalAddress()) {
            return true
          }
        } catch (Throwable ignored) {
          return true
        }
        int b0 = v4[0] & 0xff
        int b1 = v4[1] & 0xff
        if (b0 == 0) {
          return true
        }
        if (b0 == 100 && b1 >= 64 && b1 <= 127) {
          return true
        }
        return false
      }
      int b0 = a[0] & 0xff
      int b1 = a[1] & 0xff
      if (b0 == 0xfe && (b1 & 0xc0) == 0x80) {
        return true
      }
      if ((b0 & 0xfe) == 0xfc) {
        return true
      }
      return false
    }
    return true
  }

  /** {@code ::ffff:0:0/96} — embedded IPv4 must be checked separately from native IPv6 rules. */
  private static boolean isIpv4MappedIpv6(byte[] a) {
    if (a == null || a.length != 16) {
      return false
    }
    for (int i = 0; i < 10; i++) {
      if (a[i] != (byte) 0) {
        return false
      }
    }
    return (a[10] & 0xff) == 0xff && (a[11] & 0xff) == 0xff
  }

  /**
   * Blocks bare IPs plus localhost synonyms.
   * Reads aiassistant.http.fetch.blockHosts additions.
   * Works alongside inet checks for defense in depth.
   */
  static boolean hostnameBlocked(String host) {
    if (!host) {
      return true
    }
    String h = host.toLowerCase(Locale.ROOT)
    if ('localhost' == h || '0.0.0.0' == h || '::1' == h || '[::1]' == h) {
      return true
    }
    if (h.endsWith('.local')) {
      return true
    }
    if ('169.254.169.254' == h) {
      return true
    }
    if ('metadata.google.internal' == h || 'metadata.google.internal.' == h) {
      return true
    }
    return false
  }

  /**
   * When JVM {@code aiassistant.httpFetch.allowedHostSuffixes} is set (comma-separated), host must equal a suffix or be a subdomain of it.
   * @return empty string if allowed, otherwise an error message
   */
  static String allowedSuffixesViolation(String host) {
    def prop = System.getProperty('aiassistant.httpFetch.allowedHostSuffixes')?.toString()?.trim()
    if (!prop) {
      return ''
    }
    List<String> parts = []
    for (String part : prop.split(',')) {
      def p = part.trim().toLowerCase(Locale.ROOT)
      if (p) {
        parts.add(p)
      }
    }
    if (parts.isEmpty()) {
      return ''
    }
    String h = host.toLowerCase(Locale.ROOT)
    for (String suf : parts) {
      if (h == suf || h.endsWith('.' + suf)) {
        return ''
      }
    }
    return "Host '${host}' is not in aiassistant.httpFetch.allowedHostSuffixes (${prop})."
  }

  /**
   * Validates scheme, userinfo, hostname blocklist, optional suffix allowlist, and that all resolved IPs are public.
   * @return {@code null} if OK, otherwise an error message
   */
  static String ssrfErrorForUri(URI u) {
    if (!u.scheme || (!'http'.equalsIgnoreCase(u.scheme) && !'https'.equalsIgnoreCase(u.scheme))) {
      return 'url must use http or https'
    }
    String rawUi = ''
    try {
      rawUi = u.getRawUserInfo() ?: ''
    } catch (Throwable ignored) {
      rawUi = ''
    }
    if (rawUi?.trim()) {
      return 'URLs with userinfo (user:password@) are not allowed'
    }
    String host = u.host
    if (!host) {
      return 'url must include a host'
    }
    if (hostnameBlocked(host)) {
      return "Host '${host}' is blocked for SSRF safety."
    }
    String suf = allowedSuffixesViolation(host)
    if (suf) {
      return suf
    }
    InetAddress[] resolved
    try {
      resolved = InetAddress.getAllByName(host)
    } catch (Throwable t) {
      return "DNS resolution failed: ${t.message}"
    }
    for (InetAddress ia : resolved) {
      if (inetBlocked(ia)) {
        return "Host '${host}' resolves to a non-public address (${ia.hostAddress}) — fetch denied."
      }
    }
    return null
  }
  /**
   * @return {@code null} if allowed, otherwise an error message
   */
  static String validateUrl(String absoluteUrl) {
    if (!globallyEnabled()) {
      return 'HTTP outbound is disabled (JVM aiassistant.httpFetch.enabled=false).'
    }
    URI start
    try {
      start = new URI((absoluteUrl ?: '').toString().trim())
    } catch (Throwable t) {
      return "Invalid URL: ${t.message}"
    }
    return ssrfErrorForUri(start)
  }
}
