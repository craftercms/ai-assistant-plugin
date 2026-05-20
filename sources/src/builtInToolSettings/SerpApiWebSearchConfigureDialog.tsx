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
import type { SerpApiWebSearchSettingsFormState } from './serpApiWebSearchSettings';

export default function SerpApiWebSearchConfigureDialog(
  props: BuiltInToolConfigureDialogProps<SerpApiWebSearchSettingsFormState>
) {
  const { open, draft, onDraftChange, onClose, onApply } = props;

  const patch = (partial: Partial<SerpApiWebSearchSettingsFormState>) => {
    onDraftChange({ ...draft, ...partial });
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>SerpApiWebSearch — site defaults</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Typography variant="body2" color="text.secondary">
            Set search defaults for this site. Your SerpAPI API key is configured separately on{' '}
            <strong>Project Tools → Secrets</strong> (SerpAPI entry). It is not entered here.
          </Typography>
          <TextField
            label="Engine"
            size="small"
            fullWidth
            value={draft.engine}
            onChange={(e) => patch({ engine: e.target.value })}
          />
          <TextField
            label="Google domain"
            size="small"
            fullWidth
            value={draft.googleDomain}
            onChange={(e) => patch({ googleDomain: e.target.value })}
            placeholder="google.com"
          />
          <Stack direction="row" spacing={2}>
            <TextField
              label="Country (gl)"
              size="small"
              fullWidth
              value={draft.gl}
              onChange={(e) => patch({ gl: e.target.value })}
              placeholder="us"
            />
            <TextField
              label="Language (hl)"
              size="small"
              fullWidth
              value={draft.hl}
              onChange={(e) => patch({ hl: e.target.value })}
              placeholder="en"
            />
          </Stack>
          <TextField
            label="Location"
            size="small"
            fullWidth
            value={draft.location}
            onChange={(e) => patch({ location: e.target.value })}
          />
          <Stack direction="row" spacing={2}>
            <TextField
              label="Default result count (num)"
              size="small"
              fullWidth
              value={draft.num}
              onChange={(e) => patch({ num: e.target.value })}
              helperText="1–20 when set"
            />
            <TextField
              label="Device"
              size="small"
              fullWidth
              value={draft.device}
              onChange={(e) => patch({ device: e.target.value })}
            />
          </Stack>
          <TextField
            label="Safe search"
            size="small"
            fullWidth
            value={draft.safe}
            onChange={(e) => patch({ safe: e.target.value })}
          />
          <Typography variant="caption" color="text.secondary">
            <Link href="https://serpapi.com/search-api" target="_blank" rel="noopener noreferrer">
              SerpAPI parameter reference
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
