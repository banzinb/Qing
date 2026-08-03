import { createRequire } from 'node:module';
import { join } from 'node:path';
import { homedir } from 'node:os';

const require = createRequire(import.meta.url);
const Database = require(join(process.env.TEMP || 'C:/Users/0000/AppData/Local/Temp', 'codex-asar-extract', 'node_modules', 'better-sqlite3'));

const candidates = [
  join(homedir(), '.codex', 'state_5.sqlite'),
  join(homedir(), '.codex', 'sqlite', 'state_5.sqlite'),
];

for (const path of candidates) {
  console.log('=== ' + path + ' ===');
  try {
    const db = new Database(path, { readonly: true });
    const tables = db.prepare("SELECT name FROM sqlite_master WHERE type='table'").all();
    for (const { name } of tables) {
      if (!/thread|host|session/i.test(name)) continue;
      console.log('\n-- table ' + name + ' --');
      const cols = db.prepare(`PRAGMA table_info(${name})`).all().map((c) => c.name);
      console.log('cols:', cols.join(', '));
      const rows = db.prepare(`SELECT * FROM ${name} LIMIT 5`).all();
      for (const row of rows) console.log(JSON.stringify(row));
    }
    db.close();
  } catch (error) {
    console.log('error:', error.message);
  }
}