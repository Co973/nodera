import http from "node:http";
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { join, resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { randomBytes } from "node:crypto";
import { networkInterfaces } from "node:os";
import { Vault } from "./vault.js";
import {
  card,
  verifyCard,
  verifyEnvelope,
  seal,
  open,
  hash,
  encrypt,
  decrypt,
} from "./crypto.js";
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const port = Number(process.env.PORT || 4310),
  meshPort = Number(process.env.MESH_PORT || port + 1);
const vault = new Vault(resolve(process.env.DATA_DIR || join(root, "data")));
const blobs = join(vault.dir, "blobs");
mkdirSync(blobs, { recursive: true });
const token = randomBytes(32).toString("hex");
const chunkSize = 192 * 1024,
  maxFile = 25 * 1024 * 1024;
let state = null,
  busy = false;
const relaySeen = new Map(),
  rates = new Map();
const json = (res, value, status = 200) => {
  res.writeHead(status, {
    "Content-Type": "application/json",
    "Cache-Control": "no-store",
  });
  res.end(JSON.stringify(value));
};
async function body(req, limit = 1024 * 1024) {
  let total = 0;
  const chunks = [];
  for await (const chunk of req) {
    total += chunk.length;
    if (total > limit) throw Error("Request too large");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString() || "{}");
}
function address(value) {
  const u = new URL(value.includes("://") ? value : `http://${value}`);
  const h = u.hostname;
  if (
    u.protocol !== "http:" ||
    u.username ||
    u.password ||
    u.pathname !== "/" ||
    u.search ||
    u.hash ||
    !u.port ||
    !(
      h === "localhost" ||
      /^127\.\d+\.\d+\.\d+$/.test(h) ||
      /^10\.\d+\.\d+\.\d+$/.test(h) ||
      /^192\.168\.\d+\.\d+$/.test(h) ||
      /^172\.(1[6-9]|2\d|3[01])\.\d+\.\d+$/.test(h)
    )
  )
    throw Error(
      "Enter a private LAN IPv4 address and mesh port, e.g. 192.168.1.8:4311",
    );
  if (Number(u.port) === meshPort && (h === "localhost" || h === "127.0.0.1"))
    throw Error("That is this node");
  return u.origin;
}
async function remote(url, path, data) {
  const response = await fetch(url + path, {
    method: data ? "POST" : "GET",
    headers: data ? { "Content-Type": "application/json" } : undefined,
    body: data ? JSON.stringify(data) : undefined,
    signal: AbortSignal.timeout(5000),
    redirect: "error",
  });
  if (!response.ok) throw Error("Peer unavailable or rejected request");
  if (Number(response.headers.get("content-length") || 0) > 1024 * 1024)
    throw Error("Peer response too large");
  let length = 0;
  const parts = [];
  for await (const chunk of response.body) {
    length += chunk.length;
    if (length > 1024 * 1024) throw Error("Peer response too large");
    parts.push(chunk);
  }
  return JSON.parse(Buffer.concat(parts));
}
function save() {
  vault.save();
}
function requirePeer(id) {
  const p = state.peers.find((p) => p.id === id);
  if (!p) throw Error("Add this peer before exchanging messages");
  return p;
}
function chunkPath(h, index) {
  if (
    !/^[a-f0-9]{64}$/.test(h) ||
    !Number.isInteger(index) ||
    index < 0 ||
    index > 134
  )
    throw Error("Invalid chunk");
  return join(blobs, `${h}.${index}`);
}
function storeChunk(h, index, bytes) {
  writeFileSync(
    chunkPath(h, index),
    JSON.stringify(encrypt(vault.key, bytes, `${h}:${index}`)),
    { mode: 0o600 },
  );
}
function getChunk(h, index) {
  return decrypt(
    vault.key,
    JSON.parse(readFileSync(chunkPath(h, index), "utf8")),
    `${h}:${index}`,
  );
}
async function route(envelope, ttl = 7) {
  const header = verifyEnvelope(envelope);
  if (header.to === card(state.identity).id) {
    const peer = requirePeer(header.from.id);
    if (peer.exchange !== header.from.exchange) throw Error("Peer key changed");
    const payload = open(state.identity, envelope);
    if (payload.kind === "chunk-request") {
      const file = state.files.find(
        (f) =>
          f.hash === payload.hash &&
          f.direction === "out" &&
          f.peer === peer.id,
      );
      if (!file || payload.index >= file.chunks)
        throw Error("File not shared with this peer");
      return seal(state.identity, peer, {
        kind: "chunk",
        request: header.id,
        hash: file.hash,
        index: payload.index,
        data: getChunk(file.hash, payload.index).toString("base64"),
      });
    }
    if (!state.seen.includes(header.id)) {
      if (state.messages.length >= 10000) throw Error("Message store is full");
      if (payload.kind === "text") {
        if (
          typeof payload.text !== "string" ||
          !payload.text.trim() ||
          payload.text.length > 8000
        )
          throw Error("Invalid text");
        state.messages.push({
          id: header.id,
          peer: peer.id,
          direction: "in",
          text: payload.text,
          time: Date.now(),
          status: "delivered",
        });
      } else if (payload.kind === "file") {
        if (
          !/^[a-f0-9]{64}$/.test(payload.hash) ||
          !Number.isInteger(payload.size) ||
          payload.size < 1 ||
          payload.size > maxFile ||
          typeof payload.name !== "string" ||
          payload.name.length > 200
        )
          throw Error("Invalid file announcement");
        if (
          !state.files.some(
            (f) =>
              f.hash === payload.hash &&
              f.peer === peer.id &&
              f.direction === "in",
          )
        ) {
          if (
            state.files.length >= 1000 ||
            state.files.reduce((n, f) => n + f.size, 0) + payload.size >
              250 * 1024 * 1024
          )
            throw Error("Local file quota reached");
          state.files.push({
            hash: payload.hash,
            size: payload.size,
            name: payload.name,
            peer: peer.id,
            direction: "in",
            chunks: Math.ceil(payload.size / chunkSize),
            have: [],
            status: "offered",
          });
        }
      } else throw Error("Unsupported message");
      state.seen.push(header.id);
      state.seen = state.seen.slice(-20000);
      save();
    }
    return seal(state.identity, peer, { kind: "ack", packet: header.id });
  }
  if (ttl <= 0) return null;
  const last = relaySeen.get(header.id);
  if (last && Date.now() - last < 6000) return null;
  relaySeen.set(header.id, Date.now());
  if (relaySeen.size > 10000) relaySeen.delete(relaySeen.keys().next().value);
  const peers = [...state.peers].sort(
    (a, b) => Number(b.id === header.to) - Number(a.id === header.to),
  );
  for (const peer of peers) {
    try {
      const result = await remote(peer.address, "/mesh", {
        envelope,
        ttl: ttl - 1,
      });
      if (result.reply) return result.reply;
    } catch {}
  }
  return null;
}
async function flush() {
  if (!state || busy) return;
  busy = true;
  try {
    for (const item of [...state.outbox]) {
      if (item.envelope.expires < Date.now()) {
        state.outbox = state.outbox.filter((x) => x !== item);
        const m = state.messages.find((m) => m.id === item.envelope.id);
        if (m) m.status = "expired";
        save();
        continue;
      }
      try {
        relaySeen.delete(item.envelope.id);
        const reply = await route(item.envelope);
        if (!reply) continue;
        const peer = requirePeer(item.envelope.to);
        if (reply.from.id !== peer.id || reply.from.exchange !== peer.exchange)
          continue;
        const ack = open(state.identity, reply);
        if (ack.kind !== "ack" || ack.packet !== item.envelope.id) continue;
        state.outbox = state.outbox.filter((x) => x !== item);
        const m = state.messages.find((m) => m.id === item.envelope.id);
        if (m) m.status = "delivered";
        save();
      } catch {}
    }
  } finally {
    busy = false;
  }
}
function queue(peer, payload) {
  if (state.outbox.length >= 1000) throw Error("Outbox is full");
  const envelope = seal(state.identity, peer, payload);
  state.outbox.push({ envelope });
  return envelope;
}
const activeTransfers = new Set();
async function download(file) {
  const key = file.peer + file.hash;
  if (activeTransfers.has(key)) return;
  activeTransfers.add(key);
  file.status = "transferring";
  save();
  try {
    const peer = requirePeer(file.peer);
    for (let index = 0; index < file.chunks; index++) {
      if (file.status !== "transferring") return;
      if (file.have.includes(index)) continue;
      const request = seal(state.identity, peer, {
        kind: "chunk-request",
        hash: file.hash,
        index,
      });
      // Bulk transfer is direct LAN only. Never relay media onto a radio lane.
      const result = await remote(peer.address, "/mesh", {
        envelope: request,
        ttl: 0,
      });
      if (
        !result.reply ||
        result.reply.from.id !== peer.id ||
        result.reply.from.exchange !== peer.exchange
      )
        throw Error("Invalid chunk sender");
      const part = open(state.identity, result.reply);
      if (
        part.kind !== "chunk" ||
        part.request !== request.id ||
        part.hash !== file.hash ||
        part.index !== index
      )
        throw Error("Invalid chunk response");
      const bytes = Buffer.from(part.data, "base64");
      if (bytes.length !== Math.min(chunkSize, file.size - index * chunkSize))
        throw Error("Invalid chunk length");
      storeChunk(file.hash, index, bytes);
      file.have.push(index);
      save();
    }
    const bytes = Buffer.concat(
      Array.from({ length: file.chunks }, (_, i) => getChunk(file.hash, i)),
    );
    if (hash(bytes) !== file.hash) {
      file.have = [];
      throw Error("File integrity check failed");
    }
    file.status = "complete";
    delete file.error;
  } catch (e) {
    file.status = "paused";
    file.error = e.message;
  } finally {
    activeTransfers.delete(key);
    save();
  }
}
const mesh = http.createServer(async (req, res) => {
  try {
    if (!state) return json(res, { error: "Node locked" }, 503);
    const ip = req.socket.remoteAddress,
      now = Date.now();
    let rate = rates.get(ip);
    if (!rate || now - rate.start > 60000) {
      rate = { start: now, count: 0 };
      rates.set(ip, rate);
    }
    if (++rate.count > 240) return json(res, { error: "Rate limited" }, 429);
    if (req.method === "GET" && req.url === "/identity")
      return json(res, card(state.identity));
    if (req.method === "POST" && req.url === "/mesh") {
      const data = await body(req);
      if (!Number.isInteger(data.ttl) || data.ttl < 0 || data.ttl > 7)
        throw Error("Invalid TTL");
      return json(res, { reply: await route(data.envelope, data.ttl) });
    }
    json(res, { error: "Not found" }, 404);
  } catch (e) {
    json(res, { error: e.message }, 400);
  }
});
mesh.requestTimeout = 15000;
const ui = http.createServer(async (req, res) => {
  try {
    const expected = `127.0.0.1:${port}`;
    if (req.headers.host !== expected)
      return json(res, { error: "Invalid host" }, 403);
    if (req.headers.origin && req.headers.origin !== `http://${expected}`)
      return json(res, { error: "Invalid origin" }, 403);
    res.setHeader(
      "Content-Security-Policy",
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
    );
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("Referrer-Policy", "no-referrer");
    res.setHeader("Cache-Control", "no-store");
    if (
      req.method === "GET" &&
      ["/", "/app.js", "/style.css"].includes(req.url)
    ) {
      const file = req.url === "/" ? "index.html" : req.url.slice(1);
      res.setHeader(
        "Content-Type",
        file.endsWith(".html")
          ? "text/html"
          : file.endsWith(".css")
            ? "text/css"
            : "text/javascript",
      );
      return res.end(readFileSync(join(root, "public", file)));
    }
    if (req.method === "GET" && req.url === "/api/session")
      return json(res, { token, locked: !state, exists: vault.exists, instance: process.env.MESH_INSTANCE_ID || null });
    if (req.headers["x-mesh-token"] !== token)
      return json(res, { error: "Invalid session" }, 403);
    if (req.method === "POST" && req.url === "/api/unlock") {
      if (state) throw Error("Already unlocked");
      const data = await body(req, 4096);
      state = vault.unlock(data.password, data.name || "Explorer");
      for (const f of state.files)
        if (f.status === "transferring") f.status = "paused";
      return json(res, { ok: true });
    }
    if (!state) return json(res, { error: "Unlock your node first" }, 423);
    if (req.method === "GET" && req.url === "/api/state")
      return json(res, {
        demo: process.env.MESH_DEMO === "1",
        identity: card(state.identity),
        peers: state.peers,
        messages: state.messages,
        files: state.files,
        outbox: state.outbox.length,
        meshPort,
        addresses:
          process.env.MESH_HOST === "127.0.0.1"
            ? [`127.0.0.1:${meshPort}`]
            : Object.values(networkInterfaces())
                .flat()
                .filter((i) => i.family === "IPv4" && !i.internal)
                .map((i) => `${i.address}:${meshPort}`),
      });
    if (req.method !== "POST") return json(res, { error: "Not found" }, 404);
    const data = await body(
      req,
      req.url === "/api/file" ? 36 * 1024 * 1024 : 1024 * 1024,
    );
    if (req.url === "/api/peer") {
      if (state.peers.length >= 100) throw Error("Peer limit reached");
      const url = address(data.address);
      const p = verifyCard(await remote(url, "/identity"));
      if (p.id === card(state.identity).id) throw Error("Cannot add yourself");
      const existing = state.peers.find((p) => p.address === url);
      if (existing && existing.id !== p.id)
        throw Error(
          "Identity changed at this address. Verify it out of band before using another address.",
        );
      const pinned = state.peers.find((x) => x.id === p.id);
      if (pinned && pinned.exchange !== p.exchange)
        throw Error("Pinned key changed");
      if (pinned) pinned.address = url;
      else state.peers.push({ ...p, address: url, verified: false });
      save();
      void flush();
      return json(res, { ok: true });
    }
    if (req.url === "/api/verify") {
      requirePeer(data.peer).verified = Boolean(data.verified);
      save();
      return json(res, { ok: true });
    }
    if (req.url === "/api/send") {
      const peer = requirePeer(data.peer);
      if (state.messages.length >= 10000) throw Error("Message store is full");
      if (
        typeof data.text !== "string" ||
        !data.text.trim() ||
        data.text.length > 8000
      )
        throw Error("Text must be 1–8,000 characters");
      const e = queue(peer, { kind: "text", text: data.text.trim() });
      state.messages.push({
        id: e.id,
        peer: peer.id,
        text: data.text.trim(),
        direction: "out",
        status: "queued",
        time: Date.now(),
      });
      save();
      void flush();
      return json(res, { ok: true });
    }
    if (req.url === "/api/file") {
      const peer = requirePeer(data.peer);
      if (
        typeof data.name !== "string" ||
        data.name.length > 200 ||
        typeof data.data !== "string"
      )
        throw Error("Invalid file");
      const bytes = Buffer.from(data.data, "base64");
      if (!bytes.length || bytes.length > maxFile)
        throw Error("Choose a file between 1 byte and 25 MB");
      if (
        state.files.reduce((n, f) => n + f.size, 0) + bytes.length >
        250 * 1024 * 1024
      )
        throw Error("Local file quota reached");
      const h = hash(bytes),
        chunks = Math.ceil(bytes.length / chunkSize);
      for (let i = 0; i < chunks; i++)
        storeChunk(h, i, bytes.subarray(i * chunkSize, (i + 1) * chunkSize));
      if (
        !state.files.some(
          (f) => f.hash === h && f.peer === peer.id && f.direction === "out",
        )
      )
        state.files.push({
          hash: h,
          name: data.name,
          size: bytes.length,
          peer: peer.id,
          chunks,
          direction: "out",
          status: "shared",
          have: Array.from({ length: chunks }, (_, i) => i),
        });
      queue(peer, {
        kind: "file",
        hash: h,
        name: data.name,
        size: bytes.length,
      });
      save();
      void flush();
      return json(res, { ok: true });
    }
    if (req.url === "/api/transfer") {
      const f = state.files.find(
        (f) =>
          f.hash === data.hash && f.peer === data.peer && f.direction === "in",
      );
      if (!f) throw Error("Unknown transfer");
      if (data.action === "pause") {
        f.status = "paused";
        save();
      } else if (data.action === "decline") {
        f.status = "declined";
        save();
      } else if (data.action === "accept") {
        if (f.status !== "complete") void download(f);
      } else if (data.action === "save") {
        if (f.status !== "complete") throw Error("File is incomplete");
        const bytes = Buffer.concat(
          Array.from({ length: f.chunks }, (_, i) => getChunk(f.hash, i)),
        );
        if (hash(bytes) !== f.hash) throw Error("Integrity check failed");
        res.setHeader("Content-Type", "application/octet-stream");
        return res.end(bytes);
      } else throw Error("Invalid action");
      return json(res, { ok: true });
    }
    json(res, { error: "Not found" }, 404);
  } catch (e) {
    json(res, { error: e.message }, 400);
  }
});
ui.requestTimeout = 30000;
mesh.listen(meshPort, process.env.MESH_HOST || "0.0.0.0", () =>
  console.log(`Mesh LAN port ${meshPort}`),
);
ui.listen(port, "127.0.0.1", () =>
  console.log(`Mesh Chat: http://127.0.0.1:${port}`),
);
setInterval(() => void flush(), 10000).unref();
setInterval(() => {
  for (const [ip, r] of rates)
    if (Date.now() - r.start > 60000) rates.delete(ip);
}, 60000).unref();
