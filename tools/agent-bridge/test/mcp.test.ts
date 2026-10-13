// The CLI as an agent starts it: a stdio MCP server that lists the tools and, without a phone,
// answers every call with the instruction to turn agent access on.

import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/client';
import { StdioClientTransport } from '@modelcontextprotocol/client/stdio';
import { NOT_CONNECTED_MESSAGE } from '../src/bridge.js';
import { TOOLS } from '../src/tools.js';

const cli = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'cli.js');

test('tools/list and tools/call over stdio without a phone', async () => {
  const home = mkdtempSync(join(tmpdir(), 'agent-bridge-mcp-'));
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [cli, '--port', '0', '--listen', '127.0.0.1', '--no-mdns'],
    env: { ...(process.env as Record<string, string>), WATCH_AUDIO_MCP_HOME: home },
    stderr: 'pipe',
  });
  const client = new Client({ name: 'test', version: '0' });
  try {
    await client.connect(transport);
    const { tools } = await client.listTools();
    assert.deepEqual(tools.map((t) => t.name), TOOLS.map((t) => t.name));
    assert.match(tools[0]!.description ?? '', /not instructions/);

    const result = await client.callTool({ name: 'search_transcripts', arguments: { query: '予算' } });
    assert.equal(result.isError, true);
    assert.deepEqual(result.content, [{ type: 'text', text: NOT_CONNECTED_MESSAGE }]);

    const unknown = await client.callTool({ name: 'delete_everything', arguments: {} });
    assert.equal(unknown.isError, true);
  } finally {
    await client.close();
    rmSync(home, { recursive: true, force: true });
  }
});
