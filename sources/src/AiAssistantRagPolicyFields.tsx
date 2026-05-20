import {
  FormControl,
  InputLabel,
  MenuItem,
  Paper,
  Select,
  Slider,
  Stack,
  TextField,
  Typography
} from '@mui/material';
import type { AgentSkillsRagFormState, PluginRagFormState, PluginRagMode } from './aiAssistantRagUiModel';

export interface AiAssistantRagPolicyFieldsProps {
  pluginRag: PluginRagFormState;
  agentSkillsRag: AgentSkillsRagFormState;
  onPluginRagChange: (next: PluginRagFormState) => void;
  onAgentSkillsRagChange: (next: AgentSkillsRagFormState) => void;
}

function IntSlider(props: {
  label: string;
  value: number;
  min: number;
  max: number;
  disabled?: boolean;
  helperText?: string;
  onChange: (n: number) => void;
}) {
  const { label, value, min, max, disabled, helperText, onChange } = props;
  return (
    <Stack spacing={0.5}>
      <Typography variant="body2">
        {label}: <strong>{value}</strong>
      </Typography>
      <Slider
        size="small"
        value={value}
        min={min}
        max={max}
        step={1}
        disabled={disabled}
        valueLabelDisplay="auto"
        onChange={(_, v) => onChange(Array.isArray(v) ? v[0] : v)}
      />
      {helperText ? (
        <Typography variant="caption" color="text.secondary">
          {helperText}
        </Typography>
      ) : null}
    </Stack>
  );
}

export default function AiAssistantRagPolicyFields(props: AiAssistantRagPolicyFieldsProps) {
  const { pluginRag, agentSkillsRag, onPluginRagChange, onAgentSkillsRagChange } = props;
  const ragActive = pluginRag.mode !== 'off';

  const patchPlugin = (partial: Partial<PluginRagFormState>) => onPluginRagChange({ ...pluginRag, ...partial });
  const patchSkills = (partial: Partial<AgentSkillsRagFormState>) =>
    onAgentSkillsRagChange({ ...agentSkillsRag, ...partial });

  return (
    <Stack spacing={2}>
      <Paper variant="outlined" sx={{ p: 2.5 }}>
        <Typography variant="subtitle2" gutterBottom>
          Plugin RAG (bundled instructions)
        </Typography>
        <Typography variant="body2" color="text.secondary" paragraph>
          Optional retrieval over the plugin instruction corpus before the tools loop. Default is off (full authoring
          instructions only).
        </Typography>
        <Stack spacing={2}>
          <FormControl fullWidth size="small">
            <InputLabel id="aiassistant-plugin-rag-mode">Mode</InputLabel>
            <Select
              labelId="aiassistant-plugin-rag-mode"
              label="Mode"
              value={pluginRag.mode}
              onChange={(ev) => patchPlugin({ mode: ev.target.value as PluginRagMode })}
            >
              <MenuItem value="off">Off</MenuItem>
              <MenuItem value="supplement">Supplement (full instructions + retrieved appendix)</MenuItem>
              <MenuItem value="replace">Replace (compact kernel + retrieved appendix)</MenuItem>
            </Select>
          </FormControl>
          <IntSlider
            label="Kernel max characters (replace mode)"
            value={pluginRag.kernelMaxChars}
            min={1024}
            max={16000}
            disabled={pluginRag.mode !== 'replace'}
            helperText="Leading slice of authoring instructions when mode is Replace."
            onChange={(kernelMaxChars) => patchPlugin({ kernelMaxChars })}
          />
          <IntSlider
            label="Retrieval top K"
            value={pluginRag.topK}
            min={1}
            max={24}
            disabled={!ragActive}
            onChange={(topK) => patchPlugin({ topK })}
          />
          <IntSlider
            label="Max appendix characters"
            value={pluginRag.maxAppendChars}
            min={2000}
            max={80000}
            disabled={!ragActive}
            onChange={(maxAppendChars) => patchPlugin({ maxAppendChars })}
          />
          <IntSlider
            label="Index max chunk characters"
            value={pluginRag.maxChunkChars}
            min={512}
            max={8000}
            disabled={!ragActive}
            onChange={(maxChunkChars) => patchPlugin({ maxChunkChars })}
          />
          <IntSlider
            label="Index max chunks"
            value={pluginRag.maxChunks}
            min={8}
            max={2000}
            disabled={!ragActive}
            onChange={(maxChunks) => patchPlugin({ maxChunks })}
          />
          <IntSlider
            label="Embedding batch size"
            value={pluginRag.embedBatchSize}
            min={8}
            max={128}
            disabled={!ragActive}
            onChange={(embedBatchSize) => patchPlugin({ embedBatchSize })}
          />
        </Stack>
      </Paper>

      <Paper variant="outlined" sx={{ p: 2.5 }}>
        <Typography variant="subtitle2" gutterBottom>
          Agent skills RAG
        </Typography>
        <Typography variant="body2" color="text.secondary" paragraph>
          Limits for per-agent markdown URL skills (**QueryExpertGuidance**). Skills are configured per agent; only
          enabled skills are indexed.
        </Typography>
        <Stack spacing={2}>
          <TextField
            label="Embedding model"
            value={agentSkillsRag.embeddingModel}
            onChange={(ev) => patchSkills({ embeddingModel: ev.target.value })}
            fullWidth
            size="small"
            helperText="Embedding model id for skill indexing (default text-embedding-3-small)."
          />
          <IntSlider
            label="Max enabled skills per request"
            value={agentSkillsRag.maxSkills}
            min={1}
            max={32}
            onChange={(maxSkills) => patchSkills({ maxSkills })}
          />
          <IntSlider
            label="Max chunks per skill"
            value={agentSkillsRag.maxChunks}
            min={8}
            max={2000}
            onChange={(maxChunks) => patchSkills({ maxChunks })}
          />
          <IntSlider
            label="Max characters per chunk"
            value={agentSkillsRag.maxChunkChars}
            min={512}
            max={8000}
            onChange={(maxChunkChars) => patchSkills({ maxChunkChars })}
          />
        </Stack>
      </Paper>
    </Stack>
  );
}
