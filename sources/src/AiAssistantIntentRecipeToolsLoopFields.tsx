import { Autocomplete, Box, Stack, TextField, Typography } from '@mui/material';
import AiAssistantMarkdownEditor from './AiAssistantMarkdownEditor';
import type { IntentRecipe } from './aiAssistantIntentRecipesModel';
import { INTENT_RECIPE_WIRE_TOOL_OPTIONS } from './aiAssistantIntentRecipesModel';

const TOOLS_LOOP_WIRE_OPTIONS = [...INTENT_RECIPE_WIRE_TOOL_OPTIONS];

export interface AiAssistantIntentRecipeToolsLoopFieldsProps {
  recipe: IntentRecipe;
  onChange: (recipe: IntentRecipe) => void;
}

/** Recipe tools-loop policy: force tool, allowlist, excludes, fetch caps, prelude. */
export default function AiAssistantIntentRecipeToolsLoopFields(props: AiAssistantIntentRecipeToolsLoopFieldsProps) {
  const { recipe, onChange } = props;

  const patchRecipe = (partial: Partial<IntentRecipe>) => {
    onChange({ ...recipe, ...partial });
  };

  return (
    <Stack spacing={2}>
      <Typography variant="subtitle2">Tools loop (orchestration)</Typography>
      <Typography variant="body2" color="text.secondary">
        Applied when this recipe matches: round-0 <strong>tool_choice</strong>, tool allowlist, and server fetch limits.
        Names must match built-in wire tools (e.g. <strong>WebSearch</strong>, <strong>SerpApiWebSearch</strong>,{' '}
        <strong>FetchHttpUrl</strong>).
      </Typography>
      <Autocomplete
        freeSolo
        options={TOOLS_LOOP_WIRE_OPTIONS}
        value={recipe.toolsLoopForceTool ?? ''}
        onInputChange={(_, v, reason) => {
          if (reason === 'input') {
            patchRecipe({ toolsLoopForceTool: v.trim() || undefined });
          }
        }}
        onChange={(_, v) => {
          const value = typeof v === 'string' ? v : (v?.toString() ?? '');
          patchRecipe({ toolsLoopForceTool: value.trim() || undefined });
        }}
        renderInput={(params) => (
          <TextField
            {...params}
            label="Force tool (round 0)"
            size="small"
            placeholder="WebSearch"
            helperText="Required first tool call. If disabled in tools.json, the turn fails with “Recipe tool unavailable”."
            InputProps={{ ...params.InputProps, sx: { fontFamily: 'monospace' } }}
          />
        )}
      />
      <Autocomplete
        multiple
        freeSolo
        options={TOOLS_LOOP_WIRE_OPTIONS}
        value={recipe.toolsLoopAllowlist ?? []}
        onChange={(_, v) => patchRecipe({ toolsLoopAllowlist: v.length ? v.map(String) : undefined })}
        renderInput={(params) => (
          <TextField
            {...params}
            label="Tools-loop allowlist"
            size="small"
            helperText="Only these tools are registered for the turn (unless bypass keywords match)."
          />
        )}
      />
      <Autocomplete
        multiple
        freeSolo
        options={TOOLS_LOOP_WIRE_OPTIONS}
        value={recipe.toolsLoopExcludeTools ?? []}
        onChange={(_, v) => patchRecipe({ toolsLoopExcludeTools: v.length ? v.map(String) : undefined })}
        renderInput={(params) => (
          <TextField
            {...params}
            label="Exclude tools"
            size="small"
            helperText="Removed from the session tool list when this recipe matches."
          />
        )}
      />
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        <TextField
          label="Max FetchHttpUrl calls per turn"
          type="number"
          size="small"
          value={recipe.toolsLoopMaxFetchHttpUrlCalls ?? ''}
          onChange={(e) => {
            const t = e.target.value.trim();
            patchRecipe({
              toolsLoopMaxFetchHttpUrlCalls: t === '' ? undefined : Math.max(0, Math.min(10, Number(t) || 0))
            });
          }}
          inputProps={{ min: 0, max: 10 }}
          sx={{ flex: '0 0 220px' }}
        />
        <TextField
          label="FetchHttpUrl wire max chars"
          type="number"
          size="small"
          value={recipe.toolsLoopFetchHttpUrlWireMaxChars ?? ''}
          onChange={(e) => {
            const t = e.target.value.trim();
            patchRecipe({
              toolsLoopFetchHttpUrlWireMaxChars: t === '' ? undefined : Math.max(256, Math.min(24000, Number(t) || 0))
            });
          }}
          inputProps={{ min: 256, max: 24000 }}
          sx={{ flex: '0 0 220px' }}
        />
      </Stack>
      <Box>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
          Matched user prelude — prepended to the tools-loop user message when this recipe matches. Use an external
          markdown file for long preludes (readable in git); inline text is the fallback when the file is missing.
        </Typography>
        <TextField
          label="Prelude file (studio module path)"
          size="small"
          fullWidth
          value={recipe.matchedUserPreludePath ?? ''}
          onChange={(e) => {
            const t = e.target.value.trim();
            patchRecipe({ matchedUserPreludePath: t || undefined });
          }}
          placeholder="scripts/aiassistant/recipes/preludes/my-recipe-matched-user-prelude.md"
          helperText="Optional. When set, the server loads prelude text from this path instead of the field below."
          InputProps={{ sx: { fontFamily: 'monospace' } }}
          sx={{ mb: 2 }}
        />
        <AiAssistantMarkdownEditor
          value={recipe.matchedUserPrelude ?? ''}
          onChange={(next) => {
            const t = next.trim();
            patchRecipe({ matchedUserPrelude: t ? next : undefined });
          }}
          minHeightPx={320}
          defaultView="split"
          helperText={
            recipe.matchedUserPreludePath
              ? 'Inline prelude is ignored when Prelude file is set. Clear the path to edit inline.'
              : 'Use headings and lists; preview shows how authors will read injected orchestration text.'
          }
        />
      </Box>
    </Stack>
  );
}
