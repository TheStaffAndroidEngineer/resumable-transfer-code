"""Parcel: actual bounded chunks and atomic complete-object publication."""

import base64
import hashlib
import json
import uuid
from base import BaseService, Problem, fields, integer, text, require, handler
from http.server import ThreadingHTTPServer


def digest(data):
    return hashlib.sha256(data).hexdigest()


def decode(value):
    try:
        return base64.b64decode(value, validate=True)
    except Exception:
        raise Problem("Invalid base64 bytes")


class Service(BaseService):
    KIND = "parcel"

    def seed(self):
        return {"uploads": {}, "current": None}

    def summary(self, row):
        return {k: v for k, v in row.items() if k != "bytes"}

    def view(self, state, now):
        return {
            "upload": self.summary(state["uploads"][state["current"]]) if state["current"] else None
        }

    def apply(self, state, action, args, now):
        if action == "create":
            fields(args, {"uploadId", "length", "sha256"})
            key = text(args["uploadId"], 36)
            try:
                require(str(uuid.UUID(key)) == key, "Canonical upload UUID required")
            except ValueError:
                raise Problem("Invalid upload UUID")
            length = integer(args["length"], 1, 65536)
            sha = text(args["sha256"], 64)
            require(len(sha) == 64 and all(c in "0123456789abcdef" for c in sha), "Invalid digest")
            old = state["uploads"].get(key)
            if old:
                require(old["length"] == length and old["sha256"] == sha, "Upload identity changed")
                return {"accepted": True, "uploadId": key}
            if len(state["uploads"]) >= 20:
                return {"accepted": False, "reason": "Fixture upload limit reached"}
            state["uploads"][key] = {
                "id": key,
                "length": length,
                "sha256": sha,
                "offset": 0,
                "status": "OPEN",
                "bytes": "",
            }
            state["current"] = key
            return {"accepted": True, "uploadId": key}
        fields(
            args, {"uploadId", "offset", "data", "sha256"} if action == "chunk" else {"uploadId"}
        )
        key = text(args["uploadId"], 36)
        row = state["uploads"].get(key)
        if row is None:
            return {"accepted": False, "reason": "Unknown upload"}
        if action == "cancel":
            if row["status"] == "COMMITTED":
                return {"accepted": False, "reason": "Committed object cannot be cancelled"}
            row["status"] = "CANCELLED"
            return {"accepted": True}
        if row["status"] != "OPEN":
            return {"accepted": False, "reason": "Upload is terminal: " + row["status"]}
        raw = decode(row["bytes"])
        if action == "chunk":
            offset = integer(args["offset"], 0, 65536)
            chunk = decode(text(args["data"], 6000))
            sha = text(args["sha256"], 64)
            require(
                0 < len(chunk) <= 4096 and digest(chunk) == sha, "Chunk digest or size mismatch"
            )
            if offset < row["offset"]:
                if (
                    offset + len(chunk) <= row["offset"]
                    and raw[offset : offset + len(chunk)] == chunk
                ):
                    return {"accepted": True, "offset": row["offset"]}
                return {"accepted": False, "reason": "Previously accepted bytes differ"}
            if offset != row["offset"]:
                return {"accepted": False, "reason": "Probe the accepted offset before continuing"}
            require(offset + len(chunk) <= row["length"], "Chunk exceeds declared length")
            raw += chunk
            row["bytes"] = base64.b64encode(raw).decode()
            row["offset"] = len(raw)
            return {"accepted": True, "offset": row["offset"]}
        if action == "complete":
            if len(raw) != row["length"] or digest(raw) != row["sha256"]:
                return {
                    "accepted": False,
                    "reason": "Whole object is incomplete or fails its digest",
                }
            row["status"] = "COMMITTED"
            return {"accepted": True, "uploadId": key}
        raise Problem("Unknown action")

    def route(self, method, path, body):
        if method == "GET" and path.startswith("/uploads/"):
            key = path.removeprefix("/uploads/")
            with self.transaction() as db:
                controls = self.controls(db)
                if controls.get("unavailable"):
                    raise Problem("Fixture unavailable", 503)
                state = json.loads(db.execute("SELECT value FROM state WHERE id=1").fetchone()[0])
                row = state["uploads"].get(key)
                return (
                    200,
                    {
                        "kind": self.KIND,
                        "observedAt": self.now(controls),
                        "data": {"upload": self.summary(row) if row else None},
                    },
                    False,
                )
        return super().route(method, path, body)

    def object(self, key):
        with self.transaction() as db:
            if self.controls(db).get("unavailable"):
                raise Problem("Fixture unavailable", 503)
            state = json.loads(db.execute("SELECT value FROM state WHERE id=1").fetchone()[0])
            row = state["uploads"].get(key)
            if not row or row["status"] != "COMMITTED":
                raise Problem("No committed object", 404)
            return decode(row["bytes"]), row["sha256"]


def object_handler(service):
    parent = handler(service)

    class Handler(parent):
        def do_GET(self):
            if not self.path.startswith("/objects/"):
                return super().do_GET()
            try:
                raw, sha = service.object(self.path.removeprefix("/objects/"))
                self.send_response(200)
                self.send_header("Content-Type", "text/plain; charset=utf-8")
                self.send_header("Content-Length", str(len(raw)))
                self.send_header("ETag", '"' + sha + '"')
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(raw)
                self.close_connection = True
            except Problem as error:
                self.send_error(error.status, str(error))
                self.close_connection = True
            except (BrokenPipeError, ConnectionResetError):
                self.close_connection = True

    return Handler


if __name__ == "__main__":
    import argparse
    from pathlib import Path

    parser = argparse.ArgumentParser()
    parser.add_argument("--database", default="runtime/state.sqlite")
    parser.add_argument("--port", type=int, default=8920)
    args = parser.parse_args()
    Path(args.database).parent.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), object_handler(Service(args.database)))
    print("Parcel loopback service", args.port, flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
