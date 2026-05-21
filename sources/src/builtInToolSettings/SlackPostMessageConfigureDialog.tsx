import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Link from '@mui/material/Link';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import type { BuiltInToolConfigureDialogProps } from './types';
import type { SlackPostMessageSettingsFormState } from './slackPostMessageSettings';

export default function SlackPostMessageConfigureDialog(
  props: BuiltInToolConfigureDialogProps<SlackPostMessageSettingsFormState>
) {
  const { open, draft, onDraftChange, onClose, onApply } = props;

  const patch = (partial: Partial<SlackPostMessageSettingsFormState>) => {
    onDraftChange({ ...draft, ...partial });
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>SlackPostMessage — site defaults</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Typography variant="body2" color="text.secondary">
            Configure Slack defaults for this site. The bot token is set on{' '}
            <strong>Project Tools → Secrets</strong> (Slack entry, <code>slack_bot_token</code>,{' '}
            <code>chat:write</code> scope).
          </Typography>
          <TextField
            label="Default channel (optional)"
            size="small"
            fullWidth
            value={draft.defaultChannel}
            onChange={(e) => patch({ defaultChannel: e.target.value })}
            placeholder="#general or C01234567"
            helperText="Used when the agent omits channel on a tool call."
          />
          <TextField
            label="Secrets key override (optional)"
            size="small"
            fullWidth
            value={draft.secretKey}
            onChange={(e) => patch({ secretKey: e.target.value })}
            placeholder="slack_bot_token"
            helperText="Leave empty to use the built-in slack_bot_token Secrets slot."
            InputProps={{ sx: { fontFamily: 'monospace' } }}
          />
          <Typography variant="caption" color="text.secondary">
            <Link
              href="https://docs.slack.dev/reference/methods/chat.postMessage/"
              target="_blank"
              rel="noopener noreferrer"
            >
              Slack chat.postMessage API reference
            </Link>
          </Typography>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          onClick={() => {
            onApply(draft);
            onClose();
          }}
        >
          Apply
        </Button>
      </DialogActions>
    </Dialog>
  );
}
