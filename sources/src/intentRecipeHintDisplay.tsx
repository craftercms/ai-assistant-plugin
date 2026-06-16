import { Box } from '@mui/material';
import AiAssistantMarkdownPreview from './AiAssistantMarkdownPreview';

const LONG_HINT_CHARS = 220;

export type IntentRecipeHintDisplayKind = 'compact' | 'long';

export function classifyIntentRecipeHint(hint: string): IntentRecipeHintDisplayKind {
  const t = hint.trim();
  if (!t) return 'compact';
  if (t.length >= LONG_HINT_CHARS) return 'long';
  return 'compact';
}

export function IntentRecipeHintLine(props: { hint: string; index: number }) {
  const { hint, index } = props;
  const kind = classifyIntentRecipeHint(hint);

  if (kind === 'long') {
    return (
      <Box
        key={`hint-${index}`}
        sx={{
          px: 1,
          py: 0.75,
          borderRadius: 1,
          bgcolor: 'action.hover',
          border: '1px solid',
          borderColor: 'divider'
        }}
      >
        <AiAssistantMarkdownPreview value={hint} compact maxHeightPx={280} />
      </Box>
    );
  }

  return (
    <Box key={`hint-${index}`} sx={{ fontSize: 13, lineHeight: 1.4 }}>
      <AiAssistantMarkdownPreview value={hint} compact />
    </Box>
  );
}
