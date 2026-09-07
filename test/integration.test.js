import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { hash, seal } from "../src/crypto.js";
import { Vault } from "../src/vault.js";
test(
  "three nodes: offline queue, relay delivery, dedup, accepted file, resume and restart",
  { timeout: 60000 },
  async () => {
    const base = 20000 + Math.floor(Math.random() * 15000),
      dirs = [],
      children = [];
    async function start(index) {
      const port = base + index * 2,
        dir = dirs[index] || mkdtempSync(join(tmpdir(), "mesh-node-"));
      dirs[index] = dir;
      const child = spawn(process.execPath, ["src/server.js"], {
        env: {
          ...process.env,
          PORT: String(port),
          MESH_PORT: String(port + 1),
          MESH_HOST: "127.0.0.1",
          DATA_DIR: dir,
        },
        stdio: "pipe",
      });
      children.push(child);
      let logs = "";
      child.stderr.on("data", (d) => (logs += d));
      child.stdout.on("data", (d) => (logs += d));
      let token;
      for (let i = 0; i < 100; i++) {
        try {
          token = (
            await (await fetch(`http://127.0.0.1:${port}/api/session`)).json()
          ).token;
          break;
        } catch {
          await delay(50);
        }
      }
      assert.ok(token, logs);
      const api = async (path, data, raw = false) => {
        const r = await fetch(`http://127.0.0.1:${port}/api/${path}`, {
          method: data ? "POST" : "GET",
          headers: {
            "Content-Type": "application/json",
            "X-Mesh-Token": token,
          },
          body: data ? JSON.stringify(data) : undefined,
        });
        if (raw) return r;
        const value = await r.json();
        assert.equal(r.status, 200, JSON.stringify(value));
        return value;
      };
      await api("unlock", {
        name: ["Alice", "Relay", "Bob"][index],
        password: "integration test passphrase",
      });
      return {
        api,
        child,
        port,
        address: `127.0.0.1:${port + 1}`,
        id: (await api("state")).identity.id,
      };
    }
    async function eventually(check) {
      for (let i = 0; i < 150; i++) {
        if (await check()) return;
        await delay(150);
      }
      assert.fail("Expected state not reached");
    }
    async function stop(n) {
      if (n.child.exitCode === null && n.child.signalCode === null) {
        const done = new Promise((r) => n.child.once("exit", r));
        n.child.kill();
        await done;
      }
    }
    try {
      const a = await start(0),
        relay = await start(1);
      let b = await start(2);
      await a.api("peer", { address: b.address });
      await b.api("peer", { address: a.address });
      await a.api("peer", { address: relay.address });
      await relay.api("peer", { address: b.address });
      const invalid = await fetch(`http://127.0.0.1:${a.port}/api/send`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: "{}",
      });
      assert.equal(invalid.status, 403);
      const origin = await fetch(`http://127.0.0.1:${a.port}/api/session`, {
        headers: { Origin: "https://evil.example" },
      });
      assert.equal(origin.status, 403);
      await stop(b);
      await a.api("send", { peer: b.id, text: "queued while offline" });
      assert.equal((await a.api("state")).outbox, 1);
      b = await start(2);
      await eventually(
        async () => (await b.api("state")).messages.length === 1,
      );
      await eventually(async () => (await a.api("state")).outbox === 0);
      // Pin Bob's address to a stopped listener. The relay still knows his real address.
      // A local test-only state fixture is unnecessary: stop direct delivery using a proxy that
      // exposes identity but refuses mesh requests, then use the real relay route.
      const http = await import("node:http");
      const proxyPort = base + 8;
      const bobCard = (await b.api("state")).identity;
      const proxy = http.createServer((req, res) => {
        if (req.url === "/identity") {
          res.setHeader("Content-Type", "application/json");
          res.end(JSON.stringify(bobCard));
        } else {
          res.writeHead(503);
          res.end();
        }
      });
      await new Promise((r) => proxy.listen(proxyPort, "127.0.0.1", r));
      try {
        await a.api("peer", { address: `127.0.0.1:${proxyPort}` });
        await a.api("send", { peer: b.id, text: "through the relay" });
        await eventually(async () => (await a.api("state")).outbox === 0);
        assert.equal(
          (await b.api("state")).messages.filter(
            (m) => m.text === "through the relay",
          ).length,
          1,
        );
      } finally {
        await new Promise((r) => proxy.close(r));
      }
      await a.api("peer", { address: b.address });
      const bytes = Buffer.alloc(450000, 42);
      await a.api("file", {
        peer: b.id,
        name: "test.bin",
        data: bytes.toString("base64"),
      });
      await eventually(async () => (await b.api("state")).files.length === 1);
      let f = (await b.api("state")).files[0];
      assert.equal(f.status, "offered");
      assert.deepEqual(f.have, []);
      await stop(a);
      await b.api("transfer", { peer: a.id, hash: f.hash, action: "accept" });
      await eventually(
        async () => (await b.api("state")).files[0].status === "paused",
      );
      // Replay an identical valid envelope: one stored message, two valid delivery replies.
      const sender = new Vault(dirs[0]).unlock(
        "integration test passphrase",
      ).identity;
      const repeated = seal(sender, (await b.api("state")).identity, {
        kind: "text",
        text: "replay once only",
      });
      for (let i = 0; i < 2; i++) {
        const r = await fetch(`http://${b.address}/mesh`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ envelope: repeated, ttl: 0 }),
        });
        assert.equal(r.status, 200);
        assert.ok((await r.json()).reply);
      }
      assert.equal(
        (await b.api("state")).messages.filter(
          (m) => m.text === "replay once only",
        ).length,
        1,
      );
      const a2 = await start(0);
      assert.equal(a2.id, a.id);
      // Drop the link after the first piece, restart the receiver, then fetch only missing pieces.
      let chunkRequests = 0;
      const aliceCard = (await a2.api("state")).identity;
      const chunkProxy = http.createServer(async (req, res) => {
        if (req.url === "/identity") {
          res.end(JSON.stringify(aliceCard));
          return;
        }
        if (++chunkRequests > 1) {
          res.writeHead(503);
          res.end();
          return;
        }
        const parts = [];
        for await (const part of req) parts.push(part);
        const upstream = await fetch(`http://${a2.address}/mesh`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: Buffer.concat(parts),
        });
        res.writeHead(upstream.status);
        res.end(Buffer.from(await upstream.arrayBuffer()));
      });
      await new Promise((r) => chunkProxy.listen(base + 9, "127.0.0.1", r));
      try {
        await b.api("peer", { address: `127.0.0.1:${base + 9}` });
        await b.api("transfer", { peer: a.id, hash: f.hash, action: "accept" });
        await eventually(async () => {
          const file = (await b.api("state")).files[0];
          return file.status === "paused" && file.have.length === 1;
        });
      } finally {
        await new Promise((r) => chunkProxy.close(r));
      }
      await stop(b);
      b = await start(2);
      assert.deepEqual((await b.api("state")).files[0].have, [0]);
      await b.api("peer", { address: a2.address });
      await b.api("transfer", { peer: a.id, hash: f.hash, action: "accept" });
      await eventually(
        async () => (await b.api("state")).files[0].status === "complete",
      );
      const response = await b.api(
        "transfer",
        { peer: a.id, hash: f.hash, action: "save" },
        true,
      );
      assert.equal(response.status, 200);
      assert.equal(
        hash(Buffer.from(await response.arrayBuffer())),
        hash(bytes),
      );
      await stop(b);
      b = await start(2);
      assert.equal((await b.api("state")).files[0].status, "complete");
      assert.equal((await b.api("state")).messages.length, 3);
    } finally {
      for (const child of children)
        if (child.exitCode === null && child.signalCode === null) {
          const done = new Promise((r) => child.once("exit", r));
          child.kill();
          await done;
        }
      for (const dir of dirs) rmSync(dir, { recursive: true, force: true });
    }
  },
);
