import { spawn } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomBytes } from "node:crypto";
import { setTimeout as delay } from "node:timers/promises";
const children = [],
  dirs = [];
async function node(port, name) {
  const dir = mkdtempSync(join(tmpdir(), "mesh-demo-"));
  dirs.push(dir);
  const child = spawn(process.execPath, ["src/server.js"], {
    stdio: "inherit",
    env: {
      ...process.env,
      PORT: String(port),
      MESH_PORT: String(port + 1),
      MESH_HOST: "127.0.0.1",
      DATA_DIR: dir,
      MESH_DEMO: "1",
    },
  });
  children.push(child);
  let token;
  for (let n = 0; n < 60; n++) {
    try {
      token = (
        await (await fetch(`http://127.0.0.1:${port}/api/session`)).json()
      ).token;
      break;
    } catch {
      await delay(100);
    }
  }
  if (!token) throw Error("Demo node could not start");
  const api = async (path, data) => {
    const r = await fetch(`http://127.0.0.1:${port}/api/${path}`, {
      method: data ? "POST" : "GET",
      headers: { "Content-Type": "application/json", "X-Mesh-Token": token },
      body: data ? JSON.stringify(data) : undefined,
    });
    const b = await r.json();
    if (!r.ok) throw Error(b.error);
    return b;
  };
  await api("unlock", { name, password: randomBytes(24).toString("hex") });
  return { api, id: (await api("state")).identity.id, port };
}
let cleaning = false;
async function cleanup() {
  if (cleaning) return;
  cleaning = true;
  for (const c of children)
    if (c.exitCode === null && c.signalCode === null) {
      const done = new Promise((r) => c.once("exit", r));
      c.kill();
      await done;
    }
  for (const d of dirs) rmSync(d, { recursive: true, force: true });
}
process.on("SIGINT", () => cleanup().then(() => process.exit()));
process.on("SIGTERM", () => cleanup().then(() => process.exit()));
try {
  const a = await node(4320, "You · demo"),
    b = await node(4322, "Avery · demo");
  await a.api("peer", { address: "127.0.0.1:4323" });
  await b.api("peer", { address: "127.0.0.1:4321" });
  await b.api("send", {
    peer: a.id,
    text: "Hey! Made it to the trailhead. Are you nearby?",
  });
  await a.api("send", {
    peer: b.id,
    text: "Just arrived. Nice to have a connection out here.",
  });
  await b.api("send", {
    peer: a.id,
    text: "Sending over the meeting notes. Grab them whenever you’re ready.",
  });
  await b.api("file", {
    peer: a.id,
    name: "Meeting point.txt",
    data: Buffer.from(
      "Demo file: Meet by the north entrance at 10:30.\nThis file travelled between two real local Mesh processes.",
    ).toString("base64"),
  });
  console.log(
    "Interactive demo: http://127.0.0.1:4320 (two local test nodes; temporary data)",
  );
} catch (e) {
  console.error(e.message);
  await cleanup();
  process.exitCode = 1;
}
