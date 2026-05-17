import { Box, Chip, Paper, Stack, Typography } from '@mui/material';
import ArrowForwardRounded from '@mui/icons-material/ArrowForwardRounded';
import type { IntentRecipe, IntentRecipeEngineStep, IntentRecipePhaseKey } from './aiAssistantIntentRecipesModel';
import {
  INTENT_RECIPE_PHASE_KEYS,
  INTENT_RECIPE_READ_ONLY_TOOLS,
  phaseEngineSteps,
  phaseHints
} from './aiAssistantIntentRecipesModel';

const PHASE_LABELS: Record<IntentRecipePhaseKey, string> = {
  context: 'Context',
  action: 'Action',
  confirmation: 'Confirmation'
};

function EngineStepCard(props: { step: IntentRecipeEngineStep; index: number }) {
  const { step, index } = props;
  const allowlisted = (INTENT_RECIPE_READ_ONLY_TOOLS as readonly string[]).includes(step.tool);
  return (
    <Paper
      variant="outlined"
      sx={{
        p: 1.25,
        bgcolor: allowlisted ? 'action.hover' : 'warning.light',
        borderColor: allowlisted ? 'divider' : 'warning.main'
      }}
    >
      <Typography variant="caption" color="text.secondary" display="block">
        Step {index + 1}
      </Typography>
      <Stack direction="row" spacing={0.5} alignItems="center" flexWrap="wrap" useFlexGap>
        <Typography variant="subtitle2">{step.tool}</Typography>
        {step.as?.trim() ? (
          <Chip size="small" label={`as: ${step.as.trim()}`} color="primary" variant="outlined" sx={{ fontFamily: 'monospace', fontSize: 11 }} />
        ) : null}
      </Stack>
      {!allowlisted ? (
        <Typography variant="caption" color="warning.dark">
          Not in read-only prefetch allowlist — server will skip at runtime.
        </Typography>
      ) : null}
      {step.args && Object.keys(step.args).length > 0 ? (
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

  return (
    <Paper
      variant="outlined"
      sx={{
        flex: '1 1 0',
        minWidth: 200,
        p: 1.5,
        display: 'flex',
        flexDirection: 'column',
        gap: 1,
        minHeight: 160,
        bgcolor: 'background.paper'
      }}
    >
      <Typography variant="overline" color="text.secondary" sx={{ lineHeight: 1.2 }}>
        {PHASE_LABELS[phaseKey]}
      </Typography>
      {hints.length > 0 ? (
        <Stack spacing={0.5}>
          {hints.map((h, i) => (
            <Typography key={`${phaseKey}-hint-${i}`} variant="body2" sx={{ fontSize: 13 }}>
              {h}
            </Typography>
          ))}
        </Stack>
      ) : (
        <Typography variant="body2" color="text.disabled" sx={{ fontSize: 13 }}>
          No phase hints
        </Typography>
      )}
      {steps.length > 0 ? (
        <Stack spacing={1} sx={{ mt: 0.5 }}>
          <Typography variant="caption" color="text.secondary">
            Prefetch wiring
          </Typography>
          {steps.map((step, i) => (
            <EngineStepCard key={`${phaseKey}-step-${i}`} step={step} index={i} />
          ))}
        </Stack>
      ) : null}
    </Paper>
  );
}

export interface AiAssistantIntentRecipeSwimlaneProps {
  recipe: IntentRecipe;
}

/**
 * Phased swimlane (Context → Action → Confirmation) aligned with server {@code AuthoringIntentRecipeCatalog}.
 * Tool steps render as wired cards inside the phase that owns {@code engineSteps}.
 */
export default function AiAssistantIntentRecipeSwimlane(props: AiAssistantIntentRecipeSwimlaneProps) {
  const { recipe } = props;
  const prefetchOrder = INTENT_RECIPE_PHASE_KEYS.flatMap((k) => phaseEngineSteps(recipe, k));

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
      {prefetchOrder.length > 1 ? (
        <Paper variant="outlined" sx={{ p: 1.5, bgcolor: 'action.hover' }}>
          <Typography variant="caption" color="text.secondary" display="block" gutterBottom>
            Server prefetch execution order (context → action → confirmation)
          </Typography>
          <Stack direction="row" flexWrap="wrap" useFlexGap spacing={0.5} alignItems="center">
            {prefetchOrder.map((step, i) => (
              <Box key={`flow-${i}`} sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
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
