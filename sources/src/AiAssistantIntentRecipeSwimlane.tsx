import { Box, Chip, Paper, Stack, Typography } from '@mui/material';
import ArrowForwardRounded from '@mui/icons-material/ArrowForwardRounded';
import type { IntentRecipe, IntentRecipeEngineStep, IntentRecipePhaseKey } from './aiAssistantIntentRecipesModel';
import {
  INTENT_RECIPE_CONFIRMATION_STEP_TOOLS,
  INTENT_RECIPE_PHASE_KEYS,
  INTENT_RECIPE_READ_ONLY_TOOLS,
  phaseEngineSteps,
  phaseHints
} from './aiAssistantIntentRecipesModel';
import { IntentRecipeHintLine } from './intentRecipeHintDisplay';
import { replaceSlackColonEmojisInText, resolveSlackColonEmojiToken } from './slackColonEmoji';

const PHASE_LABELS: Record<IntentRecipePhaseKey, string> = {
  context: 'Context',
  action: 'Action',
  confirmation: 'Confirmation'
};

function confirmationStepArgSummary(args: Record<string, string> | undefined): string[] {
  if (!args) return [];
  const chips: string[] = [];
  const text = String(args.text ?? args.message ?? '').trim();
  const icon = String(args.iconEmoji ?? '').trim();
  const thread = String(args.threadTs ?? '').trim();
  if (text) {
    const short = text.length > 48 ? `${text.slice(0, 45)}…` : text;
    chips.push(short);
  }
  if (icon) chips.push(resolveSlackColonEmojiToken(icon));
  if (thread) chips.push(`thread: ${thread}`);
  return chips;
}

function EngineStepCard(props: {
  step: IntentRecipeEngineStep;
  index: number;
  phaseKey: IntentRecipePhaseKey;
}) {
  const { step, index, phaseKey } = props;
  const isConfirmation = phaseKey === 'confirmation';
  const isLlmRefine = step.tool === 'llmRefine' || Boolean(step.llmRefine?.trim());
  const isSlack = step.tool === 'SlackPostMessage';

  const prefetchAllowlisted = (INTENT_RECIPE_READ_ONLY_TOOLS as readonly string[]).includes(step.tool);
  const confirmationKnown = (INTENT_RECIPE_CONFIRMATION_STEP_TOOLS as readonly string[]).includes(
    isLlmRefine ? 'llmRefine' : step.tool
  );

  let bgcolor = 'action.hover';
  let borderColor = 'divider';
  let caption: string | null = null;

  if (isConfirmation) {
    bgcolor = 'background.paper';
    borderColor = 'primary.light';
    if (!confirmationKnown) {
      bgcolor = 'warning.light';
      borderColor = 'warning.main';
      caption = 'Unknown confirmation step — verify server allowlist.';
    }
  } else if (!prefetchAllowlisted) {
    bgcolor = 'warning.light';
    borderColor = 'warning.main';
    caption = 'Not in read-only prefetch allowlist — server will skip at runtime.';
  }

  const title = isLlmRefine ? `llmRefine` : step.tool;
  const profile = step.llmRefine?.trim();

  return (
    <Paper variant="outlined" sx={{ p: 1.25, bgcolor, borderColor }}>
      <Typography variant="caption" color="text.secondary" display="block">
        Step {index + 1}
      </Typography>
      <Stack direction="row" spacing={0.5} alignItems="center" flexWrap="wrap" useFlexGap>
        <Typography variant="subtitle2">{title}</Typography>
        {profile ? (
          <Chip size="small" label={profile} variant="outlined" sx={{ fontFamily: 'monospace', fontSize: 11 }} />
        ) : null}
        {isLlmRefine && step.outputFormat ? (
          <Chip
            size="small"
            label={step.outputFormat}
            variant="outlined"
            sx={{ fontFamily: 'monospace', fontSize: 11 }}
          />
        ) : null}
        {step.as?.trim() ? (
          <Chip
            size="small"
            label={`as: ${step.as.trim()}`}
            color="primary"
            variant="outlined"
            sx={{ fontFamily: 'monospace', fontSize: 11 }}
          />
        ) : null}
      </Stack>
      {caption ? (
        <Typography variant="caption" color={isConfirmation && confirmationKnown ? 'text.secondary' : 'warning.dark'}>
          {caption}
        </Typography>
      ) : null}
      {isLlmRefine && (step.refineHints?.length ?? 0) > 0 ? (
        <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.5 }}>
          {step.refineHints!.length} refine hint{step.refineHints!.length === 1 ? '' : 's'}
        </Typography>
      ) : null}
      {isLlmRefine && (step.outputKeys?.length ?? 0) > 0 ? (
        <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap sx={{ mt: 0.75 }}>
          {step.outputKeys!.map((key) => (
            <Chip key={key} size="small" label={key} variant="outlined" sx={{ fontFamily: 'monospace', fontSize: 11 }} />
          ))}
        </Stack>
      ) : null}
      {isSlack && confirmationStepArgSummary(step.args).length > 0 ? (
        <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap sx={{ mt: 0.75 }}>
          {confirmationStepArgSummary(step.args).map((label, i) => (
            <Chip
              key={`${label}-${i}`}
              size="small"
              label={replaceSlackColonEmojisInText(label)}
              variant="outlined"
              sx={{ fontFamily: 'monospace', fontSize: 11 }}
            />
          ))}
        </Stack>
      ) : null}
      {!isSlack && !isLlmRefine && step.args && Object.keys(step.args).length > 0 ? (
        <Box component="pre" sx={{ mt: 0.75, mb: 0, fontSize: 11, whiteSpace: 'pre-wrap', fontFamily: 'monospace' }}>
          {JSON.stringify(step.args, null, 2)}
        </Box>
      ) : null}
    </Paper>
  );
}

