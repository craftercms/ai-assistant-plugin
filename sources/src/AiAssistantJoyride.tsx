import { useCallback, useEffect, useLayoutEffect, useState, type RefObject } from 'react';
import SmartToyOutlined from '@mui/icons-material/SmartToyOutlined';
import {
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  Paper,
  Popover,
  Stack,
  Typography
} from '@mui/material';
import {
  AI_ASSISTANT_JOYRIDE_STEPS,
  type AiAssistantJoyrideStep,
  type AiAssistantJoyrideTab
} from './aiAssistantJoyrideSteps';
import {
  markConfigurationJoyrideSeen,
  shouldShowConfigurationJoyride
} from './aiAssistantJoyrideStorage';

export type AiAssistantJoyridePhase = 'idle' | 'welcome' | 'tour';

export function projectToolsTabAnchorSelector(tab: AiAssistantJoyrideTab): string {
  return `[data-aiassistant-project-tools-tab="${tab}"]`;
}

function JoyrideSpeechBubble(props: {
  step: AiAssistantJoyrideStep;
  stepIndex: number;
  stepCount: number;
  onNext: () => void;
  onSkip: () => void;
  isLast: boolean;
}) {
  const { step, stepIndex, stepCount, onNext, onSkip, isLast } = props;
  const Icon = step.Icon;
  return (
    <Paper
      elevation={8}
      sx={{
        p: 2,
        maxWidth: 360,
        borderRadius: 2,
        position: 'relative',
        '&::before': {
          content: '""',
          position: 'absolute',
          top: -8,
          left: '50%',
          transform: 'translateX(-50%)',
          width: 0,
          height: 0,
          borderLeft: '8px solid transparent',
          borderRight: '8px solid transparent',
          borderBottom: (theme) => `8px solid ${theme.palette.background.paper}`
        }
      }}
    >
      <Stack spacing={1.5}>
        <Stack direction="row" spacing={1.5} alignItems="flex-start">
          <Box
            sx={{
              width: 40,
              height: 40,
              borderRadius: '50%',
              bgcolor: 'primary.main',
              color: 'primary.contrastText',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              flexShrink: 0
            }}
          >
            <Icon fontSize="small" />
          </Box>
          <Box sx={{ minWidth: 0, flex: 1 }}>
            <Typography variant="subtitle1" fontWeight={700} gutterBottom>
              {step.title}
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ whiteSpace: 'pre-line' }}>
              {step.body}
            </Typography>
          </Box>
        </Stack>
        <Stack direction="row" justifyContent="space-between" alignItems="center">
          <Typography variant="caption" color="text.secondary">
            {stepIndex + 1} of {stepCount}
          </Typography>
          <Stack direction="row" spacing={1}>
            <Button size="small" color="inherit" onClick={onSkip}>
              Skip tour
            </Button>
            <Button size="small" variant="contained" onClick={onNext}>
              {isLast ? 'Done' : 'Next'}
            </Button>
          </Stack>
        </Stack>
      </Stack>
    </Paper>
  );
}

export interface UseAiAssistantConfigurationJoyrideResult {
  phase: AiAssistantJoyridePhase;
  activeStep: AiAssistantJoyrideStep | null;
  activeStepIndex: number;
  /** Call when the configuration panel mounts. */
  onPanelReady: () => void;
  startTour: () => void;
  /** Re-open the welcome dialog and tour (current plugin version). */
  replayJoyride: () => void;
  dismissJoyride: () => void;
  goNext: () => void;
}

export function useAiAssistantConfigurationJoyride(
  onNavigateTab: (tab: AiAssistantJoyrideTab) => void,
  siteId: string
): UseAiAssistantConfigurationJoyrideResult {
  const [phase, setPhase] = useState<AiAssistantJoyridePhase>('idle');
  const [stepIndex, setStepIndex] = useState(0);
  const [panelReady, setPanelReady] = useState(false);
  const studioSiteId = (siteId || '').trim();

  const dismissJoyride = useCallback(() => {
    markConfigurationJoyrideSeen(studioSiteId);
    setPhase('idle');
    setStepIndex(0);
  }, [studioSiteId]);

  const onPanelReady = useCallback(() => {
    setPanelReady(true);
  }, []);

  useEffect(() => {
    if (!panelReady || phase !== 'idle' || !studioSiteId) return;
    if (shouldShowConfigurationJoyride(studioSiteId)) {
      setPhase('welcome');
    }
  }, [panelReady, phase, studioSiteId]);

  const activeStep =
    phase === 'tour' && stepIndex >= 0 && stepIndex < AI_ASSISTANT_JOYRIDE_STEPS.length
      ? AI_ASSISTANT_JOYRIDE_STEPS[stepIndex]
      : null;

  useLayoutEffect(() => {
    if (phase !== 'tour' || !activeStep) return;
    onNavigateTab(activeStep.tab);
  }, [phase, activeStep, onNavigateTab]);

  const startTour = useCallback(() => {
    setStepIndex(0);
    setPhase('tour');
  }, []);

  const replayJoyride = useCallback(() => {
    setStepIndex(0);
    setPhase('welcome');
  }, []);

  const goNext = useCallback(() => {
    if (stepIndex >= AI_ASSISTANT_JOYRIDE_STEPS.length - 1) {
      dismissJoyride();
      return;
    }
    setStepIndex((i) => i + 1);
  }, [stepIndex, dismissJoyride]);

  return {
    phase,
    activeStep,
    activeStepIndex: stepIndex,
    onPanelReady,
    startTour,
    replayJoyride,
    dismissJoyride,
    goNext
  };
}

