"""v3curve 模拟的 MCP 客户端（每次运行重新握手，不依赖旧会话）。"""

import json
import urllib.request

MCP = 'http://127.0.0.1:5725/mcp'


def _post(body, sid=None):
    h = {'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream'}
    if sid:
        h['Mcp-Session-Id'] = sid
    req = urllib.request.Request(MCP, data=json.dumps(body).encode(), headers=h)
    r = urllib.request.urlopen(req, timeout=600)
    raw = r.read().decode()
    return dict(r.headers), raw


def handshake():
    h, _ = _post({
        'jsonrpc': '2.0', 'id': 1, 'method': 'initialize',
        'params': {'protocolVersion': '2024-11-05', 'capabilities': {},
                   'clientInfo': {'name': 'v3curve', 'version': '1'}},
    })
    sid = h.get('Mcp-session-id') or h.get('Mcp-Session-Id')
    _post({'jsonrpc': '2.0', 'method': 'notifications/initialized'}, sid)
    return sid


def call(sid, name, args):
    """返回工具结果的文本（JSON 字符串）。"""
    _, raw = _post({'jsonrpc': '2.0', 'id': 9, 'method': 'tools/call',
                    'params': {'name': name, 'arguments': args}}, sid)
    d = [l[6:] for l in raw.splitlines() if l.startswith('data: ')]
    o = json.loads(d[-1])
    r = o.get('result', {})
    txt = ' '.join(c.get('text', '') for c in r.get('content', []))
    if r.get('isError'):
        raise RuntimeError(f'{name} 报错: {txt[:500]}')
    return txt


def call_json(sid, name, args):
    return json.loads(call(sid, name, args))
