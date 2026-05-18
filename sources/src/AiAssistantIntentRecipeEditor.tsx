import { useCallback, useEffect, useMemo, useState } from 'react';
import AddRounded from '@mui/icons-material/AddRounded';
import DeleteOutlineRounded from '@mui/icons-material/DeleteOutlineRounded';
import DragIndicatorRounded from '@mui/icons-material/DragIndicatorRounded';
import {
  Autocomplete,
  Box,
  Button,
  IconButton,
  Paper,
  Stack,
  Tab,
  Tabs,
  TextField,
  Typography
} from '@mui/material';
import type {
  IntentRecipe,
  IntentRecipeEngineStep,
  IntentRecipePhaseEditState,
  IntentRecipePhaseKey
} from './aiAssistantIntentRecipesModel';
import {
  declaredBindingNames,
  INTENT_RECIPE_PHASE_KEYS,
  INTENT_RECIPE_READ_ONLY_TOOLS,
  recipeFromPhaseEdits,
  recipeToPhaseEdits
} from './aiAssistantIntentRecipesModel';
import AiAssistantIntentRecipePhaseHintsField from './AiAssistantIntentRecipePhaseHintsField';
import AiAssistantIntentRecipePrefetchArgsHelp, {
  AiAssistantIntentRecipePrefetchBindingsHelp
} from './AiAssistantIntentRecipePrefetchArgsHelp';
import AiAssistantIntentRecipeEmojiField from './AiAssistantIntentRecipeEmojiField';
import { AiAssistantIntentRecipeRoutingRulesSection } from './AiAssistantIntentRecipeMatchRulesField';
import AiAssistantIntentRecipeSwimlane from './AiAssistantIntentRecipeSwimlane';
import { defaultPrefetchArgsJsonForTool } from './intentRecipePrefetchToolReference';

const PHASE_TAB_LABELS: Record<IntentRecipePhaseKey, string> = {
  context: 'Context',
  action: 'Action',
  confirmation: 'Confirmation'
};

type EditorTab = IntentRecipePhaseKey | 'preview';

const EDITOR_TABS: EditorTab[] = [...INTENT_RECIPE_PHASE_KEYS, 'preview'];

function parseArgsJson(text: string): Record<string, string> | undefined {
  const t = text.trim();
  if (!t) return undefined;
  try {
    const o = JSON.parse(t) as unknown;
    if (!o || typeof o !== 'object' || Array.isArray(o)) return undefined;
    return Object.fromEntries(Object.entries(o as Record<string, unknown>).map(([k, v]) => [k, String(v ?? '')]));
  } catch {
    return undefined;
  }
}

function argsToJsonText(args: Record<string, string> | undefined): string {
  if (!args || Object.keys(args).length === 0) return '';
  return JSON.stringify(args, null, 2);
}

