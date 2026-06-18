import { resolveSlackColonEmojiToken } from './slackColonEmoji';

/** Fallback when server does not send {@code recipeChatLine} (catalog {@code chatDefaults.prefixEmoji}). */
export const INTENT_RECIPE_CHAT_PREFIX_EMOJI = '🥗';

/** Fallback workflow emoji when server does not send {@code recipeChatLine} (catalog {@code chatDefaults.fallbackEmoji}). */
export const INTENT_RECIPE_CHAT_FALLBACK_EMOJI = '📋';

export type IntentRecipeChatLineSource = {
  id?: string;
  title?: string;
  chatEmoji?: string;
  chatPrefixEmoji?: string;
  chatLineSuffix?: string;
};

export type IntentCardModel = {
  elaboration: string;
  anchorPath?: string;
  successBars: string[];
  willNot: string[];
  recipeWorkflowLine?: string;
};

/** Mirrors server {@code AuthoringIntentRecipeCatalog.formatIntentRecipeChatLine} for Studio config preview. */
export function formatIntentRecipeChatLineFromRecipe(
  recipe: IntentRecipeChatLineSource,
  defaults?: { prefixEmoji?: string; fallbackEmoji?: string; lineSuffix?: string }
): string {
  const title = String(recipe.title ?? recipe.id ?? '').trim();
  if (!title) return '';
  const prefix = resolveSlackColonEmojiToken(
    String(recipe.chatPrefixEmoji ?? defaults?.prefixEmoji ?? INTENT_RECIPE_CHAT_PREFIX_EMOJI).trim()
  );
  const emoji = resolveSlackColonEmojiToken(
    String(recipe.chatEmoji ?? defaults?.fallbackEmoji ?? INTENT_RECIPE_CHAT_FALLBACK_EMOJI).trim()
  );
  const suffix = String(recipe.chatLineSuffix ?? defaults?.lineSuffix ?? 'workflow').trim() || 'workflow';
  return `${prefix} ${emoji} **${title}** ${suffix}\n`;
}

/** Legacy client fallback — prefer {@code intentRecipeRouting.recipeChatLine} from the server. */
export function formatIntentRecipeChatLine(recipeId: string, recipeTitle?: string): string {
  const id = recipeId.trim();
  if (!id) return '';
  const title = (recipeTitle ?? id).trim() || id;
  return `${INTENT_RECIPE_CHAT_PREFIX_EMOJI} ${INTENT_RECIPE_CHAT_FALLBACK_EMOJI} **${title}** workflow\n`;
}

export function intentRecipeLineFromRoutingTelemetry(telemetry: unknown): string | undefined {
  if (!telemetry || typeof telemetry !== 'object') return undefined;
  const o = telemetry as Record<string, unknown>;
  if (String(o.outcome ?? '') !== 'matched') return undefined;
  const fromServer = String(o.recipeChatLine ?? '').trim();
  if (fromServer) {
    return fromServer.endsWith('\n') ? fromServer : `${fromServer}\n`;
  }
  const id = String(o.recipeId ?? '').trim();
  if (!id) return undefined;
  const title = String(o.recipeTitle ?? '').trim() || id;
  return formatIntentRecipeChatLine(id, title);
}

function splitSuccessBars(criteria: string): string[] {
  const out: string[] = [];
  for (const part of criteria.split(/\s*;\s*/)) {
    const t = part.trim();
    if (t) out.push(t);
  }
  if (!out.length && criteria.trim()) {
    out.push(criteria.trim());
  }
  return out;
}

function stringListFromTelemetry(raw: unknown): string[] {
  if (!Array.isArray(raw)) return [];
  return raw.map((s) => String(s).trim()).filter(Boolean);
}

