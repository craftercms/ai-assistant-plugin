/**
 * Slack-style {@code :short_name:} tokens for Studio UI (recipe swimlane chips, chat markdown, workflow emoji).
 * Unknown names are left unchanged.
 */
const SLACK_COLON_EMOJI: Readonly<Record<string, string>> = {
  '+1': '👍',
  '-1': '👎',
  arrow_down: '⬇️',
  arrow_left: '⬅️',
  arrow_right: '➡️',
  arrow_up: '⬆️',
  bulb: '💡',
  dart: '🎯',
  gear: '⚙️',
  hammer_and_wrench: '🛠️',
  link: '🔗',
  memo: '📝',
  pencil2: '✏️',
  pushpin: '📌',
  rocket: '🚀',
  thought_balloon: '💭',
  white_check_mark: '✅',
  warning: '⚠️',
  x: '❌',
  books: '📚',
  book: '📖',
  clipboard: '📋',
  light_bulb: '💡',
  mag: '🔍',
  microscope: '🔬',
  newspaper: '📰',
  page_facing_up: '📄',
  spiral_note_pad: '🗒️',
  speech_balloon: '💬',
  star: '⭐',
  tada: '🎉',
  thumbsup: '👍',
  thumbsdown: '👎',
  wrench: '🔧',
  zap: '⚡',
  chart_with_upwards_trend: '📈',
  bar_chart: '📊',
  calendar: '📅',
  clock1: '🕐',
  email: '📧',
  envelope: '✉️',
  eyes: '👀',
  fire: '🔥',
  globe_with_meridians: '🌐',
  heart: '❤️',
  information_source: 'ℹ️',
  key: '🔑',
  lock: '🔒',
  package: '📦',
  point_right: '👉',
  question: '❓',
  recycle: '♻️',
  robot_face: '🤖',
  rotating_light: '🚨',
  seedling: '🌱',
  shield: '🛡️',
  sparkles: '✨',
  siren: '🚨',
  stopwatch: '⏱️',
  trophy: '🏆',
  umbrella: '☂️',
  vertical_traffic_light: '🚦',
  wave: '👋',
  writing_hand: '✍️'
};

const COLON_EMOJI_RE = /:([a-z0-9_+-]+):/gi;

const MARKDOWN_FENCE_RE = /(`{3,}|~{3,})[^\n]*\n[\s\S]*?\1/g;

function slackColonEmojiNameToUnicode(name: string): string | undefined {
  const key = name.trim().toLowerCase();
  return SLACK_COLON_EMOJI[key];
}

/** When {@code value} is exactly {@code :name:}, return the Unicode emoji if known. */
export function resolveSlackColonEmojiToken(value: string): string {
  const t = value.trim();
  const m = /^:([a-z0-9_+-]+):$/i.exec(t);
  if (!m) {
    return value;
  }
  return slackColonEmojiNameToUnicode(m[1]!) ?? value;
}

/** Replace every {@code :name:} token in prose. */
export function replaceSlackColonEmojisInText(text: string): string {
  if (!text || !text.includes(':')) {
    return text;
  }
  return text.replace(COLON_EMOJI_RE, (full, name: string) => slackColonEmojiNameToUnicode(name) ?? full);
}

function textHasMarkdownFences(text: string): boolean {
  return text.includes('```') || text.includes('~~~');
}

/** Replace colon emojis outside fenced code blocks (safe for assistant markdown). */
export function replaceSlackColonEmojisOutsideMarkdownFences(text: string): string {
  if (!text || !text.includes(':')) {
    return text;
  }
  if (!textHasMarkdownFences(text)) {
    return replaceSlackColonEmojisInText(text);
  }
  const parts: string[] = [];
  let last = 0;
  let m: RegExpExecArray | null;
  MARKDOWN_FENCE_RE.lastIndex = 0;
  while ((m = MARKDOWN_FENCE_RE.exec(text)) !== null) {
    if (m.index > last) {
      parts.push(replaceSlackColonEmojisInText(text.slice(last, m.index)));
    }
    parts.push(m[0]);
    last = m.index + m[0].length;
  }
  if (last < text.length) {
    parts.push(replaceSlackColonEmojisInText(text.slice(last)));
  }
  return parts.join('');
}
