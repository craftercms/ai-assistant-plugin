'use strict';
import { take, takeUntil } from 'rxjs';
import {
  Editor,
} from 'tinymce';
import { aiAssistantClosedMessageId, llmAssistantMessageId, popoverWidgetId } from './consts';
import type { AiAssistantPopoverProps } from './AiAssistantPopover';

export type AiAssistantMessage = {
  role: string;
  content: string;
};

export interface CrafterCMSAiAssistantConfig {
  strings?: {
    /** Toolbar tooltip for the main “open assistant” button (`aiAssistantOpen`). */
    llmAssistant?: string;
    /** Toolbar tooltip for the shortcuts menu (`aiAssistantShortcuts`). */
    aiAssistantShortcuts?: string;
  };
  prependMessages?: AiAssistantMessage[];
  shortcuts?: Array<{
    label: string;
    messages?: AiAssistantMessage[];
    shortcuts?: { label: string; messages: AiAssistantMessage[] }[];
  }>;
  /** Opens the AI Assistant with the built message list (selection/context). */
  onOpenAssistant?: (editor: Editor, api: unknown, messages: AiAssistantMessage[]) => void;
  onShortcutClick?: (editor: Editor, api: unknown, messages: AiAssistantMessage[]) => void;
  emptyStateOptions?: unknown;
  AiAssistantPopoverProps?: Partial<AiAssistantPopoverProps>;
}

const BASE_CONFIG: Partial<CrafterCMSAiAssistantConfig> = {
  strings: {
    llmAssistant: 'Open AI Assistant',
    aiAssistantShortcuts: 'AI Shortcuts'
  },
  prependMessages: [],
  shortcuts: []
};

const craftercms: any = (window as any).craftercms;
const tinymce: any = (window as any).tinymce;
const xb: any = craftercms?.xb;
const isXb = Boolean(xb);

const pluginManager = tinymce.util.Tools.resolve('tinymce.PluginManager');

const alert = (editor, message) => {
  editor.windowManager.alert(message);
};

const setContent = (editor, html) => {
  // editor.focus();
  // editor.undoManager.transact(() => {
  //   editor.setContent(html);
  // });
  // editor.selection.setCursorLocation();
  // editor.nodeChanged();
  // editor.setContent(html);
  editor.insertContent(html);
};

const getSource = (editor) => {
  return editor.getContent({ source_view: true });
};

const getContent = (editor) => {
  return editor.getContent({ format: 'text' });
};

const getSelection = (editor) => {
  return editor.selection.getContent({ format: 'text' });
};

const handleChatActionClick = (editor: Editor, id: string, content: string) => {
  switch (id) {
    case 'insert':
      // Don't see a way of avoiding the editor regaining the focus using "insertContent".
      // editor.insertContent(content, { no_events: true, focus: false });
      // Hence, using "setContent" instead.
      editor.selection.setContent(content);
      break;
  }
};

const tellStudioToOpenAssistant = (editor, props) => {
  xb.post(llmAssistantMessageId, props);
  xb.fromTopic(aiAssistantClosedMessageId)
    .pipe(take(1))
    .subscribe(() => {
      setTimeout(() => editor.focus());
    });
};

const createDefaultHandler = (config) => {
  return (editor, api, messages) => {
    if (!isXb) {
      const site = craftercms.getStore().getState().sites.active;
      craftercms.services.plugin
        .importPlugin(site, 'aiassistant', 'components', 'index.js', 'org.craftercms.aiassistant.studio')
        .then((plugin) => {
          const store = craftercms?.getStore?.();
          const st = store?.getState?.();
          const rawUser = st?.user?.username;
          const userName =
            (typeof rawUser === 'string' && rawUser.trim()) || (rawUser != null && String(rawUser).trim()) || 'anonymous';
          const container = document.createElement('div');
          const root = craftercms.libs.ReactDOMClient.createRoot(container);
          const AiAssistantPopover: any = plugin.widgets[popoverWidgetId]; // Same as craftercms.utils.constants.components.get('...');
          const CrafterRoot: any = craftercms.utils.constants.components.get('craftercms.components.CrafterCMSNextBridge');
          root.render(
            <CrafterRoot>
              <AiAssistantPopover
                open
                onClose={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                  root.unmount();
                  container.remove();
                }}
                {...config.AiAssistantPopoverProps}
                AiAssistantProps={{
                  userName,
                  emptyStateOptions: config.emptyStateOptions,
                  initialMessages: messages,
                  extraActions: [{ label: 'Insert', id: 'insert' }],
                  onExtraActionClick: ((e, id, content, api) => {
                    handleChatActionClick(editor, id, content);
                  }) as any
                }}
              />
            </CrafterRoot>
          );
        });
    } else {
      tellStudioToOpenAssistant(editor, {
        ...config.AiAssistantPopoverProps,
        AiAssistantProps: {
          ...config.AiAssistantPopoverProps?.AiAssistantProps,
          emptyStateOptions: config.emptyStateOptions,
          initialMessages: messages,
          extraActions: [{ label: 'Insert', id: 'insert' }]
        }
      });
    }
  };
};

