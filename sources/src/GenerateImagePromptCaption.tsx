import React from 'react';
import { Typography, useTheme } from '@mui/material';

export type GenerateImagePromptCaptionProps = {
  /** Image model prompt from {@code GenerateImage} SSE metadata ({@code generateImagePrompt}). */
  prompt?: string;
};

/** Shows the prompt sent to the image backend after (or while) the chat strip displays the bitmap. */
export default function GenerateImagePromptCaption({ prompt }: GenerateImagePromptCaptionProps) {
  const theme = useTheme();
  const text = (prompt || '').trim();
  if (!text) return null;
  return (
    <Typography
      component="p"
      variant="caption"
      color="text.secondary"
      data-aiassistant-generate-image-prompt
      sx={{
        mt: 0.5,
        mb: 0,
        lineHeight: 1.45,
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-word',
        maxWidth: '100%',
        px: 0.25,
        borderLeft: `2px solid ${theme.palette.mode === 'dark' ? theme.palette.grey[700] : theme.palette.grey[300]}`,
        pl: 1
      }}
    >
      <Typography component="span" variant="caption" sx={{ fontWeight: 600, color: 'text.primary', mr: 0.5 }}>
        Prompt used:
      </Typography>
      {text}
    </Typography>
  );
}
