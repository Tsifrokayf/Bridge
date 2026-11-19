"""Bridge v1 — reference implementation of the protocol (see docs/PROTOCOL.md).

Used as the source of truth for the test vectors that the Kotlin (Android) and
Swift (Mac) implementations are checked against, and as a headless test peer.
"""
from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import os
import re
import struct
from dataclasses import dataclass
from typing import Callable, Optional

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

VERSION = 1

# Handshake frame types (plaintext phase)
T_PAIR_HELLO = 0x01
T_RECONNECT_HELLO = 0x02
T_RECONNECT_REPLY = 0x03
T_RECONNECT_FINISH = 0x04
T_PAIR_REPLY = 0x05

# Record types (encrypted phase)
REC_JSON = 0x01
REC_CHUNK = 0x02

MAX_FRAME = 2 * 1024 * 1024
CHUNK_SIZE = 256 * 1024
MAX_FILE_SIZE = 8 * 1024 * 1024 * 1024

SERVICE_TYPE = "_bridgesync._tcp"


class ProtocolError(Exception):
    pass


# --------------------------------------------------------------------------
# Primitives
# --------------------------------------------------------------------------

def priv_from_scalar(d: int) -> ec.EllipticCurvePrivateKey:
    return ec.derive_private_key(d, ec.SECP256R1())


def new_ephemeral() -> ec.EllipticCurvePrivateKey:
    return ec.generate_private_key(ec.SECP256R1())


def pub_bytes(priv: ec.EllipticCurvePrivateKey) -> bytes:
    """65-byte uncompressed SEC1 point (0x04 || X || Y)."""
    return priv.public_key().public_bytes(
        serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint
    )


def ecdh(priv: ec.EllipticCurvePrivateKey, peer_pub: bytes) -> bytes:
    if len(peer_pub) != 65 or peer_pub[0] != 4:
        raise ProtocolError("bad public key encoding")
    try:
        pub = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), peer_pub)
    except ValueError as e:
        raise ProtocolError("public key not on curve") from e
    return priv.exchange(ec.ECDH(), pub)


def hkdf(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=length, salt=salt, info=info).derive(ikm)


def sha256(*parts: bytes) -> bytes:
    h = hashlib.sha256()
    for p in parts:
        h.update(p)
    return h.digest()


def mac(secret: bytes, label: bytes, th: bytes) -> bytes:
    return hmac.new(secret, label + th, hashlib.sha256).digest()


def clip_name(name: str) -> bytes:
    b = name.encode("utf-8")[:64]
    return b.decode("utf-8", "ignore").encode("utf-8")


# --------------------------------------------------------------------------
# Handshake message encoding
# --------------------------------------------------------------------------

def enc_pair_hello(device_id: bytes, pub: bytes, nonce: bytes, name: str) -> bytes:
    nb = clip_name(name)
    return bytes([T_PAIR_HELLO, VERSION]) + device_id + pub + nonce + bytes([len(nb)]) + nb


def parse_pair_hello(body: bytes):
    if len(body) < 116 or body[0] != T_PAIR_HELLO:
        raise ProtocolError("bad pair hello")
    n = body[115]
    if len(body) != 116 + n:
        raise ProtocolError("bad pair hello length")
    return body[1], body[2:18], body[18:83], body[83:115], body[116:].decode("utf-8", "replace")


def enc_pair_reply(device_id: bytes, pub: bytes, nonce: bytes, name: str) -> bytes:
    nb = clip_name(name)
    return bytes([T_PAIR_REPLY]) + device_id + pub + nonce + bytes([len(nb)]) + nb


def parse_pair_reply(body: bytes):
    if len(body) < 115 or body[0] != T_PAIR_REPLY:
        raise ProtocolError("bad pair reply")
    n = body[114]
    if len(body) != 115 + n:
        raise ProtocolError("bad pair reply length")
    return body[1:17], body[17:82], body[82:114], body[115:].decode("utf-8", "replace")


def enc_reconnect_hello(device_id: bytes, pub: bytes, nonce: bytes) -> bytes:
    return bytes([T_RECONNECT_HELLO, VERSION]) + device_id + pub + nonce


def parse_reconnect_hello(body: bytes):
    if len(body) != 115 or body[0] != T_RECONNECT_HELLO:
        raise ProtocolError("bad reconnect hello")
    return body[1], body[2:18], body[18:83], body[83:115]


def enc_reconnect_reply(pub: bytes, nonce: bytes, server_mac: bytes) -> bytes:
    return bytes([T_RECONNECT_REPLY]) + pub + nonce + server_mac


