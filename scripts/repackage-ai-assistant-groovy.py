#!/usr/bin/env python3
"""
Repackage ai-assistant Groovy classes: spi / engine / contrib / studio layout.
Run from repo root: python3 scripts/repackage-ai-assistant-groovy.py
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
ROOT = REPO / "authoring/scripts/classes/plugins/org/craftercms/aiassistant/studio"
BASE_PKG = "plugins.org.craftercms.aiassistant.studio"

# (relative path under ROOT, new relative path under ROOT)
MOVES: list[tuple[str, str]] = [
    # spi/tool
    ("tools/spi/StudioAiOrchestrationTool.groovy", "spi/tool/StudioAiOrchestrationTool.groovy"),
    ("tools/spi/AbstractStudioAiTool.groovy", "spi/tool/AbstractStudioAiTool.groovy"),
    ("tools/spi/StudioAiToolContext.groovy", "spi/tool/StudioAiToolContext.groovy"),
    ("tools/spi/StudioAiToolSchemas.groovy", "spi/tool/StudioAiToolSchemas.groovy"),
    ("tools/spi/StudioAiToolMaintainerObservability.groovy", "spi/tool/StudioAiToolMaintainerObservability.groovy"),
    ("tools/spi/StudioAiToolProgress.groovy", "spi/tool/StudioAiToolProgress.groovy"),
    ("tools/spi/StudioAiToolSupport.groovy", "spi/tool/StudioAiToolSupport.groovy"),
    # spi/llm
    ("llm/StudioAiLlmRuntime.groovy", "spi/llm/StudioAiLlmRuntime.groovy"),
    ("llm/StudioAiLlmKind.groovy", "spi/llm/StudioAiLlmKind.groovy"),
    ("llm/StudioAiRuntimeBuildRequest.groovy", "spi/llm/StudioAiRuntimeBuildRequest.groovy"),
    # spi/imagegen
    ("imagegen/StudioAiImageGenerator.groovy", "spi/imagegen/StudioAiImageGenerator.groovy"),
    ("imagegen/StudioAiImageGenContext.groovy", "spi/imagegen/StudioAiImageGenContext.groovy"),
    # contrib/llm
    ("llm/OpenAiSpecSpringAiLlmRuntime.groovy", "contrib/llm/wire/openaispec/OpenAiSpecSpringAiLlmRuntime.groovy"),
    ("llm/AnthropicSpringAiLlmRuntime.groovy", "contrib/llm/vendor/anthropic/AnthropicSpringAiLlmRuntime.groovy"),
    ("llm/StudioAiScriptLlmLoader.groovy", "contrib/llm/script/StudioAiScriptLlmLoader.groovy"),
    ("llm/StudioAiMapBackedScriptLlmRuntime.groovy", "contrib/llm/script/StudioAiMapBackedScriptLlmRuntime.groovy"),
    ("llm/StudioAiScriptLlmContainerRuntime.groovy", "contrib/llm/script/StudioAiScriptLlmContainerRuntime.groovy"),
    ("llm/StudioAiProviderCredentials.groovy", "contrib/llm/StudioAiProviderCredentials.groovy"),
    # contrib/imagegen
    ("imagegen/CompatibleImageGenerator.groovy", "contrib/imagegen/CompatibleImageGenerator.groovy"),
    ("imagegen/StudioAiScriptImageGenLoader.groovy", "contrib/imagegen/StudioAiScriptImageGenLoader.groovy"),
    # engine/catalog
    ("llm/StudioAiLlmRuntimeFactory.groovy", "engine/catalog/StudioAiLlmRuntimeFactory.groovy"),
    ("imagegen/StudioAiImageGeneratorFactory.groovy", "engine/catalog/StudioAiImageGeneratorFactory.groovy"),
    ("tools/AiOrchestrationTools.groovy", "engine/catalog/AiOrchestrationTools.groovy"),
    ("tools/catalog/StudioAiToolRegistry.groovy", "engine/catalog/StudioAiToolRegistry.groovy"),
    # engine/turn
    ("orchestration/AiOrchestration.groovy", "engine/turn/AiOrchestration.groovy"),
    ("orchestration/ProseDeclaredToolCalls.groovy", "engine/turn/ProseDeclaredToolCalls.groovy"),
    ("orchestration/AuthoringIntentRefineWithTools.groovy", "engine/turn/AuthoringIntentRefineWithTools.groovy"),
    ("orchestration/chatcompletions/ChatCompletionsToolWire.groovy", "engine/turn/chatcompletions/ChatCompletionsToolWire.groovy"),
    ("plan/PlanOrchestration.groovy", "engine/turn/plan/PlanOrchestration.groovy"),
    # engine/routing (Router.groovy is authored in-tree under engine/routing/)
    ("recipes/AuthoringIntentRecipeCatalog.groovy", "engine/routing/subrouting/AuthoringIntentRecipeCatalog.groovy"),
    ("recipes/AuthoringIntentRecipeEngine.groovy", "engine/routing/subrouting/AuthoringIntentRecipeEngine.groovy"),
    ("recipes/AuthoringIntentRecipeWhen.groovy", "engine/routing/subrouting/AuthoringIntentRecipeWhen.groovy"),
    ("recipes/AuthoringIntentRecipeBindings.groovy", "engine/routing/subrouting/AuthoringIntentRecipeBindings.groovy"),
    ("recipes/AuthoringIntentRecipeLlmRefiner.groovy", "engine/routing/subrouting/AuthoringIntentRecipeLlmRefiner.groovy"),
    ("recipes/AuthoringIntentRecipePlanCompiler.groovy", "engine/routing/subrouting/AuthoringIntentRecipePlanCompiler.groovy"),
    ("recipes/AuthoringIntentRecipeRouter.groovy", "engine/routing/subrouting/AuthoringIntentRecipeRouter.groovy"),
    ("recipes/AuthoringIntentRoutingEngine.groovy", "engine/routing/subrouting/AuthoringIntentRoutingEngine.groovy"),
    ("recipes/AuthoringIntentSiteToolCatalog.groovy", "engine/routing/subrouting/AuthoringIntentSiteToolCatalog.groovy"),
    ("recipes/PriorConversationDraftExtract.groovy", "engine/routing/subrouting/PriorConversationDraftExtract.groovy"),
    ("recipes/ToolsLoopWriteVerification.groovy", "engine/routing/subrouting/ToolsLoopWriteVerification.groovy"),
    ("recipes/RecipeMarkdownSections.groovy", "engine/routing/subrouting/RecipeMarkdownSections.groovy"),
    ("recipes/StudioRecipeClockTemplates.groovy", "engine/routing/subrouting/StudioRecipeClockTemplates.groovy"),
    ("recipes/authoring-intent-recipes-default.json", "engine/routing/authoring-intent-recipes-default.json"),
    # engine/prompt, rag, autonomous, context, policy, util
    ("prompt/ToolPrompts.groovy", "engine/prompt/ToolPrompts.groovy"),
    ("prompt/ToolPromptsLoader.groovy", "engine/prompt/ToolPromptsLoader.groovy"),
    ("prompt/ToolPromptsBuiltinDefaults.groovy", "engine/prompt/ToolPromptsBuiltinDefaults.groovy"),
    ("prompt/ToolPromptsOverrideCatalog.groovy", "engine/prompt/ToolPromptsOverrideCatalog.groovy"),
    ("prompt/ToolPromptsSiteContext.groovy", "engine/prompt/ToolPromptsSiteContext.groovy"),
    ("rag/PluginRagVectorRegistry.groovy", "engine/rag/PluginRagVectorRegistry.groovy"),
    ("rag/ExpertSkillVectorRegistry.groovy", "engine/rag/ExpertSkillVectorRegistry.groovy"),
    ("autonomous/AutonomousAssistantWorker.groovy", "engine/autonomous/AutonomousAssistantWorker.groovy"),
    ("autonomous/AutonomousAssistantSupervisor.groovy", "engine/autonomous/AutonomousAssistantSupervisor.groovy"),
    ("autonomous/AutonomousAssistantRegistry.groovy", "engine/autonomous/AutonomousAssistantRegistry.groovy"),
    ("autonomous/AutonomousAssistantStateStore.groovy", "engine/autonomous/AutonomousAssistantStateStore.groovy"),
    ("autonomous/AutonomousAssistantStatus.groovy", "engine/autonomous/AutonomousAssistantStatus.groovy"),
    ("autonomous/AutonomousAssistantRuntimeHooks.groovy", "engine/autonomous/AutonomousAssistantRuntimeHooks.groovy"),
    ("autonomous/AutonomousScopeGuard.groovy", "engine/autonomous/AutonomousScopeGuard.groovy"),
    ("autonomous/AutonomousScheduleProbe.groovy", "engine/autonomous/AutonomousScheduleProbe.groovy"),
    ("autonomous/AutonomousAgentIdBuilder.groovy", "engine/autonomous/AutonomousAgentIdBuilder.groovy"),
    ("autonomous/AutonomousSiteDigestBuilder.groovy", "engine/autonomous/AutonomousSiteDigestBuilder.groovy"),
    ("authoring/AuthoringPreviewContext.groovy", "engine/context/AuthoringPreviewContext.groovy"),
    ("context/SiteProjectContext.groovy", "engine/context/SiteProjectContext.groovy"),
    ("tools/loop/ToolsLoopWirePolicy.groovy", "engine/policy/ToolsLoopWirePolicy.groovy"),
    ("tools/loop/ToolsLoopWirePolicyRegistry.groovy", "engine/policy/ToolsLoopWirePolicyRegistry.groovy"),
    ("content/ContentSubgraphAggregator.groovy", "engine/util/ContentSubgraphAggregator.groovy"),
    ("concurrent/ParallelToolExecutor.groovy", "engine/util/ParallelToolExecutor.groovy"),
    # studio
    ("tools/StudioToolOperations.groovy", "studio/repository/StudioToolOperations.groovy"),
    ("tools/operations/StudioToolOperationsSupport.groovy", "studio/repository/StudioToolOperationsSupport.groovy"),
    ("config/StudioAiAssistantProjectConfig.groovy", "studio/config/StudioAiAssistantProjectConfig.groovy"),
    ("config/StudioAiSiteModuleText.groovy", "studio/config/StudioAiSiteModuleText.groovy"),
    ("secrets/StudioAiAssistantSecretsService.groovy", "studio/secrets/StudioAiAssistantSecretsService.groovy"),
    ("secrets/StudioAiAssistantSecretsCatalog.groovy", "studio/secrets/StudioAiAssistantSecretsCatalog.groovy"),
    ("secrets/StudioAiSecretMacroResolver.groovy", "studio/secrets/StudioAiSecretMacroResolver.groovy"),
    ("http/AiHttpProxy.groovy", "studio/http/AiHttpProxy.groovy"),
    # contrib agents
    ("http/AiAssistantCentralAgentsMerge.groovy", "contrib/agents/AiAssistantCentralAgentsMerge.groovy"),
    ("catalog/AgentCatalogIds.groovy", "contrib/agents/AgentCatalogIds.groovy"),
    # contrib tool site
    ("tools/StudioAiUserSiteTools.groovy", "contrib/tool/site/StudioAiUserSiteTools.groovy"),
    # contrib mcp
    ("mcp/StudioAiMcpClient.groovy", "contrib/tool/mcp/StudioAiMcpClient.groovy"),
    ("tools/mcp/McpWireStudioAiTool.groovy", "contrib/tool/mcp/McpWireStudioAiTool.groovy"),
    # contrib playbook
    ("playbook/CrafterizingPlaybookLoader.groovy", "contrib/tool/builtin/playbook/CrafterizingPlaybookLoader.groovy"),
    ("playbook/CrafterizingPlaybook.md", "contrib/tool/builtin/playbook/CrafterizingPlaybook.md"),
    # contrib cms internal (before cms tools)
    ("tools/cms/support/CmsContentExists.groovy", "contrib/tool/builtin/cms/internal/CmsContentExists.groovy"),
    ("tools/cms/support/CmsContentVersionHistory.groovy", "contrib/tool/builtin/cms/internal/CmsContentVersionHistory.groovy"),
    ("tools/cms/support/CmsContentVersionXml.groovy", "contrib/tool/builtin/cms/internal/CmsContentVersionXml.groovy"),
    ("tools/cms/support/CmsFindContentVersion.groovy", "contrib/tool/builtin/cms/internal/CmsFindContentVersion.groovy"),
    ("tools/cms/support/CmsCompareContentVersions.groovy", "contrib/tool/builtin/cms/internal/CmsCompareContentVersions.groovy"),
    ("tools/cms/support/CmsGetContent.groovy", "contrib/tool/builtin/cms/internal/CmsGetContent.groovy"),
    ("tools/cms/support/CmsGetContentTypeFormDefinition.groovy", "contrib/tool/builtin/cms/internal/CmsGetContentTypeFormDefinition.groovy"),
    ("tools/cms/support/CmsListPagesAndComponents.groovy", "contrib/tool/builtin/cms/internal/CmsListPagesAndComponents.groovy"),
    ("tools/cms/support/CmsListStudioContentTypes.groovy", "contrib/tool/builtin/cms/internal/CmsListStudioContentTypes.groovy"),
    ("tools/cms/support/CmsPreviewHtmlFetch.groovy", "contrib/tool/builtin/cms/internal/CmsPreviewHtmlFetch.groovy"),
    ("tools/cms/support/CmsPublishContent.groovy", "contrib/tool/builtin/cms/internal/CmsPublishContent.groovy"),
    ("tools/cms/support/CmsRepositorySupport.groovy", "contrib/tool/builtin/cms/internal/CmsRepositorySupport.groovy"),
    ("tools/cms/support/CmsResearchSiteContent.groovy", "contrib/tool/builtin/cms/internal/CmsResearchSiteContent.groovy"),
    ("tools/cms/support/CmsRevertChange.groovy", "contrib/tool/builtin/cms/internal/CmsRevertChange.groovy"),
    ("tools/cms/support/CmsContentVersionXml.groovy", "contrib/tool/builtin/cms/internal/CmsContentVersionXml.groovy"),
    ("tools/cms/support/CmsFindContentVersion.groovy", "contrib/tool/builtin/cms/internal/CmsFindContentVersion.groovy"),
    ("tools/cms/support/CmsCompareContentVersions.groovy", "contrib/tool/builtin/cms/internal/CmsCompareContentVersions.groovy"),
    ("tools/cms/support/CmsStudioPlaceholderImage.groovy", "contrib/tool/builtin/cms/internal/CmsStudioPlaceholderImage.groovy"),
    ("tools/cms/support/CmsWriteContent.groovy", "contrib/tool/builtin/cms/internal/CmsWriteContent.groovy"),
    # contrib cms tools
    ("tools/cms/ContentExistsTool.groovy", "contrib/tool/builtin/cms/ContentExistsTool.groovy"),
    ("tools/cms/GeneratePlaceholderImageTool.groovy", "contrib/tool/builtin/cms/GeneratePlaceholderImageTool.groovy"),
    ("tools/cms/GetContentTool.groovy", "contrib/tool/builtin/cms/GetContentTool.groovy"),
    ("tools/cms/GetContentTypeFormDefinitionTool.groovy", "contrib/tool/builtin/cms/GetContentTypeFormDefinitionTool.groovy"),
    ("tools/cms/GetContentVersionHistoryTool.groovy", "contrib/tool/builtin/cms/GetContentVersionHistoryTool.groovy"),
    ("tools/cms/FindContentVersionTool.groovy", "contrib/tool/builtin/cms/FindContentVersionTool.groovy"),
    ("tools/cms/CompareContentVersionsTool.groovy", "contrib/tool/builtin/cms/CompareContentVersionsTool.groovy"),
    ("tools/cms/GetPreviewHtmlTool.groovy", "contrib/tool/builtin/cms/GetPreviewHtmlTool.groovy"),
    ("tools/cms/ListContentDependencyScopeTool.groovy", "contrib/tool/builtin/cms/ListContentDependencyScopeTool.groovy"),
    ("tools/cms/ListPagesAndComponentsTool.groovy", "contrib/tool/builtin/cms/ListPagesAndComponentsTool.groovy"),
    ("tools/cms/ListStudioContentTypesTool.groovy", "contrib/tool/builtin/cms/ListStudioContentTypesTool.groovy"),
    ("tools/cms/PublishContentTool.groovy", "contrib/tool/builtin/cms/PublishContentTool.groovy"),
    ("tools/cms/ResearchSiteContentTool.groovy", "contrib/tool/builtin/cms/ResearchSiteContentTool.groovy"),
    ("tools/cms/RevertChangeTool.groovy", "contrib/tool/builtin/cms/RevertChangeTool.groovy"),
    ("tools/cms/UpdateContentTool.groovy", "contrib/tool/builtin/cms/UpdateContentTool.groovy"),
    ("tools/cms/UpdateContentTypeTool.groovy", "contrib/tool/builtin/cms/UpdateContentTypeTool.groovy"),
    ("tools/cms/WriteContentTool.groovy", "contrib/tool/builtin/cms/WriteContentTool.groovy"),
    # contrib http
    ("tools/http/CrafterQChatApiClient.groovy", "contrib/tool/builtin/http/CrafterQChatApiClient.groovy"),
    ("tools/http/HttpUrlFetch.groovy", "contrib/tool/builtin/http/HttpUrlFetch.groovy"),
    ("tools/http/HttpUrlPost.groovy", "contrib/tool/builtin/http/HttpUrlPost.groovy"),
    ("tools/http/OutboundHttpPolicy.groovy", "contrib/tool/builtin/http/OutboundHttpPolicy.groovy"),
    # contrib integrations
    ("tools/general/ConsultCrafterQTool.groovy", "contrib/tool/builtin/integrations/ConsultCrafterQTool.groovy"),
    ("tools/general/ConsultCrafterQProjectSettings.groovy", "contrib/tool/builtin/integrations/ConsultCrafterQProjectSettings.groovy"),
    ("tools/general/CrafterQConsultFeedbackFormatter.groovy", "contrib/tool/builtin/integrations/CrafterQConsultFeedbackFormatter.groovy"),
    ("tools/general/FetchHttpUrlTool.groovy", "contrib/tool/builtin/integrations/FetchHttpUrlTool.groovy"),
    ("tools/general/OpenWebSearchQueryDisambiguation.groovy", "contrib/tool/builtin/integrations/OpenWebSearchQueryDisambiguation.groovy"),
    ("tools/general/PostHttpUrlTool.groovy", "contrib/tool/builtin/integrations/PostHttpUrlTool.groovy"),
    ("tools/general/SerpApiWebSearchProjectSettings.groovy", "contrib/tool/builtin/integrations/SerpApiWebSearchProjectSettings.groovy"),
    ("tools/general/SerpApiWebSearchTool.groovy", "contrib/tool/builtin/integrations/SerpApiWebSearchTool.groovy"),
    ("tools/general/SlackConfirmationPostFormatter.groovy", "contrib/tool/builtin/integrations/SlackConfirmationPostFormatter.groovy"),
    ("tools/general/SlackPostMessageProjectSettings.groovy", "contrib/tool/builtin/integrations/SlackPostMessageProjectSettings.groovy"),
    ("tools/general/SlackPostMessageTool.groovy", "contrib/tool/builtin/integrations/SlackPostMessageTool.groovy"),
    ("tools/general/WebSearchResultTextUtil.groovy", "contrib/tool/builtin/integrations/WebSearchResultTextUtil.groovy"),
    ("tools/general/WebSearchTool.groovy", "contrib/tool/builtin/integrations/WebSearchTool.groovy"),
    # contrib development
    ("tools/development/AnalyzeTemplateTool.groovy", "contrib/tool/builtin/development/AnalyzeTemplateTool.groovy"),
    ("tools/development/GetCrafterizingPlaybookTool.groovy", "contrib/tool/builtin/development/GetCrafterizingPlaybookTool.groovy"),
    ("tools/development/UpdateTemplateTool.groovy", "contrib/tool/builtin/development/UpdateTemplateTool.groovy"),
]

# FQCN replacements (longest first)
IMPORT_REPLACEMENTS: list[tuple[str, str]] = sorted(
    [
        (f"{BASE_PKG}.tools.cms.support", f"{BASE_PKG}.contrib.tool.builtin.cms.internal"),
        (f"{BASE_PKG}.tools.cms", f"{BASE_PKG}.contrib.tool.builtin.cms"),
        (f"{BASE_PKG}.tools.general", f"{BASE_PKG}.contrib.tool.builtin.integrations"),
        (f"{BASE_PKG}.tools.development", f"{BASE_PKG}.contrib.tool.builtin.development"),
        (f"{BASE_PKG}.tools.operations", f"{BASE_PKG}.studio.repository"),
        (f"{BASE_PKG}.tools.catalog", f"{BASE_PKG}.engine.catalog"),
        (f"{BASE_PKG}.tools.loop", f"{BASE_PKG}.engine.policy"),
        (f"{BASE_PKG}.tools.http", f"{BASE_PKG}.contrib.tool.builtin.http"),
        (f"{BASE_PKG}.tools.mcp", f"{BASE_PKG}.contrib.tool.mcp"),
        (f"{BASE_PKG}.tools.spi", f"{BASE_PKG}.spi.tool"),
        (f"{BASE_PKG}.orchestration.chatcompletions", f"{BASE_PKG}.engine.turn.chatcompletions"),
        (f"{BASE_PKG}.orchestration", f"{BASE_PKG}.engine.turn"),
        (f"{BASE_PKG}.recipes", f"{BASE_PKG}.engine.routing"),
        (f"{BASE_PKG}.llm.OpenAiSpecSpringAiLlmRuntime", f"{BASE_PKG}.contrib.llm.wire.openaispec.OpenAiSpecSpringAiLlmRuntime"),
        (f"{BASE_PKG}.llm.AnthropicSpringAiLlmRuntime", f"{BASE_PKG}.contrib.llm.vendor.anthropic.AnthropicSpringAiLlmRuntime"),
        (f"{BASE_PKG}.llm.StudioAiScriptLlmContainerRuntime", f"{BASE_PKG}.contrib.llm.script.StudioAiScriptLlmContainerRuntime"),
        (f"{BASE_PKG}.llm.StudioAiMapBackedScriptLlmRuntime", f"{BASE_PKG}.contrib.llm.script.StudioAiMapBackedScriptLlmRuntime"),
        (f"{BASE_PKG}.llm.StudioAiScriptLlmLoader", f"{BASE_PKG}.contrib.llm.script.StudioAiScriptLlmLoader"),
        (f"{BASE_PKG}.llm.StudioAiProviderCredentials", f"{BASE_PKG}.contrib.llm.StudioAiProviderCredentials"),
        (f"{BASE_PKG}.llm.StudioAiLlmRuntimeFactory", f"{BASE_PKG}.engine.catalog.StudioAiLlmRuntimeFactory"),
        (f"{BASE_PKG}.llm.StudioAiRuntimeBuildRequest", f"{BASE_PKG}.spi.llm.StudioAiRuntimeBuildRequest"),
        (f"{BASE_PKG}.llm.StudioAiLlmRuntime", f"{BASE_PKG}.spi.llm.StudioAiLlmRuntime"),
        (f"{BASE_PKG}.llm.StudioAiLlmKind", f"{BASE_PKG}.spi.llm.StudioAiLlmKind"),
        (f"{BASE_PKG}.imagegen.StudioAiImageGeneratorFactory", f"{BASE_PKG}.engine.catalog.StudioAiImageGeneratorFactory"),
        (f"{BASE_PKG}.imagegen.StudioAiScriptImageGenLoader", f"{BASE_PKG}.contrib.imagegen.StudioAiScriptImageGenLoader"),
        (f"{BASE_PKG}.imagegen.CompatibleImageGenerator", f"{BASE_PKG}.contrib.imagegen.CompatibleImageGenerator"),
        (f"{BASE_PKG}.imagegen.StudioAiImageGenContext", f"{BASE_PKG}.spi.imagegen.StudioAiImageGenContext"),
        (f"{BASE_PKG}.imagegen.StudioAiImageGenerator", f"{BASE_PKG}.spi.imagegen.StudioAiImageGenerator"),
        (f"{BASE_PKG}.imagegen", f"{BASE_PKG}.contrib.imagegen"),
        (f"{BASE_PKG}.prompt", f"{BASE_PKG}.engine.prompt"),
        (f"{BASE_PKG}.rag", f"{BASE_PKG}.engine.rag"),
        (f"{BASE_PKG}.autonomous", f"{BASE_PKG}.engine.autonomous"),
        (f"{BASE_PKG}.authoring", f"{BASE_PKG}.engine.context"),
        (f"{BASE_PKG}.context", f"{BASE_PKG}.engine.context"),
        (f"{BASE_PKG}.plan", f"{BASE_PKG}.engine.turn.plan"),
        (f"{BASE_PKG}.content", f"{BASE_PKG}.engine.util"),
        (f"{BASE_PKG}.concurrent", f"{BASE_PKG}.engine.util"),
        (f"{BASE_PKG}.config", f"{BASE_PKG}.studio.config"),
        (f"{BASE_PKG}.secrets.StudioAiAssistantSecretsService", f"{BASE_PKG}.studio.secrets.StudioAiAssistantSecretsService"),
        (f"{BASE_PKG}.secrets.StudioAiAssistantSecretsCatalog", f"{BASE_PKG}.studio.secrets.StudioAiAssistantSecretsCatalog"),
        (f"{BASE_PKG}.secrets.StudioAiSecretMacroResolver", f"{BASE_PKG}.studio.secrets.StudioAiSecretMacroResolver"),
        (f"{BASE_PKG}.mcp", f"{BASE_PKG}.contrib.tool.mcp"),
        (f"{BASE_PKG}.playbook", f"{BASE_PKG}.contrib.tool.builtin.playbook"),
        (f"{BASE_PKG}.catalog", f"{BASE_PKG}.contrib.agents"),
        (f"{BASE_PKG}.http.AiAssistantCentralAgentsMerge", f"{BASE_PKG}.contrib.agents.AiAssistantCentralAgentsMerge"),
        (f"{BASE_PKG}.http.AiHttpProxy", f"{BASE_PKG}.studio.http.AiHttpProxy"),
        (f"{BASE_PKG}.tools.AiOrchestrationTools", f"{BASE_PKG}.engine.catalog.AiOrchestrationTools"),
        (f"{BASE_PKG}.tools.StudioAiUserSiteTools", f"{BASE_PKG}.contrib.tool.site.StudioAiUserSiteTools"),
        (f"{BASE_PKG}.tools.StudioToolOperations", f"{BASE_PKG}.studio.repository.StudioToolOperations"),
    ],
    key=lambda x: len(x[0]),
    reverse=True,
)


def rel_to_package(rel: str) -> str:
    parts = Path(rel).with_suffix("").parts
    return BASE_PKG + "".join("." + p for p in parts[:-1]) if len(parts) > 1 else BASE_PKG


def set_package(path: Path, pkg: str) -> None:
    text = path.read_text(encoding="utf-8")
    if re.search(r"^package\s+", text, re.M):
        text = re.sub(r"^package\s+[\w.]+", f"package {pkg}", text, count=1, flags=re.M)
    else:
        text = f"package {pkg}\n\n{text}"
    path.write_text(text, encoding="utf-8")


def git_mv(src: Path, dst: Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["git", "mv", str(src), str(dst)], cwd=REPO, check=True)


def apply_replacements(root: Path) -> None:
    exts = {".groovy", ".md", ".ts", ".js", ".json", ".yaml", ".yml"}
    for path in root.rglob("*"):
        if not path.is_file() or path.suffix not in exts:
            continue
        if "node_modules" in path.parts or ".git" in path.parts:
            continue
        text = path.read_text(encoding="utf-8")
        orig = text
        for old, new in IMPORT_REPLACEMENTS:
            text = text.replace(old, new)
        if text != orig:
            path.write_text(text, encoding="utf-8")


def main() -> int:
    os.chdir(REPO)
    for old, new in MOVES:
        src = ROOT / old
        dst = ROOT / new
        if not src.exists():
            print(f"SKIP missing: {old}", file=sys.stderr)
            continue
        if dst.exists():
            print(f"SKIP exists: {new}", file=sys.stderr)
            continue
        git_mv(src, dst)
        if dst.suffix == ".groovy":
            pkg = rel_to_package(new)
            set_package(dst, pkg)

    apply_replacements(REPO)
    print("Moves and import replacements done.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
