import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { identity, card, seal, open, verifyCard } from "../src/crypto.js";
import { Vault } from "../src/vault.js";
import { encodePacket, decodePacket } from "../src/packet.js";
test("sealed messages authenticate sender and recipient; tampering fails", () => {
  const a = identity("Alice"),
    b = identity("Bob"),
    c = identity("Carol"),
    e = seal(a, card(b), { text: "secret" });
  assert.deepEqual(open(b, e), { text: "secret" });
  assert.throws(() => open(c, e));
  assert.throws(() => open(b, { ...e, to: card(c).id }));
  assert.throws(() =>
    open(b, {
      ...e,
      box: { ...e.box, data: Buffer.from("tampered").toString("base64") },
    }),
  );
  assert.throws(() => verifyCard({ ...card(a), exchange: card(c).exchange }));
  assert.notEqual(seal(a, card(b), { text: "secret" }).box.data, e.box.data);
});
test("vault keeps private material encrypted and rejects wrong passphrase", () => {
  const dir = mkdtempSync(join(tmpdir(), "mesh-vault-"));
  try {
    const v = new Vault(dir),
      s = v.unlock("a long test password", "Alice");
    s.messages.push({ text: "hidden message" });
    v.save();
    const disk = readFileSync(v.file, "utf8");
    assert.ok(!disk.includes("hidden message"));
    assert.ok(!disk.includes("PRIVATE KEY"));
    assert.throws(() => new Vault(dir).unlock("incorrect password"));
    assert.equal(
      new Vault(dir).unlock("a long test password").messages[0].text,
      "hidden message",
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
test("39-byte wire codec round trips and rejects malformed lengths and TTLs", () => {
  const p = {
    type: 2,
    ttl: 7,
    senderId: "aa".repeat(8),
    packetId: "bb".repeat(16),
    timestamp: 123,
    payload: Buffer.from("payload"),
  };
  const bytes = encodePacket(p);
  assert.equal(bytes.length, 46);
  assert.deepEqual(decodePacket(bytes), p);
  assert.throws(() => decodePacket(bytes.subarray(0, 40)));
  assert.throws(() => encodePacket({ ...p, ttl: 8 }));
  for (let i = 0; i < 39; i++)
    assert.throws(() => decodePacket(bytes.subarray(0, i)));
});
