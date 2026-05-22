import { Box, Typography } from '@mui/material';

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
          maxHeight: 96,
          overflow: 'auto',
          px: 1,
          py: 0.75,
          borderRadius: 1,
          bgcolor: 'action.hover',
          border: '1px solid',
          borderColor: 'divider'
        }}
      >
        <Typography variant="caption" component="div" sx={{ fontSize: 12, lineHeight: 1.45, whiteSpace: 'pre-wrap' }}>
          {hint}
        </Typography>
      </Box>
    );
  }

  return (
    <Typography key={`hint-${index}`} variant="body2" sx={{ fontSize: 13, lineHeight: 1.4 }}>
      {hint}
    </Typography>
  );
}
