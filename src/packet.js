export const HEADER_SIZE = 39;
export function encodePacket({
  type,
  ttl = 7,
  senderId,
  packetId,
  timestamp = Date.now(),
  payload,
}) {
  if (
    !Number.isInteger(type) ||
    type < 1 ||
    type > 6 ||
    !Number.isInteger(ttl) ||
    ttl < 0 ||
    ttl > 7 ||
    !/^[a-f0-9]{16}$/.test(senderId) ||
    !/^[a-f0-9]{32}$/.test(packetId) ||
    payload.length > 1048576
  )
    throw Error("Invalid packet");
  const b = Buffer.alloc(HEADER_SIZE + payload.length);
  b[0] = 1;
  b[1] = type;
  b[2] = ttl;
  Buffer.from(senderId, "hex").copy(b, 3);
  Buffer.from(packetId, "hex").copy(b, 11);
  b.writeBigUInt64BE(BigInt(timestamp), 27);
  b.writeUInt32BE(payload.length, 35);
  payload.copy(b, 39);
  return b;
}
export function decodePacket(b) {
  if (
    b.length < HEADER_SIZE ||
    b[0] !== 1 ||
    b[1] < 1 ||
    b[1] > 6 ||
    b[2] > 7 ||
    b.readUInt32BE(35) > 1048576 ||
    b.readUInt32BE(35) !== b.length - HEADER_SIZE
  )
    throw Error("Malformed packet");
  return {
    type: b[1],
    ttl: b[2],
    senderId: b.subarray(3, 11).toString("hex"),
    packetId: b.subarray(11, 27).toString("hex"),
    timestamp: Number(b.readBigUInt64BE(27)),
    payload: b.subarray(39),
  };
}
