import { useCallback, useEffect, useMemo, useState } from 'react';
import AutoFixHighRounded from '@mui/icons-material/AutoFixHighRounded';
import BuildRounded from '@mui/icons-material/BuildRounded';
import {
  Autocomplete,
  Box,
  Button,
  Chip,
  Paper,
  Popover,
  Stack,
  TextField,
  Typography
} from '@mui/material';
import type { IntentRecipePhaseKey } from './aiAssistantIntentRecipesModel';
import { AiAssistantIntentRecipePrefetchBindingsHelp } from './AiAssistantIntentRecipePrefetchArgsHelp';
import {
  buildActionFlowHint,
  hintArrayToLines,
  hintLinesToArray,
  INTENT_RECIPE_HINT_TEMPLATES,
  INTENT_RECIPE_WIRE_TOOL_OPTIONS,
  parseActionFlowHint
} from './aiAssistantIntentRecipesModel';

const PHASE_LABELS: Record<IntentRecipePhaseKey, string> = {
  context: 'Context',
  action: 'Action',
  confirmation: 'Confirmation'
};

export interface AiAssistantIntentRecipePhaseHintsFieldProps {
  phaseKey: IntentRecipePhaseKey;
  hintsLines: string;
  /** Prefetch step {@code as} names in this phase — enables binding template chips. */
  bindingNames?: string[];
  onChange: (hintsLines: string) => void;
}

