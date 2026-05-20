import { useEffect, useMemo, useState } from 'react';
import {
  Button,
  Paper,
  Stack,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography
} from '@mui/material';
import type { ToolsPolicyFormState } from './aiAssistantToolsMcpUiModel';
import {
  builtInToolHasProjectSettings,
  builtInToolSettingsDescriptorForWire,
  getBuiltInToolSettingsState,
  patchBuiltInToolSettings
} from './builtInToolSettings/registry';
import { BUILTIN_ORCHESTRATION_TOOL_WIRES, isBuiltInToolEnabled, setBuiltInToolEnabled } from './aiAssistantToolsPolicyUi';

export interface AiAssistantSiteOrchestrationToolsFormProps {
  value: ToolsPolicyFormState;
  onChange: (next: ToolsPolicyFormState) => void;
}

/**
 * Site-wide {@code tools.json} policy: built-in tool enable/disable and optional per-tool settings.
 */
export default function AiAssistantSiteOrchestrationToolsForm(props: AiAssistantSiteOrchestrationToolsFormProps) {
  const { value, onChange } = props;
  const whitelistMode = value.enabledBuiltInTools.length > 0;
  const builtInWires = useMemo(() => [...BUILTIN_ORCHESTRATION_TOOL_WIRES], []);
  const [configureWire, setConfigureWire] = useState<string | null>(null);
  const [configureDraft, setConfigureDraft] = useState<unknown>(undefined);

  const activeDescriptor = configureWire ? builtInToolSettingsDescriptorForWire(configureWire) : undefined;

  useEffect(() => {
    if (!activeDescriptor) {
      setConfigureDraft(undefined);
      return;
    }
    setConfigureDraft(getBuiltInToolSettingsState(value, activeDescriptor));
  }, [activeDescriptor, configureWire, value]);

  return (
    <Stack spacing={3}>
      <Typography variant="body2" color="text.secondary">
        Intent recipe routing and the recipe catalog are configured under the <strong>Recipes</strong> tab.
      </Typography>

      <Paper variant="outlined" sx={{ p: 2.5 }}>
        <Typography variant="subtitle2" gutterBottom>
          Built-in tools
        </Typography>
        <Typography variant="body2" color="text.secondary" paragraph>
          {whitelistMode ? (
            <>
              <strong>Whitelist mode:</strong> only enabled tools register (plus <code>InvokeSiteUserTool</code> and{' '}
              <code>mcp_*</code> when configured).
            </>
          ) : (
            <>
              Disabled tools are listed in <code>disabledBuiltInTools</code> in <code>tools.json</code>.{' '}
              <code>InvokeSiteUserTool</code> and dynamic <code>mcp_*</code> tools follow MCP settings unless also
              disabled here.
            </>
          )}
        </Typography>
        <Table size="small" sx={{ border: 1, borderColor: 'divider', borderRadius: 1 }}>
          <TableHead>
            <TableRow>
              <TableCell>Tool</TableCell>
              <TableCell width={100} align="center">
                Enabled
              </TableCell>
              <TableCell width={120} align="center">
                Settings
              </TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {builtInWires.map((wire) => (
              <TableRow key={wire}>
                <TableCell>
                  <Typography variant="body2" component="code">
                    {wire}
                  </Typography>
                </TableCell>
                <TableCell align="center">
                  <Switch
                    size="small"
                    checked={isBuiltInToolEnabled(value, wire)}
                    onChange={(_, checked) => onChange(setBuiltInToolEnabled(value, wire, checked))}
                    inputProps={{ 'aria-label': `Enable ${wire}` }}
                  />
                </TableCell>
                <TableCell align="center">
                  {builtInToolHasProjectSettings(wire) ? (
                    <Button size="small" variant="outlined" onClick={() => setConfigureWire(wire)}>
                      Configure
                    </Button>
                  ) : (
                    <Typography variant="caption" color="text.secondary">
                      —
                    </Typography>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Paper>

      {activeDescriptor && configureDraft !== undefined ? (
        <activeDescriptor.ConfigureDialog
          open={Boolean(configureWire)}
          draft={configureDraft}
          onDraftChange={setConfigureDraft}
          onClose={() => setConfigureWire(null)}
          onApply={(draft) => {
            onChange(patchBuiltInToolSettings(value, activeDescriptor, draft));
            setConfigureWire(null);
          }}
        />
      ) : null}
    </Stack>
  );
}
