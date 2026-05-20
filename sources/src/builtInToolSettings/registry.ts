import type { ToolsPolicyFormState } from '../aiAssistantToolsMcpUiModel';
import { serpApiWebSearchSettingsDescriptor } from './serpApiWebSearchSettings';
import type { BuiltInToolSettingsDescriptor } from './types';

/** Built-in wires that expose a Project Tools → Integrations configure dialog. */
export const BUILTIN_TOOL_SETTINGS_DESCRIPTORS: readonly BuiltInToolSettingsDescriptor<unknown>[] = [
  serpApiWebSearchSettingsDescriptor as BuiltInToolSettingsDescriptor<unknown>
];

const DESCRIPTOR_BY_WIRE = new Map(
  BUILTIN_TOOL_SETTINGS_DESCRIPTORS.map((d) => [d.wireName, d] as const)
);

export function builtInToolSettingsDescriptorForWire(
  wireName: string
): BuiltInToolSettingsDescriptor<unknown> | undefined {
  return DESCRIPTOR_BY_WIRE.get(wireName.trim());
}

export function builtInToolHasProjectSettings(wireName: string): boolean {
  return DESCRIPTOR_BY_WIRE.has(wireName.trim());
}

export function defaultBuiltInToolSettingsByWire(): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const d of BUILTIN_TOOL_SETTINGS_DESCRIPTORS) {
    out[d.wireName] = d.defaultState();
  }
  return out;
}

export function parseBuiltInToolSettingsByWire(
  builtInRaw: Record<string, unknown>
): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const d of BUILTIN_TOOL_SETTINGS_DESCRIPTORS) {
    out[d.wireName] = d.parse(builtInRaw[d.wireName]);
  }
  for (const [wire, raw] of Object.entries(builtInRaw)) {
    if (!DESCRIPTOR_BY_WIRE.has(wire)) {
      out[wire] = raw;
    }
  }
  return out;
}

export function mergeBuiltInToolSettingsForSave(
  state: ToolsPolicyFormState,
  priorBuiltIn: Record<string, unknown>
): Record<string, unknown> {
  const merged: Record<string, unknown> = { ...priorBuiltIn };
  for (const d of BUILTIN_TOOL_SETTINGS_DESCRIPTORS) {
    const block = state.builtInToolSettingsByWire[d.wireName];
    merged[d.wireName] = d.serialize(block !== undefined ? d.parse(block) : d.defaultState());
  }
  for (const [wire, raw] of Object.entries(state.builtInToolSettingsByWire)) {
    if (!DESCRIPTOR_BY_WIRE.has(wire)) {
      merged[wire] = raw;
    }
  }
  return merged;
}

export function getBuiltInToolSettingsState<TState>(
  policy: ToolsPolicyFormState,
  descriptor: BuiltInToolSettingsDescriptor<TState>
): TState {
  const raw = policy.builtInToolSettingsByWire[descriptor.wireName];
  if (raw === undefined) {
    return descriptor.defaultState();
  }
  return descriptor.parse(raw);
}

export function patchBuiltInToolSettings<TState>(
  policy: ToolsPolicyFormState,
  descriptor: BuiltInToolSettingsDescriptor<TState>,
  state: TState
): ToolsPolicyFormState {
  return {
    ...policy,
    builtInToolSettingsByWire: {
      ...policy.builtInToolSettingsByWire,
      [descriptor.wireName]: state
    }
  };
}

export function validateBuiltInToolSettings(state: ToolsPolicyFormState): { ok: true } | { ok: false; message: string } {
  for (const d of BUILTIN_TOOL_SETTINGS_DESCRIPTORS) {
    if (!d.validate) {
      continue;
    }
    const toolState = d.parse(state.builtInToolSettingsByWire[d.wireName]);
    const result = d.validate(toolState);
    if (!result.ok) {
      return result;
    }
  }
  return { ok: true };
}
