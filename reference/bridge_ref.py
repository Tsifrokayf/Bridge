"""Headless reference peer ("fake Mac") for testing Bridge clients.

  python3 bridge_ref.py serve --state-dir DIR [--pair] [--once] [--port 0]

Prints machine-readable lines on stdout (PORT/MODE/SAS/PAIRED/CLIPBOARD/FILE/CLOSED/REJECT).
Right after the handshake it sends a clipboard text and a 300000-byte demo file,
so a client under test can verify both directions.
"""
import argparse
import asyncio
import hashlib
import json
import os
import sys

import bridge_proto as bp

DEMO_NAME = "from-mac.bin"
DEMO_CLIP = "from-mac ✓"


def demo_bytes() -> bytes:
    return bytes((i * 7 + 3) & 0xFF for i in range(300_000))


def load_state(path):
    if os.path.exists(path):
        with open(path) as f:
            return json.load(f)
    return {"device_id": os.urandom(16).hex(), "peers": {}}


def save_state(path, state):
    with open(path, "w") as f:
        json.dump(state, f, indent=2)


def out(*parts):
    print(*parts, flush=True)


async def serve(args):
    os.makedirs(args.state_dir, exist_ok=True)
    state_path = os.path.join(args.state_dir, "state.json")
    save_dir = os.path.join(args.state_dir, "received")
    state = load_state(state_path)
    save_state(state_path, state)
    device_id = bytes.fromhex(state["device_id"])
    stop = asyncio.Event()

    def lookup(cid: bytes):
        p = state["peers"].get(cid.hex())
        return bytes.fromhex(p["secret"]) if p else None

    async def on_conn(r, w):
        try:
            res = await bp.server_handshake(r, w, device_id=device_id, name="RefMac",
                                            lookup_secret=lookup, pairing_enabled=lambda: args.pair)
        except (bp.ProtocolError, asyncio.IncompleteReadError, ConnectionError) as e:
            out("REJECT", e)
            w.close()
            if args.once:
                stop.set()
            return
        out("MODE", res.mode)
        if res.mode == "pair":
            out("SAS", res.sas)

        def on_confirm(ok):
            if ok and res.mode == "pair":
                state["peers"][res.peer_id.hex()] = {"name": res.peer_name, "secret": res.pairing_secret.hex()}
                save_state(state_path, state)
                out("PAIRED", res.peer_name)

        def on_file(path):
            with open(path, "rb") as f:
                data = f.read()
            out("FILE", os.path.basename(path), len(data), hashlib.sha256(data).hexdigest())

        peer = bp.Peer(r, w, res.codec, save_dir,
                       on_clipboard=lambda t: out("CLIPBOARD", json.dumps(t, ensure_ascii=False)),
                       on_notification=lambda m: out("NOTIFICATION", json.dumps(m, ensure_ascii=False)),
                       on_notification_removed=lambda k: out("NOTIFICATION_REMOVED", k),
                       on_file=on_file, on_pair_confirm=on_confirm)
        task = asyncio.create_task(peer.run())
        await peer.send_hello("RefMac", "mac")
        if res.mode == "pair":
            await peer.send_json({"t": "pair_confirm", "ok": True})
        await peer.send_json({"t": "clipboard", "text": DEMO_CLIP, "ts": 0})
        ok = await peer.send_file(DEMO_NAME, demo_bytes())
        out("SENT", DEMO_NAME, ok)
        await task
        out("CLOSED")
        if args.once:
            stop.set()

    server = await asyncio.start_server(on_conn, args.host, args.port)
    out("PORT", server.sockets[0].getsockname()[1])
    out("DEVICE_ID", state["device_id"])
    async with server:
        if args.once:
            await stop.wait()
        else:
            await server.serve_forever()


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("serve")
    s.add_argument("--host", default="127.0.0.1")
    s.add_argument("--port", type=int, default=0)
    s.add_argument("--state-dir", required=True)
    s.add_argument("--pair", action="store_true", help="accept pairing requests")
    s.add_argument("--once", action="store_true", help="exit after the first connection ends")
    args = ap.parse_args()
    try:
        asyncio.run(serve(args))
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()
