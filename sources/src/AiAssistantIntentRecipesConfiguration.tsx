import { forwardRef, useCallback, useEffect, useImperativeHandle, useMemo, useState } from 'react';
import useActiveSiteId from '@craftercms/studio-ui/hooks/useActiveSiteId';
import { writeConfiguration } from '@craftercms/studio-ui/services/configuration';
import { firstValueFrom } from 'rxjs';
import AddRounded from '@mui/icons-material/AddRounded';
import ContentCopyRounded from '@mui/icons-material/ContentCopyRounded';
import DeleteOutlineRounded from '@mui/icons-material/DeleteOutlineRounded';
import DragIndicatorRounded from '@mui/icons-material/DragIndicatorRounded';
import DownloadRounded from '@mui/icons-material/DownloadRounded';
import RestartAltRounded from '@mui/icons-material/RestartAltRounded';
import SaveRounded from '@mui/icons-material/SaveRounded';
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  List,
  ListItemButton,
  ListItemText,
  Paper,
  Snackbar,
  Stack,
  Typography
} from '@mui/material';
import AiAssistantIntentRecipeEditor from './AiAssistantIntentRecipeEditor';
import AiAssistantIntentRecipeView from './AiAssistantIntentRecipeView';
import AiAssistantIntentRecipeRoutingFields from './AiAssistantIntentRecipeRoutingFields';
import AiAssistantStudioCodeEditor from './AiAssistantStudioCodeEditor';
import {
  defaultToolsPolicyFormState,
  parseToolsPolicyFromJsonText,
  serializeToolsPolicyToJson,
  validateToolsPolicy,
  type ToolsPolicyFormState
} from './aiAssistantToolsMcpUiModel';
import {
  bundledIntentRecipesCatalog,
  cloneRecipe,
  copyTextToClipboard,
  defaultIntentRecipesFile,
  defaultRecipeOrderForCatalog,
  downloadTextFile,
  emptyRecipe,
  INTENT_RECIPES_JSON_SANDBOX_PATH,
  intentRecipesFileFromMergedRecipes,
  listIntentRecipeEntries,
  mergeIntentRecipeCatalog,
  orderIntentRecipes,
  parseIntentRecipesFile,
  serializeIntentRecipesFile,
  validateRecipe,
  type IntentRecipe,
  type IntentRecipeListEntry,
  type IntentRecipesFile
} from './aiAssistantIntentRecipesModel';
import {
  fetchOptionalStudioSandboxUtf8,
  fetchStudioConfigFileUtf8,
  studioConfigRelativePath
} from './aiAssistantScriptsApi';

const TOOLS_JSON_SANDBOX_PATH = '/scripts/aiassistant/config/tools.json';
const TOOLS_JSON_REL = 'scripts/aiassistant/config/tools.json';

function sourceChip(entry: IntentRecipeListEntry) {
  switch (entry.source) {
    case 'bundled':
      return <Chip size="small" label="Built-in" variant="outlined" />;
    case 'custom':
      return <Chip size="small" label="Project custom" color="primary" variant="outlined" />;
    case 'override':
      return <Chip size="small" label="Overrides built-in" color="secondary" variant="outlined" />;
    default:
      return null;
  }
}

function customRecipesPathFromPolicy(policy: ToolsPolicyFormState): string {
  return policy.intentRecipeRouting.customRecipesPath.trim() || INTENT_RECIPES_JSON_SANDBOX_PATH;
}

function intentRecipesStudioRel(policy: ToolsPolicyFormState): string {
  return studioConfigRelativePath(customRecipesPathFromPolicy(policy));
}

function parseIntentRecipesFileFromText(text: string): IntentRecipesFile {
  if (!text.trim()) {
    return defaultIntentRecipesFile();
  }
  const parsed = parseIntentRecipesFile(text);
  return parsed.ok ? parsed.file : defaultIntentRecipesFile();
}

