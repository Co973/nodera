import * as c from "node:crypto";
export const hash = (value) =>
  c.createHash("sha256").update(value).digest("hex");
const pub = (key) => key.export({ type: "spki", format: "pem" }).toString();
const priv = (key) => key.export({ type: "pkcs8", format: "pem" }).toString();
export function identity(name) {
  const ed = c.generateKeyPairSync("ed25519"),
    x = c.generateKeyPairSync("x25519");
  return {
    name,
    signing: pub(ed.publicKey),
    exchange: pub(x.publicKey),
    signingPrivate: priv(ed.privateKey),
    exchangePrivate: priv(x.privateKey),
  };
}
export function card(i) {
  const body = { name: i.name, signing: i.signing, exchange: i.exchange };
  return {
    ...body,
    id: hash(i.signing),
    signature: c
      .sign(null, Buffer.from(JSON.stringify(body)), i.signingPrivate)
      .toString("base64"),
  };
}
export function verifyCard(i) {
  if (
    !i ||
    typeof i.name !== "string" ||
    i.name.length > 60 ||
    i.id !== hash(i.signing)
  )
    throw Error("Invalid identity");
  if (
    c.createPublicKey(i.signing).asymmetricKeyType !== "ed25519" ||
    c.createPublicKey(i.exchange).asymmetricKeyType !== "x25519"
  )
    throw Error("Invalid key type");
  if (
    !c.verify(
      null,
      Buffer.from(
        JSON.stringify({
          name: i.name,
          signing: i.signing,
          exchange: i.exchange,
        }),
      ),
      i.signing,
      Buffer.from(i.signature, "base64"),
    )
  )
    throw Error("Invalid identity signature");
  return i;
}
export function encrypt(key, bytes, aad = "") {
  const nonce = c.randomBytes(12),
    cipher = c.createCipheriv("chacha20-poly1305", key, nonce, {
      authTagLength: 16,
    });
  cipher.setAAD(Buffer.from(aad));
  return {
    nonce: nonce.toString("base64"),
    data: Buffer.concat([cipher.update(bytes), cipher.final()]).toString(
      "base64",
    ),
    tag: cipher.getAuthTag().toString("base64"),
  };
}
export function decrypt(key, box, aad = "") {
  const decipher = c.createDecipheriv(
    "chacha20-poly1305",
    key,
    Buffer.from(box.nonce, "base64"),
    { authTagLength: 16 },
  );
  decipher.setAAD(Buffer.from(aad));
  decipher.setAuthTag(Buffer.from(box.tag, "base64"));
  return Buffer.concat([
    decipher.update(Buffer.from(box.data, "base64")),
    decipher.final(),
  ]);
}
function shared(privateKey, publicKey, context) {
  return Buffer.from(
    c.hkdfSync(
      "sha256",
      c.diffieHellman({
        privateKey: c.createPrivateKey(privateKey),
        publicKey: c.createPublicKey(publicKey),
      }),
      Buffer.alloc(0),
      Buffer.from(context),
      32,
    ),
  );
}
// Experimental sealed envelopes, deliberately NOT advertised as Noise or a ratchet.
export function seal(sender, recipient, payload) {
  const e = c.generateKeyPairSync("x25519");
  const header = {
    version: 1,
    id: c.randomUUID(),
    from: card(sender),
    to: recipient.id,
    ephemeral: pub(e.publicKey),
    expires: Date.now() + 72 * 3600000,
  };
  const aad = JSON.stringify(header),
    box = encrypt(
      shared(priv(e.privateKey), recipient.exchange, aad),
      Buffer.from(JSON.stringify(payload)),
      aad,
    );
  const body = { ...header, box };
  return {
    ...body,
    signature: c
      .sign(null, Buffer.from(JSON.stringify(body)), sender.signingPrivate)
      .toString("base64"),
  };
}
export function verifyEnvelope(envelope) {
  const { signature, box, ...header } = envelope;
  verifyCard(header.from);
  if (
    header.version !== 1 ||
    !/^[a-f0-9]{64}$/.test(header.to) ||
    typeof header.id !== "string" ||
    header.id.length > 64 ||
    !Number.isFinite(header.expires) ||
    header.expires < Date.now() ||
    header.expires > Date.now() + 73 * 3600000
  )
    throw Error("Invalid or expired envelope");
  if (
    !c.verify(
      null,
      Buffer.from(JSON.stringify({ ...header, box })),
      header.from.signing,
      Buffer.from(signature, "base64"),
    )
  )
    throw Error("Invalid envelope signature");
  return header;
}
export function open(receiver, envelope) {
  const header = verifyEnvelope(envelope),
    box = envelope.box;
  if (header.to !== card(receiver).id) throw Error("Wrong recipient");
  const aad = JSON.stringify(header);
  return JSON.parse(
    decrypt(shared(receiver.exchangePrivate, header.ephemeral, aad), box, aad),
  );
}
