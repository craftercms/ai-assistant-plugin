import { Autocomplete, Stack, TextField } from '@mui/material';
import type { IntentRecipe } from './aiAssistantIntentRecipesModel';
import AiAssistantIntentRecipeEmojiField from './AiAssistantIntentRecipeEmojiField';
import { AiAssistantIntentRecipeRoutingRulesSection } from './AiAssistantIntentRecipeMatchRulesField';
import AiAssistantIntentRecipeToolsLoopFields from './AiAssistantIntentRecipeToolsLoopFields';

export interface AiAssistantIntentRecipeGeneralFieldsProps {
  recipe: IntentRecipe;
  onChange: (recipe: IntentRecipe) => void;
  idReadOnly?: boolean;
}

/** Recipe metadata, match rules, and tools-loop policy (editor General tab). */
export default function AiAssistantIntentRecipeGeneralFields(props: AiAssistantIntentRecipeGeneralFieldsProps) {
  const { recipe, onChange, idReadOnly } = props;

  const patchRecipe = (partial: Partial<IntentRecipe>) => {
    onChange({ ...recipe, ...partial });
  };

  return (
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
      <AiAssistantIntentRecipeToolsLoopFields recipe={recipe} onChange={onChange} />
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
  );
}
