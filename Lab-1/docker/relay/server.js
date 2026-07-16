// Control relay: routes remote-control commands from dashboards to kiosk
// devices over WebSocket. Kiosks and dashboards both connect OUT to this server
// (on the SRS host), so the dashboard controls kiosks via the server IP — no
// need to reach each device directly.
//
//   Device connects:    ws://<server>:8092/?role=device&id=<deviceId>
//   Dashboard connects:  ws://<server>:8092/?role=dashboard
//
// Dashboard -> device: send JSON { to:<deviceId>, action:"tap", x, y, ... }
// Device -> dashboards: any message a device sends is relayed to all dashboards
// (acks/status). The relay also pushes { type:"devices", devices:[...] } to
// dashboards whenever the connected-device set changes.

const http = require('http');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 8092;
const devices = new Map();     // deviceId -> ws
const dashboards = new Set();  // ws

const server = http.createServer((req, res) => {
  if (req.url.startsWith('/devices')) {
    res.writeHead(200, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' });
    res.end(JSON.stringify({ devices: [...devices.keys()] }));
  } else {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('control-relay ok');
  }
});

const wss = new WebSocketServer({ server });

function deviceList() {
  return JSON.stringify({ type: 'devices', devices: [...devices.keys()] });
}
function pushDevices() {
  const payload = deviceList();
  for (const d of dashboards) safeSend(d, payload);
}
function safeSend(ws, data) {
  try { if (ws.readyState === ws.OPEN) ws.send(data); } catch (_) {}
}

wss.on('connection', (ws, req) => {
  const params = new URL(req.url, 'http://x').searchParams;
  const role = params.get('role');
  const id = params.get('id');

  if (role === 'device' && id) {
    const prev = devices.get(id);
    if (prev && prev !== ws) safeSend(prev, JSON.stringify({ type: 'replaced' }));
    devices.set(id, ws);
    console.log(`device connected: ${id} (total ${devices.size})`);
    pushDevices();
    ws.on('message', (m) => { for (const d of dashboards) safeSend(d, m.toString()); });
    ws.on('close', () => {
      if (devices.get(id) === ws) { devices.delete(id); console.log(`device left: ${id}`); pushDevices(); }
    });
  } else if (role === 'dashboard') {
    dashboards.add(ws);
    safeSend(ws, deviceList());
    ws.on('message', (m) => {
      let msg; try { msg = JSON.parse(m.toString()); } catch (_) { return; }
      const dev = devices.get(msg.to);
      if (dev) safeSend(dev, JSON.stringify(msg));
      else safeSend(ws, JSON.stringify({ type: 'error', error: 'device offline', to: msg.to }));
    });
    ws.on('close', () => dashboards.delete(ws));
  } else {
    ws.close();
  }
});

server.listen(PORT, () => console.log(`control relay listening on :${PORT}`));
