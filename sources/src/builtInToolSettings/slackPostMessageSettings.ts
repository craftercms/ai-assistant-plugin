import type { BuiltInToolSettingsDescriptor, BuiltInToolSettingsValidation } from './types';
import SlackPostMessageConfigureDialog from './SlackPostMessageConfigureDialog';

export const SLACK_POST_MESSAGE_WIRE = 'SlackPostMessage';

/** Site defaults for SlackPostMessage (bot token is on Project Tools → Secrets). */
export interface SlackPostMessageSettingsFormState {
  defaultChannel: string;
  secretKey: string;
}

export function defaultSlackPostMessageSettingsFormState(): SlackPostMessageSettingsFormState {
  return {
    defaultChannel: '',
    secretKey: ''
  };
}

function parseSlackSettingsFromUnknown(raw: unknown): SlackPostMessageSettingsFormState {
  const base = defaultSlackPostMessageSettingsFormState();
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    return base;
  }
  const o = raw as Record<string, unknown>;
  const defaults =
    o.defaults && typeof o.defaults === 'object' && !Array.isArray(o.defaults)
      ? (o.defaults as Record<string, unknown>)
      : {};
  return {
    defaultChannel:
      defaults.defaultChannel != null ? String(defaults.defaultChannel) : base.defaultChannel,
    secretKey: o.secretKey != null ? String(o.secretKey) : base.secretKey
  };
}

function slackSettingsToJsonObject(state: SlackPostMessageSettingsFormState): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  const secretKey = state.secretKey.trim();
  if (secretKey) {
    out.secretKey = secretKey;
  }
  const ch = state.defaultChannel.trim();
  if (ch) {
    out.defaults = { defaultChannel: ch };
  }
  return out;
}

function validateSlackPostMessageSettings(
  state: SlackPostMessageSettingsFormState
): BuiltInToolSettingsValidation {
  const sk = state.secretKey.trim();
  if (sk && !/^[a-z][a-z0-9_]*$/i.test(sk)) {
    return { ok: false, message: 'Slack secretKey must be a simple secrets.json key (letters, numbers, underscores).' };
  }
  return { ok: true };
}

export const slackPostMessageSettingsDescriptor: BuiltInToolSettingsDescriptor<SlackPostMessageSettingsFormState> =
  {
    wireName: SLACK_POST_MESSAGE_WIRE,
    defaultState: defaultSlackPostMessageSettingsFormState,
    parse: parseSlackSettingsFromUnknown,
    serialize: slackSettingsToJsonObject,
    validate: validateSlackPostMessageSettings,
    ConfigureDialog: SlackPostMessageConfigureDialog
  };
