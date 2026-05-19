import { useCallback, useEffect, useMemo, useState } from 'react';
import useActiveSiteId from '@craftercms/studio-ui/hooks/useActiveSiteId';
import AddRounded from '@mui/icons-material/AddRounded';
import DeleteOutlineRounded from '@mui/icons-material/DeleteOutlineRounded';
import SaveRounded from '@mui/icons-material/SaveRounded';
import VisibilityOffRounded from '@mui/icons-material/VisibilityOffRounded';
import VisibilityRounded from '@mui/icons-material/VisibilityRounded';
import {
  Alert,
  Box,
  Button,
  FormControl,
  FormControlLabel,
  Divider,
  IconButton,
  InputAdornment,
  Radio,
  RadioGroup,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Tooltip,
  Typography
} from '@mui/material';
import { fetchAiAssistantSecretsIndex, saveAiAssistantSecretsEntries } from './aiAssistantSecretsApi';
import {
  buildSecretSaveEntries,
  rowDraftFromAdmin,
  SECRETS_JSON_REL,
  type AiAssistantSecretEditMode,
  type AiAssistantSecretRowDraft
} from './aiAssistantSecretsModel';
import { effectiveStudioSiteId } from './aiAssistantStudioUiConfig';

function isValidCustomKey(key: string): boolean {
  return /^[a-z][a-z0-9_]{0,63}$/.test(key.trim());
}

function SecretValueField(props: {
  draft: AiAssistantSecretRowDraft;
  showPlain: boolean;
  onToggleShow: () => void;
  onEditMode: (mode: AiAssistantSecretEditMode) => void;
  onEnvVar: (v: string) => void;
  onExpression: (v: string) => void;
  onPlain: (v: string) => void;
}) {
  const { draft, showPlain, onToggleShow, onEditMode, onEnvVar, onExpression, onPlain } = props;

  return (
    <Stack spacing={1.25} sx={{ minWidth: 280 }}>
      <FormControl size="small">
        <RadioGroup
          row
          value={draft.editMode}
          onChange={(_, v) => onEditMode(v as AiAssistantSecretEditMode)}
        >
          <FormControlLabel value="env" control={<Radio size="small" />} label="Environment" />
          <FormControlLabel value="enc" control={<Radio size="small" />} label="Encrypted" />
          <FormControlLabel value="plain" control={<Radio size="small" />} label="Plain (encrypt on save)" />
        </RadioGroup>
      </FormControl>

      {draft.editMode === 'env' ? (
        <TextField
          size="small"
          label="Environment variable"
          placeholder="OPENAI_API_KEY"
          value={draft.envVar}
          onChange={(e) => onEnvVar(e.target.value)}
          fullWidth
        />
      ) : null}

      {draft.editMode === 'enc' ? (
        <TextField
          size="small"
          label="Encrypted value"
          placeholder="${enc:…} from Studio Encrypt Marked"
          value={draft.expressionDraft}
          onChange={(e) => onExpression(e.target.value)}
          helperText="Paste ${enc:…} from Crafter Studio configuration encryption, or ciphertext only."
          fullWidth
        />
      ) : null}

      {draft.editMode === 'plain' ? (
        <TextField
          size="small"
          label={draft.hasStoredLiteral ? 'Replace secret value' : 'Secret value'}
          placeholder={draft.hasStoredLiteral ? 'Leave blank to keep existing encrypted value' : ''}
          type={showPlain ? 'text' : 'password'}
          value={draft.plainDraft}
          onChange={(e) => onPlain(e.target.value)}
          helperText={
            draft.hasStoredLiteral
              ? 'A value is already stored encrypted on the server. Enter text only to replace it.'
              : 'Saved with Crafter encryptionService as ${enc:…}; plaintext is never returned to the browser.'
          }
          fullWidth
          InputProps={{
            endAdornment: (
              <InputAdornment position="end">
                <IconButton size="small" aria-label={showPlain ? 'Hide value' : 'Show value'} onClick={onToggleShow}>
                  {showPlain ? <VisibilityOffRounded fontSize="small" /> : <VisibilityRounded fontSize="small" />}
                </IconButton>
              </InputAdornment>
            )
          }}
        />
      ) : null}
    </Stack>
  );
}

