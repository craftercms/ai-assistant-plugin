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

/** Mirrors server {@code AuthoringIntentRecipeCatalog.formatIntentRecipeChatLine} for Studio config preview. */
export function formatIntentRecipeChatLineFromRecipe(
  recipe: IntentRecipeChatLineSource,
  defaults?: { prefixEmoji?: string; fallbackEmoji?: string; lineSuffix?: string }
): string {
  const title = String(recipe.title ?? recipe.id ?? '').trim();
  if (!title) return '';
  const prefix = String(recipe.chatPrefixEmoji ?? defaults?.prefixEmoji ?? INTENT_RECIPE_CHAT_PREFIX_EMOJI).trim();
  const emoji = String(recipe.chatEmoji ?? defaults?.fallbackEmoji ?? INTENT_RECIPE_CHAT_FALLBACK_EMOJI).trim();
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

/** Author-visible intent contract from SSE {@code intentRecipeRouting.intentCardMarkdown}. */
export function intentCardFromRoutingTelemetry(telemetry: unknown): string | undefined {
  if (!telemetry || typeof telemetry !== 'object') return undefined;
  const o = telemetry as Record<string, unknown>;
  const card = String(o.intentCardMarkdown ?? '').trim();
  if (card) {
    return card.endsWith('\n') ? card : `${card}\n`;
  }
  const stepsRaw = o.authorRequestSteps;
  const steps = Array.isArray(stepsRaw)
    ? stepsRaw.map((s) => String(s).trim()).filter(Boolean)
    : [];
  const authorText = String(o.authorRequestText ?? o.turnGoal ?? '').trim();
  if (!authorText && !steps.length) return undefined;

  const lines = ['## Intent', ''];
  if (steps.length) {
    lines.push('**Your request:**');
    steps.forEach((step, i) => lines.push(`${i + 1}. ${step}`));
    lines.push('');
  } else if (authorText) {
    lines.push(`**Your request:** ${authorText}`, '');
  }
  const anchor = String(o.anchorPath ?? '').trim();
  if (anchor) {
    lines.push(`**On page:** \`${anchor}\``, '');
  }
  const criteria = String(o.successCriteria ?? '').trim();
  if (criteria) {
    lines.push('**Success looks like:**');
    criteria.split(/\s*;\s*/).forEach((bar) => {
      const t = bar.trim();
      if (t) lines.push(`- ${t}`);
    });
    lines.push('');
  }
  lines.push('_Proceeding with tools…_', '');
  return lines.join('\n');
}

/** Intent card first, then optional matched-recipe workflow line. */
export function intentRoutingDisplayMarkdown(telemetry: unknown, streamText?: string): string | undefined {
  const card = intentCardFromRoutingTelemetry(telemetry) || (streamText || '').trim();
  const recipe = intentRecipeLineFromRoutingTelemetry(telemetry);
  const parts = [card, recipe].filter((p) => p && p.trim());
  if (!parts.length) return undefined;
  return parts.join('\n');
}
