import type { ComponentType } from 'react';

export type BuiltInToolSettingsValidation =
  | { ok: true }
  | { ok: false; message: string };

/** Per-wire {@code tools.json} → {@code builtInToolSettings.<wireName>} editor (first: SerpAPI). */
export interface BuiltInToolSettingsDescriptor<TState> {
  wireName: string;
  defaultState: () => TState;
  parse: (raw: unknown) => TState;
  serialize: (state: TState) => Record<string, unknown>;
  validate?: (state: TState) => BuiltInToolSettingsValidation;
  ConfigureDialog: ComponentType<BuiltInToolConfigureDialogProps<TState>>;
}

export interface BuiltInToolConfigureDialogProps<TState> {
  open: boolean;
  draft: TState;
  onDraftChange: (draft: TState) => void;
  onClose: () => void;
  onApply: (draft: TState) => void;
}