/** Structured intent card from SSE {@code intentRecipeRouting} (preferred over markdown fallback). */
export function parseIntentCardFromTelemetry(telemetry: unknown): IntentCardModel | undefined {
  if (!telemetry || typeof telemetry !== 'object') return undefined;
  const o = telemetry as Record<string, unknown>;

  const elaboration = String(o.intentCardElaboration ?? '').trim();
  const recipeWorkflowLine = intentRecipeLineFromRoutingTelemetry(telemetry);
  const willNot = stringListFromTelemetry(o.intentCardWillNot);
  const anchorPath = String(o.intentCardAnchorPath ?? o.anchorPath ?? '').trim() || undefined;
  let successBars = stringListFromTelemetry(o.intentCardSuccessBars);
  if (!successBars.length) {
    const criteria = String(o.successCriteria ?? '').trim();
    if (criteria) {
      successBars = splitSuccessBars(criteria);
    }
  }

  if (elaboration) {
    return {
      elaboration,
      anchorPath,
      successBars,
      willNot,
      ...(recipeWorkflowLine ? { recipeWorkflowLine } : {})
    };
  }

  const card = String(o.intentCardMarkdown ?? '').trim();
  if (!card) {
    const authorText = String(o.authorRequestText ?? o.turnGoal ?? '').trim();
    if (!authorText) return undefined;
    return {
      elaboration: authorText,
      anchorPath,
      successBars,
      willNot,
      ...(recipeWorkflowLine ? { recipeWorkflowLine } : {})
    };
  }

  const lines = card.split(/\r?\n/);
  const body: string[] = [];
  let inSuccess = false;
  let parsedAnchor: string | undefined = anchorPath;
  const parsedSuccess: string[] = [];
  const parsedWillNot: string[] = [];
  let inWillNot = false;

  for (const line of lines) {
    const t = line.trim();
    if (!t || t === '## Intent') continue;
    if (t.startsWith('**On page:**')) {
      const m = /`([^`]+)`/.exec(t);
      if (m?.[1]) parsedAnchor = m[1].trim();
      continue;
    }
    if (t.startsWith('**Success looks like:**')) {
      inSuccess = true;
      inWillNot = false;
      continue;
    }
    if (t.startsWith('**I will not:**')) {
      inWillNot = true;
      inSuccess = false;
      continue;
    }
    if (t.startsWith('_Proceeding with tools')) {
      inSuccess = false;
      inWillNot = false;
      continue;
    }
    if (inSuccess && t.startsWith('- ')) {
      parsedSuccess.push(t.slice(2).trim());
      continue;
    }
    if (inWillNot && t.startsWith('- ')) {
      parsedWillNot.push(t.slice(2).trim());
      continue;
    }
    if (!t.startsWith('**') && !inSuccess && !inWillNot) {
      body.push(t);
    }
  }

  const parsedElaboration = body.join(' ').trim();
  if (!parsedElaboration) return undefined;

  return {
    elaboration: parsedElaboration,
    anchorPath: parsedAnchor,
    successBars: parsedSuccess.length ? parsedSuccess : successBars,
    willNot: parsedWillNot.length ? parsedWillNot : willNot,
    ...(recipeWorkflowLine ? { recipeWorkflowLine } : {})
  };
}

/** Author-visible intent contract from SSE {@code intentRecipeRouting.intentCardMarkdown}. */
export function intentCardFromRoutingTelemetry(telemetry: unknown): string | undefined {
  const model = parseIntentCardFromTelemetry(telemetry);
  if (model?.elaboration) {
    const lines = ['## Intent', '', model.elaboration, ''];
    if (model.anchorPath) {
      lines.push(`**On page:** \`${model.anchorPath}\``, '');
    }
    if (model.successBars.length) {
      lines.push('**Success looks like:**');
      model.successBars.forEach((bar) => lines.push(`- ${bar}`));
      lines.push('');
    }
    lines.push('_Proceeding with tools…_', '');
    return lines.join('\n');
  }
  return undefined;
}

/** Intent card first, then optional matched-recipe workflow line. */
export function intentRoutingDisplayMarkdown(telemetry: unknown, streamText?: string): string | undefined {
  const card = intentCardFromRoutingTelemetry(telemetry) || (streamText || '').trim();
  const recipe = intentRecipeLineFromRoutingTelemetry(telemetry);
  const parts = [card, recipe].filter((p) => p && p.trim());
  if (!parts.length) return undefined;
  return parts.join('\n');
}
