import type { SvgIconComponent } from '@mui/icons-material';
import CheckCircleOutlineRounded from '@mui/icons-material/CheckCircleOutlineRounded';
import ExtensionOutlined from '@mui/icons-material/ExtensionOutlined';
import GroupsOutlined from '@mui/icons-material/GroupsOutlined';
import VpnKeyOutlined from '@mui/icons-material/VpnKeyOutlined';
import ViewQuiltOutlined from '@mui/icons-material/ViewQuiltOutlined';

export type AiAssistantJoyrideTab = 'secrets' | 'ui' | 'agents' | 'integrations';

export type AiAssistantJoyrideStep = {
  id: string;
  tab: AiAssistantJoyrideTab;
  title: string;
  /** Paragraphs separated by blank lines (`\n\n`) for line breaks in the speech bubble. */
  body: string;
  Icon: SvgIconComponent;
};

export const AI_ASSISTANT_JOYRIDE_STEPS: AiAssistantJoyrideStep[] = [
  {
    id: 'secrets-start',
    tab: 'secrets',
    title: 'Start with Secrets',
    Icon: VpnKeyOutlined,
    body:
      'Add your LLM API key here, or point a row at an environment variable on the Studio server.\n\n' +
      'Chat and agents use these credentials at runtime — that is all you need to get started.'
  },
  {
    id: 'ui',
    tab: 'ui',
    title: 'Studio UI',
    Icon: ViewQuiltOutlined,
    body:
      'Tune how the assistant appears in Studio.\n\n' +
      'Add the AI Assistant control to content entry forms when you want authors to chat while they edit.'
  },
  {
    id: 'agents',
    tab: 'agents',
    title: 'Agents',
    Icon: GroupsOutlined,
    body:
      'Fine-tune or add chat agents that authors pick in forms and the helper.\n\n' +
      'Add and configure autonomous agents (experimental) when you need them on a schedule.'
  },
  {
    id: 'integrations',
    tab: 'integrations',
    title: 'Integrations',
    Icon: ExtensionOutlined,
    body:
      'Optional extras: script-based LLMs, image generators, CMS tools, and MCP servers.\n\n' +
      'Skip this until you need something beyond the defaults.'
  },
  {
    id: 'secrets-finish',
    tab: 'secrets',
    title: "You're all set",
    Icon: CheckCircleOutlineRounded,
    body:
      'When you are ready, save your secrets and try the assistant from a content form or the Studio helper.\n\n' +
      'You can reopen this guide from the UI tab or when a new plugin version is installed.'
  }
];
