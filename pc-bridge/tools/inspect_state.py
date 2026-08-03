import os, sqlite3, sys

home = os.path.expanduser('~')
paths = [
    os.path.join(home, '.codex', 'state_5.sqlite'),
    os.path.join(home, '.codex', 'sqlite', 'state_5.sqlite'),
]
for path in paths:
    print('=== %s ===' % path)
    if not os.path.exists(path):
        print('missing')
        continue
    try:
        con = sqlite3.connect('file:%s?mode=ro' % path, uri=True)
        cur = con.cursor()
        cur.execute("SELECT name FROM sqlite_master WHERE type='table'")
        tables = [r[0] for r in cur.fetchall()]
        for name in tables:
            low = name.lower()
            if not any(k in low for k in ('thread', 'host', 'session')):
                continue
            print('\n-- table %s --' % name)
            cur.execute('PRAGMA table_info(%s)' % name)
            cols = [r[1] for r in cur.fetchall()]
            print('cols:', ', '.join(cols))
            try:
                cur.execute('SELECT * FROM %s LIMIT 5' % name)
                for row in cur.fetchall():
                    print(row)
            except Exception as e:
                print('select error:', e)
        con.close()
    except Exception as e:
        print('error:', e)