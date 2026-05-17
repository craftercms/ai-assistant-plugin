import { exclusiveCentralChatAgentsFromFile, fetchCentralAgentsFile } from './centralAgentCatalog';
import type { AgentConfig } from './agentConfig';

/**
 * Load chat agents from site `config/studio/ai-assistant/agents.json` (Project Tools → Agents).
 * When the file is missing or has no chat rows, returns an empty list — agent settings are not read from `ui.xml`.
 */
export async function fetchSiteChatAgentsForOverlay(
  siteId: string
): Promise<{ agents: AgentConfig[]; exclusive: boolean }> {
  if (!siteId) return { agents: [], exclusive: false };
  const file = await fetchCentralAgentsFile(siteId);
  if (file && file.agents.length > 0) {
    const ex = exclusiveCentralChatAgentsFromFile(file);
    if (ex) return { agents: ex, exclusive: true };
  }
  return { agents: [], exclusive: false };
}

/**
 * @deprecated Prefer {@link fetchSiteChatAgentsForOverlay}.
 */
export async function fetchAiAssistantAgentsFromSiteUi(siteId: string): Promise<AgentConfig[]> {
  const { agents } = await fetchSiteChatAgentsForOverlay(siteId);
  return agents;
}