def parse_reconnect_reply(body: bytes):
    if len(body) != 130 or body[0] != T_RECONNECT_REPLY:
        raise ProtocolError("bad reconnect reply")
    return body[1:66], body[66:98], body[98:130]


# --------------------------------------------------------------------------
# Pure handshake steps (no I/O) — these are what vectors are generated from
# --------------------------------------------------------------------------

@dataclass
class Keys:
    c2s: bytes
    s2c: bytes


@dataclass
class PairKeys(Keys):
    sas: str
    secret: bytes


def derive_pair(z: bytes, th: bytes) -> PairKeys:
    okm = hkdf(z, th, b"bridge-v1 pair", 100)
    sas = "%06d" % (int.from_bytes(okm[64:68], "big") % 1_000_000)
    return PairKeys(okm[:32], okm[32:64], sas, okm[68:100])


def derive_reconnect(z: bytes, secret: bytes, th: bytes) -> Keys:
    okm = hkdf(z + secret, th, b"bridge-v1 keys", 64)
    return Keys(okm[:32], okm[32:64])


def pair_th(hello: bytes, reply: bytes) -> bytes:
    return sha256(hello, reply)


def reconnect_th(hello: bytes, server_pub: bytes, server_nonce: bytes) -> bytes:
    return sha256(hello, server_pub, server_nonce)


def client_pair_finish(eph, hello: bytes, reply: bytes):
    sid, spub, _snonce, sname = parse_pair_reply(reply)
    z = ecdh(eph, spub)
    return derive_pair(z, pair_th(hello, reply)), sid, sname


def server_pair_respond(hello: bytes, device_id: bytes, name: str, eph, nonce: bytes):
    ver, cid, cpub, _cnonce, cname = parse_pair_hello(hello)
    if ver != VERSION:
        raise ProtocolError("unsupported version")
    reply = enc_pair_reply(device_id, pub_bytes(eph), nonce, name)
    z = ecdh(eph, cpub)
    return reply, derive_pair(z, pair_th(hello, reply)), cid, cname


def server_reconnect_respond(hello: bytes, secret: bytes, eph, nonce: bytes):
    ver, _cid, cpub, _cnonce = parse_reconnect_hello(hello)
    if ver != VERSION:
        raise ProtocolError("unsupported version")
    spub = pub_bytes(eph)
    th = reconnect_th(hello, spub, nonce)
    reply = enc_reconnect_reply(spub, nonce, mac(secret, b"bridge-v1 server", th))
    z = ecdh(eph, cpub)
    return reply, th, z


def server_reconnect_finish(finish: bytes, secret: bytes, th: bytes, z: bytes) -> Keys:
    if len(finish) != 33 or finish[0] != T_RECONNECT_FINISH:
        raise ProtocolError("bad reconnect finish")
    if not hmac.compare_digest(finish[1:], mac(secret, b"bridge-v1 client", th)):
        raise ProtocolError("client authentication failed")
    return derive_reconnect(z, secret, th)


def client_reconnect_finish(eph, secret: bytes, hello: bytes, reply: bytes):
    spub, snonce, smac = parse_reconnect_reply(reply)
    th = reconnect_th(hello, spub, snonce)
    if not hmac.compare_digest(smac, mac(secret, b"bridge-v1 server", th)):
        raise ProtocolError("server authentication failed")
    z = ecdh(eph, spub)
    finish = bytes([T_RECONNECT_FINISH]) + mac(secret, b"bridge-v1 client", th)
    return finish, derive_reconnect(z, secret, th), th, z


# --------------------------------------------------------------------------
# Framing and encrypted records
# --------------------------------------------------------------------------

async def read_frame(reader: asyncio.StreamReader) -> bytes:
    n = struct.unpack(">I", await reader.readexactly(4))[0]
    if n == 0 or n > MAX_FRAME:
        raise ProtocolError("bad frame length")
    return await reader.readexactly(n)


def write_frame(writer: asyncio.StreamWriter, body: bytes) -> None:
    writer.write(struct.pack(">I", len(body)) + body)


class Codec:
    """AES-256-GCM records, nonce = 00 00 00 00 || counter(8, BE), no AAD."""

    def __init__(self, send_key: bytes, recv_key: bytes):
        self._send = AESGCM(send_key)
        self._recv = AESGCM(recv_key)
        self.send_counter = 0
        self.recv_counter = 0

    @staticmethod
    def nonce(counter: int) -> bytes:
        return b"\x00\x00\x00\x00" + counter.to_bytes(8, "big")

    def seal(self, rec_type: int, body: bytes) -> bytes:
        ct = self._send.encrypt(self.nonce(self.send_counter), bytes([rec_type]) + body, None)
        self.send_counter += 1
        return struct.pack(">I", len(ct)) + ct

    def open(self, ct: bytes):
        try:
            pt = self._recv.decrypt(self.nonce(self.recv_counter), ct, None)
        except InvalidTag as e:
            raise ProtocolError("record authentication failed") from e
        self.recv_counter += 1
        if not pt:
            raise ProtocolError("empty record")
        return pt[0], pt[1:]


