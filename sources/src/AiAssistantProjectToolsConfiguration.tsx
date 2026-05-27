import { useCallback, useEffect, useMemo, useRef, useState, type SyntheticEvent } from 'react';
import CloseRounded from '@mui/icons-material/CloseRounded';
import FullscreenExitRounded from '@mui/icons-material/FullscreenExitRounded';
import FullscreenRounded from '@mui/icons-material/FullscreenRounded';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import IconButton from '@mui/material/IconButton';
import Stack from '@mui/material/Stack';
import Tab from '@mui/material/Tab';
import Tabs from '@mui/material/Tabs';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import AiAssistantCentralAgentsConfiguration, {
  type AiAssistantCentralAgentsCatalogHandle
} from './AiAssistantCentralAgentsConfiguration';
import AiAssistantIntentRecipesConfiguration, {
  type AiAssistantIntentRecipesConfigurationHandle
} from './AiAssistantIntentRecipesConfiguration';
import AiAssistantScriptsSandboxConfiguration from './AiAssistantScriptsSandboxConfiguration';
import AiAssistantSecretsConfiguration from './AiAssistantSecretsConfiguration';
import AiAssistantStudioUiSettings from './AiAssistantStudioUiSettings';
import { aiAssistantProjectToolsPanelContentSx } from './aiAssistantProjectToolsFormSx';
import useActiveSiteId from '@craftercms/studio-ui/hooks/useActiveSiteId';
import { useDomFullscreen } from './aiAssistantDomFullscreen';
import {
  AiAssistantJoyrideTourPopover,
  AiAssistantJoyrideWelcomeDialog,
  useAiAssistantConfigurationJoyride
} from './AiAssistantJoyride';
import { AI_ASSISTANT_JOYRIDE_STEPS } from './aiAssistantJoyrideSteps';
import { effectiveStudioSiteId } from './aiAssistantStudioUiConfig';

/** Sub-tabs inside Project Tools → Integrations. */
export type AiAssistantIntegrationsSubTab = 'llms' | 'imagegen' | 'tools' | 'mcp';

export type AiAssistantProjectToolsTab =
  | 'ui'
  | 'agents'
  | 'recipes'
  | 'integrations'
  | 'secrets'
  | 'prompts'
  /** @deprecated Opens Integrations → Tools (legacy widget id). */
  | 'scripts'
  /** @deprecated Opens Integrations with the matching sub-tab. */
  | AiAssistantIntegrationsSubTab;

function isIntegrationsSubTab(t: AiAssistantProjectToolsTab): t is AiAssistantIntegrationsSubTab {
  return t === 'llms' || t === 'imagegen' || t === 'tools' || t === 'mcp' || t === 'scripts';
}

function resolveProjectToolsTabs(defaultTab: AiAssistantProjectToolsTab): {
  tab: 'ui' | 'agents' | 'recipes' | 'integrations' | 'secrets' | 'prompts';
  integrationsSub: AiAssistantIntegrationsSubTab;
} {
  if (defaultTab === 'scripts') {
    return { tab: 'integrations', integrationsSub: 'tools' };
  }
  if (isIntegrationsSubTab(defaultTab)) {
    return { tab: 'integrations', integrationsSub: defaultTab };
  }
  if (defaultTab === 'integrations') {
    return { tab: 'integrations', integrationsSub: 'tools' };
  }
  return { tab: defaultTab, integrationsSub: 'tools' };
}

function projectToolsTabLabel(t: AiAssistantProjectToolsTab): string {
  switch (t) {
    case 'ui':
      return 'UI';
    case 'agents':
      return 'Agents';
    case 'recipes':
      return 'Recipes';
    case 'integrations':
      return 'Integrations';
    case 'secrets':
      return 'Secrets';
    case 'prompts':
      return 'Context and Prompts';
    case 'llms':
      return 'LLMs';
    case 'imagegen':
      return 'Image Generators';
    case 'tools':
      return 'Tools';
    case 'mcp':
      return 'MCP';
    default:
      return t;
  }
}

function integrationsSandboxPanel(sub: AiAssistantIntegrationsSubTab): AiAssistantIntegrationsSubTab {
  return sub;
}

export interface AiAssistantProjectToolsConfigurationProps {
  /** Initial tab; used for legacy Project Tools widget ids that map to this shell. */
  defaultTab?: AiAssistantProjectToolsTab;
}

/**
 * Tabbed configuration body (tabs + panels + unsaved guard). Used inside {@link AiAssistantProjectToolsConfiguration}.
 */
