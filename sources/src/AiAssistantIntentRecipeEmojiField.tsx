import { useMemo, useState } from 'react';
import { Box, Button, Popover, Stack, TextField, Typography } from '@mui/material';
import { bundledIntentRecipesCatalog, normalizeChatEmoji } from './aiAssistantIntentRecipesModel';
import {
  formatIntentRecipeChatLineFromRecipe,
  INTENT_RECIPE_CHAT_FALLBACK_EMOJI
} from './intentRecipeChatDisplay';

const EXTRA_SUGGESTED_EMOJIS = ['📋', '🛠️', '✨', '⚙️', '📝', '🔗', '🎯', '⏪', '🔄', '✅'];

function suggestedRecipeEmojis(): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  const push = (e: string) => {
    const n = normalizeChatEmoji(e);
    if (n && !seen.has(n)) {
      seen.add(n);
      out.push(n);
    }
  };
  for (const r of bundledIntentRecipesCatalog().recipes) {
    push(String(r.chatEmoji ?? ''));
  }
  for (const e of EXTRA_SUGGESTED_EMOJIS) {
    push(e);
  }
  push(INTENT_RECIPE_CHAT_FALLBACK_EMOJI);
  return out;
}

export interface AiAssistantIntentRecipeEmojiFieldProps {
  emoji: string;
  title: string;
  recipeId: string;
  onChange: (emoji: string) => void;
  disabled?: boolean;
}

export default function AiAssistantIntentRecipeEmojiField(props: AiAssistantIntentRecipeEmojiFieldProps) {
  const { emoji, title, recipeId, onChange, disabled } = props;
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const suggestions = useMemo(() => suggestedRecipeEmojis(), []);
  const display = normalizeChatEmoji(emoji) || INTENT_RECIPE_CHAT_FALLBACK_EMOJI;
  const preview = formatIntentRecipeChatLineFromRecipe({
    id: recipeId,
    title: title || recipeId,
    chatEmoji: display
  });

  return (
    <Stack spacing={0.75} sx={{ flex: '0 0 auto' }}>
      <Typography variant="caption" color="text.secondary">
        Workflow emoji
      </Typography>
      <Stack direction="row" spacing={1} alignItems="center">
        <Button
          variant="outlined"
          disabled={disabled}
          onClick={(e) => setAnchor(e.currentTarget)}
          aria-label="Pick workflow emoji"
          sx={{
            minWidth: 52,
            width: 52,
            height: 40,
            fontSize: '1.35rem',
            lineHeight: 1,
            px: 0
          }}
        >
          {display}
        </Button>
        <TextField
          label="Emoji"
          value={emoji}
          onChange={(e) => onChange(normalizeChatEmoji(e.target.value))}
          size="small"
          disabled={disabled}
          placeholder={INTENT_RECIPE_CHAT_FALLBACK_EMOJI}
          inputProps={{ maxLength: 8, 'aria-label': 'Workflow emoji character' }}
          sx={{ width: 88 }}
        />
      </Stack>
      <Typography variant="caption" color="text.secondary" sx={{ fontFamily: 'inherit', whiteSpace: 'pre-wrap' }}>
        Chat preview: {preview.trim()}
      </Typography>
      <Popover
        open={Boolean(anchor)}
        anchorEl={anchor}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'left' }}
      >
        <Box sx={{ p: 1.5, maxWidth: 280 }}>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1 }}>
            Pick an emoji
          </Typography>
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: 'repeat(8, 1fr)',
              gap: 0.5
            }}
          >
            {suggestions.map((e) => (
              <Button
                key={e}
                size="small"
                onClick={() => {
                  onChange(e);
                  setAnchor(null);
                }}
                sx={{ minWidth: 36, fontSize: '1.2rem', lineHeight: 1, p: 0.5 }}
              >
                {e}
              </Button>
            ))}
          </Box>
        </Box>
      </Popover>
    </Stack>
  );
}
