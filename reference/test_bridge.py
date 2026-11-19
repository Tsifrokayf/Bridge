import asyncio
import hashlib
import json
import os
import struct
import tempfile
import unittest

import bridge_proto as bp
import gen_vectors

SERVER_ID = bytes(range(16))
CLIENT_ID = bytes(range(100, 116))


def run(coro):
    return asyncio.run(asyncio.wait_for(coro, 30))


async def loopback(server_cb):
    server = await asyncio.start_server(server_cb, "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    return server, port


class VectorTests(unittest.TestCase):
    def test_committed_vectors_are_current(self):
        with open(gen_vectors.vectors_path()) as f:
            committed = json.load(f)
        self.assertEqual(committed, json.loads(json.dumps(gen_vectors.build_vectors(), ensure_ascii=False)))

    def test_sas_is_six_digits(self):
        v = gen_vectors.build_vectors()
        self.assertRegex(v["pair"]["sas"], r"^\d{6}$")

    def test_record_vectors_decrypt(self):
        v = gen_vectors.build_vectors()
        k = bytes.fromhex(v["records"]["key"])
        codec = bp.Codec(os.urandom(32), k)
        for item in v["records"]["items"]:
            frame = bytes.fromhex(item["frame"])
            self.assertEqual(struct.unpack(">I", frame[:4])[0], len(frame) - 4)
            t, body = codec.open(frame[4:])
            self.assertEqual((t, body.hex()), (item["type"], item["body"]))


class HandshakeTests(unittest.TestCase):
    def _pair_and_reconnect(self, tamper_secret=False, known=True):
        tmp = tempfile.mkdtemp()
        results = {}

        async def main():
            state = {"secret": None}
            done = asyncio.Event()

            async def on_conn(r, w):
                try:
                    res = await bp.server_handshake(
                        r, w, device_id=SERVER_ID, name="Mac",
                        lookup_secret=lambda cid: state["secret"] if (known and cid == CLIENT_ID) else None,
                        pairing_enabled=lambda: state["secret"] is None)
                    results.setdefault("server", []).append(res)
                    if res.mode == "pair":
                        state["secret"] = res.pairing_secret
                    peer = bp.Peer(r, w, res.codec, tmp)
                    await peer.send_hello("Mac", "mac")
                    await peer.run()
                except (bp.ProtocolError, asyncio.IncompleteReadError) as e:
                    results.setdefault("server_err", []).append(str(e))
                    w.close()
                finally:
                    done.set()

            server, port = await loopback(on_conn)
            # --- pairing
            r, w = await asyncio.open_connection("127.0.0.1", port)
            cres = await bp.client_pair(r, w, device_id=CLIENT_ID, name="Phone")
            peer = bp.Peer(r, w, cres.codec, tmp)
            task = asyncio.create_task(peer.run())
            await peer.send_hello("Phone", "android")
            await asyncio.sleep(0.1)
            results["pair_client"] = cres
            await peer.close()
            await task
            await asyncio.sleep(0.1)
            # --- reconnect
            secret = cres.pairing_secret if not tamper_secret else os.urandom(32)
            r, w = await asyncio.open_connection("127.0.0.1", port)
            try:
                rres = await bp.client_reconnect(r, w, device_id=CLIENT_ID, secret=secret)
                results["reconnect_client"] = rres
                peer = bp.Peer(r, w, rres.codec, tmp)
                task = asyncio.create_task(peer.run())
                await peer.send_hello("Phone", "android")
                await asyncio.sleep(0.1)
                await peer.close()
                await task
            except (bp.ProtocolError, asyncio.IncompleteReadError, ConnectionError) as e:
                results["client_err"] = repr(e)
                w.close()  # a real client must drop the socket after a failed handshake
            await asyncio.sleep(0.1)
            server.close()
            await server.wait_closed()

        run(main())
        return results

    def test_pair_gives_same_sas_and_secret(self):
        res = self._pair_and_reconnect()
        srv, cli = res["server"][0], res["pair_client"]
        self.assertEqual(srv.mode, "pair")
        self.assertEqual(srv.sas, cli.sas)
        self.assertEqual(srv.pairing_secret, cli.pairing_secret)
        self.assertEqual(srv.peer_id, CLIENT_ID)
        self.assertEqual(cli.peer_id, SERVER_ID)

    def test_reconnect_works_after_pairing(self):
        res = self._pair_and_reconnect()
        self.assertNotIn("client_err", res)
        self.assertEqual([r.mode for r in res["server"]], ["pair", "reconnect"])

    def test_reconnect_with_wrong_secret_is_rejected_by_client(self):
        # With a wrong secret the client notices first: the server's MAC does not verify.
        res = self._pair_and_reconnect(tamper_secret=True)
        self.assertIn("server authentication failed", res["client_err"])

    def test_server_rejects_wrong_client_mac(self):
        v = gen_vectors.build_vectors()["reconnect"]
        secret = bytes.fromhex(v["pairing_secret"])
        th, z = bytes.fromhex(v["transcript_hash"]), bytes.fromhex(v["shared_secret"])
        good = bytes.fromhex(v["finish_body"])
        bp.server_reconnect_finish(good, secret, th, z)
        bad = good[:-1] + bytes([good[-1] ^ 1])
        with self.assertRaisesRegex(bp.ProtocolError, "client authentication failed"):
            bp.server_reconnect_finish(bad, secret, th, z)

    def test_unknown_device_is_rejected(self):
        # known=False: server forgets the client; pairing is closed after the first pair
        res = self._pair_and_reconnect(known=False)
        self.assertIn("client_err", res)
        self.assertIn("unknown device", res["server_err"][0])


class RecordTests(unittest.TestCase):
    def setUp(self):
        k1, k2 = os.urandom(32), os.urandom(32)
        self.a, self.b = bp.Codec(k1, k2), bp.Codec(k2, k1)

    def test_roundtrip_and_counters(self):
        for i in range(5):
            f = self.a.seal(bp.REC_JSON, b"x%d" % i)
            self.assertEqual(self.b.open(f[4:]), (bp.REC_JSON, b"x%d" % i))

    def test_tamper_detected(self):
        f = bytearray(self.a.seal(bp.REC_JSON, b"hello"))
        f[6] ^= 1
        with self.assertRaises(bp.ProtocolError):
            self.b.open(bytes(f[4:]))

    def test_replay_and_reorder_detected(self):
        f0, f1 = self.a.seal(bp.REC_JSON, b"a"), self.a.seal(bp.REC_JSON, b"b")
        with self.assertRaises(bp.ProtocolError):
            self.b.open(f1[4:])  # out of order
        self.b.open(f0[4:])
        with self.assertRaises(bp.ProtocolError):
            self.b.open(f0[4:])  # replay

    def test_oversize_frame_rejected(self):
        async def main():
            r = asyncio.StreamReader()
            r.feed_data(struct.pack(">I", bp.MAX_FRAME + 1) + b"x")
            with self.assertRaises(bp.ProtocolError):
                await bp.read_frame(r)
        run(main())


class FileAndMessageTests(unittest.TestCase):
    def _session(self, body):
        async def main():
            tmp_a, tmp_b = tempfile.mkdtemp(), tempfile.mkdtemp()
            k1, k2 = os.urandom(32), os.urandom(32)
            ready = asyncio.Future()

            async def on_conn(r, w):
                ready.set_result((r, w))

            server, port = await loopback(on_conn)
            cr, cw = await asyncio.open_connection("127.0.0.1", port)
            sr, sw = await ready
            inbox = {"clip": [], "notif": [], "removed": []}
            a = bp.Peer(cr, cw, bp.Codec(k1, k2), tmp_a)
            b = bp.Peer(sr, sw, bp.Codec(k2, k1), tmp_b, on_clipboard=inbox["clip"].append,
                        on_notification=inbox["notif"].append, on_notification_removed=inbox["removed"].append)
            ta, tb = asyncio.create_task(a.run()), asyncio.create_task(b.run())
            await body(a, b, inbox, tmp_a, tmp_b)
            await a.close()
            await b.close()
            await asyncio.gather(ta, tb)
            server.close()
        run(main())

    def test_file_transfer_both_directions(self):
        data = os.urandom(700_000)

        async def body(a, b, inbox, tmp_a, tmp_b):
            self.assertTrue(await a.send_file("photo.jpg", data))
            self.assertTrue(await b.send_file("back.bin", b"hi"))
            with open(os.path.join(tmp_b, "photo.jpg"), "rb") as f:
                self.assertEqual(f.read(), data)
            with open(os.path.join(tmp_a, "back.bin"), "rb") as f:
                self.assertEqual(f.read(), b"hi")
            self.assertTrue(await a.send_file("photo.jpg", b"second"))  # no overwrite
            self.assertTrue(os.path.exists(os.path.join(tmp_b, "photo (1).jpg")))
            self.assertTrue(await a.send_file("empty.txt", b""))
            self.assertEqual(os.path.getsize(os.path.join(tmp_b, "empty.txt")), 0)

        self._session(body)

    def test_path_traversal_is_neutralised(self):
        async def body(a, b, inbox, tmp_a, tmp_b):
            self.assertTrue(await a.send_file("../../etc/passwd", b"x"))
            self.assertEqual(os.listdir(tmp_b), ["passwd"])

        self._session(body)

    def test_bad_hash_is_reported(self):
        async def body(a, b, inbox, tmp_a, tmp_b):
            loop = asyncio.get_running_loop()
            ack = loop.create_future()
            a._ack[9] = ack
            await a.send_json({"t": "file_offer", "id": 9, "name": "x.bin", "size": 3})
            await asyncio.sleep(0.1)
            await a._send(bp.REC_CHUNK, struct.pack(">I", 9) + b"abc")
            await a.send_json({"t": "file_done", "id": 9, "sha256": hashlib.sha256(b"zzz").hexdigest()})
            self.assertFalse(await ack)
            self.assertEqual(os.listdir(tmp_b), [])

        self._session(body)

    def test_clipboard_and_notifications(self):
        async def body(a, b, inbox, tmp_a, tmp_b):
            await a.send_json({"t": "clipboard", "text": "привет ✓", "ts": 1})
            await a.send_json({"t": "notification", "key": "k1", "app": "App", "title": "T", "text": "x", "ts": 2})
            await a.send_json({"t": "notification_removed", "key": "k1"})
            await a.send_json({"t": "ping"})
            await asyncio.sleep(0.2)
            self.assertEqual(inbox["clip"], ["привет ✓"])
            self.assertEqual(inbox["notif"][0]["title"], "T")
            self.assertEqual(inbox["removed"], ["k1"])

        self._session(body)


if __name__ == "__main__":
    unittest.main()
