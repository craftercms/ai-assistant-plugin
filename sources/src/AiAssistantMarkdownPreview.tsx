import { useMemo } from 'react';
import {
  Box,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
  useTheme
} from '@mui/material';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeSanitize, { defaultSchema } from 'rehype-sanitize';
import { replaceSlackColonEmojisOutsideMarkdownFences } from './slackColonEmoji';

function markdownSanitizeSchema() {
  const srcProtocols = defaultSchema.protocols?.src ?? [];
  return {
    ...defaultSchema,
    attributes: {
      ...defaultSchema.attributes,
      code: [...(defaultSchema.attributes?.code || []), 'className']
    },
    protocols: {
      ...defaultSchema.protocols,
      src: [...new Set([...srcProtocols, 'http', 'https', 'mailto'])]
    }
  };
}

export interface AiAssistantMarkdownPreviewProps {
  value: string;
  /** Smaller typography for swimlane / config panels. */
  compact?: boolean;
  /** Scroll when content exceeds this height (px). Omit for no cap. */
  maxHeightPx?: number;
}

/** Renders markdown for Studio config previews (recipe swimlane, etc.). */
export default function AiAssistantMarkdownPreview(props: Readonly<AiAssistantMarkdownPreviewProps>) {
  const { value, compact = false, maxHeightPx } = props;
  const theme = useTheme();
  const sanitizeSchema = useMemo(() => markdownSanitizeSchema(), []);
  const md = useMemo(() => replaceSlackColonEmojisOutsideMarkdownFences(value), [value]);

  const bodySize = compact ? '0.75rem' : '0.8125rem';
  const headingVariant = compact ? 'caption' : 'body2';

  const mdComponents = useMemo(
    () => ({
      h1: ({ children }: { children?: React.ReactNode }) => (
        <Typography variant={headingVariant} sx={{ fontWeight: 700, mt: 0.75, mb: 0.35, fontSize: compact ? '0.8125rem' : undefined }}>
          {children}
        </Typography>
      ),
      h2: ({ children }: { children?: React.ReactNode }) => (
        <Typography variant={headingVariant} sx={{ fontWeight: 700, mt: 0.75, mb: 0.35, fontSize: compact ? '0.8125rem' : undefined }}>
          {children}
        </Typography>
      ),
      h3: ({ children }: { children?: React.ReactNode }) => (
        <Typography variant={headingVariant} sx={{ fontWeight: 700, mt: 0.5, mb: 0.25, fontSize: bodySize }}>
          {children}
        </Typography>
      ),
      p: ({ children }: { children?: React.ReactNode }) => (
        <Typography variant="body2" component="div" sx={{ fontSize: bodySize, lineHeight: 1.45, mb: 0.5, '&:last-child': { mb: 0 } }}>
          {children}
        </Typography>
      ),
      ul: ({ children }: { children?: React.ReactNode }) => (
        <Box component="ul" sx={{ m: 0, pl: 2, mb: 0.5, fontSize: bodySize, lineHeight: 1.45 }}>
          {children}
        </Box>
      ),
      ol: ({ children }: { children?: React.ReactNode }) => (
        <Box component="ol" sx={{ m: 0, pl: 2, mb: 0.5, fontSize: bodySize, lineHeight: 1.45 }}>
          {children}
        </Box>
      ),
      li: ({ children }: { children?: React.ReactNode }) => (
        <Box component="li" sx={{ mb: 0.2, '& > p': { display: 'inline', m: 0 } }}>
          {children}
        </Box>
      ),
      strong: ({ children }: { children?: React.ReactNode }) => (
        <Box component="strong" sx={{ fontWeight: 700 }}>
          {children}
        </Box>
      ),
      em: ({ children }: { children?: React.ReactNode }) => (
        <Box component="em" sx={{ fontStyle: 'italic' }}>
          {children}
        </Box>
      ),
      a: ({ href, children }: { href?: string; children?: React.ReactNode }) => (
        <Box component="a" href={href} target="_blank" rel="noreferrer" sx={{ color: theme.palette.primary.main, fontSize: 'inherit' }}>
          {children}
        </Box>
      ),
      code: ({ className, children }: { className?: string; children?: React.ReactNode }) => {
        const raw = String(children ?? '').replace(/\n$/, '');
        const isInline = !className;
        if (isInline) {
          return (
            <Box
              component="code"
              sx={{
                px: 0.35,
                fontFamily: 'ui-monospace, monospace',
                fontSize: compact ? '0.7rem' : '0.75rem',
                bgcolor: theme.palette.mode === 'dark' ? 'grey.800' : 'grey.200',
                borderRadius: 0.5
              }}
            >
              {raw}
            </Box>
          );
        }
        return (
          <Box
            component="pre"
            sx={{
              m: 0,
              my: 0.75,
              p: 0.75,
              overflow: 'auto',
              borderRadius: 1,
              fontSize: compact ? '0.7rem' : '0.75rem',
              bgcolor: theme.palette.mode === 'dark' ? 'grey.900' : 'grey.100',
              border: `1px solid ${theme.palette.mode === 'dark' ? theme.palette.grey[800] : theme.palette.grey[300]}`
            }}
          >
            <code>{raw}</code>
          </Box>
        );
      },
      table: ({ children }: { children?: React.ReactNode }) => (
        <TableContainer
          component={Paper}
          elevation={0}
          sx={{
            my: 0.75,
            overflow: 'auto',
            borderRadius: 1,
            border: `1px solid ${theme.palette.divider}`,
            bgcolor: theme.palette.mode === 'dark' ? 'grey.900' : 'grey.50'
          }}
        >
          <Table size="small" sx={{ minWidth: 200, fontSize: bodySize }}>
            {children}
          </Table>
        </TableContainer>
      ),
      thead: ({ children }: { children?: React.ReactNode }) => <TableHead>{children}</TableHead>,
      tbody: ({ children }: { children?: React.ReactNode }) => <TableBody>{children}</TableBody>,
      tr: ({ children }: { children?: React.ReactNode }) => <TableRow>{children}</TableRow>,
      th: ({ children }: { children?: React.ReactNode }) => (
        <TableCell sx={{ fontWeight: 700, fontSize: bodySize, py: 0.5, px: 1 }}>
          {children}
        </TableCell>
      ),
      td: ({ children }: { children?: React.ReactNode }) => (
        <TableCell sx={{ fontSize: bodySize, py: 0.5, px: 1, verticalAlign: 'top' }}>
          {children}
        </TableCell>
      )
    }),
    [theme, compact, bodySize, headingVariant]
  );

  return (
    <Box
      sx={{
        ...(maxHeightPx != null ? { maxHeight: maxHeightPx, overflow: 'auto' } : {}),
        '& :first-of-type': { mt: 0 },
        '& :last-of-type': { mb: 0 }
      }}
    >
      <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[[rehypeSanitize, sanitizeSchema]]} components={mdComponents}>
        {md}
      </ReactMarkdown>
    </Box>
  );
}