function PhaseColumn(props: { phaseKey: IntentRecipePhaseKey; recipe: IntentRecipe }) {
  const { phaseKey, recipe } = props;
  const hints = phaseHints(recipe, phaseKey);
  const steps = phaseEngineSteps(recipe, phaseKey);
  const otherHints = hints;
  const stepsLabel =
    phaseKey === 'confirmation' ? 'Confirmation steps (server)' : 'Prefetch steps (read-only tools)';

  return (
    <Paper
      variant="outlined"
      sx={{
        flex: '1 1 0',
        minWidth: 200,
        maxHeight: { md: 420 },
        p: 1.5,
        display: 'flex',
        flexDirection: 'column',
        gap: 1,
        minHeight: 160,
        bgcolor: 'background.paper',
        overflow: 'hidden'
      }}
    >
      <Typography variant="overline" color="text.secondary" sx={{ lineHeight: 1.2, flexShrink: 0 }}>
        {PHASE_LABELS[phaseKey]}
      </Typography>
      <Box sx={{ flex: 1, minHeight: 0, overflow: 'auto', display: 'flex', flexDirection: 'column', gap: 1 }}>
        {otherHints.length > 0 ? (
          <Stack spacing={0.75}>
            <Typography variant="caption" color="text.secondary">
              Phase hints
            </Typography>
            {otherHints.map((h, i) => (
              <IntentRecipeHintLine key={`${phaseKey}-hint-${i}`} hint={h} index={i} />
            ))}
          </Stack>
        ) : null}
        {hints.length === 0 ? (
          <Typography variant="body2" color="text.disabled" sx={{ fontSize: 13 }}>
            No phase hints
          </Typography>
        ) : null}
        {steps.length > 0 ? (
          <Stack spacing={1} sx={{ mt: 0.5 }}>
            <Typography variant="caption" color="text.secondary">
              {stepsLabel}
            </Typography>
            {steps.map((step, i) => (
              <EngineStepCard key={`${phaseKey}-step-${i}`} step={step} index={i} phaseKey={phaseKey} />
            ))}
          </Stack>
        ) : null}
      </Box>
    </Paper>
  );
}

export interface AiAssistantIntentRecipeSwimlaneProps {
  recipe: IntentRecipe;
}

/**
 * Phased swimlane (Context → Action → Confirmation) aligned with server {@code AuthoringIntentRecipeCatalog}.
 */
export default function AiAssistantIntentRecipeSwimlane(props: AiAssistantIntentRecipeSwimlaneProps) {
  const { recipe } = props;
  const prefetchSteps = [
    ...phaseEngineSteps(recipe, 'context'),
    ...phaseEngineSteps(recipe, 'action')
  ];
  const confirmationSteps = phaseEngineSteps(recipe, 'confirmation');

  return (
    <Stack spacing={2}>
      <Box
        sx={{
          display: 'flex',
          flexDirection: { xs: 'column', md: 'row' },
          alignItems: { xs: 'stretch', md: 'flex-start' },
          gap: 1
        }}
      >
        {INTENT_RECIPE_PHASE_KEYS.map((key, idx) => (
          <Box key={key} sx={{ display: 'flex', flex: '1 1 0', alignItems: 'stretch', gap: 1, minWidth: 0 }}>
            <PhaseColumn phaseKey={key} recipe={recipe} />
            {idx < INTENT_RECIPE_PHASE_KEYS.length - 1 ? (
              <Box
                sx={{
                  display: { xs: 'none', md: 'flex' },
                  alignItems: 'center',
                  flexShrink: 0,
                  color: 'text.disabled',
                  pt: 4
                }}
              >
                <ArrowForwardRounded fontSize="small" />
              </Box>
            ) : null}
          </Box>
        ))}
      </Box>
      {confirmationSteps.length > 0 ? (
        <Paper variant="outlined" sx={{ p: 1.5, bgcolor: 'action.hover' }}>
          <Typography variant="caption" color="text.secondary" display="block" gutterBottom>
            Confirmation execution order (after tools loop)
          </Typography>
          <Stack direction="row" flexWrap="wrap" useFlexGap spacing={0.5} alignItems="center">
            {confirmationSteps.map((step, i) => (
              <Box key={`conf-${i}`} sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                {i > 0 ? <ArrowForwardRounded sx={{ fontSize: 14, color: 'text.disabled' }} /> : null}
                <Chip
                  size="small"
                  label={step.tool === 'llmRefine' ? `llmRefine (${step.llmRefine || '…'})` : step.tool}
                  variant="outlined"
                  color="primary"
                />
              </Box>
            ))}
          </Stack>
        </Paper>
      ) : null}
      {prefetchSteps.length > 0 ? (
        <Paper variant="outlined" sx={{ p: 1.5, bgcolor: 'background.default' }}>
          <Typography variant="caption" color="text.secondary" display="block" gutterBottom>
            Prefetch steps (context → action, when configured)
          </Typography>
          <Stack direction="row" flexWrap="wrap" useFlexGap spacing={0.5} alignItems="center">
            {prefetchSteps.map((step, i) => (
              <Box key={`prefetch-${i}`} sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                {i > 0 ? <ArrowForwardRounded sx={{ fontSize: 14, color: 'text.disabled' }} /> : null}
                <Chip size="small" label={step.tool} variant="outlined" />
              </Box>
            ))}
          </Stack>
        </Paper>
      ) : null}
    </Stack>
  );
}
