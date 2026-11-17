import { definePlugin } from '../../../sdk/plugin/index';

definePlugin(api => {
  api.registerOperation('reduce', async (_, context) => {
    const messages = await context.readMessages();
    return { projection: { omit: messages.slice(0, -2).filter(m => m.role !== 'system').map(m => m.id) } };
  });
  api.registerPanel('main', () => ({
    type: 'column',
    children: [
      { type: 'text', text: '下一次请求保留最近两条消息和系统消息。原始会话记录仍然可见。' },
      { type: 'button', text: '应用删减', operation: 'reduce' },
    ],
  }));
});
