export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
export interface ContextMessage { id: string; role: string; text: string }
export interface Context { readMessages(): Promise<ContextMessage[]> }
export interface OperationResult {
  projection?: { omit: string[] };
  artifacts?: { type: string; content: Json }[];
}
export interface ToolResult { content: string; details?: Record<string, Json> }
export interface UiNode {
  type: 'text' | 'markdown' | 'column' | 'button';
  text?: string;
  children?: UiNode[];
  operation?: string;
  input?: Record<string, Json>;
}
export interface PluginApi {
  registerOperation(id: string, handler: (input: Json, context: Context) => OperationResult | Promise<OperationResult>): void;
  registerTool(id: string, handler: (input: Json, context: Context) => ToolResult | Promise<ToolResult>): void;
  registerPanel(id: string, render: (input: Json) => UiNode | Promise<UiNode>): void;
}

/** One bundled factory; registration is synchronous and cannot access conversation data. */
export function definePlugin(factory: (api: PluginApi) => void): void {
  (globalThis as typeof globalThis & { rikkaPlugin: typeof factory }).rikkaPlugin = factory;
}
