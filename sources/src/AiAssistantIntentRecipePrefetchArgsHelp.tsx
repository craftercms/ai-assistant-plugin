import {
  Alert,
  Box,
  Button,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography
} from '@mui/material';
import {
  defaultPrefetchArgsJsonForTool,
  PREFETCH_BINDING_TOKENS,
  prefetchToolDoc
} from './intentRecipePrefetchToolReference';

export interface AiAssistantIntentRecipePrefetchArgsHelpProps {
  tool: string;
  onInsertDefaultArgs?: () => void;
}

export default function AiAssistantIntentRecipePrefetchArgsHelp(props: AiAssistantIntentRecipePrefetchArgsHelpProps) {
  const { tool, onInsertDefaultArgs } = props;
  const doc = prefetchToolDoc(tool);

  if (!doc) {
    return (
      <Alert severity="warning" sx={{ py: 0.5 }}>
        <Typography variant="body2">
          No built-in reference for <strong>{tool || '(pick a tool)'}</strong>. Prefetch only supports read-only
          tools documented in the plugin. Copy args from a bundled recipe or see{' '}
          <code>AuthoringIntentRecipeEngine.groovy</code>.
        </Typography>
      </Alert>
    );
  }

  return (
    <Stack spacing={1}>
      <Typography variant="body2" color="text.secondary">
        {doc.summary}
      </Typography>
      <Table size="small" sx={{ '& td, & th': { py: 0.5, px: 1 } }}>
        <TableHead>
          <TableRow>
            <TableCell>Arg</TableCell>
            <TableCell>Required</TableCell>
            <TableCell>Description</TableCell>
            <TableCell>Example</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {doc.args.map((a) => (
            <TableRow key={a.name}>
              <TableCell sx={{ fontFamily: 'monospace', fontSize: 12 }}>{a.name}</TableCell>
              <TableCell>{a.required ? 'yes' : ''}</TableCell>
              <TableCell>{a.description}</TableCell>
              <TableCell sx={{ fontFamily: 'monospace', fontSize: 11 }}>{a.example ?? ''}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      {onInsertDefaultArgs ? (
        <Button size="small" variant="text" onClick={onInsertDefaultArgs} sx={{ alignSelf: 'flex-start' }}>
          Insert default args for {tool}
        </Button>
      ) : null}
    </Stack>
  );
}

export function AiAssistantIntentRecipePrefetchBindingsHelp() {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary" display="block" gutterBottom>
        Placeholders for prefetch step arguments
      </Typography>
      <Table size="small" sx={{ '& td, & th': { py: 0.5, px: 1 } }}>
        <TableHead>
          <TableRow>
            <TableCell>Token</TableCell>
            <TableCell>Meaning</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {PREFETCH_BINDING_TOKENS.map((b) => (
            <TableRow key={b.token}>
              <TableCell sx={{ fontFamily: 'monospace', fontSize: 12 }}>{b.token}</TableCell>
              <TableCell>{b.description}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Box>
  );
}
