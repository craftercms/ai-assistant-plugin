import { BUILTIN_TOOL_NAME_OPTIONS, type ToolsPolicyFormState } from './aiAssistantToolsMcpUiModel';

/** Built-in orchestration wires shown on Project Tools → Integrations → Tools (excludes agent-only {@code mcp:*}). */
export const BUILTIN_ORCHESTRATION_TOOL_WIRES: readonly string[] = BUILTIN_TOOL_NAME_OPTIONS;

function disabledBuiltInLower(state: ToolsPolicyFormState): Set<string> {
  return new Set(state.disabledBuiltInTools.map((s) => s.trim().toLowerCase()).filter(Boolean));
}

function enabledBuiltInLower(state: ToolsPolicyFormState): Set<string> {
  return new Set(state.enabledBuiltInTools.map((s) => s.trim().toLowerCase()).filter(Boolean));
}

/** Whether a built-in wire is enabled per {@code tools.json} hide/whitelist policy. */
export function isBuiltInToolEnabled(state: ToolsPolicyFormState, wireName: string): boolean {
  const wire = wireName.trim();
  if (!wire) {
    return false;
  }
  const lower = wire.toLowerCase();
  const whitelist = state.enabledBuiltInTools.map((s) => s.trim()).filter(Boolean);
  if (whitelist.length > 0) {
    return enabledBuiltInLower(state).has(lower);
  }
  return !disabledBuiltInLower(state).has(lower);
}

export function setBuiltInToolEnabled(
  state: ToolsPolicyFormState,
  wireName: string,
  enabled: boolean
): ToolsPolicyFormState {
  const wire = wireName.trim();
  if (!wire) {
    return state;
  }
  const lower = wire.toLowerCase();
  const whitelist = state.enabledBuiltInTools.map((s) => s.trim()).filter(Boolean);
  if (whitelist.length > 0) {
    const nextWl = new Set(whitelist);
    if (enabled) {
      nextWl.add(wire);
    } else {
      for (const w of whitelist) {
        if (w.toLowerCase() === lower) {
          nextWl.delete(w);
        }
      }
    }
    return { ...state, enabledBuiltInTools: [...nextWl] };
  }
  const nextDis = new Set(state.disabledBuiltInTools.map((s) => s.trim()).filter(Boolean));
  if (enabled) {
    for (const d of [...nextDis]) {
      if (d.toLowerCase() === lower) {
        nextDis.delete(d);
      }
    }
  } else {
    let found = false;
    for (const d of nextDis) {
      if (d.toLowerCase() === lower) {
        found = true;
        break;
      }
    }
    if (!found) {
      nextDis.add(wire);
    }
  }
  return { ...state, disabledBuiltInTools: [...nextDis] };
}

export function disabledUserToolsLower(state: ToolsPolicyFormState): Set<string> {
  return new Set(state.disabledUserTools.map((s) => s.trim().toLowerCase()).filter(Boolean));
}

export function isUserToolEnabled(state: ToolsPolicyFormState, toolId: string): boolean {
  const id = toolId.trim().toLowerCase();
  return id.length > 0 && !disabledUserToolsLower(state).has(id);
}

export function setUserToolEnabled(state: ToolsPolicyFormState, toolId: string, enabled: boolean): ToolsPolicyFormState {
  const id = toolId.trim();
  if (!id) {
    return state;
  }
  const lower = id.toLowerCase();
  const next = new Set(state.disabledUserTools.map((s) => s.trim()).filter(Boolean));
  if (enabled) {
    for (const d of [...next]) {
      if (d.toLowerCase() === lower) {
        next.delete(d);
      }
    }
  } else {
    let found = false;
    for (const d of next) {
      if (d.toLowerCase() === lower) {
        found = true;
        break;
      }
    }
    if (!found) {
      next.add(id);
    }
  }
  return { ...state, disabledUserTools: [...next] };
}