# --------------------------------------------------------------------------
# Async handshakes
# --------------------------------------------------------------------------

@dataclass
class HandshakeResult:
    mode: str  # "pair" | "reconnect"
    codec: Codec
    peer_id: bytes
    peer_name: Optional[str]
    sas: Optional[str] = None
    pairing_secret: Optional[bytes] = None


async def server_handshake(
    reader,
    writer,
    *,
    device_id: bytes,
    name: str,
    lookup_secret: Callable[[bytes], Optional[bytes]],
    pairing_enabled: Callable[[], bool],
    eph=None,
    nonce: Optional[bytes] = None,
) -> HandshakeResult:
    hello = await read_frame(reader)
    eph = eph or new_ephemeral()
    nonce = nonce or os.urandom(32)
    if hello[0] == T_RECONNECT_HELLO:
        _v, cid, _p, _n = parse_reconnect_hello(hello)
        secret = lookup_secret(cid)
        if secret is None:
            raise ProtocolError("unknown device")
        reply, th, z = server_reconnect_respond(hello, secret, eph, nonce)
        write_frame(writer, reply)
        await writer.drain()
        keys = server_reconnect_finish(await read_frame(reader), secret, th, z)
        return HandshakeResult("reconnect", Codec(keys.s2c, keys.c2s), cid, None)
    if hello[0] == T_PAIR_HELLO:
        if not pairing_enabled():
            raise ProtocolError("pairing not enabled")
        reply, keys, cid, cname = server_pair_respond(hello, device_id, name, eph, nonce)
        write_frame(writer, reply)
        await writer.drain()
        return HandshakeResult("pair", Codec(keys.s2c, keys.c2s), cid, cname, keys.sas, keys.secret)
    raise ProtocolError("unknown handshake type")


async def client_pair(reader, writer, *, device_id: bytes, name: str, eph=None, nonce=None) -> HandshakeResult:
    eph = eph or new_ephemeral()
    hello = enc_pair_hello(device_id, pub_bytes(eph), nonce or os.urandom(32), name)
    write_frame(writer, hello)
    await writer.drain()
    reply = await read_frame(reader)
    keys, sid, sname = client_pair_finish(eph, hello, reply)
    return HandshakeResult("pair", Codec(keys.c2s, keys.s2c), sid, sname, keys.sas, keys.secret)


async def client_reconnect(reader, writer, *, device_id: bytes, secret: bytes, eph=None, nonce=None) -> HandshakeResult:
    eph = eph or new_ephemeral()
    hello = enc_reconnect_hello(device_id, pub_bytes(eph), nonce or os.urandom(32))
    write_frame(writer, hello)
    await writer.drain()
    reply = await read_frame(reader)
    finish, keys, _th, _z = client_reconnect_finish(eph, secret, hello, reply)
    write_frame(writer, finish)
    await writer.drain()
    return HandshakeResult("reconnect", Codec(keys.c2s, keys.s2c), b"", None)


# --------------------------------------------------------------------------
# Peer: encrypted session with the application messages
# --------------------------------------------------------------------------

def safe_name(name: str) -> str:
    name = name.replace("\\", "/").split("/")[-1]
    name = re.sub(r'[\x00-\x1f<>:"|?*]', "_", name).strip(" .")
    return name[:200] or "file"


def unique_path(directory: str, name: str) -> str:
    base, ext = os.path.splitext(name)
    path = os.path.join(directory, name)
    i = 1
    while os.path.exists(path):
        path = os.path.join(directory, f"{base} ({i}){ext}")
        i += 1
    return path


