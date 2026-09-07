import {
  existsSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
  renameSync,
} from "node:fs";
import { join } from "node:path";
import { randomBytes, scryptSync } from "node:crypto";
import { encrypt, decrypt, identity } from "./crypto.js";
export class Vault {
  constructor(dir) {
    this.dir = dir;
    this.file = join(dir, "vault.json");
    mkdirSync(dir, { recursive: true });
  }
  get exists() {
    return existsSync(this.file);
  }
  unlock(password, name = "Explorer") {
    if (typeof password !== "string" || password.length < 12)
      throw Error("Use a passphrase of at least 12 characters");
    const disk = this.exists
      ? JSON.parse(readFileSync(this.file, "utf8"))
      : null;
    const salt = disk ? Buffer.from(disk.salt, "base64") : randomBytes(16);
    const key = scryptSync(password, salt, 32);
    let state;
    try {
      state = disk
        ? JSON.parse(decrypt(key, disk.box, "mesh-vault-v1"))
        : {
            identity: identity(name.slice(0, 60)),
            peers: [],
            messages: [],
            outbox: [],
            seen: [],
            files: [],
          };
    } catch {
      throw Error("Unable to unlock vault: check your passphrase");
    }
    this.key = key;
    this.salt = salt;
    this.state = state;
    this.save();
    return state;
  }
  save() {
    writeFileSync(
      this.file + ".tmp",
      JSON.stringify({
        salt: this.salt.toString("base64"),
        box: encrypt(
          this.key,
          Buffer.from(JSON.stringify(this.state)),
          "mesh-vault-v1",
        ),
      }),
      { mode: 0o600 },
    );
    renameSync(this.file + ".tmp", this.file);
  }
}
