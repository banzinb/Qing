import { readFileSync } from 'node:fs';
import { parseArgs } from '../lib/bridge-core.mjs';
import { withCodexIpc } from '../lib/codex-ipc.mjs';

const args = parseArgs(process.argv.slice(2));
const method = args.method || 'initialize';
let params = {};
if (args.paramsFile) {
  params = JSON.parse(readFileSync(args.paramsFile, 'utf8'));
} else if (process.env.IPC_PARAMS) {
  params = JSON.parse(process.env.IPC_PARAMS);
} else if (args.params) {
  params = JSON.parse(args.params);
}
const version = Number(args.version || 0);
const timeoutMs = Number(args.timeout || 90000);
const broadcast = Boolean(args.broadcast);

await withCodexIpc(async (client) => {
  console.log(JSON.stringify({ initialized: true, clientId: client.clientId }, null, 2));
  if (broadcast) {
    client.sendBroadcast(method, params, { version, timeoutMs });
    console.log(JSON.stringify({ broadcast: method, params }, null, 2));
    return;
  }
  const response = await client.sendRequest(method, params, { version, timeoutMs });
  console.log(JSON.stringify(response, null, 2));
}, { clientType: args.clientType || process.env.IPC_CLIENT_TYPE || 'aether-pc-bridge' }).catch((error) => {
  console.error('IPC probe failed:', error.message);
  process.exit(1);
});