function EngineStepRow(props: {
  step: IntentRecipeEngineStep;
  index: number;
  onChange: (step: IntentRecipeEngineStep) => void;
  onRemove: () => void;
  onDragStart: (index: number) => void;
  onDragOver: (index: number) => void;
  onDrop: () => void;
  onDragEnd: () => void;
  dragging: boolean;
}) {
  const { step, index, onChange, onRemove, onDragStart, onDragOver, onDrop, onDragEnd, dragging } = props;
  const [argsText, setArgsText] = useState(() => argsToJsonText(step.args));
  const toolName = (step.tool || '').trim();

  useEffect(() => {
    setArgsText(argsToJsonText(step.args));
  }, [step.tool, step.args]);

  const applyDefaultArgs = () => {
    const json = defaultPrefetchArgsJsonForTool(toolName);
    if (!json) return;
    const parsed = parseArgsJson(json);
    if (parsed) {
      setArgsText(json);
      onChange({ ...step, args: parsed });
    }
  };

  const onToolChange = (v: string) => {
    const nextTool = v.trim();
    const next: IntentRecipeEngineStep = { ...step, tool: nextTool };
    const hasArgs = step.args && Object.keys(step.args).length > 0;
    if (!hasArgs && nextTool) {
      const json = defaultPrefetchArgsJsonForTool(nextTool);
      const parsed = json ? parseArgsJson(json) : undefined;
      if (parsed) {
        next.args = parsed;
        setArgsText(json!);
      }
    }
    onChange(next);
  };

  return (
    <Paper
      variant="outlined"
      draggable
      onDragStart={() => onDragStart(index)}
      onDragEnd={onDragEnd}
      onDragOver={(e) => {
        e.preventDefault();
        onDragOver(index);
      }}
      onDrop={(e) => {
        e.preventDefault();
        onDrop();
      }}
      sx={{
        p: 1.5,
        opacity: dragging ? 0.5 : 1,
        cursor: 'grab',
        bgcolor: 'action.hover'
      }}
    >
      <Stack direction="row" spacing={1} alignItems="flex-start">
        <DragIndicatorRounded sx={{ color: 'text.disabled', mt: 1, flexShrink: 0 }} fontSize="small" />
        <Stack spacing={1.5} sx={{ flex: 1, minWidth: 0 }}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
            <TextField
              label="Binding name (as)"
              value={step.as ?? ''}
              onChange={(e) => onChange({ ...step, as: e.target.value.trim() || undefined })}
              size="small"
              placeholder="pageItem"
              sx={{ flex: '0 0 160px' }}
              InputProps={{ sx: { fontFamily: 'monospace' } }}
            />
            <Autocomplete
              freeSolo
              size="small"
              options={[...INTENT_RECIPE_READ_ONLY_TOOLS]}
              value={step.tool || ''}
              onInputChange={(_, v) => onToolChange(v)}
              sx={{ flex: 1, minWidth: 0 }}
              renderInput={(params) => (
                <TextField {...params} label="Tool" helperText="Read-only tools only — see arg reference below." />
              )}
            />
          </Stack>
          {toolName ? (
            <AiAssistantIntentRecipePrefetchArgsHelp tool={toolName} onInsertDefaultArgs={applyDefaultArgs} />
          ) : null}
          <TextField
            label="Args (JSON)"
            value={argsText}
            onChange={(e) => {
              setArgsText(e.target.value);
              const parsed = parseArgsJson(e.target.value);
              onChange({ ...step, args: parsed });
            }}
            size="small"
            fullWidth
            multiline
            minRows={3}
            placeholder={defaultPrefetchArgsJsonForTool(toolName) ?? '{\n  "siteId": "$siteId"\n}'}
            InputProps={{ sx: { fontFamily: 'monospace', fontSize: 12 } }}
          />
        </Stack>
        <IconButton size="small" aria-label="Remove step" onClick={onRemove} sx={{ mt: 0.5 }}>
          <DeleteOutlineRounded fontSize="small" />
        </IconButton>
      </Stack>
    </Paper>
  );
}