pluginManager.add('craftercms_aiassistant', function (editor: Editor) {
  const configArg = editor.getParam('craftercms_aiassistant') as CrafterCMSAiAssistantConfig | undefined;
  const mergedStrings = {
    ...BASE_CONFIG.strings,
    ...configArg?.strings
  };
  const instanceConfig: CrafterCMSAiAssistantConfig & { strings: NonNullable<CrafterCMSAiAssistantConfig['strings']> } = {
    ...BASE_CONFIG,
    ...configArg,
    strings: {
      ...mergedStrings,
      llmAssistant: mergedStrings.llmAssistant ?? 'Open AI Assistant',
      aiAssistantShortcuts: mergedStrings.aiAssistantShortcuts ?? 'AI Shortcuts'
    }
  };

  const userOpen = configArg?.onOpenAssistant;
  if (!userOpen || !configArg?.onShortcutClick) {
    const defaultHandler = createDefaultHandler(instanceConfig);
    instanceConfig.onOpenAssistant = defaultHandler;
    instanceConfig.onShortcutClick = defaultHandler;
  } else {
    instanceConfig.onOpenAssistant = userOpen;
    instanceConfig.onShortcutClick = configArg.onShortcutClick;
  }

  editor.ui.registry.addButton('aiAssistantOpen', {
    icon: 'ai',
    tooltip: instanceConfig.strings.llmAssistant,
    onAction(api) {
      const content = getSelection(editor).trim() || getContent(editor);
      const messages: AiAssistantMessage[] = [...instanceConfig.prependMessages].map((item) => ({
        ...item,
        content: item.content.replace('{context}', content)
      }));
      const selection = getSelection(editor);
      if (selection) {
        messages.push({ role: 'system', content: `Context: ${selection}` });
      }
      instanceConfig.onOpenAssistant!(editor, api, messages);
    }
  });
  const registerShortcutsMenuButton = (buttonId: string) => {
    editor.ui.registry.addMenuButton(buttonId, {
      icon: 'ai-prompt',
      tooltip: instanceConfig.strings.aiAssistantShortcuts,
      fetch(callback) {
        const onAction = (api: any, item: any) => {
          const content = getSelection(editor).trim() || getContent(editor);
          const messages: AiAssistantMessage[] = [...instanceConfig.prependMessages, ...(item.messages ?? [])].map((item) => ({
            ...item,
            content: item.content.replace('{context}', content)
          }));
          instanceConfig.onShortcutClick(editor, api, messages);
        };
        const mapper = (shortcut: any) => {
          const isNested = 'shortcuts' in shortcut;
          return {
            type: isNested ? 'nestedmenuitem' : 'menuitem',
            text: shortcut.label,
            icon: shortcut.icon,
            ...(isNested
              ? { getSubmenuItems: () => shortcut.shortcuts.map(mapper) }
              : { onAction: (api) => onAction(api, shortcut) })
          };
        };
        callback(instanceConfig.shortcuts.map(mapper));
      }
    });
  };
  registerShortcutsMenuButton('aiassistantShortcuts');
  editor.ui.registry.addSplitButton('aiassistant', {
    icon: 'aiassistant',
    tooltip: 'Open AI',
    fetch(callback) {
      const mapper = (shortcut, index, collection, parent) => {
        const hasChildren = 'shortcuts' in shortcut;
        return hasChildren
          ? shortcut.shortcuts.map((a, b, c) => mapper(a, b, c, shortcut))
          : {
              type: 'choiceitem',
              text: parent ? `${parent.label}: ${shortcut.label}` : shortcut.label,
              icon: shortcut.icon,
              value: shortcut // instanceConfig.shortcuts[index] === shortcut ? `index` : ``
            };
      };
      callback(instanceConfig.shortcuts.flatMap(mapper));
    },
    onAction(api) {
      const content = getSelection(editor).trim() || getContent(editor);
      const messages: AiAssistantMessage[] = [...instanceConfig.prependMessages].map((item) => ({
        ...item,
        content: item.content.replace('{context}', content)
      }));
      instanceConfig.onOpenAssistant!(editor, api, messages);
    },
    onItemAction(api, item: any) {
      const content = getSelection(editor).trim() || getContent(editor);
      const messages: AiAssistantMessage[] = [...instanceConfig.prependMessages, ...(item.messages ?? [])].map((item) => ({
        ...item,
        content: item.content.replace('{context}', content)
      }));
      instanceConfig.onShortcutClick(editor, api, messages);
    }
  });

  return {};
});
