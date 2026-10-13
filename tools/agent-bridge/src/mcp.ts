// The MCP side: a stdio server the agent starts. It lists the fixed tools itself and forwards
// every tool call to the phone; nothing else (no resources, prompts or sampling).

import { Server } from '@modelcontextprotocol/server';
import { serveStdio, type StdioServerHandle } from '@modelcontextprotocol/server/stdio';
import type { Bridge } from './bridge.js';
import { errorResult } from './bridge.js';
import { INSTRUCTIONS, TOOLS } from './tools.js';
import type { JsonObject } from './wire.js';

const TOOL_NAMES = new Set<string>(TOOLS.map((t) => t.name));

export function createMcpServer(bridge: Pick<Bridge, 'call'>, version: string): Server {
  const server = new Server(
    { name: 'watch-audio-mcp', version },
    { capabilities: { tools: {} }, instructions: INSTRUCTIONS },
  );
  server.setRequestHandler('tools/list', async () => ({ tools: TOOLS as unknown as never[] }));
  server.setRequestHandler('tools/call', async (request) => {
    const { name, arguments: args } = request.params;
    const result = TOOL_NAMES.has(name)
      ? await bridge.call(name, (args ?? {}) as JsonObject)
      : errorResult(`Unknown tool: ${name}`);
    return server.projectCallToolResult(result as never, undefined);
  });
  return server;
}

export function serveMcp(bridge: Pick<Bridge, 'call'>, version: string): StdioServerHandle {
  return serveStdio(() => createMcpServer(bridge, version), {
    onerror: (error) => process.stderr.write(`MCP: ${error.message}\n`),
  });
}
