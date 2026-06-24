import {
  catalogChatAgents,
  defaultCentralAgentsFile,
  getEffectiveCentralAgentsCatalog
} from './centralAgentCatalog';
import type { AgentConfig } from './agentConfig';

export type SiteChatAgentsOverlayResult = {
  agents: AgentConfig[];
  /** Always true — chat agents are sourced only from the central catalog (`agents.json` or built-in defaults). */
  exclusive: boolean;
};

/**
 * Load chat agents for preview Helper / toolbar from `config/studio/ai-assistant/agents.json`
 * (Project Tools → Agents), or built-in defaults when the site file is missing/empty.
 */
export async function fetchSiteChatAgentsForOverlay(siteId: string): Promise<SiteChatAgentsOverlayResult> {
  if (!siteId) return { agents: [], exclusive: true };
  const file = await getEffectiveCentralAgentsCatalog(siteId);
  let agents = catalogChatAgents(file);
  if (!agents.length) {
    agents = catalogChatAgents(defaultCentralAgentsFile());
  }
  return { agents, exclusive: true };
}