function AiAssistantProjectToolsConfigurationPanel(props: AiAssistantProjectToolsConfigurationProps) {
  const { defaultTab = 'ui' } = props;
  const initialTabs = useMemo(() => resolveProjectToolsTabs(defaultTab), [defaultTab]);
  const [tab, setTab] = useState(initialTabs.tab);
  const [integrationsSub, setIntegrationsSub] = useState<AiAssistantIntegrationsSubTab>(initialTabs.integrationsSub);
  const [agentsCatalogDirty, setAgentsCatalogDirty] = useState(false);
  const [recipesDirty, setRecipesDirty] = useState(false);
  const [recipesEditMode, setRecipesEditMode] = useState(false);
  const [pendingTabSwitch, setPendingTabSwitch] = useState<{
    from: typeof tab;
    to: typeof tab;
  } | null>(null);
  const [tabLeaveSaveBusy, setTabLeaveSaveBusy] = useState(false);
  const agentsCatalogRef = useRef<AiAssistantCentralAgentsCatalogHandle>(null);
  const recipesConfigRef = useRef<AiAssistantIntentRecipesConfigurationHandle>(null);
  const { ref: rootRef, isFullscreen: toolFullscreen, toggleFullscreen: toggleToolFullscreen } =
    useDomFullscreen<HTMLDivElement>();
  const activeSite = useActiveSiteId();
  const studioSiteId = useMemo(() => effectiveStudioSiteId(activeSite), [activeSite]);

  const joyrideNavigateTab = useCallback((value: typeof tab) => {
    setPendingTabSwitch(null);
    setTab(value);
  }, []);

  const {
    phase: joyridePhase,
    activeStep: joyrideActiveStep,
    activeStepIndex: joyrideActiveStepIndex,
    onPanelReady: joyrideOnPanelReady,
    startTour: joyrideStartTour,
    replayJoyride: joyrideReplay,
    dismissJoyride: joyrideDismiss,
    goNext: joyrideGoNext
  } = useAiAssistantConfigurationJoyride(joyrideNavigateTab, studioSiteId);
  const joyrideTourActive = joyridePhase === 'tour';
  const joyrideBusy = joyridePhase === 'welcome' || joyrideTourActive;

  useEffect(() => {
    joyrideOnPanelReady();
  }, [joyrideOnPanelReady]);

  useEffect(() => {
    if (tab !== 'recipes') {
      setRecipesEditMode(false);
    }
  }, [tab]);

  const hideProjectToolsTopTabs = tab === 'recipes' && recipesEditMode;

  const handleTabsChange = useCallback(
    (_: SyntheticEvent, value: typeof tab) => {
      if (joyrideTourActive) {
        return;
      }
      if (tab === 'agents' && agentsCatalogDirty && value !== 'agents') {
        setPendingTabSwitch({ from: 'agents', to: value });
        return;
      }
      if (tab === 'recipes' && recipesDirty && value !== 'recipes') {
        setPendingTabSwitch({ from: 'recipes', to: value });
        return;
      }
      setTab(value);
    },
    [tab, agentsCatalogDirty, recipesDirty, joyrideTourActive]
  );

  const handleIntegrationsSubChange = useCallback((_: SyntheticEvent, value: AiAssistantIntegrationsSubTab) => {
    setIntegrationsSub(value);
  }, []);

  const cancelPendingTabSwitch = useCallback(() => {
    setPendingTabSwitch(null);
    setTabLeaveSaveBusy(false);
  }, []);

  const discardPendingTabSwitch = useCallback(async () => {
    if (pendingTabSwitch == null) return;
    const next = pendingTabSwitch.to;
    setTabLeaveSaveBusy(true);
    try {
      if (pendingTabSwitch.from === 'agents') {
        setAgentsCatalogDirty(false);
      }
      if (pendingTabSwitch.from === 'recipes') {
        await recipesConfigRef.current?.discard();
        setRecipesDirty(false);
      }
      setPendingTabSwitch(null);
      setTab(next);
    } finally {
      setTabLeaveSaveBusy(false);
    }
  }, [pendingTabSwitch]);

  const saveAndPendingTabSwitch = useCallback(async () => {
    if (pendingTabSwitch == null) return;
    const { from, to: next } = pendingTabSwitch;
    setTabLeaveSaveBusy(true);
    try {
      const ok =
        from === 'agents'
          ? (await agentsCatalogRef.current?.save()) === true
          : from === 'recipes'
            ? (await recipesConfigRef.current?.save()) === true
            : true;
      if (ok) {
        setPendingTabSwitch(null);
        setTab(next);
      }
    } finally {
      setTabLeaveSaveBusy(false);
    }
  }, [pendingTabSwitch]);

  return (
    <Box
      ref={rootRef}
      sx={{
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        minHeight: 0,
        alignSelf: 'stretch',
        ...(toolFullscreen ? { bgcolor: 'background.default' } : {})
      }}
    >
      <Stack
        direction="row"
        alignItems="stretch"
        sx={{ flexShrink: 0, borderBottom: 1, borderColor: 'divider' }}
      >
        <Tabs
          value={tab}
          onChange={handleTabsChange}
          variant="scrollable"
          scrollButtons="auto"
          allowScrollButtonsMobile
          sx={{ flex: '1 1 auto', minWidth: 0 }}
        >
          <Tab label="UI" value="ui" data-aiassistant-project-tools-tab="ui" />
          <Tab label="Agents" value="agents" data-aiassistant-project-tools-tab="agents" />
          <Tab label="Recipes" value="recipes" data-aiassistant-project-tools-tab="recipes" />
          <Tab label="Integrations" value="integrations" data-aiassistant-project-tools-tab="integrations" />
          <Tab label="Secrets" value="secrets" data-aiassistant-project-tools-tab="secrets" />
          <Tab label="Context and Prompts" value="prompts" data-aiassistant-project-tools-tab="prompts" />
        </Tabs>
        <Box sx={{ display: 'flex', alignItems: 'center', flexShrink: 0, borderLeft: 1, borderColor: 'divider', px: 0.5 }}>
          <Tooltip title={toolFullscreen ? 'Exit fullscreen' : 'Fullscreen'}>
            <IconButton
              size="small"
              aria-label={toolFullscreen ? 'Exit fullscreen' : 'Enter fullscreen'}
              onClick={() => toggleToolFullscreen()}
            >
              {toolFullscreen ? <FullscreenExitRounded /> : <FullscreenRounded />}
            </IconButton>
          </Tooltip>
        </Box>
      </Stack>
      <Box
        sx={{
          flex: '1 1 auto',
          minHeight: 0,
          overflow: 'auto',
          ...aiAssistantProjectToolsPanelContentSx
        }}
      >
        {tab === 'ui' ? (
          <AiAssistantStudioUiSettings
            onReplayConfigurationJoyride={joyrideReplay}
            configurationJoyrideActive={joyrideBusy}
          />
        ) : null}
        {tab === 'agents' ? (
          <AiAssistantCentralAgentsConfiguration
            ref={agentsCatalogRef}
            onDirtyChange={setAgentsCatalogDirty}
          />
        ) : null}
        {tab === 'recipes' ? (
          <AiAssistantIntentRecipesConfiguration
            ref={recipesConfigRef}
            onDirtyChange={setRecipesDirty}
            onRecipeEditModeChange={setRecipesEditMode}
          />
        ) : null}
        {tab === 'integrations' ? (
          <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100%' }}>
            <Tabs
              value={integrationsSub}
              onChange={handleIntegrationsSubChange}
              variant="scrollable"
              scrollButtons="auto"
              allowScrollButtonsMobile
              sx={{ flexShrink: 0, borderBottom: 1, borderColor: 'divider', px: 1 }}
            >
              <Tab label="LLMs" value="llms" />
              <Tab label="Image Generators" value="imagegen" />
              <Tab label="Tools" value="tools" />
              <Tab label="MCP" value="mcp" />
            </Tabs>
            <Box sx={{ flex: '1 1 auto', minHeight: 0, overflow: 'auto' }}>
              <AiAssistantScriptsSandboxConfiguration
                key={integrationsSub === 'tools' || integrationsSub === 'mcp' ? 'tools-policy' : integrationsSub}
                panel={integrationsSandboxPanel(integrationsSub)}
              />
            </Box>
          </Box>
        ) : null}
        {tab === 'secrets' ? <AiAssistantSecretsConfiguration /> : null}
        {tab === 'prompts' ? <AiAssistantScriptsSandboxConfiguration panel="prompts" /> : null}
      </Box>

      {joyridePhase === 'welcome' ? (
        <AiAssistantJoyrideWelcomeDialog onShowAround={joyrideStartTour} onCancel={joyrideDismiss} />
      ) : null}
      <AiAssistantJoyrideTourPopover
        open={joyrideTourActive}
        step={joyrideActiveStep}
        activeTab={tab}
        anchorScopeRef={rootRef}
        stepIndex={joyrideActiveStepIndex}
        stepCount={AI_ASSISTANT_JOYRIDE_STEPS.length}
        onNext={joyrideGoNext}
        onSkip={joyrideDismiss}
      />

      <Dialog open={pendingTabSwitch != null} onClose={cancelPendingTabSwitch} maxWidth="sm" fullWidth>
        <DialogTitle>Unsaved changes</DialogTitle>
        <DialogContent>
          <Typography variant="body2" paragraph>
            Save, discard, or stay on{' '}
            <strong>{pendingTabSwitch ? projectToolsTabLabel(pendingTabSwitch.from) : ''}</strong> before opening{' '}
            <strong>{pendingTabSwitch ? projectToolsTabLabel(pendingTabSwitch.to) : ''}</strong>.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={cancelPendingTabSwitch} disabled={tabLeaveSaveBusy}>
            Stay on {pendingTabSwitch ? projectToolsTabLabel(pendingTabSwitch.from) : 'this tab'}
          </Button>
          <Button color="warning" onClick={() => void discardPendingTabSwitch()} disabled={tabLeaveSaveBusy}>
            Discard changes
          </Button>
          <Button variant="contained" onClick={() => void saveAndPendingTabSwitch()} disabled={tabLeaveSaveBusy}>
            {tabLeaveSaveBusy ? 'Saving…' : 'Save and continue'}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

/**
 * Single Project Tools surface: **UI** (`studio-ui.json` + bulk), **Agents** (`agents.json`), **Recipes** (intent router + site overrides),
 * **Integrations** (sub-tabs: **LLMs**, **Image Generators**, **Tools**, **MCP**), **Secrets** (site API keys), **Context and Prompts** (project context markdown + tool prompt overrides).
 * Opens in a **large dialog** when the Project Tools entry mounts so authors stay focused and get more space than the default tool pane.
 * Primary widget id: {@link projectToolsAiAssistantConfigWidgetId}. Legacy ids still mount this component with a fixed default tab.
 */
export default function AiAssistantProjectToolsConfiguration(props: AiAssistantProjectToolsConfigurationProps) {
  const [shellOpen, setShellOpen] = useState(true);

  return (
    <>
      <Dialog
        open={shellOpen}
        onClose={() => setShellOpen(false)}
        maxWidth={false}
        fullWidth
        scroll="paper"
        PaperProps={{
          sx: {
            width: { xs: '100%', sm: 'min(96vw, 1680px)' },
            height: { xs: '100%', sm: 'calc(100vh - 32px)' },
            maxHeight: { xs: '100%', sm: 'calc(100vh - 16px)' },
            m: { xs: 0, sm: 2 },
            display: 'flex',
            flexDirection: 'column'
          }
        }}
      >
        <DialogTitle
          sx={{
            flexShrink: 0,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 1,
            pr: 1,
            py: 1.5
          }}
        >
          <Typography component="div" variant="h6">
            AI Assistant Configuration
          </Typography>
          <Tooltip title="Close">
            <IconButton aria-label="Close" size="small" onClick={() => setShellOpen(false)}>
              <CloseRounded fontSize="small" />
            </IconButton>
          </Tooltip>
        </DialogTitle>
        <DialogContent
          sx={{
            flex: '1 1 auto',
            minHeight: 0,
            p: 0,
            display: 'flex',
            flexDirection: 'column',
            overflow: 'hidden'
          }}
        >
          <AiAssistantProjectToolsConfigurationPanel {...props} />
        </DialogContent>
      </Dialog>

      {!shellOpen ? (
        <Box sx={{ p: 2, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 1.5 }}>
          <Typography variant="body2" color="text.secondary">
            AI Assistant configuration is closed.
          </Typography>
          <Button variant="contained" onClick={() => setShellOpen(true)}>
            Open AI Assistant configuration
          </Button>
        </Box>
      ) : null}
    </>
  );
}

/** Legacy widget id `craftercms.components.aiassistant.CentralAgentsConfiguration` — opens Agents tab. */
export function AiAssistantProjectToolsConfigurationAgentsTab() {
  return <AiAssistantProjectToolsConfiguration defaultTab="agents" />;
}

/**
 * Legacy widget id `craftercms.components.aiassistant.ScriptsSandboxConfiguration` — opens **Integrations → Tools**
 * (`tools.json` built-in + registry + user Groovy).
 */
export function AiAssistantProjectToolsConfigurationScriptsTab() {
  return <AiAssistantProjectToolsConfiguration defaultTab="tools" />;
}

/** Legacy widget id `craftercms.components.aiassistant.StudioUiSettings` — opens UI tab. */
export function AiAssistantProjectToolsConfigurationUiTab() {
  return <AiAssistantProjectToolsConfiguration defaultTab="ui" />;
}