export default function AiAssistantIntentRecipePhaseHintsField(props: AiAssistantIntentRecipePhaseHintsFieldProps) {
  const { phaseKey, hintsLines, bindingNames = [], onChange } = props;
  const hintChips = useMemo(() => hintLinesToArray(hintsLines), [hintsLines]);
  const templates = INTENT_RECIPE_HINT_TEMPLATES[phaseKey];
  const [helperFlyoutAnchor, setHelperFlyoutAnchor] = useState<HTMLElement | null>(null);
  const [templatePick, setTemplatePick] = useState<string | null>(null);

  const [flowTools, setFlowTools] = useState<string[]>([]);
  const [flowMiddle, setFlowMiddle] = useState('revise XML');
  const [flowSuffix, setFlowSuffix] = useState(
    'preserve <page>/<component> structure and node-selector shapes.'
  );

  useEffect(() => {
    if (phaseKey !== 'action' || hintChips.length === 0) return;
    const parsed = parseActionFlowHint(hintChips[0]);
    if (parsed.tools.length > 0) {
      setFlowTools(parsed.tools);
      setFlowMiddle(parsed.middleStep);
      setFlowSuffix(parsed.suffix);
    }
  }, [phaseKey, hintChips[0]]);

  const setHintChips = useCallback(
    (next: string[]) => onChange(hintArrayToLines(next)),
    [onChange]
  );

  const appendToolToNewHint = (tool: string) => {
    const t = tool.trim();
    if (!t) return;
    const last = hintChips[hintChips.length - 1] ?? '';
    if (!last) {
      setHintChips([`Use ${t}`]);
      return;
    }
    if (last.includes('→')) {
      setHintChips([...hintChips.slice(0, -1), `${last} → ${t}`]);
    } else if (/\bUse\b/i.test(last)) {
      setHintChips([...hintChips.slice(0, -1), `${last} or ${t}`]);
    } else {
      setHintChips([...hintChips, `Use ${t}`]);
    }
  };

  const applyActionFlow = () => {
    const generated = buildActionFlowHint(flowTools, flowMiddle, flowSuffix);
    const rest = hintChips.slice(1);
    setHintChips([generated, ...rest]);
  };

  const appendTemplateLine = (line: string) => {
    const t = line.trim();
    if (!t) return;
    const lines = hintLinesToArray(hintsLines);
    if (lines.includes(t)) return;
    onChange(hintArrayToLines([...lines, t]));
  };

  return (
    <Stack spacing={2}>
      <TextField
        label={`${PHASE_LABELS[phaseKey]} hints`}
        value={hintsLines}
        onChange={(e) => onChange(e.target.value)}
        placeholder="One hint per line"
        helperText="One hint per line. Edit directly or insert a template below."
        fullWidth
        multiline
        minRows={10}
        maxRows={18}
        InputLabelProps={{ shrink: true }}
        sx={{
          mt: 0.5,
          '& .MuiInputLabel-root': {
            zIndex: 1,
            bgcolor: 'background.paper',
            px: 0.5
          },
          '& .MuiInputBase-root': {
            alignItems: 'flex-start',
            py: 1.5,
            px: 1.5
          },
          '& .MuiInputBase-inputMultiline': {
            lineHeight: 1.6,
            fontSize: '0.9375rem'
          }
        }}
      />

      {templates.length > 0 ? (
        <Autocomplete
          freeSolo
          options={templates}
          value={templatePick}
          onChange={(_, v) => {
            const line = (v ?? '').toString().trim();
            if (line) {
              appendTemplateLine(line);
            }
            setTemplatePick(null);
          }}
          renderInput={(params) => (
            <TextField
              {...params}
              size="small"
              label="Insert template line"
              placeholder="Pick a bundled hint to append"
            />
          )}
        />
      ) : null}

      <Stack direction="row" justifyContent="flex-end">
        <Button
          size="small"
          variant="outlined"
          startIcon={<BuildRounded />}
          onClick={(e) => setHelperFlyoutAnchor(e.currentTarget)}
          aria-haspopup="dialog"
          aria-expanded={Boolean(helperFlyoutAnchor)}
        >
          Tools & placeholders…
        </Button>
      </Stack>

      <Popover
        open={Boolean(helperFlyoutAnchor)}
        anchorEl={helperFlyoutAnchor}
        onClose={() => setHelperFlyoutAnchor(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
        slotProps={{ paper: { sx: { maxWidth: 520 } } }}
      >
        <Box sx={{ p: 2, maxHeight: 'min(70vh, 560px)', overflow: 'auto' }}>
          <Typography variant="subtitle2" gutterBottom>
            Insert into {PHASE_LABELS[phaseKey].toLowerCase()} hints
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Tool names and placeholders for hints; token reference for prefetch step JSON args below.
          </Typography>

          <Stack spacing={2}>
            <AiAssistantIntentRecipePrefetchBindingsHelp />

            <Box>
              <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 0.75 }}>
                Tool wire names (hints)
              </Typography>
              <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5 }}>
                {INTENT_RECIPE_WIRE_TOOL_OPTIONS.map((tool) => (
                  <Chip
                    key={tool}
                    label={tool}
                    size="small"
                    variant="outlined"
                    onClick={() => appendToolToNewHint(tool)}
                    sx={{ fontFamily: 'monospace', fontSize: 11, cursor: 'pointer' }}
                  />
                ))}
              </Box>
            </Box>

            {bindingNames.length > 0 ? (
              <Box>
                <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 0.75 }}>
                  Prefetch placeholders (from engine step bindings)
                </Typography>
                <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5 }}>
                  {bindingNames.flatMap((name) => [
                    <Chip
                      key={`initial-${name}`}
                      label={`{{initial.${name}}}`}
                      size="small"
                      variant="outlined"
                      onClick={() => setHintChips([...hintChips, `{{initial.${name}}}`])}
                      sx={{ fontFamily: 'monospace', fontSize: 11, cursor: 'pointer' }}
                    />,
                    <Chip
                      key={`current-${name}`}
                      label={`{{current.${name}}}`}
                      size="small"
                      variant="outlined"
                      onClick={() => setHintChips([...hintChips, `{{current.${name}}}`])}
                      sx={{ fontFamily: 'monospace', fontSize: 11, cursor: 'pointer' }}
                    />
                  ])}
                </Box>
              </Box>
            ) : (
              <Typography variant="caption" color="text.secondary">
                Add prefetch engine steps below to enable placeholder chips for{' '}
                <code>{'{{initial.name}}'}</code> / <code>{'{{current.name}}'}</code>.
              </Typography>
            )}

            {phaseKey === 'action' ? (
              <Paper variant="outlined" sx={{ p: 1.5, bgcolor: 'action.hover' }}>
                <Typography variant="subtitle2" gutterBottom>
                  Action tool flow
                </Typography>
                <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 1.5 }}>
                  Build one hint like{' '}
                  <em>Use update_content or GetContent → revise XML → WriteContent; preserve structure…</em>
                </Typography>
                <Stack spacing={1.5}>
                  <Autocomplete
                    multiple
                    freeSolo
                    options={[...INTENT_RECIPE_WIRE_TOOL_OPTIONS]}
                    value={flowTools}
                    onChange={(_, v) => setFlowTools(v.map(String).filter(Boolean))}
                    renderInput={(params) => (
                      <TextField
                        {...params}
                        label="Tools in flow (ordered)"
                        size="small"
                        placeholder="update_content, GetContent, WriteContent"
                      />
                    )}
                    renderTags={(value, getTagProps) =>
                      value.map((option, index) => (
                        <Chip
                          {...getTagProps({ index })}
                          key={`${option}-${index}`}
                          label={option}
                          size="small"
                          sx={{ fontFamily: 'monospace', fontSize: 11 }}
                        />
                      ))
                    }
                  />
                  <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
                    <TextField
                      label="After →"
                      value={flowMiddle}
                      onChange={(e) => setFlowMiddle(e.target.value)}
                      size="small"
                      fullWidth
                      placeholder="revise XML"
                    />
                    <TextField
                      label="After ;"
                      value={flowSuffix}
                      onChange={(e) => setFlowSuffix(e.target.value)}
                      size="small"
                      fullWidth
                      placeholder="preserve <page>/<component> structure…"
                    />
                  </Stack>
                  <Button
                    size="small"
                    variant="outlined"
                    startIcon={<AutoFixHighRounded />}
                    onClick={() => {
                      applyActionFlow();
                      setHelperFlyoutAnchor(null);
                    }}
                    sx={{ alignSelf: 'flex-start' }}
                  >
                    Generate action hint from tools
                  </Button>
                  {flowTools.length > 0 ? (
                    <Typography variant="body2" sx={{ fontSize: 12, color: 'text.secondary' }}>
                      Preview: {buildActionFlowHint(flowTools, flowMiddle, flowSuffix)}
                    </Typography>
                  ) : null}
                </Stack>
              </Paper>
            ) : null}
          </Stack>
        </Box>
      </Popover>
    </Stack>
  );
}