export function AiAssistantJoyrideWelcomeDialog(props: {
  onShowAround: () => void;
  onCancel: () => void;
}) {
  const { onShowAround, onCancel } = props;
  return (
    <Dialog open onClose={onCancel} maxWidth="xs" fullWidth>
      <DialogContent sx={{ pt: 3, pb: 1 }}>
        <Stack spacing={2} alignItems="center" textAlign="center">
          <Box
            sx={{
              width: 88,
              height: 88,
              borderRadius: '50%',
              bgcolor: 'primary.light',
              color: 'primary.main',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center'
            }}
          >
            <SmartToyOutlined sx={{ fontSize: 52 }} />
          </Box>
          <Typography variant="h6" component="h2" fontWeight={700}>
            Welcome to the Studio AI Assistant
          </Typography>
          <Typography variant="body2" color="text.secondary">
            I can walk you through the configuration tabs so you know where to add API keys, set up agents, and
            explore optional integrations. It only takes a minute.
          </Typography>
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2.5, justifyContent: 'center', gap: 1 }}>
        <Button onClick={onCancel} color="inherit">
          Cancel
        </Button>
        <Button variant="contained" onClick={onShowAround}>
          Show me around
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function resolveJoyrideTabAnchor(
  step: AiAssistantJoyrideStep,
  scope: ParentNode | null
): HTMLElement | null {
  const root = scope ?? document;
  const el = root.querySelector(projectToolsTabAnchorSelector(step.tab)) as HTMLElement | null;
  if (!el) return null;
  el.scrollIntoView({ block: 'nearest', inline: 'center' });
  return el;
}

export function AiAssistantJoyrideTourPopover(props: {
  open: boolean;
  step: AiAssistantJoyrideStep | null;
  /** Main Project Tools tab; anchor only when this matches {@link step}.tab. */
  activeTab: string;
  anchorScopeRef?: RefObject<HTMLElement | null>;
  stepIndex: number;
  stepCount: number;
  onNext: () => void;
  onSkip: () => void;
}) {
  const { open, step, activeTab, anchorScopeRef, stepIndex, stepCount, onNext, onSkip } = props;
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null);

  useLayoutEffect(() => {
    if (!open || !step || activeTab !== step.tab) {
      setAnchorEl(null);
      return;
    }
    const syncAnchor = () => {
      setAnchorEl(resolveJoyrideTabAnchor(step, anchorScopeRef?.current ?? null));
    };
    syncAnchor();
    const raf = requestAnimationFrame(syncAnchor);
    return () => cancelAnimationFrame(raf);
  }, [open, step, activeTab, anchorScopeRef]);

  if (!open || !step) {
    return null;
  }

  const isLast = stepIndex >= stepCount - 1;
  const popoverOpen = Boolean(anchorEl) && activeTab === step.tab;

  return (
    <>
      <Box
        component="button"
        type="button"
        aria-label="Dismiss tour"
        onClick={onSkip}
        sx={{
          position: 'fixed',
          inset: 0,
          m: 0,
          p: 0,
          border: 0,
          cursor: 'default',
          bgcolor: 'rgba(0, 0, 0, 0.35)',
          zIndex: (theme) => theme.zIndex.modal - 1
        }}
      />
      <Popover
        open={popoverOpen}
        anchorEl={anchorEl}
        onClose={onSkip}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
        transformOrigin={{ vertical: 'top', horizontal: 'center' }}
        disableScrollLock
        slotProps={{
          paper: {
            sx: {
              mt: 1.5,
              overflow: 'visible',
              bgcolor: 'transparent',
              boxShadow: 'none'
            }
          }
        }}
        sx={{ zIndex: (theme) => theme.zIndex.modal }}
      >
        <JoyrideSpeechBubble
          step={step}
          stepIndex={stepIndex}
          stepCount={stepCount}
          onNext={onNext}
          onSkip={onSkip}
          isLast={isLast}
        />
      </Popover>
    </>
  );
}
