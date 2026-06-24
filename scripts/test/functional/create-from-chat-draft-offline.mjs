#!/usr/bin/env node
/**
 * Offline parity for create-from-chat-draft prefetch (Turn 3/4 regression guards).
 */
import {
  inferCreateTypePhraseCandidates,
  resolveCreateContentTypeFromCatalog,
  assistantBlockLooksLikeToolStrip,
  lastSubstantiveAssistantBlockText,
  slugifyForRepositoryFileName,
  CRAFTERCMS_BLOG_TYPE_ROWS,
} from '../lib/create-from-chat-draft-parity.mjs';
import {
  bindApprovalToPersistRecipeMode,
  looksLikeCreateTargetCorrection,
  authorVisibleLooksLikePersistPriorChatDraft,
} from '../lib/deliverable-policy-parity.mjs';

function assert(cond, msg) {
  if (!cond) {
    console.error(`FAIL: ${msg}`);
    process.exit(1);
  }
}

const priorDraftBlog = `User: Draft a substantive blog copy about the Crafter Studio AI Assistant.

Assistant: The Crafter Studio AI Assistant represents a significant evolution in how content management systems can leverage artificial intelligence. Unlike generic AI tools that may only assist with text generation, the AI Assistant is intricately integrated into CrafterCMS Studio itself.

User: Can you add some bullets?

Assistant: Certainly! Here's the refined blog copy with emphasized bullet points.

- **Page structure**: Knowledge of page layouts enhances content consistency.
- **Components and content types**: Tailored guidance based on specific project requirements.

The value of having an AI Assistant integrated into Studio is evident during the authoring process.`;

const priorWithToolStrip = `${priorDraftBlog}

User: This looks good, create a technical blog post by Russ

Assistant: 🛠️🔍 **GenerateTextNoTools** …

🛠️🔍 ✅ **GenerateTextNoTools** finished. ·10.6s

🛠️✏️ **WriteContent** (\`/site/website/index.xml\`) …

*Stopped.*`;

// --- content type resolution ---

const turn3Current = 'This looks good, create a technical blog post by Russ';
const phrases = inferCreateTypePhraseCandidates(priorDraftBlog, turn3Current);
assert(phrases[0] === 'article', 'create technical blog post → article first in phrase list');
assert(!phrases.includes('blog'), 'bare blog phrase removed when post/article intent present');

const resolved = resolveCreateContentTypeFromCatalog(CRAFTERCMS_BLOG_TYPE_ROWS, phrases);
assert(resolved === '/page/article', 'blog post intent resolves to /page/article not /page/blog roll');

// --- draft extraction skips tool strips ---

assert(assistantBlockLooksLikeToolStrip('🛠️🔍 **GenerateTextNoTools** …'), 'tool strip detected');
const substantive = lastSubstantiveAssistantBlockText(priorWithToolStrip);
assert(substantive.includes('bullet points'), 'substantive block skips GenerateTextNoTools strip');
assert(!substantive.includes('GenerateTextNoTools'), 'tool strip not in substantive block');
assert(
  slugifyForRepositoryFileName('🛠️🔍 **GenerateTextNoTools** …') === '',
  'tool-strip title does not slugify',
);

// --- deliverable policy ---

const wireTurn3 = `[Prior conversation — abbreviated for context. Current request follows after the separator.]

${priorDraftBlog}

---

Current request:
${turn3Current}`;

const bound = bindApprovalToPersistRecipeMode(
  { mode: 'plan', recipeId: 'new_content_item_from_chat_draft', confidence: 0.4 },
  turn3Current,
  wireTurn3,
  'Draft blog copy about AI Assistant',
);
assert(bound.mode === 'recipe', 'approval bind forces recipe mode (not plan defer)');
assert(bound.deliverable === 'repo_create', 'approval bind sets repo_create');
assert(Number(bound.confidence) >= 0.55, 'approval bind boosts confidence');

assert(
  authorVisibleLooksLikePersistPriorChatDraft(turn3Current, wireTurn3),
  'Turn 3 request detected as persist-from-chat',
);

const turn4 = 'Why are you updating the home page?I told you to create a new technical blog post';
assert(
  looksLikeCreateTargetCorrection(turn4, wireTurn3),
  'Turn 4 wrong-page correction detected',
);

const corrected = bindApprovalToPersistRecipeMode(
  {
    mode: 'plan',
    turnRelation: 'correction',
    deliverable: 'repo_create',
    recipeId: 'new_content_item_from_chat_draft',
    confidence: 0.85,
  },
  turn4,
  wireTurn3,
  'Create technical blog post from draft',
);
assert(corrected.mode === 'recipe', 'correction with create target keeps recipe mode');

console.log('OK create-from-chat-draft-offline');
