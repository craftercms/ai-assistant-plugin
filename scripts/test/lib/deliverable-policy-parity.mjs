/**
 * JavaScript parity for {@code AuthoringDeliverablePolicy} invariant guards (offline tests only).
 */

const CHAT_DELIVERABLES = new Set(['chat_prose', 'chat_answer']);
const REPO_CREATE_DELIVERABLES = new Set(['repo_create']);

const RECIPE_PRIMARY_DELIVERABLE = {
  llm_research: 'chat_prose',
  new_content_item: 'repo_create',
  new_content_item_from_chat_draft: 'repo_create',
  open_page_inquiry: 'repo_read',
  modify_page_content: 'repo_write',
};

const CHAT_PROSE_AUTHOR_INTENT =
  /\b(?:draft|write|compose|author|create|produce|prepare|develop)\b.{0,64}\b(?:blog|article|copy|prose|post|essay|brief|whitepaper|white\s+paper)\b|\b(?:blog|article|whitepaper|white\s+paper)\b.{0,64}\b(?:copy|post|draft|piece)\b|\bdraft\s+a\s+(?:substantive\s+)?(?:blog|article)\b/is;

const REPO_WRITE_AUTHOR_INTENT =
  /\b(?:writecontent|write\s+content|save\s+(?:this|it)\s+(?:to|on)|update\s+(?:this|the)\s+page|change\s+(?:this|the)\s+page|put\s+(?:this|it)\s+(?:on|into)\s+the\s+(?:page|site|cms)|publish\s+(?:this|the|now)|persist\s+to\s+(?:the\s+)?(?:repo|cms|site))\b|\b(?:redo|rewrite|refresh)\s+(?:this|the)\s+(?:page|homepage|home\s+page)\b/is;

const BROKEN_PREVIEW_ROUTER_REASON = /preview\s*\/\s*render\s+failure|broken\s+preview|repair\s+anchored\s+page/is;

const PERSIST_PRIOR_CHAT_DRAFT =
  /\b(?:this\s+looks\s+good|looks\s+great|that\s+works|(?:please\s+)?(?:save|create|make|publish)\s+(?:this|it|a\s+(?:new\s+)?(?:technical\s+)?(?:blog\s+)?(?:post|article|page)))\b|\bcreate\s+(?:a\s+)?(?:new\s+)?(?:technical\s+)?(?:blog\s+)?post\b|\b(?:save|publish)\s+(?:this|it)\s+(?:as\s+)?(?:a\s+)?(?:blog\s+)?(?:post|article)\b/is;

