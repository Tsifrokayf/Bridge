"""Deterministic test vectors for Bridge v1. Run: python3 gen_vectors.py"""
import hashlib
import json
import os

import bridge_proto as bp


def _scalar(label: bytes) -> int:
    d = int.from_bytes(hashlib.sha256(label).digest(), "big")
    assert 0 < d < 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
    return d


def _h(b: bytes) -> str:
    return b.hex()


def build_vectors() -> dict:
    c_priv = bp.priv_from_scalar(_scalar(b"bridge-test client ephemeral"))
    s_priv = bp.priv_from_scalar(_scalar(b"bridge-test server ephemeral"))
    c_id = hashlib.sha256(b"bridge-test client id").digest()[:16]
    s_id = hashlib.sha256(b"bridge-test server id").digest()[:16]
    c_nonce = hashlib.sha256(b"bridge-test client nonce").digest()
    s_nonce = hashlib.sha256(b"bridge-test server nonce").digest()
    c_name = "Pixel тест"  # non-ASCII on purpose
    s_name = "MacBook Air"

    # ---- pairing
    hello = bp.enc_pair_hello(c_id, bp.pub_bytes(c_priv), c_nonce, c_name)
    reply, skeys, _cid, _cname = bp.server_pair_respond(hello, s_id, s_name, s_priv, s_nonce)
    ckeys, _sid, _sname = bp.client_pair_finish(c_priv, hello, reply)
    assert (ckeys.c2s, ckeys.s2c, ckeys.sas, ckeys.secret) == (skeys.c2s, skeys.s2c, skeys.sas, skeys.secret)
    z = bp.ecdh(c_priv, bp.pub_bytes(s_priv))
    pair = {
        "client_device_id": _h(c_id), "client_priv": _h(c_priv.private_numbers().private_value.to_bytes(32, "big")),
        "client_pub": _h(bp.pub_bytes(c_priv)), "client_nonce": _h(c_nonce), "client_name": c_name,
        "server_device_id": _h(s_id), "server_priv": _h(s_priv.private_numbers().private_value.to_bytes(32, "big")),
        "server_pub": _h(bp.pub_bytes(s_priv)), "server_nonce": _h(s_nonce), "server_name": s_name,
        "hello_body": _h(hello), "reply_body": _h(reply),
        "transcript_hash": _h(bp.pair_th(hello, reply)), "shared_secret": _h(z),
        "c2s_key": _h(skeys.c2s), "s2c_key": _h(skeys.s2c), "sas": skeys.sas, "pairing_secret": _h(skeys.secret),
    }

    # ---- reconnect (uses the pairing secret from above)
    rc_priv = bp.priv_from_scalar(_scalar(b"bridge-test client ephemeral 2"))
    rs_priv = bp.priv_from_scalar(_scalar(b"bridge-test server ephemeral 2"))
    rc_nonce = hashlib.sha256(b"bridge-test client nonce 2").digest()
    rs_nonce = hashlib.sha256(b"bridge-test server nonce 2").digest()
    secret = skeys.secret
    rhello = bp.enc_reconnect_hello(c_id, bp.pub_bytes(rc_priv), rc_nonce)
    rreply, th, rz = bp.server_reconnect_respond(rhello, secret, rs_priv, rs_nonce)
    finish, ckeys2, th2, _z2 = bp.client_reconnect_finish(rc_priv, secret, rhello, rreply)
    skeys2 = bp.server_reconnect_finish(finish, secret, th, rz)
    assert th == th2 and (ckeys2.c2s, ckeys2.s2c) == (skeys2.c2s, skeys2.s2c)
    reconnect = {
        "pairing_secret": _h(secret), "client_device_id": _h(c_id),
        "client_priv": _h(rc_priv.private_numbers().private_value.to_bytes(32, "big")),
        "client_pub": _h(bp.pub_bytes(rc_priv)), "client_nonce": _h(rc_nonce),
        "server_priv": _h(rs_priv.private_numbers().private_value.to_bytes(32, "big")),
        "server_pub": _h(bp.pub_bytes(rs_priv)), "server_nonce": _h(rs_nonce),
        "hello_body": _h(rhello), "reply_body": _h(rreply), "finish_body": _h(finish),
        "transcript_hash": _h(th), "shared_secret": _h(rz),
        "server_mac": _h(rreply[98:130]), "client_mac": _h(finish[1:]),
        "c2s_key": _h(skeys2.c2s), "s2c_key": _h(skeys2.s2c),
    }

    # ---- encrypted records (client -> server direction, reconnect keys)
    codec = bp.Codec(skeys2.c2s, skeys2.s2c)
    j = json.dumps({"t": "clipboard", "text": "привет ✓", "ts": 1}, ensure_ascii=False,
                   separators=(",", ":")).encode("utf-8")
    chunk = (7).to_bytes(4, "big") + bytes(range(64))
    records = {
        "key": _h(skeys2.c2s),
        "items": [
            {"counter": 0, "type": bp.REC_JSON, "body": _h(j), "frame": _h(codec.seal(bp.REC_JSON, j))},
            {"counter": 1, "type": bp.REC_CHUNK, "body": _h(chunk), "frame": _h(codec.seal(bp.REC_CHUNK, chunk))},
        ],
    }
    return {"version": bp.VERSION, "pair": pair, "reconnect": reconnect, "records": records}


def vectors_path() -> str:
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "vectors", "vectors.json")


if __name__ == "__main__":
    with open(vectors_path(), "w") as f:
        json.dump(build_vectors(), f, indent=2, ensure_ascii=False, sort_keys=True)
        f.write("\n")
    print("wrote", os.path.normpath(vectors_path()))
