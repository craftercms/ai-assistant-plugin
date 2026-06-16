import { useEffect, useState } from 'react';
import { Autocomplete, Box, CircularProgress, Stack, TextField, Typography } from '@mui/material';
import { writeConfiguration } from '@craftercms/studio-ui/services/configuration';
import { firstValueFrom } from 'rxjs';
import AiAssistantMarkdownEditor from './AiAssistantMarkdownEditor';
import type { IntentRecipe } from './aiAssistantIntentRecipesModel';
import { INTENT_RECIPE_WIRE_TOOL_OPTIONS } from './aiAssistantIntentRecipesModel';
import { fetchStudioConfigFileUtf8 } from './aiAssistantScriptsApi';

const TOOLS_LOOP_WIRE_OPTIONS = [...INTENT_RECIPE_WIRE_TOOL_OPTIONS];

export interface AiAssistantIntentRecipeToolsLoopFieldsProps {
  recipe: IntentRecipe;
  onChange: (recipe: IntentRecipe) => void;
  siteId?: string;
  /** Parent calls before Done / Save to persist an external prelude file when dirty. */
  preludeFileSaveRef?: React.MutableRefObject<(() => Promise<void>) | null>;
}

function normalizeStudioModulePath(path: string): string {
  return path.trim().replace(/^\/+/, '');
}

/** Recipe tools-loop policy: force tool, allowlist, excludes, fetch caps, prelude. */
export default function AiAssistantIntentRecipeToolsLoopFields(props: AiAssistantIntentRecipeToolsLoopFieldsProps) {
  const { recipe, onChange, siteId, preludeFileSaveRef } = props;
  const preludePath = normalizeStudioModulePath(recipe.matchedUserPreludePath ?? '');
  const usesPreludeFile = Boolean(preludePath);

  const [preludeFileBody, setPreludeFileBody] = useState('');
  const [preludeFileLoading, setPreludeFileLoading] = useState(false);
  const [preludeFileDirty, setPreludeFileDirty] = useState(false);

  const patchRecipe = (partial: Partial<IntentRecipe>) => {
    onChange({ ...recipe, ...partial });
  };

  useEffect(() => {
    if (!siteId || !preludePath) {
      setPreludeFileBody('');
      setPreludeFileDirty(false);
      setPreludeFileLoading(false);
      return;
    }
    let cancelled = false;
    setPreludeFileLoading(true);
    void fetchStudioConfigFileUtf8(siteId, preludePath).then((text) => {
      if (cancelled) return;
      setPreludeFileBody(text);
      setPreludeFileDirty(false);
      setPreludeFileLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [siteId, preludePath]);

  useEffect(() => {
    if (!preludeFileSaveRef) return;
    if (!siteId || !preludePath) {
      preludeFileSaveRef.current = null;
      return;
    }
    preludeFileSaveRef.current = async () => {
      if (!preludeFileDirty) return;
      await firstValueFrom(writeConfiguration(siteId, preludePath, 'studio', preludeFileBody));
      setPreludeFileDirty(false);
    };
    return () => {
      preludeFileSaveRef.current = null;
    };
  }, [siteId, preludePath, preludeFileBody, preludeFileDirty, preludeFileSaveRef]);

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
          Matched user prelude — prepended to the tools-loop user message when this recipe matches. Long preludes can
          live in a markdown file (readable in git); otherwise edit inline below.
        </Typography>
        <TextField
          label="Prelude file (studio module path)"
          size="small"
          fullWidth
          value={recipe.matchedUserPreludePath ?? ''}
          onChange={(e) => {
            const t = normalizeStudioModulePath(e.target.value);
            patchRecipe({ matchedUserPreludePath: t || undefined });
          }}
          placeholder="scripts/aiassistant/recipes/preludes/my-recipe-matched-user-prelude.md"
          helperText={
            usesPreludeFile
              ? 'Server loads prelude text from this file at runtime. Edit the file contents below.'
              : 'Optional. When set, prelude text is read from this path instead of the inline field.'
          }
          InputProps={{ sx: { fontFamily: 'monospace' } }}
          sx={{ mb: 2 }}
        />
        {usesPreludeFile && preludeFileLoading ? (
          <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
            <CircularProgress size={18} />
            <Typography variant="body2" color="text.secondary">
              Loading <code>{preludePath}</code>…
            </Typography>
          </Stack>
        ) : null}
        <AiAssistantMarkdownEditor
          value={usesPreludeFile ? preludeFileBody : (recipe.matchedUserPrelude ?? '')}
          onChange={(next) => {
            if (usesPreludeFile) {
              setPreludeFileBody(next);
              setPreludeFileDirty(true);
              return;
            }
            const t = next.trim();
            patchRecipe({ matchedUserPrelude: t ? next : undefined });
          }}
          readOnly={usesPreludeFile && (preludeFileLoading || !siteId)}
          minHeightPx={320}
          helperText={
            usesPreludeFile
              ? preludeFileDirty
                ? `Unsaved changes to ${preludePath} — use Done or Save recipes to write the file.`
                : `Editing ${preludePath}`
              : 'Whitespace and line breaks are preserved. Use headings and lists for orchestration blocks.'
          }
        />
      </Box>
    </Stack>
  );
}