const CREATE_TARGET_CORRECTION =
  /\b(?:why\s+(?:are|were)\s+you\s+(?:updating|editing|changing|writing|modifying)|you\s+(?:updated|changed|edited|wrote\s+to)\s+(?:the\s+)?(?:wrong|home|homepage)|(?:not|don't)\s+(?:update|edit|change|modify|overwrite)\s+(?:the\s+)?(?:home|homepage|index|this\s+page)|i\s+(?:said|told\s+you|asked\s+(?:you\s+)?to)\s+(?:create|make|add)\s+(?:a\s+)?(?:new\s+)?(?:technical\s+)?(?:blog\s+)?post|create\s+(?:a\s+)?new\s+(?:technical\s+)?(?:blog\s+)?post)\b/is;

/** @param {string} wirePrompt */
export function extractPriorConversationBody(wirePrompt) {
  const w = String(wirePrompt ?? '');
  const marker = '[Prior conversation';
  const idx = w.indexOf(marker);
  if (idx < 0) {
    return '';
  }
  const sep = '\n---\n\nCurrent request:';
  const end = w.indexOf(sep, idx);
  const slice = end >= 0 ? w.slice(idx, end) : w.slice(idx);
  return slice.replace(/^[^\n]*\n?/, '').trim();
}

/** @param {string} priorBody @param {number} [minChars=200] */
export function priorConversationHasMaterializableAssistantReply(priorBody, minChars = 200) {
  const prior = String(priorBody ?? '');
  if (!prior.trim()) {
    return false;
  }
  const blocks = [];
  const re = /Assistant:\s*([\s\S]*?)(?=\nUser:|$)/gis;
  let m;
  while ((m = re.exec(prior)) !== null) {
    blocks.push(String(m[1] ?? '').trim());
  }
  const floor = Math.max(80, minChars);
  for (let i = blocks.length - 1; i >= 0; i--) {
    const block = blocks[i];
    if (block.length >= floor && !/\*\*GenerateTextNoTools\*\*/.test(block)) {
      return true;
    }
  }
  return false;
}

/** @param {string} authorVisible @param {string} wirePrompt */
export function authorVisibleLooksLikePersistPriorChatDraft(authorVisible, wirePrompt) {
  const req = String(authorVisible ?? '').trim();
  if (!req || req.length > 280) {
    return false;
  }
  if (!PERSIST_PRIOR_CHAT_DRAFT.test(req)) {
    return false;
  }
  const prior = extractPriorConversationBody(wirePrompt);
  return priorConversationHasMaterializableAssistantReply(prior, 200);
}

/** @param {string} authorVisible @param {string} [_wirePrompt] */
export function looksLikeCreateTargetCorrection(authorVisible, _wirePrompt) {
  const req = String(authorVisible ?? '').trim();
  if (!req) {
    return false;
  }
  return CREATE_TARGET_CORRECTION.test(req);
}

/**
 * @param {Record<string, unknown>} decision
 * @param {string} authorVisible
 * @param {string} wirePrompt
 * @param {string} [priorSessionObjective]
 */
export function bindApprovalToPersistRecipeMode(
  decision,
  authorVisible,
  wirePrompt,
  priorSessionObjective = '',
) {
  const out = { ...decision };
  const persistIntent =
    String(out.turnRelation ?? '').trim() === 'approval_to_persist' ||
    authorVisibleLooksLikePersistPriorChatDraft(authorVisible, wirePrompt);
  if (!persistIntent) {
    return out;
  }
  let rid = String(out.recipeId ?? '').trim();
  if (!rid) {
    rid = 'new_content_item_from_chat_draft';
    out.recipeId = rid;
  }
  if (rid === 'new_content_item_from_chat_draft' || rid === 'new_content_item') {
    out.mode = 'recipe';
    out.deliverable = 'repo_create';
    if (!String(out.turnRelation ?? '').trim()) {
      out.turnRelation = 'approval_to_persist';
    }
    const conf = Number(out.confidence);
    if (!Number.isFinite(conf) || conf < 0.55) {
      out.confidence = 0.9;
    }
    if (!String(out.sessionObjective ?? '').trim() && String(priorSessionObjective ?? '').trim()) {
      out.sessionObjective = String(priorSessionObjective).trim();
    }
  }
  return out;
}

/** @param {string | null | undefined} authorVisible */
export function authorVisibleLooksLikeChatProseBrief(authorVisible) {
  const v = String(authorVisible ?? '').trim();
  if (!v || v.length < 350) {
    return false;
  }
  if (REPO_WRITE_AUTHOR_INTENT.test(v)) {
    return false;
  }
  return CHAT_PROSE_AUTHOR_INTENT.test(v);
}

/** @param {Record<string, unknown>} out */
function forceChatOnly(out) {
  out.mode = 'chat_only';
  out.recipeId = null;
  out.toolName = null;
  if (!CHAT_DELIVERABLES.has(String(out.deliverable ?? ''))) {
    out.deliverable = 'chat_prose';
  }
}

/** @param {Record<string, unknown>} out */
function clearRepoRepairRouterReason(out) {
  const reason = String(out.reason ?? '').trim();
  if (!reason || !BROKEN_PREVIEW_ROUTER_REASON.test(reason)) {
    return;
  }
  const objective = String(out.sessionObjective ?? out.authorUnderstanding ?? out.turnGoal ?? '').trim();
  out.reason = objective
    ? 'Author wants substantive chat prose this turn — no repository writes unless they explicitly ask to persist.'
    : 'Substantive chat prose this turn — no repository writes unless the author explicitly asks to persist.';
}

/** @param {Record<string, unknown>} out */
function enforceRecipeDeliverableCompatibility(out) {
  if (out.mode !== 'recipe' || !out.recipeId) {
    return;
  }
  const rid = String(out.recipeId).trim();
  const recipeDeliverable = RECIPE_PRIMARY_DELIVERABLE[rid];
  if (!recipeDeliverable) {
    return;
  }
  const chosen = String(out.deliverable ?? '').trim();
  if (CHAT_DELIVERABLES.has(chosen) && REPO_CREATE_DELIVERABLES.has(recipeDeliverable)) {
    forceChatOnly(out);
  }
  if (CHAT_DELIVERABLES.has(chosen) && recipeDeliverable === 'repo_write') {
    forceChatOnly(out);
  }
}

/** @param {string} wire */
export function authoringScopeFieldEditActive(wire) {
  const w = String(wire ?? '');
  if (!w.includes('Scope: **selected Experience Builder field**')) {
    return false;
  }
  return /\bXB focused field id:\s*\S+/m.test(w) && /\bXB focused content item path:\s*\S+/m.test(w);
}

const XB_SCOPED_DEICTIC_COPY_EDIT =
  /\b(?:update|change|rewrite|re-?write|revise|rephrase|refresh|replace|make)\b.{0,96}\b(?:this|the|here)\b.{0,48}\b(?:copy|text|field|title|headline|subtitle|body)\b|\b(?:this|the)\s+(?:copy|text|field|title|headline)\b.{0,96}\b(?:update|change|rewrite|re-?write|revise|rephrase|refresh|replace|make)\b/is;

/** @param {string} wire @param {string} authorVisible */
export function authorVisibleSuggestsXbScopedFieldCopyEdit(wire, authorVisible) {
  if (!authoringScopeFieldEditActive(wire)) {
    return false;
  }
  const v = String(authorVisible ?? '').trim();
  return v.length > 0 && XB_SCOPED_DEICTIC_COPY_EDIT.test(v);
}

/**
 * @param {Record<string, unknown>} decision
 * @param {string | null | undefined} [authorVisible]
 * @param {string | null | undefined} [wirePrompt]
 */
export function finalizeAfterCorrections(decision, authorVisible = null, wirePrompt = null) {
  const out = { ...decision };
  const xbFieldEdit = authorVisibleSuggestsXbScopedFieldCopyEdit(wirePrompt, authorVisible);
  if (authorVisibleLooksLikeChatProseBrief(authorVisible)) {
    if (!xbFieldEdit) {
      out.deliverable = 'chat_prose';
      forceChatOnly(out);
      clearRepoRepairRouterReason(out);
    }
  }
  if (CHAT_DELIVERABLES.has(String(out.deliverable ?? ''))) {
    if (!xbFieldEdit) {
      forceChatOnly(out);
      clearRepoRepairRouterReason(out);
    }
  }
  enforceRecipeDeliverableCompatibility(out);
  return out;
}

/** Parity with {@code BROKEN_PREVIEW_REPAIR} in AuthoringPreviewContext.groovy */
export function authorVisibleReportsBrokenPreviewRepair(authorVisible) {
  const v = String(authorVisible ?? '').trim();
  if (!v) {
    return false;
  }
  const BROKEN_PREVIEW_REPAIR =
    /\b(?:500|502|503|http\s+500|server\s+error|rendering\s+error|free\s*marker\s+template\s+error)\b|\b(?:broke|broken|break|fix|repair|unacceptable|not\s+working|doesn.t\s+work|still\s+failing)\b(?!(?:\s+references?\b|\s+links?\b)).{0,96}\b(?:preview|(?:the|this|a)\s+page|homepage|home\s+page|site\b)\b|\b(?:preview|(?:the|this|a)\s+page|homepage|home\s+page)\b.{0,96}\b(?:broke|broken|500|error|unacceptable)\b(?!(?:\s+references?\b))|\bpreview\s+check\s+confir(?:m|med)\b/is;
  return BROKEN_PREVIEW_REPAIR.test(v);
}
