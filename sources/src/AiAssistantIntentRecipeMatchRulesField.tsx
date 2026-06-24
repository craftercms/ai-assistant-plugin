import { useMemo, useState } from 'react';
import {
  Autocomplete,
  Box,
  Checkbox,
  FormControlLabel,
  Paper,
  Stack,
  TextField,
  Typography
} from '@mui/material';
import type { IntentRecipe, IntentRecipeMatchRule, IntentRecipeMatchRules } from './aiAssistantIntentRecipesModel';
import { INTENT_RECIPE_WHEN_LEAF_OPTIONS } from './aiAssistantIntentRecipesModel';

function isRuleArray(rules: IntentRecipeMatchRules | undefined): rules is IntentRecipeMatchRule[] {
  return Array.isArray(rules);
}

function defaultRule(): IntentRecipeMatchRule {
  return {
    priority: 50,
    routerReason: '',
    authorFromMatchHints: true,
    respectDontMatchHints: true
  };
}

function normalizeSingleRule(rules: IntentRecipeMatchRules | undefined): IntentRecipeMatchRule {
  if (!rules) return defaultRule();
  if (isRuleArray(rules)) return rules[0] ?? defaultRule();
  return { ...defaultRule(), ...rules };
}

export interface AiAssistantIntentRecipeMatchRulesFieldProps {
  label: string;
  value: IntentRecipeMatchRules | undefined;
  onChange: (next: IntentRecipeMatchRules | undefined) => void;
  matchHints: string[];
}

export default function AiAssistantIntentRecipeMatchRulesField(props: AiAssistantIntentRecipeMatchRulesFieldProps) {
  const { label, value, onChange, matchHints } = props;
  const [advancedJson, setAdvancedJson] = useState('');
  const multi = isRuleArray(value);

  const rule = useMemo(() => normalizeSingleRule(value), [value]);

  const patchRule = (partial: Partial<IntentRecipeMatchRule>) => {
    onChange({ ...rule, ...partial });
  };

  const enabled = value != null && (!isRuleArray(value) || value.length > 0);

  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Stack spacing={1.5}>
        <Typography variant="subtitle2">{label}</Typography>
        <Typography variant="body2" color="text.secondary">
          Routing rules live in recipe JSON — the server evaluates `when` / shorthands generically (no per-recipe Java).
          Match hints can drive matching via &quot;Use match hints as phrases&quot;.
        </Typography>
        <FormControlLabel
          control={
            <Checkbox
              size="small"
              checked={enabled}
              onChange={(_, c) => onChange(c ? defaultRule() : undefined)}
            />
          }
          label="Enable rules for this recipe"
        />
        {enabled && multi ? (
          <TextField
            label="Rules (JSON array)"
            value={advancedJson || JSON.stringify(value, null, 2)}
            onChange={(e) => {
              setAdvancedJson(e.target.value);
              try {
                const parsed = JSON.parse(e.target.value) as IntentRecipeMatchRule[];
                if (Array.isArray(parsed)) onChange(parsed);
              } catch {
                /* keep typing */
              }
            }}
            fullWidth
            multiline
            minRows={6}
            size="small"
            InputProps={{ sx: { fontFamily: 'monospace', fontSize: 12 } }}
            helperText="Multiple rules (e.g. llm_research). Edit JSON directly."
          />
        ) : null}
        {enabled && !multi ? (
          <Stack spacing={1.5}>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
              <TextField
                label="Priority"
                type="number"
                size="small"
                value={rule.priority ?? ''}
                onChange={(e) =>
                  patchRule({ priority: e.target.value === '' ? undefined : Number(e.target.value) })
                }
                sx={{ width: 120 }}
              />
              <TextField
                label="Router reason"
                size="small"
                value={rule.routerReason ?? ''}
                onChange={(e) => patchRule({ routerReason: e.target.value })}
                fullWidth
                InputProps={{ sx: { fontFamily: 'monospace' } }}
              />
            </Stack>
            <FormControlLabel
              control={
                <Checkbox
                  size="small"
                  checked={Boolean(rule.skipPrefetch)}
                  onChange={(_, c) => patchRule({ skipPrefetch: c || undefined })}
                />
              }
              label="Skip prefetch"
            />
            <FormControlLabel
              control={
                <Checkbox
                  size="small"
                  checked={Boolean(rule.requiresAnchoredSiteXml)}
                  onChange={(_, c) => patchRule({ requiresAnchoredSiteXml: c || undefined })}
                />
              }
              label="Requires anchored /site/…/*.xml"
            />
            <FormControlLabel
              control={
                <Checkbox
                  size="small"
                  checked={Boolean(rule.authorFromMatchHints)}
                  onChange={(_, c) => patchRule({ authorFromMatchHints: c || undefined })}
                />
              }
              label={`Use match hints as author phrases (${matchHints.length})`}
            />
            <FormControlLabel
              control={
                <Checkbox
                  size="small"
                  checked={Boolean(rule.respectDontMatchHints)}
                  onChange={(_, c) => patchRule({ respectDontMatchHints: c || undefined })}
                />
              }
              label="Respect don't-match hints"
            />
            <Autocomplete
              freeSolo
              size="small"
              options={[...INTENT_RECIPE_WHEN_LEAF_OPTIONS]}
              value={typeof rule.when === 'string' ? rule.when : ''}
              onChange={(_, v) => patchRule({ when: (v || '').trim() || undefined })}
              renderInput={(params) => (
                <TextField
                  {...params}
                  label="When (leaf predicate or leave empty)"
                  helperText="Built-in predicates: translateIntent, concreteFieldEdit, … — or use advanced JSON for allOf/regex."
                />
              )}
            />
            <TextField
              label="When (advanced JSON, optional)"
              size="small"
              fullWidth
              multiline
              minRows={3}
              placeholder='{"allOf":["anchoredSiteXml",{"authorMatchesRegex":"..."}]}'
              value={
                rule.when != null && typeof rule.when !== 'string'
                  ? JSON.stringify(rule.when, null, 2)
                  : ''
              }
              onChange={(e) => {
                const t = e.target.value.trim();
                if (!t) {
                  patchRule({ when: undefined });
                  return;
                }
                try {
                  patchRule({ when: JSON.parse(t) as IntentRecipeMatchRule['when'] });
                } catch {
                  /* typing */
                }
              }}
              InputProps={{ sx: { fontFamily: 'monospace', fontSize: 12 } }}
            />
          </Stack>
        ) : null}
      </Stack>
    </Paper>
  );
}

/** Deterministic + optional ambiguity blocks on the recipe editor. */
export function AiAssistantIntentRecipeRoutingRulesSection(props: {
  recipe: IntentRecipe;
  onChange: (recipe: IntentRecipe) => void;
}) {
  const { recipe, onChange } = props;
  return (
    <Box>
      <Stack spacing={2}>
        <AiAssistantIntentRecipeMatchRulesField
          label="Deterministic match"
          value={recipe.deterministicMatch}
          matchHints={recipe.matchHints ?? []}
          onChange={(deterministicMatch) => onChange({ ...recipe, deterministicMatch })}
        />
        <AiAssistantIntentRecipeMatchRulesField
          label="Ambiguity match (optional)"
          value={recipe.ambiguityMatch}
          matchHints={recipe.matchHints ?? []}
          onChange={(ambiguityMatch) => onChange({ ...recipe, ambiguityMatch })}
        />
      </Stack>
    </Box>
  );
}