type PendingNavigate =
  | { kind: 'selectRecipe'; id: string }
  | { kind: 'leaveEdit' }
  | { kind: 'openJson' }
  | { kind: 'deleteRecipe'; id: string }
  | { kind: 'newRecipe' };

type SaveCustomRecipesOptions = {
  /** Do not persist these ids in the project file (e.g. revert to built-in). */
  omitRecipeIdsFromCustom?: string[];
  successToast?: string;
};

function pendingNavigateDescription(p: PendingNavigate): string {
  switch (p.kind) {
    case 'selectRecipe':
      return 'switch to another recipe';
    case 'leaveEdit':
      return 'leave edit mode';
    case 'openJson':
      return 'open the project JSON editor';
    case 'deleteRecipe':
      return 'delete this project recipe';
    case 'newRecipe':
      return 'create a new recipe';
    default:
      return 'continue';
  }
}

export type AiAssistantIntentRecipesConfigurationHandle = {
  save: () => Promise<boolean>;
  /** Reload routing and project recipes from the repository (discards unsaved edits). */
  discard: () => Promise<void>;
};

export type AiAssistantIntentRecipesConfigurationProps = {
  onDirtyChange?: (dirty: boolean) => void;
};

const AiAssistantIntentRecipesConfiguration = forwardRef<
  AiAssistantIntentRecipesConfigurationHandle,
  AiAssistantIntentRecipesConfigurationProps
