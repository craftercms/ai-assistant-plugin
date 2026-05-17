import EditRounded from '@mui/icons-material/EditRounded';
import { Box, Button, Chip, Stack, Typography } from '@mui/material';
import AiAssistantIntentRecipeSwimlane from './AiAssistantIntentRecipeSwimlane';
import {
  declaredBindingNames,
  resolveRecipeChatEmoji,
  type IntentRecipe,
  type IntentRecipeListEntry
} from './aiAssistantIntentRecipesModel';

export interface AiAssistantIntentRecipeViewProps {
  recipe: IntentRecipe;
  entry: IntentRecipeListEntry;
  onEdit: () => void;
}

/**
 * Read-only recipe panel: swimlane visualization and metadata. Full editor opens on demand.
 */
export default function AiAssistantIntentRecipeView(props: AiAssistantIntentRecipeViewProps) {
  const { recipe, entry, onEdit } = props;
  const bindingNames = declaredBindingNames(recipe);
  const chatEmoji = resolveRecipeChatEmoji(recipe);

  return (
    <Stack spacing={2}>
      <Stack direction="row" justifyContent="space-between" alignItems="flex-start" gap={1} flexWrap="wrap">
        <Box sx={{ minWidth: 0 }}>
          <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: recipe.description ? 1 : 0 }}>
            <Typography component="span" sx={{ fontSize: '1.5rem', lineHeight: 1 }} aria-hidden>
              {chatEmoji}
            </Typography>
            <Typography variant="subtitle2">{recipe.title || recipe.id}</Typography>
          </Stack>
          {recipe.description ? (
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
              {recipe.description}
            </Typography>
          ) : null}
          {recipe.matchHints && recipe.matchHints.length > 0 ? (
            <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap alignItems="center" sx={{ mb: 0.5 }}>
              <Typography variant="caption" color="text.secondary">
                Match:
              </Typography>
              {recipe.matchHints.map((h) => (
                <Chip key={`m:${h}`} size="small" label={h} variant="outlined" />
              ))}
            </Stack>
          ) : null}
          {recipe.dontMatchHints && recipe.dontMatchHints.length > 0 ? (
            <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap alignItems="center" sx={{ mb: 1 }}>
              <Typography variant="caption" color="text.secondary">
                Don't match:
              </Typography>
              {recipe.dontMatchHints.map((h) => (
                <Chip key={`d:${h}`} size="small" label={h} variant="outlined" color="warning" />
              ))}
            </Stack>
          ) : null}
        </Box>
        <Button size="small" variant="contained" startIcon={<EditRounded />} onClick={onEdit}>
          Edit recipe
        </Button>
      </Stack>

      {bindingNames.length > 0 ? (
        <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap alignItems="center">
          <Typography variant="caption" color="text.secondary">
            Prefetch:
          </Typography>
          {bindingNames.map((n) => (
            <Chip key={n} size="small" label={n} variant="outlined" />
          ))}
        </Stack>
      ) : null}

      <AiAssistantIntentRecipeSwimlane recipe={recipe} />

      {entry.source === 'bundled' ? (
        <Typography variant="caption" color="text.secondary">
          Built-in recipe — Edit to customize for your project.
        </Typography>
      ) : null}
    </Stack>
  );
}
