import { Box, Typography } from '@mui/material';
import AiAssistantStudioCodeEditor from './AiAssistantStudioCodeEditor';

export interface AiAssistantMarkdownEditorProps {
  value: string;
  onChange?: (next: string) => void;
  readOnly?: boolean;
  minHeightPx?: number;
  flexFill?: boolean;
  helperText?: string;
  id?: string;
}

/** Markdown source editor (CodeMirror) — whitespace-safe; no preview/split tabs. */
export default function AiAssistantMarkdownEditor(props: Readonly<AiAssistantMarkdownEditorProps>) {
  const { value, onChange, readOnly = false, minHeightPx = 280, flexFill = false, helperText, id } = props;

  return (
    <Box id={id} sx={flexFill ? { flex: '1 1 auto', minHeight: 0, display: 'flex', flexDirection: 'column' } : undefined}>
      {helperText ? (
        <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 1, flexShrink: 0 }}>
          {helperText}
        </Typography>
      ) : null}
      <AiAssistantStudioCodeEditor
        language="markdown"
        value={value}
        onChange={readOnly ? undefined : onChange}
        readOnly={readOnly}
        minHeightPx={minHeightPx}
        flexFill={flexFill}
      />
    </Box>
  );
}