>(function AiAssistantIntentRecipesConfiguration(props, ref) {
  const { onDirtyChange } = props;
  const siteId = useActiveSiteId() ?? '';
  const bundledCatalog = useMemo(() => bundledIntentRecipesCatalog(), []);
  const bundled = useMemo(() => bundledCatalog.recipes, [bundledCatalog]);

  const [toolsPolicy, setToolsPolicy] = useState<ToolsPolicyFormState>(() => defaultToolsPolicyFormState());
  const [customFile, setCustomFile] = useState<IntentRecipesFile>(() => defaultIntentRecipesFile());
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [recipeDraft, setRecipeDraft] = useState<IntentRecipe | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [jsonDialogOpen, setJsonDialogOpen] = useState(false);
  const [jsonDraft, setJsonDraft] = useState('');
  const [jsonError, setJsonError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  /** Bumps when selection or server reload should rehydrate the editor draft. */
  const [draftSyncToken, setDraftSyncToken] = useState(0);
  /** Full recipe editor vs read-only swimlane visualization. */
  const [editingRecipe, setEditingRecipe] = useState(false);
  const [pendingNavigate, setPendingNavigate] = useState<PendingNavigate | null>(null);
  const [navigateSaveBusy, setNavigateSaveBusy] = useState(false);
  const [recipeDragIndex, setRecipeDragIndex] = useState<number | null>(null);
  const [recipeDropIndex, setRecipeDropIndex] = useState<number | null>(null);
  const [pendingRevertId, setPendingRevertId] = useState<string | null>(null);
  const [revertBusy, setRevertBusy] = useState(false);

  const recipeOrder = useMemo(() => {
    if (customFile.recipeOrder?.length) {
      return customFile.recipeOrder;
    }
    if (bundledCatalog.recipeOrder?.length) {
      return bundledCatalog.recipeOrder;
    }
    return defaultRecipeOrderForCatalog(bundled, customFile.recipes);
  }, [bundled, bundledCatalog.recipeOrder, customFile.recipeOrder, customFile.recipes]);

  const entries = useMemo(
    () =>
      listIntentRecipeEntries(
        bundled,
        customFile.recipes,
        recipeOrder,
        customFile.chatDefaults ?? bundledCatalog.chatDefaults
      ),
    [bundled, bundledCatalog.chatDefaults, customFile.chatDefaults, customFile.recipes, recipeOrder]
  );

  const selectedEntry = useMemo(
    () => entries.find((e) => e.id === selectedId) ?? entries[0] ?? null,
    [entries, selectedId]
  );

  useEffect(() => {
    setEditingRecipe(false);
  }, [selectedEntry?.id, draftSyncToken]);

  useEffect(() => {
    onDirtyChange?.(dirty);
  }, [dirty, onDirtyChange]);

  useEffect(() => {
    if (!dirty) return;
    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, [dirty]);

  const mergedRecipes = useMemo(
    () => orderIntentRecipes(mergeIntentRecipeCatalog(bundled, customFile.recipes), recipeOrder),
    [bundled, customFile.recipes, recipeOrder]
  );

  const reorderRecipeList = useCallback(
    (from: number, to: number) => {
      if (from === to) return;
      const ids = entries.map((e) => e.id);
      const [moved] = ids.splice(from, 1);
      ids.splice(to, 0, moved);
      setCustomFile((f) => ({ ...f, recipeOrder: ids }));
      setDirty(true);
    },
    [entries]
  );

  const reload = useCallback(async () => {
    if (!siteId) return;
    setLoadError(null);
    setLoaded(false);
    try {
      const toolsText = await fetchOptionalStudioSandboxUtf8(siteId, TOOLS_JSON_SANDBOX_PATH);
      const parsedTools = parseToolsPolicyFromJsonText(toolsText.trim() ? toolsText : '');
      if (!parsedTools.ok) {
        setLoadError(parsedTools.message);
        setToolsPolicy(defaultToolsPolicyFormState());
      } else {
        setToolsPolicy(parsedTools.state);
      }

      const policyState = parsedTools.ok ? parsedTools.state : defaultToolsPolicyFormState();
      const customText = await fetchStudioConfigFileUtf8(siteId, intentRecipesStudioRel(policyState));
      setCustomFile(parseIntentRecipesFileFromText(customText));
      setDirty(false);
      setDraftSyncToken((t) => t + 1);
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoaded(true);
    }
  }, [siteId]);

  useEffect(() => {
    void reload();
  }, [reload]);

  useEffect(() => {
    if (selectedId && entries.some((e) => e.id === selectedId)) return;
    setSelectedId(entries[0]?.id ?? null);
  }, [entries, selectedId]);

  useEffect(() => {
    if (!selectedId) {
      setRecipeDraft(null);
      return;
    }
    const entry = listIntentRecipeEntries(
      bundled,
      customFile.recipes,
      recipeOrder,
      customFile.chatDefaults ?? bundledCatalog.chatDefaults
    ).find((e) => e.id === selectedId);
    if (entry) setRecipeDraft(cloneRecipe(entry.recipe));
    // Rehydrate only on selection change or server reload — not on each editor keystroke (customFile updates).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId, draftSyncToken, bundled]);

  const patchToolsRouting = (intentRecipeRouting: ToolsPolicyFormState['intentRecipeRouting']) => {
    setToolsPolicy((p) => ({ ...p, intentRecipeRouting }));
    setDirty(true);
  };

  const upsertCustomRecipe = useCallback((recipe: IntentRecipe) => {
    const v = validateRecipe(recipe);
    if (!v.ok) {
      setSaveError(v.message);
      return false;
    }
    setCustomFile((f) => {
      const recipes = [...f.recipes];
      const idx = recipes.findIndex((r) => r.id === recipe.id);
      if (idx >= 0) recipes[idx] = cloneRecipe(recipe);
      else recipes.push(cloneRecipe(recipe));
      return { ...f, recipes };
    });
    setDirty(true);
    setSelectedId(recipe.id);
    return true;
  }, []);

  const commitRecipeDraft = useCallback(
    (recipe: IntentRecipe) => {
      setRecipeDraft(cloneRecipe(recipe));
      upsertCustomRecipe(recipe);
    },
    [upsertCustomRecipe]
  );

  const applyPendingNavigate = useCallback(
    (p: PendingNavigate) => {
      switch (p.kind) {
        case 'selectRecipe':
          setSelectedId(p.id);
          setEditingRecipe(false);
          setDraftSyncToken((t) => t + 1);
          break;
        case 'leaveEdit':
          setEditingRecipe(false);
          break;
        case 'openJson':
          setJsonDraft(serializeIntentRecipesFile(customFile));
          setJsonError(null);
          setJsonDialogOpen(true);
          break;
        case 'deleteRecipe': {
          setCustomFile((f) => ({
            ...f,
            recipes: f.recipes.filter((r) => r.id !== p.id),
            recipeOrder: (f.recipeOrder ?? recipeOrder).filter((id) => id !== p.id)
          }));
          setDirty(true);
          const next = entries.find((e) => e.id !== p.id);
          setSelectedId(next?.id ?? null);
          break;
        }
        case 'newRecipe': {
          const id = `recipe_${Date.now()}`;
          const recipe = emptyRecipe(id);
          setCustomFile((f) => {
            const recipes = [...f.recipes, cloneRecipe(recipe)];
            const order = [...(f.recipeOrder ?? recipeOrder), id];
            return { ...f, recipes, recipeOrder: order };
          });
          setDirty(true);
          setSelectedId(id);
          setRecipeDraft(cloneRecipe(recipe));
          setEditingRecipe(true);
          break;
        }
        default:
          break;
      }
    },
    [bundled, customFile, entries, recipeOrder, upsertCustomRecipe]
  );

  const requestNavigate = useCallback(
    (p: PendingNavigate) => {
      if (!dirty) {
        applyPendingNavigate(p);
        return;
      }
      setPendingNavigate(p);
    },
    [applyPendingNavigate, dirty]
  );

  const cancelPendingNavigate = useCallback(() => {
    setPendingNavigate(null);
    setNavigateSaveBusy(false);
  }, []);

  const discardPendingNavigate = useCallback(async () => {
    if (!pendingNavigate) return;
    const p = pendingNavigate;
    setPendingNavigate(null);
    setNavigateSaveBusy(true);
    try {
      await reload();
      applyPendingNavigate(p);
    } finally {
      setNavigateSaveBusy(false);
    }
  }, [applyPendingNavigate, pendingNavigate, reload]);

  const removeCustomRecipe = (id: string) => {
    requestNavigate({ kind: 'deleteRecipe', id });
  };

  const resetBuiltInToBundled = (id: string) => {
    setPendingRevertId(id);
  };

  const cancelPendingRevert = useCallback(() => {
    setPendingRevertId(null);
    setRevertBusy(false);
  }, []);

  const addNewRecipe = () => {
    requestNavigate({ kind: 'newRecipe' });
  };

  const openJsonEditor = () => {
    requestNavigate({ kind: 'openJson' });
  };

  const applyJsonEditor = () => {
    const parsed = parseIntentRecipesFile(jsonDraft);
    if (!parsed.ok) {
      setJsonError(parsed.message);
      return;
    }
    setCustomFile(parsed.file);
    setDirty(true);
    setJsonDialogOpen(false);
    setJsonError(null);
    setToast('Applied project recipes JSON to draft.');
  };

  const exportSiteFile = () => {
    downloadTextFile('intent-recipes.json', serializeIntentRecipesFile(customFile));
    setToast('Downloaded project recipes file.');
  };

  const exportMergedCatalog = () => {
    const effective = recipeDraft && selectedEntry ? mergeIntentRecipeCatalog(bundled, customFile.recipes.map((r) => (r.id === recipeDraft.id ? recipeDraft : r))) : mergedRecipes;
    downloadTextFile(
      'intent-recipes-merged.json',
      serializeIntentRecipesFile(intentRecipesFileFromMergedRecipes(effective, 1, recipeOrder))
    );
    setToast('Downloaded merged catalog.');
  };

  const copySiteJson = async () => {
    const ok = await copyTextToClipboard(serializeIntentRecipesFile(customFile));
    setToast(ok ? 'Copied project recipes JSON to clipboard.' : 'Could not copy to clipboard.');
  };

  const copyMergedJson = async () => {
    const effective =
      recipeDraft && selectedEntry
        ? mergeIntentRecipeCatalog(
            bundled,
            customFile.recipes.map((r) => (r.id === recipeDraft.id ? recipeDraft : r))
          )
        : mergedRecipes;
    const ok = await copyTextToClipboard(
      serializeIntentRecipesFile(intentRecipesFileFromMergedRecipes(effective, 1, recipeOrder))
    );
    setToast(ok ? 'Copied merged catalog JSON to clipboard.' : 'Could not copy to clipboard.');
  };

  const buildCustomFileForSave = useCallback(
    (options?: SaveCustomRecipesOptions): IntentRecipesFile => {
      const omit = new Set((options?.omitRecipeIdsFromCustom ?? []).map((x) => x.trim()).filter(Boolean));
      let recipes = customFile.recipes.filter((r) => !omit.has(String(r.id ?? '').trim()));
      if (!recipeDraft || omit.has(String(recipeDraft.id ?? '').trim())) {
        return {
          ...customFile,
          recipes,
          ...(customFile.recipeOrder?.length ? { recipeOrder: [...customFile.recipeOrder] } : {})
        };
      }
      const v = validateRecipe(recipeDraft);
      if (!v.ok) return { ...customFile, recipes, ...(customFile.recipeOrder?.length ? { recipeOrder: [...customFile.recipeOrder] } : {}) };
      const idx = recipes.findIndex((r) => r.id === recipeDraft.id);
      const copy = cloneRecipe(recipeDraft);
      if (idx >= 0) recipes[idx] = copy;
      else recipes.push(copy);
      return {
        ...customFile,
        recipes,
        ...(customFile.recipeOrder?.length ? { recipeOrder: [...customFile.recipeOrder] } : {})
      };
    },
    [customFile, recipeDraft]
  );

  const saveToRepository = useCallback(
    async (options?: SaveCustomRecipesOptions): Promise<boolean> => {
      if (!siteId) return false;
      setSaveError(null);
      const omit = new Set((options?.omitRecipeIdsFromCustom ?? []).map((x) => x.trim()).filter(Boolean));
      if (recipeDraft && !omit.has(String(recipeDraft.id ?? '').trim())) {
        const v = validateRecipe(recipeDraft);
        if (!v.ok) {
          setSaveError(v.message);
          return false;
        }
      }
      const validation = validateToolsPolicy(toolsPolicy);
      if (!validation.ok) {
        setSaveError(validation.message);
        return false;
      }
      const fileToWrite = buildCustomFileForSave(options);
      const recipesRel = intentRecipesStudioRel(toolsPolicy);
      setSaving(true);
      try {
        const routing = toolsPolicy.intentRecipeRouting;
        const customPath = routing.customRecipesPath.trim() || INTENT_RECIPES_JSON_SANDBOX_PATH;
        const toolsBody = serializeToolsPolicyToJson({
          ...toolsPolicy,
          intentRecipeRouting: { ...routing, customRecipesPath: customPath }
        });
        await firstValueFrom(writeConfiguration(siteId, TOOLS_JSON_REL, 'studio', toolsBody));
        await firstValueFrom(
          writeConfiguration(siteId, recipesRel, 'studio', serializeIntentRecipesFile(fileToWrite))
        );
        setCustomFile(fileToWrite);
        setDirty(false);
        setDraftSyncToken((t) => t + 1);
        setToast(options?.successToast ?? 'Saved routing and project recipes.');
        return true;
      } catch (e) {
        setSaveError(e instanceof Error ? e.message : String(e));
        return false;
      } finally {
        setSaving(false);
      }
    },
    [buildCustomFileForSave, recipeDraft, siteId, toolsPolicy]
  );

  const confirmRevertToBuiltIn = useCallback(async () => {
    const id = pendingRevertId?.trim();
    if (!id || !siteId) return;
    const bundledRecipe = bundled.find((r) => r.id === id);
    if (!bundledRecipe) {
      setSaveError(`Built-in recipe "${id}" was not found.`);
      setPendingRevertId(null);
      return;
    }
    setRevertBusy(true);
    setSaveError(null);
    try {
      const ok = await saveToRepository({
        omitRecipeIdsFromCustom: [id],
        successToast: 'Reverted to built-in and saved for this project.'
      });
      if (ok) {
        setRecipeDraft(cloneRecipe(bundledRecipe));
        setEditingRecipe(false);
        setDraftSyncToken((t) => t + 1);
        setPendingRevertId(null);
      }
    } finally {
      setRevertBusy(false);
    }
  }, [bundled, pendingRevertId, saveToRepository, siteId]);

  useImperativeHandle(
    ref,
    () => ({
      save: saveToRepository,
      discard: reload
    }),
    [reload, saveToRepository]
  );

  const saveAndPendingNavigate = useCallback(async () => {
    if (!pendingNavigate) return;
    const p = pendingNavigate;
    setNavigateSaveBusy(true);
    try {
      const ok = await saveToRepository();
      if (ok) {
        setPendingNavigate(null);
        applyPendingNavigate(p);
      }
    } finally {
      setNavigateSaveBusy(false);
    }
  }, [applyPendingNavigate, pendingNavigate, saveToRepository]);

  const save = async () => {
    await saveToRepository();
  };

  const selectedIsSiteStored =
    selectedEntry?.source === 'custom' || selectedEntry?.source === 'override';
  const selectedIsBundledOnly = selectedEntry?.source === 'bundled';
  const idReadOnly = selectedEntry?.source !== 'custom';

  const saveHint = selectedIsBundledOnly || selectedIsSiteStored ? 'Save to keep your changes for this project.' : undefined;

  return (
    <Stack spacing={2.5} sx={{ pb: 3 }}>
      <Box>
        <Typography variant="h6" gutterBottom>
          Intent recipes
        </Typography>
        <Typography variant="body2" color="text.secondary">
          Recipes guide the assistant through common authoring tasks—editing content, translating, generating images,
          publishing, and more. Built-in recipes are included; customize and save changes for your project.
        </Typography>
      </Box>

      {loadError ? <Alert severity="error">{loadError}</Alert> : null}
      {saveError ? <Alert severity="error" onClose={() => setSaveError(null)}>{saveError}</Alert> : null}
      {dirty ? (
        <Alert severity="warning">You have unsaved changes. Save or discard before you leave.</Alert>
      ) : null}

      <AiAssistantIntentRecipeRoutingFields value={toolsPolicy.intentRecipeRouting} onChange={patchToolsRouting} />

      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap alignItems="center">
        <Button
          variant="contained"
          startIcon={<SaveRounded />}
          disabled={!dirty || saving || !siteId}
          onClick={() => void save()}
        >
          {saving ? 'Saving…' : 'Save routing and project recipes'}
        </Button>
        <Button size="small" variant="outlined" startIcon={<AddRounded />} onClick={addNewRecipe} disabled={!loaded}>
          New recipe
        </Button>
        <Button size="small" variant="outlined" onClick={openJsonEditor} disabled={!loaded}>
          Edit project JSON
        </Button>
        <Button size="small" variant="outlined" startIcon={<DownloadRounded />} onClick={exportSiteFile} disabled={!loaded}>
          Export project file
        </Button>
        <Button size="small" variant="outlined" startIcon={<DownloadRounded />} onClick={exportMergedCatalog} disabled={!loaded}>
          Export merged catalog
        </Button>
        <Button size="small" variant="outlined" startIcon={<ContentCopyRounded />} onClick={() => void copySiteJson()} disabled={!loaded}>
          Copy project JSON
        </Button>
        <Button size="small" variant="outlined" startIcon={<ContentCopyRounded />} onClick={() => void copyMergedJson()} disabled={!loaded}>
          Copy merged JSON
        </Button>
      </Stack>

      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', md: 'minmax(220px, 280px) 1fr' },
          gap: 2,
          minHeight: 360
        }}
      >
        <Paper variant="outlined" sx={{ overflow: 'auto', maxHeight: { md: '78vh' } }}>
          <Typography variant="subtitle2" sx={{ px: 2, py: 1.5, borderBottom: 1, borderColor: 'divider' }}>
            Recipes ({entries.length})
          </Typography>
          <List dense disablePadding>
            {entries.map((entry, index) => (
              <ListItemButton
                key={entry.id}
                selected={entry.id === selectedEntry?.id}
                draggable
                onDragStart={(e) => {
                  e.stopPropagation();
                  setRecipeDragIndex(index);
                }}
                onDragEnd={() => {
                  setRecipeDragIndex(null);
                  setRecipeDropIndex(null);
                }}
                onDragOver={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                  setRecipeDropIndex(index);
                }}
                onDrop={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                  if (recipeDragIndex != null && recipeDropIndex != null) {
                    reorderRecipeList(recipeDragIndex, recipeDropIndex);
                  }
                  setRecipeDragIndex(null);
                  setRecipeDropIndex(null);
                }}
                onClick={() => {
                  if (entry.id === selectedEntry?.id) return;
                  requestNavigate({ kind: 'selectRecipe', id: entry.id });
                }}
                sx={{
                  opacity: recipeDragIndex === index ? 0.55 : 1,
                  cursor: 'grab',
                  alignItems: 'flex-start'
                }}
              >
                <DragIndicatorRounded
                  sx={{ color: 'text.disabled', mt: 0.75, mr: 0.5, flexShrink: 0 }}
                  fontSize="small"
                />
                <Typography
                  component="span"
                  aria-hidden
                  sx={{ fontSize: '1.15rem', lineHeight: 1, mt: 0.85, mr: 1, flexShrink: 0, width: 24, textAlign: 'center' }}
                >
                  {entry.chatEmoji}
                </Typography>
                <ListItemText
                  primary={entry.title}
                  secondary={entry.id}
                  primaryTypographyProps={{ variant: 'body2', fontWeight: entry.id === selectedEntry?.id ? 600 : 400 }}
                  secondaryTypographyProps={{ variant: 'caption', fontFamily: 'monospace' }}
                />
                <Box sx={{ ml: 1, flexShrink: 0 }}>{sourceChip(entry)}</Box>
              </ListItemButton>
            ))}
          </List>
        </Paper>

        <Paper variant="outlined" sx={{ p: 2, minWidth: 0, overflow: 'auto', maxHeight: { md: '78vh' } }}>
          {!selectedEntry || !recipeDraft ? (
            <Typography variant="body2" color="text.secondary">
              {loaded ? 'Select or create a recipe.' : 'Loading…'}
            </Typography>
          ) : (
            <Stack spacing={2}>
              <Stack direction="row" justifyContent="space-between" alignItems="flex-start" gap={1} flexWrap="wrap">
                <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                  <Typography component="span" sx={{ fontSize: '1.35rem', lineHeight: 1 }} aria-hidden>
                    {selectedEntry.chatEmoji}
                  </Typography>
                  <Typography variant="subtitle1">{recipeDraft.title || recipeDraft.id}</Typography>
                  {sourceChip(selectedEntry)}
                </Stack>
                <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
                  {selectedEntry.source === 'override' ? (
                    <Button
                      size="small"
                      variant="outlined"
                      startIcon={<RestartAltRounded />}
                      onClick={() => resetBuiltInToBundled(selectedEntry.id)}
                    >
                      Reset to built-in
                    </Button>
                  ) : null}
                  {selectedEntry.source === 'custom' ? (
                    <Button
                      size="small"
                      color="error"
                      startIcon={<DeleteOutlineRounded />}
                      onClick={() => removeCustomRecipe(selectedEntry.id)}
                    >
                      Delete project recipe
                    </Button>
                  ) : null}
                </Stack>
              </Stack>

              {editingRecipe ? (
                <AiAssistantIntentRecipeEditor
                  key={selectedEntry.id}
                  recipe={recipeDraft}
                  onChange={commitRecipeDraft}
                  idReadOnly={idReadOnly}
                  saveHint={saveHint}
                  onDone={() => requestNavigate({ kind: 'leaveEdit' })}
                />
              ) : (
                <AiAssistantIntentRecipeView
                  recipe={recipeDraft}
                  entry={selectedEntry}
                  onEdit={() => setEditingRecipe(true)}
                />
              )}
            </Stack>
          )}
        </Paper>
      </Box>

      <Dialog open={pendingRevertId != null} onClose={cancelPendingRevert} maxWidth="sm" fullWidth>
        <DialogTitle>Revert to built-in?</DialogTitle>
        <DialogContent>
          <Typography variant="body2" paragraph>
            This removes your project customization for{' '}
            <strong>{pendingRevertId ?? ''}</strong> and restores the built-in recipe. The change is saved to your
            project file immediately (no override entry is kept).
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={cancelPendingRevert} disabled={revertBusy || saving}>
            Cancel
          </Button>
          <Button
            variant="contained"
            color="warning"
            onClick={() => void confirmRevertToBuiltIn()}
            disabled={revertBusy || saving || !siteId}
          >
            {revertBusy || saving ? 'Saving…' : 'Revert and save'}
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={pendingNavigate != null} onClose={cancelPendingNavigate} maxWidth="sm" fullWidth>
        <DialogTitle>Unsaved changes</DialogTitle>
        <DialogContent>
          <Typography variant="body2" paragraph>
            Save your changes, discard them, or stay here
            {pendingNavigate ? ` before you ${pendingNavigateDescription(pendingNavigate)}` : ''}.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={cancelPendingNavigate} disabled={navigateSaveBusy || saving}>
            Stay
          </Button>
          <Button color="warning" onClick={() => void discardPendingNavigate()} disabled={navigateSaveBusy || saving}>
            Discard changes
          </Button>
          <Button
            variant="contained"
            onClick={() => void saveAndPendingNavigate()}
            disabled={navigateSaveBusy || saving}
          >
            {navigateSaveBusy || saving ? 'Saving…' : 'Save and continue'}
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={jsonDialogOpen} onClose={() => setJsonDialogOpen(false)} maxWidth="md" fullWidth>
        <DialogTitle>Project intent recipes</DialogTitle>
        <DialogContent>
          {jsonError ? (
            <Alert severity="error" sx={{ mb: 2 }}>
              {jsonError}
            </Alert>
          ) : null}
          <AiAssistantStudioCodeEditor value={jsonDraft} onChange={setJsonDraft} language="json" minHeight={360} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setJsonDialogOpen(false)}>Cancel</Button>
          <Button
            onClick={() => {
              void copyTextToClipboard(jsonDraft).then((ok) =>
                setToast(ok ? 'Copied.' : 'Could not copy.')
              );
            }}
          >
            Copy
          </Button>
          <Button variant="contained" onClick={applyJsonEditor}>
            Apply to draft
          </Button>
        </DialogActions>
      </Dialog>

      <Snackbar
        open={Boolean(toast)}
        autoHideDuration={4000}
        onClose={() => setToast(null)}
        message={toast ?? ''}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      />
    </Stack>
  );
});

export default AiAssistantIntentRecipesConfiguration;
