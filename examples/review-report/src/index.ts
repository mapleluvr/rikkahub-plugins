import { definePlugin, type Context } from '../../../sdk/plugin/index';

async function report(context: Context) {
  const messages = await context.readMessages();
  const todos = messages.filter(message => /TODO|待办/i.test(message.text)).map(message => message.id);
  return { title: '本地审阅', messages: messages.length, todos };
}

definePlugin(api => {
  api.registerOperation('review', async (_, context) => ({
    artifacts: [{ type: 'review.report', content: await report(context) }],
  }));
  api.registerTool('review', async (_, context) => {
    const details = await report(context);
    return { content: `已审阅 ${details.messages} 条消息，找到 ${details.todos.length} 条待办记录。`, details };
  });
  api.registerPanel('main', () => ({
    type: 'column',
    children: [
      { type: 'text', text: '生成独立报告，统计消息和待办标记。查看此页面不会读取会话或调用模型。' },
      { type: 'button', text: '生成报告', operation: 'review' },
    ],
  }));
});