function PhaseEditor(props: {
  phaseKey: IntentRecipePhaseKey;
  state: IntentRecipePhaseEditState;
  bindingNamesForHints: string[];
  onChange: (next: IntentRecipePhaseEditState) => void;
}) {
  const { phaseKey, state, bindingNamesForHints, onChange } = props;
  const [dragIndex, setDragIndex] = useState<number | null>(null);
  const [dropIndex, setDropIndex] = useState<number | null>(null);

  const reorderSteps = useCallback(
    (from: number, to: number) => {
      if (from === to) return;
      const steps = [...state.engineSteps];
      const [moved] = steps.splice(from, 1);
      steps.splice(to, 0, moved);
      onChange({ ...state, engineSteps: steps });
    },
    [state, onChange]
  );

  return (
    <Stack spacing={2}>
      <AiAssistantIntentRecipePhaseHintsField
        phaseKey={phaseKey}
        hintsLines={state.hintsLines}
        bindingNames={bindingNamesForHints}
        onChange={(hintsLines) => onChange({ ...state, hintsLines })}
      />
      <Box>
        <Typography variant="subtitle2" sx={{ mb: 1 }}>
          Prefetch steps ({phaseKey})
        </Typography>
        <Paper variant="outlined" sx={{ p: 1.5, mb: 1.5, bgcolor: 'background.default' }}>
          <AiAssistantIntentRecipePrefetchBindingsHelp />
        </Paper>
        <Stack direction="row" justifyContent="flex-end" alignItems="center" sx={{ mb: 1 }}>
          <Button
            size="small"
            startIcon={<AddRounded />}
            onClick={() =>
              onChange({
                ...state,
                engineSteps: [...state.engineSteps, { tool: 'GetContent', args: { siteId: '$siteId', path: '$contentPath' } }]
              })
            }
          >
            Add step
          </Button>
        </Stack>
        <Stack spacing={1}>
          {state.engineSteps.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              No engine steps — hints only for this phase.
            </Typography>
          ) : (
            state.engineSteps.map((step, i) => (
              <EngineStepRow
                key={`step-${i}`}
                step={step}
                index={i}
                dragging={dragIndex === i}
                onChange={(next) => {
                  const steps = [...state.engineSteps];
                  steps[i] = next;
                  onChange({ ...state, engineSteps: steps });
                }}
                onRemove={() => {
                  const steps = state.engineSteps.filter((_, j) => j !== i);
                  onChange({ ...state, engineSteps: steps });
                }}
                onDragStart={(idx) => setDragIndex(idx)}
                onDragOver={(idx) => setDropIndex(idx)}
                onDrop={() => {
                  if (dragIndex != null && dropIndex != null) reorderSteps(dragIndex, dropIndex);
                  setDragIndex(null);
                  setDropIndex(null);
                }}
                onDragEnd={() => {
                  setDragIndex(null);
                  setDropIndex(null);
                }}
              />
            ))
          )}
        </Stack>
      </Box>
    </Stack>
  );
}

export interface AiAssistantIntentRecipeEditorProps {
  recipe: IntentRecipe;
  onChange: (recipe: IntentRecipe) => void;
  /** When true, recipe id cannot change (built-in override keeps stable id). */
  idReadOnly?: boolean;
  saveHint?: string;
  onDone?: () => void;
}

