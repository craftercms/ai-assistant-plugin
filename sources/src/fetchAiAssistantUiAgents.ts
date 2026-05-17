import {
  exclusiveCentralChatAgentsFromFile,
  getEffectiveCentralAgentsCatalog
} from './centralAgentCatalog';
import type { AgentConfig } from './agentConfig';

export type SiteChatAgentsOverlayResult = {
  agents: AgentConfig[];
  exclusive: boolean;
};

/**
 * Load chat agents for preview Helper / toolbar: site `agents.json` when saved, else built-in defaults.
 */
export async function fetchSiteChatAgentsForOverlay(siteId: string): Promise<SiteChatAgentsOverlayResult> {
  if (!siteId) return { agents: [], exclusive: false };
  const file = await getEffectiveCentralAgentsCatalog(siteId);
  const ex = exclusiveCentralChatAgentsFromFile(file);
  if (ex) return { agents: ex, exclusive: true };
  return { agents: [], exclusive: false };
}

/**
 * @deprecated Prefer {@link fetchSiteChatAgentsForOverlay}.
 */
export async function fetchAiAssistantAgentsFromSiteUi(siteId: string): Promise<AgentConfig[]> {
  const { agents } = await fetchSiteChatAgentsForOverlay(siteId);
  return agents;
}
