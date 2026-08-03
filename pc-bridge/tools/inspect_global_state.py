import json, os

path = os.path.join(os.path.expanduser('~'), '.codex', '.codex-global-state.json')
data = json.load(open(path, encoding='utf-8'))

def walk(obj, path=''):
    if isinstance(obj, dict):
        for k, v in obj.items():
            key = k.lower()
            if 'host' in key or k in ('local', 'remote'):
                print('KEY %s.%s = %r' % (path, k, v)[:400])
            walk(v, path + '.' + k)
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            walk(v, '%s[%d]' % (path, i))

walk(data)