class Peer:
    def __init__(self, reader, writer, codec: Codec, save_dir: str, *, on_clipboard=None,
                 on_notification=None, on_notification_removed=None, on_file=None, on_pair_confirm=None):
        self.reader, self.writer, self.codec = reader, writer, codec
        self.save_dir = save_dir
        self.on_file = on_file
        self.on_pair_confirm = on_pair_confirm
        self.on_clipboard = on_clipboard
        self.on_notification = on_notification
        self.on_notification_removed = on_notification_removed
        self.peer_hello = None
        self.received_files: list[str] = []
        self._next_id = 1
        self._accept: dict[int, asyncio.Future] = {}
        self._ack: dict[int, asyncio.Future] = {}
        self._incoming: dict[int, dict] = {}
        self._lock = asyncio.Lock()
        self.closed = asyncio.Event()

    async def _send(self, rec_type: int, body: bytes):
        async with self._lock:
            self.writer.write(self.codec.seal(rec_type, body))
            await self.writer.drain()

    async def send_json(self, obj: dict):
        await self._send(REC_JSON, json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))

    async def send_hello(self, name: str, platform: str):
        await self.send_json({"t": "hello", "name": name, "platform": platform,
                              "caps": ["clipboard", "notifications", "files"]})

    async def send_file(self, name: str, data: bytes) -> bool:
        fid = self._next_id
        self._next_id += 1
        loop = asyncio.get_running_loop()
        acc, ack = loop.create_future(), loop.create_future()
        self._accept[fid], self._ack[fid] = acc, ack
        await self.send_json({"t": "file_offer", "id": fid, "name": name, "size": len(data)})
        if not await acc:
            return False
        for i in range(0, len(data), CHUNK_SIZE):
            await self._send(REC_CHUNK, struct.pack(">I", fid) + data[i:i + CHUNK_SIZE])
        await self.send_json({"t": "file_done", "id": fid, "sha256": hashlib.sha256(data).hexdigest()})
        return await ack

    async def run(self):
        try:
            while True:
                rec_type, body = self.codec.open(await read_frame(self.reader))
                if rec_type == REC_JSON:
                    await self._on_json(json.loads(body.decode("utf-8")))
                elif rec_type == REC_CHUNK:
                    self._on_chunk(body)
                else:
                    raise ProtocolError("unknown record type")
        except (asyncio.IncompleteReadError, ConnectionError):
            pass
        finally:
            self.closed.set()
            for fut in list(self._accept.values()) + list(self._ack.values()):
                if not fut.done():
                    fut.set_result(False)

    async def _on_json(self, m: dict):
        t = m.get("t")
        if t == "hello":
            self.peer_hello = m
        elif t == "pair_confirm":
            if self.on_pair_confirm:
                self.on_pair_confirm(bool(m.get("ok")))
        elif t == "ping":
            await self.send_json({"t": "pong"})
        elif t == "clipboard" and self.on_clipboard:
            self.on_clipboard(m["text"])
        elif t == "notification" and self.on_notification:
            self.on_notification(m)
        elif t == "notification_removed" and self.on_notification_removed:
            self.on_notification_removed(m["key"])
        elif t == "file_offer":
            size = int(m["size"])
            if size < 0 or size > MAX_FILE_SIZE:
                await self.send_json({"t": "file_reject", "id": m["id"], "reason": "size"})
                return
            self._incoming[m["id"]] = {"name": safe_name(m["name"]), "size": size, "buf": bytearray()}
            await self.send_json({"t": "file_accept", "id": m["id"]})
        elif t == "file_accept":
            fut = self._accept.get(m["id"])
            if fut and not fut.done():
                fut.set_result(True)
        elif t == "file_reject":
            fut = self._accept.get(m["id"])
            if fut and not fut.done():
                fut.set_result(False)
        elif t == "file_done":
            f = self._incoming.pop(m["id"], None)
            ok = bool(f) and len(f["buf"]) == f["size"] and hashlib.sha256(f["buf"]).hexdigest() == m["sha256"]
            if ok:
                os.makedirs(self.save_dir, exist_ok=True)
                path = unique_path(self.save_dir, f["name"])
                with open(path, "wb") as fh:
                    fh.write(f["buf"])
                self.received_files.append(path)
                if self.on_file:
                    self.on_file(path)
            await self.send_json({"t": "file_ack", "id": m["id"], "ok": ok})
        elif t == "file_ack":
            fut = self._ack.get(m["id"])
            if fut and not fut.done():
                fut.set_result(bool(m.get("ok")))
        elif t == "file_cancel":
            self._incoming.pop(m["id"], None)

    def _on_chunk(self, body: bytes):
        if len(body) < 4:
            raise ProtocolError("short chunk")
        fid = struct.unpack(">I", body[:4])[0]
        f = self._incoming.get(fid)
        if f is None:
            return
        f["buf"] += body[4:]
        if len(f["buf"]) > f["size"]:
            self._incoming.pop(fid, None)

    async def close(self):
        self.writer.close()
        try:
            await self.writer.wait_closed()
        except Exception:
            pass