export default function AiAssistantIntentRecipeEditor(props: AiAssistantIntentRecipeEditorProps) {
  const { recipe, onChange, idReadOnly, saveHint, onDone } = props;
  const [editorTab, setEditorTab] = useState<EditorTab>('context');
  const [phaseEdits, setPhaseEdits] = useState(() => recipeToPhaseEdits(recipe));

  useEffect(() => {
    setPhaseEdits(recipeToPhaseEdits(recipe));
  }, [recipe.id]);

  const previewRecipe = useMemo(() => recipeFromPhaseEdits(recipe, phaseEdits), [recipe, phaseEdits]);
  const bindingNamesForHints = useMemo(() => declaredBindingNames(previewRecipe), [previewRecipe]);

  const patchRecipe = (partial: Partial<IntentRecipe>) => {
    onChange({ ...recipe, ...partial });
  };

  const patchPhase = (key: IntentRecipePhaseKey, next: IntentRecipePhaseEditState) => {
    const merged = { ...phaseEdits, [key]: next };
    setPhaseEdits(merged);
    onChange(recipeFromPhaseEdits(recipe, merged));
  };

  return (
    <Stack spacing={2}>
      <Stack direction="row" justifyContent="space-between" alignItems="center" flexWrap="wrap" useFlexGap>
        {saveHint ? (
          <Typography variant="caption" color="text.secondary">
            {saveHint}
          </Typography>
        ) : (
          <Box />
        )}
        {onDone ? (
          <Button size="small" variant="outlined" onClick={onDone}>
            Done editing
          </Button>
        ) : null}
      </Stack>

      <Stack spacing={2}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems="flex-start">
          <AiAssistantIntentRecipeEmojiField
            emoji={recipe.chatEmoji ?? ''}
            title={recipe.title ?? ''}
            recipeId={recipe.id}
            onChange={(chatEmoji) => patchRecipe({ chatEmoji: chatEmoji || undefined })}
          />
          <TextField
            label="Recipe id"
            value={recipe.id}
            onChange={(e) => patchRecipe({ id: e.target.value.trim() })}
            size="small"
            disabled={idReadOnly}
            sx={{ flex: '0 0 220px' }}
            InputProps={{ sx: { fontFamily: 'monospace' } }}
          />
          <TextField
            label="Title"
            value={recipe.title ?? ''}
            onChange={(e) => patchRecipe({ title: e.target.value })}
            size="small"
            fullWidth
          />
        </Stack>
        <TextField
          label="Description (router)"
          value={recipe.description ?? ''}
          onChange={(e) => patchRecipe({ description: e.target.value })}
          size="small"
          fullWidth
          multiline
          minRows={2}
        />
        <Autocomplete
          multiple
          freeSolo
          options={[]}
          value={recipe.matchHints ?? []}
          onChange={(_, v) => patchRecipe({ matchHints: v.map(String) })}
          renderInput={(params) => (
            <TextField {...params} label="Match hints" size="small" placeholder="translate, publish, …" />
          )}
        />
        <Autocomplete
          multiple
          freeSolo
          options={[]}
          value={recipe.dontMatchHints ?? []}
          onChange={(_, v) =>
            patchRecipe({ dontMatchHints: v.length ? v.map(String) : undefined })
          }
          renderInput={(params) => (
            <TextField
              {...params}
              label="Don't match hints"
              size="small"
              placeholder="translate, publish, …"
            />
          )}
        />
        <AiAssistantIntentRecipeRoutingRulesSection recipe={recipe} onChange={onChange} />
        <Autocomplete
          multiple
          freeSolo
          options={[...INTENT_RECIPE_READ_ONLY_TOOLS, 'GenerateImage', 'WriteContent', 'update_content']}
          value={recipe.toolsLoopAllowlist ?? []}
          onChange={(_, v) => patchRecipe({ toolsLoopAllowlist: v.length ? v.map(String) : undefined })}
          renderInput={(params) => (
            <TextField {...params} label="Tools-loop allowlist (optional)" size="small" />
          )}
        />
        <Autocomplete
          multiple
          freeSolo
          options={[]}
          value={recipe.toolsLoopAllowlistBypassIfAuthorMentions ?? []}
          onChange={(_, v) =>
            patchRecipe({
              toolsLoopAllowlistBypassIfAuthorMentions: v.length ? v.map(String) : undefined
            })
          }
          renderInput={(params) => (
            <TextField {...params} label="Allowlist bypass keywords (optional)" size="small" />
          )}
        />
      </Stack>

      <Tabs value={editorTab} onChange={(_, v) => setEditorTab(v)} variant="scrollable" scrollButtons="auto">
        {EDITOR_TABS.map((k) => (
          <Tab key={k} label={k === 'preview' ? 'Preview' : PHASE_TAB_LABELS[k]} value={k} />
        ))}
      </Tabs>
      {INTENT_RECIPE_PHASE_KEYS.map((k) =>
        editorTab === k ? (
          <PhaseEditor
            key={k}
            phaseKey={k}
            state={phaseEdits[k]}
            bindingNamesForHints={bindingNamesForHints}
            onChange={(n) => patchPhase(k, n)}
          />
        ) : null
      )}
      {editorTab === 'preview' ? <AiAssistantIntentRecipeSwimlane recipe={previewRecipe} /> : null}
    </Stack>
  );
}
