import { useMemo, useState } from 'react';
import { Box, Tab, Tabs, Typography, useMediaQuery, useTheme } from '@mui/material';
import AiAssistantStudioCodeEditor from './AiAssistantStudioCodeEditor';
import MarkdownMessage from './MarkdownMessage';

export type AiAssistantMarkdownEditorView = 'edit' | 'preview' | 'split';

export interface AiAssistantMarkdownEditorProps {
  value: string;
  onChange?: (next: string) => void;
  readOnly?: boolean;
  minHeightPx?: number;
  flexFill?: boolean;
  /** Initial tab when editable; read-only defaults to preview. */
  defaultView?: AiAssistantMarkdownEditorView;
  helperText?: string;
  id?: string;
}

/**
 * Markdown authoring for project context, prompt overrides, and recipe preludes —
 * CodeMirror source + rendered preview (not a plain multiline {@code TextField}).
 */
export default function AiAssistantMarkdownEditor(props: Readonly<AiAssistantMarkdownEditorProps>) {
  const {
    value,
    onChange,
    readOnly = false,
    minHeightPx = 280,
    flexFill = false,
    defaultView = 'split',
    helperText,
    id
  } = props;
  const theme = useTheme();
  const isNarrow = useMediaQuery(theme.breakpoints.down('md'));
  const initialView: AiAssistantMarkdownEditorView = readOnly
    ? 'preview'
    : isNarrow
      ? 'edit'
      : defaultView;
  const [view, setView] = useState<AiAssistantMarkdownEditorView>(initialView);

  const showEdit = view === 'edit' || view === 'split';
  const showPreview = view === 'preview' || view === 'split';

  const paneMinHeight = Math.max(200, minHeightPx);

  const rootSx = useMemo(
    () =>
      flexFill
        ? {
            flex: '1 1 auto',
            minHeight: 0,
            display: 'flex',
            flexDirection: 'column' as const,
            alignSelf: 'stretch' as const
          }
        : { display: 'flex', flexDirection: 'column' as const },
    [flexFill]
  );

  const paneSx = useMemo(
    () =>
      flexFill
        ? {
            flex: '1 1 0',
            minHeight: 0,
            minWidth: 0,
            display: 'flex',
            flexDirection: 'column' as const
          }
        : {
            flex: '1 1 0',
            minHeight: paneMinHeight,
            minWidth: 0,
            display: 'flex',
            flexDirection: 'column' as const
          },
    [flexFill, paneMinHeight]
  );

  return (
    <Box id={id} sx={rootSx}>
      <Tabs
        value={view}
        onChange={(_, next) => setView(next as AiAssistantMarkdownEditorView)}
        sx={{ minHeight: 36, mb: helperText ? 0.5 : 1, flexShrink: 0 }}
      >
        {!readOnly ? <Tab label="Edit" value="edit" sx={{ minHeight: 36, py: 0.5 }} /> : null}
        <Tab label={readOnly ? 'Rendered' : 'Preview'} value="preview" sx={{ minHeight: 36, py: 0.5 }} />
        {!readOnly && !isNarrow ? (
          <Tab label="Split" value="split" sx={{ minHeight: 36, py: 0.5 }} />
        ) : null}
        {readOnly ? <Tab label="Source" value="edit" sx={{ minHeight: 36, py: 0.5 }} /> : null}
      </Tabs>
      {helperText ? (
        <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 1, flexShrink: 0 }}>
          {helperText}
        </Typography>
      ) : null}
      <Box
        sx={{
          display: 'flex',
          flexDirection: { xs: 'column', md: view === 'split' ? 'row' : 'column' },
          gap: view === 'split' ? 2 : 0,
          flex: flexFill ? '1 1 auto' : undefined,
          minHeight: flexFill ? 0 : paneMinHeight
        }}
      >
        {showEdit ? (
          <Box sx={paneSx}>
            {view === 'split' ? (
              <Typography variant="caption" color="text.secondary" sx={{ mb: 0.5, flexShrink: 0 }}>
                Markdown source
              </Typography>
            ) : null}
            <AiAssistantStudioCodeEditor
              value={value}
              onChange={readOnly ? undefined : onChange}
              language="markdown"
              readOnly={readOnly}
              flexFill={flexFill || view === 'split'}
              minHeightPx={paneMinHeight}
            />
          </Box>
        ) : null}
        {showPreview ? (
          <Box
            sx={{
              ...paneSx,
              overflow: 'auto',
              border: 1,
              borderColor: 'divider',
              borderRadius: 1,
              px: 2,
              py: 1.5,
              bgcolor: theme.palette.mode === 'dark' ? 'grey.900' : 'background.paper'
            }}
          >
            {view === 'split' ? (
              <Typography variant="caption" color="text.secondary" sx={{ mb: 0.5, display: 'block', flexShrink: 0 }}>
                Rendered preview
              </Typography>
            ) : null}
            {value.trim() ? (
              <MarkdownMessage text={value} />
            ) : (
              <Typography variant="body2" color="text.secondary" fontStyle="italic">
                Nothing to preview yet.
              </Typography>
            )}
          </Box>
        ) : null}
      </Box>
    </Box>
  );
}
