import { useCallback, useEffect, useMemo, useState } from 'react';
import AutoFixHighRounded from '@mui/icons-material/AutoFixHighRounded';
import {
  Autocomplete,
  Box,
  Button,
  Chip,
  Paper,
  Stack,
  TextField,
  Typography
} from '@mui/material';
import type { IntentRecipePhaseKey } from './aiAssistantIntentRecipesModel';
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

  return (
    <Stack spacing={1.5}>
      <Autocomplete
        multiple
        freeSolo
        options={templates}
        value={hintChips}
        onChange={(_, v) => setHintChips(v.map(String).map((s) => s.trim()).filter(Boolean))}
        filterSelectedOptions
        renderTags={(value, getTagProps) =>
          value.map((option, index) => (
            <Chip
              {...getTagProps({ index })}
              key={`${option}-${index}`}
              label={option.length > 72 ? `${option.slice(0, 69)}…` : option}
              size="small"
              sx={{ maxWidth: '100%', height: 'auto', '& .MuiChip-label': { whiteSpace: 'normal', py: 0.5 } }}
            />
          ))
        }
        renderInput={(params) => (
          <TextField
            {...params}
            label={`${PHASE_LABELS[phaseKey]} hints`}
            size="small"
            placeholder="Pick a template or type a hint and press Enter"
            helperText="One hint per line. Pick a template or type your own."
          />
        )}
      />

      {bindingNames.length > 0 ? (
        <Box>
          <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 0.75 }}>
            Insert placeholder
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
      ) : null}

      <Box>
        <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 0.75 }}>
          Insert CMS tool name
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

      {phaseKey === 'action' ? (
        <Paper variant="outlined" sx={{ p: 1.5, bgcolor: 'action.hover' }}>
          <Typography variant="subtitle2" gutterBottom>
            Action tool flow
          </Typography>
          <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 1.5 }}>
            Build hints like{' '}
            <em>Use update_content or GetContent → revise XML → WriteContent; preserve structure…</em>. Tools are
            ordered chips; click Generate to update the first hint line.
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
              onClick={applyActionFlow}
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
  );
}