function SecretsTable(props: {
  rows: AiAssistantSecretRowDraft[];
  showPlainByKey: Record<string, boolean>;
  onToggleShow: (key: string) => void;
  onPatch: (key: string, patch: Partial<AiAssistantSecretRowDraft>) => void;
  allowRemove: boolean;
}) {
  const { rows, showPlainByKey, onToggleShow, onPatch, allowRemove } = props;
  const visible = rows.filter((r) => !r.remove);
  if (visible.length === 0) {
    return (
      <Typography variant="body2" color="text.secondary">
        None
      </Typography>
    );
  }
  return (
    <Table size="small" sx={{ '& th': { fontWeight: 600 } }}>
      <TableHead>
        <TableRow>
          <TableCell sx={{ width: '24%' }}>Name</TableCell>
          <TableCell>Value</TableCell>
          {allowRemove ? <TableCell sx={{ width: 56 }} /> : null}
        </TableRow>
      </TableHead>
      <TableBody>
        {visible.map((row) => (
          <TableRow key={row.key} hover>
            <TableCell valign="top">
              <Stack spacing={0.5}>
                <Typography variant="body2" fontWeight={600}>
                  {row.label}
                </Typography>
                <Typography variant="caption" color="text.secondary" component="code">
                  {row.key}
                </Typography>
              </Stack>
            </TableCell>
            <TableCell valign="top">
              <SecretValueField
                draft={row}
                showPlain={Boolean(showPlainByKey[row.key])}
                onToggleShow={() => onToggleShow(row.key)}
                onEditMode={(mode) => onPatch(row.key, { editMode: mode, plainDraft: '', expressionDraft: '' })}
                onEnvVar={(v) => onPatch(row.key, { envVar: v })}
                onExpression={(v) => onPatch(row.key, { expressionDraft: v })}
                onPlain={(v) => onPatch(row.key, { plainDraft: v })}
              />
            </TableCell>
            {allowRemove ? (
              <TableCell valign="top">
                <Tooltip title="Remove custom secret">
                  <IconButton
                    size="small"
                    aria-label={`Remove ${row.key}`}
                    onClick={() => onPatch(row.key, { remove: true })}
                  >
                    <DeleteOutlineRounded fontSize="small" />
                  </IconButton>
                </Tooltip>
              </TableCell>
            ) : null}
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

export default function AiAssistantSecretsConfiguration() {
  const activeSite = useActiveSiteId();
  const siteId = useMemo(() => effectiveStudioSiteId(activeSite), [activeSite]);
  const [providerRows, setProviderRows] = useState<AiAssistantSecretRowDraft[]>([]);
  const [customRows, setCustomRows] = useState<AiAssistantSecretRowDraft[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [catalogWarning, setCatalogWarning] = useState<string | null>(null);
  const [secretsSeededNotice, setSecretsSeededNotice] = useState<string | null>(null);
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [showPlainByKey, setShowPlainByKey] = useState<Record<string, boolean>>({});
  const [newCustomKey, setNewCustomKey] = useState('');

  const allRows = useMemo(() => [...providerRows, ...customRows], [providerRows, customRows]);

  const reload = useCallback(async () => {
    if (!siteId) return;
    setLoadError(null);
    setCatalogWarning(null);
    setSecretsSeededNotice(null);
    setLoaded(false);
    try {
      const idx = await fetchAiAssistantSecretsIndex(siteId);
      if (idx.ok === false) {
        setLoadError(idx.message ?? 'Failed to load secrets');
        setProviderRows([]);
        setCustomRows([]);
        setLoaded(true);
        return;
      }
      setCatalogWarning(idx.catalogWarning?.trim() || null);
      setSecretsSeededNotice(
        idx.secretsSeeded
          ? `Created ${SECRETS_JSON_REL} with default \${env:…} entries for each built-in LLM provider. Set the matching variables on the Studio host or edit a row below.`
          : null
      );
      const providers = (idx.knownSecrets ?? []).map((row) => rowDraftFromAdmin(row, true));
      setProviderRows(providers);
      setCustomRows((idx.customSecrets ?? []).map((row) => rowDraftFromAdmin(row, false)));
      setDirty(false);
      setShowPlainByKey({});
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : 'Failed to load secrets');
      setProviderRows([]);
      setCustomRows([]);
    } finally {
      setLoaded(true);
    }
  }, [siteId]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const patchRow = useCallback((key: string, patch: Partial<AiAssistantSecretRowDraft>) => {
    const apply = (list: AiAssistantSecretRowDraft[]) =>
      list.map((r) => (r.key === key ? { ...r, ...patch, notPersisted: false } : r));
    setProviderRows((prev) => apply(prev));
    setCustomRows((prev) => apply(prev));
    setDirty(true);
    setSaveError(null);
  }, []);

  const addCustom = useCallback(() => {
    const key = newCustomKey.trim().toLowerCase();
    if (!isValidCustomKey(key)) {
      setSaveError('Custom key must start with a letter and use lowercase letters, digits, or underscores only.');
      return;
    }
    if (allRows.some((r) => r.key === key)) {
      setSaveError(`Secret key "${key}" already exists.`);
      return;
    }
    setCustomRows((prev) => [
      ...prev,
      {
        key,
        label: key,
        known: false,
        notPersisted: true,
        editMode: 'env',
        envVar: '',
        expressionDraft: '',
        plainDraft: '',
        hasStoredLiteral: false,
        remove: false
      }
    ]);
    setNewCustomKey('');
    setDirty(true);
    setSaveError(null);
  }, [allRows, newCustomKey]);

  const save = useCallback(async () => {
    if (!siteId) return;
    setSaving(true);
    setSaveError(null);
    try {
      const entries = buildSecretSaveEntries(allRows.filter((r) => !r.remove));
      const res = await saveAiAssistantSecretsEntries(siteId, entries);
      if (res.ok === false) {
        setSaveError(res.message ?? 'Save failed');
        return;
      }
      await reload();
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  }, [allRows, reload, siteId]);

  return (
    <Box sx={{ p: 2, maxWidth: 1200 }}>
      <Stack spacing={2.5}>
        <Box>
          <Typography variant="h6" gutterBottom>
            Secrets
          </Typography>
          <Typography variant="body2" color="text.secondary" paragraph>
            Configure credentials the AI Assistant resolves on the Studio server for this site. Settings are stored in{' '}
            <code>{SECRETS_JSON_REL}</code> separately from tool and MCP policy so secrets can be managed and audited on
            their own.
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 0 }}>
            After you save, resolved values are never sent back to the browser. To update an encrypted entry, enter a
            new value; previously saved plaintext is not shown again.
          </Typography>
        </Box>

        <Alert severity="info" sx={{ '& .MuiAlert-message': { width: '100%' } }}>
          <Typography variant="subtitle2" gutterBottom>
            Value formats
          </Typography>
          <Typography variant="body2" component="div" sx={{ '& ul': { m: 0, pl: 2.25 } }}>
            <ul>
              <li>
                <strong>Environment variable</strong> — <code>{'${env:VAR_NAME}'}</code>. Studio reads the variable from
                the JVM environment at runtime.
              </li>
              <li>
                <strong>Encrypted (Crafter)</strong> — <code>{'${enc:…}'}</code> ciphertext from Studio{' '}
                <em>Encrypt Marked</em>, or paste an existing ciphertext when editing.
              </li>
              <li>
                <strong>Plain text (encrypt on save)</strong> — Values you enter are encrypted before storage and saved
                as <code>{'${enc:…}'}</code>.
              </li>
              <li>
                <strong>Secret reference</strong> — <code>{'${secret:key}'}</code> in MCP headers or other config (for
                example <code>{'${secret:openai_api_key}'}</code>).
              </li>
            </ul>
          </Typography>
        </Alert>

        {loadError ? <Alert severity="error">{loadError}</Alert> : null}
        {secretsSeededNotice ? <Alert severity="success">{secretsSeededNotice}</Alert> : null}
        {catalogWarning ? <Alert severity="warning">{catalogWarning}</Alert> : null}
        {saveError ? <Alert severity="warning">{saveError}</Alert> : null}

        {dirty ? (
          <Alert severity="warning" variant="outlined">
            LLM provider rows below show recommended environment variables. Click <strong>Save secrets</strong> to write
            them to <code>{SECRETS_JSON_REL}</code> for this site.
          </Alert>
        ) : null}

        {!loaded ? (
          <Typography variant="body2" color="text.secondary">
            Loading secrets…
          </Typography>
        ) : (
          <>
            <Box>
              <Typography variant="subtitle1" gutterBottom>
                LLM provider credentials
              </Typography>
              <Typography variant="body2" color="text.secondary" paragraph>
                Each supported provider has a fixed secret key and defaults to the environment variable shown. Set the
                variable on the Studio host, or switch the row to an encrypted value.
              </Typography>
              <SecretsTable
                rows={providerRows}
                showPlainByKey={showPlainByKey}
                onToggleShow={(key) => setShowPlainByKey((m) => ({ ...m, [key]: !m[key] }))}
                onPatch={patchRow}
                allowRemove={false}
              />
            </Box>

            <Divider />

            <Box>
              <Typography variant="subtitle1" gutterBottom>
                Custom secrets
              </Typography>
              <Typography variant="body2" color="text.secondary" paragraph>
                Additional keys for MCP headers, webhooks, or other integrations. Reference them with{' '}
                <code>{'${secret:your_key}'}</code>.
              </Typography>
              <SecretsTable
                rows={customRows}
                showPlainByKey={showPlainByKey}
                onToggleShow={(key) => setShowPlainByKey((m) => ({ ...m, [key]: !m[key] }))}
                onPatch={patchRow}
                allowRemove
              />
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} alignItems={{ sm: 'flex-end' }} sx={{ mt: 2 }}>
                <TextField
                  size="small"
                  label="Custom secret key"
                  placeholder="my_api_token"
                  value={newCustomKey}
                  onChange={(e) => setNewCustomKey(e.target.value)}
                  sx={{ minWidth: 220 }}
                />
                <Button variant="outlined" startIcon={<AddRounded />} onClick={addCustom} disabled={!newCustomKey.trim()}>
                  Add secret
                </Button>
              </Stack>
            </Box>
          </>
        )}

        <Stack direction="row" spacing={1}>
          <Button variant="contained" startIcon={<SaveRounded />} disabled={!dirty || saving} onClick={() => void save()}>
            {saving ? 'Saving…' : 'Save secrets'}
          </Button>
          <Button variant="text" disabled={saving || !dirty} onClick={() => void reload()}>
            Reload
          </Button>
        </Stack>
      </Stack>
    </Box>
  );
}
