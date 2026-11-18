#!/usr/bin/env python3
"""Minimal HTTP + WebSocket server for the ZCode WebView probe simulation.

Serves index.html and a /ws endpoint that logs connect/disconnect/heartbeats,
and pushes a 'task_done' message 20s after each connection.
"""
import base64
import hashlib
import os
import socket
import struct
import sys
import threading
import time

HOST = "127.0.0.1"
PORT = 8899
ROOT = os.path.dirname(os.path.abspath(__file__))

_lock = threading.Lock()


def log(msg):
    line = time.strftime("%H:%M:%S") + "." + f"{int((time.time() % 1) * 1000):03d}" + " " + msg
    with _lock:
        print(line, flush=True)
        with open(os.path.join(ROOT, "server.log"), "a") as f:
            f.write(line + "\n")


def read_http(conn):
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = conn.recv(4096)
        if not chunk:
            return None
        data += chunk
    head, _, rest = data.partition(b"\r\n\r\n")
    lines = head.decode("latin1").split("\r\n")
    try:
        method, path, _ = lines[0].split(" ")
    except ValueError:
        return None
    headers = {}
    for line in lines[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            headers[k.strip().lower()] = v.strip()
    return method, path, headers, rest


def ws_handshake(conn, headers):
    key = headers.get("sec-websocket-key", "")
    accept = base64.b64encode(
        hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()
    ).decode()
    conn.sendall(
        (
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            "Sec-WebSocket-Accept: " + accept + "\r\n\r\n"
        ).encode()
    )


def send_frame(conn, opcode, payload=b""):
    n = len(payload)
    header = bytes([0x80 | opcode])
    if n < 126:
        header += bytes([n])
    elif n < 65536:
        header += bytes([126]) + struct.pack(">H", n)
    else:
        header += bytes([127]) + struct.pack(">Q", n)
    conn.sendall(header + payload)


def send_text(conn, text):
    send_frame(conn, 0x1, text.encode())


def recv_exact(conn, n):
    buf = b""
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            return None
        buf += chunk
    return buf


def read_frame(conn):
    hdr = recv_exact(conn, 2)
    if hdr is None:
        return None
    b1, b2 = hdr[0], hdr[1]
    opcode = b1 & 0x0F
    masked = bool(b2 & 0x80)
    ln = b2 & 0x7F
    if ln == 126:
        ext = recv_exact(conn, 2)
        if ext is None:
            return None
        ln = struct.unpack(">H", ext)[0]
    elif ln == 127:
        ext = recv_exact(conn, 8)
        if ext is None:
            return None
        ln = struct.unpack(">Q", ext)[0]
    mask = recv_exact(conn, 4) if masked else b"\x00\x00\x00\x00"
    if mask is None:
        return None
    payload = recv_exact(conn, ln) if ln else b""
    if payload is None:
        return None
    if masked:
        payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
    return opcode, payload


def serve_http(conn, path):
    if "?" in path:
        path = path.split("?", 1)[0]
    if path in ("/", "/index.html"):
        with open(os.path.join(ROOT, "index.html"), "rb") as f:
            body = f.read()
        conn.sendall(
            b"HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
            b"Cache-Control: no-store\r\n"
            b"Content-Length: " + str(len(body)).encode() + b"\r\n\r\n" + body
        )
    elif path == "/files.html":
        with open(os.path.join(ROOT, "files.html"), "rb") as f:
            body = f.read()
        conn.sendall(
            b"HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
            b"Cache-Control: no-store\r\n"
            b"Content-Length: " + str(len(body)).encode() + b"\r\n\r\n" + body
        )
    elif path == "/download/zc.apk":
        with open(os.path.join(ROOT, "..", "app", "out", "ZCRemote.apk"), "rb") as f:
            body = f.read()
        head = (
            "HTTP/1.1 200 OK\r\n"
            "Content-Type: application/vnd.android.package-archive\r\n"
            "Content-Disposition: attachment; filename=\"zc-remote.apk\"\r\n"
            "Content-Length: " + str(len(body)) + "\r\n\r\n"
        ).encode()
        conn.sendall(head + body)
    elif path.startswith("/download/"):
        name = path[len("/download/"):]
        body = ("sim server download: " + name + "\n" + "D" * 4096).encode()
        head = (
            "HTTP/1.1 200 OK\r\n"
            "Content-Type: application/octet-stream\r\n"
            "Content-Disposition: attachment; filename=\"sim-demo.txt\"; "
            "filename*=UTF-8''%E6%B5%8B%E8%AF%95%E6%96%87%E4%BB%B6.txt\r\n"
            "Content-Length: " + str(len(body)) + "\r\n\r\n"
        ).encode()
        conn.sendall(head + body)
    else:
        conn.sendall(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n")


def handle_ws(conn, addr, mode):
    tag = f"conn-{addr[1]}"
    log(f"WS CONNECT {tag} mode={mode}")
    stop = threading.Event()

    def push_task_done():
        n = 0
        while not stop.wait(15):
            n += 1
            try:
                send_text(conn, "task_done " + str(n) + " " + str(int(time.time() * 1000)))
                log(f"WS PUSH task_done#{n} -> {tag}")
            except OSError:
                return

    threading.Thread(target=push_task_done, daemon=True).start()
    hb = 0
    try:
        while True:
            frame = read_frame(conn)
            if frame is None:
                log(f"WS DISCONNECT {tag} (eof)")
                break
            opcode, payload = frame
            if opcode == 0x8:
                log(f"WS DISCONNECT {tag} (client close)")
                try:
                    send_frame(conn, 0x8, b"")
                except OSError:
                    pass
                break
            if opcode == 0x9:
                send_frame(conn, 0xA, payload)
                continue
            if opcode == 0x1:
                text = payload.decode("utf-8", "replace")
                if text.startswith("hb"):
                    hb += 1
                    log(f"WS HB {tag} #{hb} {text}")
                    send_text(conn, "ack " + str(hb))
                else:
                    log(f"WS MSG {tag} {text}")
    except OSError as e:
        log(f"WS DISCONNECT {tag} (oserr {e})")
    finally:
        stop.set()
        try:
            conn.close()
        except OSError:
            pass


def handle(conn, addr):
    try:
        req = read_http(conn)
        if req is None:
            return
        method, path, headers, _ = req
        if headers.get("upgrade", "").lower() == "websocket":
            mode = "?"
            if "?" in path:
                mode = path.split("?", 1)[1]
            ws_handshake(conn, headers)
            handle_ws(conn, addr, mode)
        else:
            serve_http(conn, path)
    except Exception as e:  # noqa: BLE001
        log(f"ERROR {addr}: {e}")
    finally:
        try:
            conn.close()
        except OSError:
            pass


def main():
    open(os.path.join(ROOT, "server.log"), "w").close()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((HOST, PORT))
    srv.listen(16)
    log(f"LISTEN {HOST}:{PORT}")
    while True:
        conn, addr = srv.accept()
        threading.Thread(target=handle, args=(conn, addr), daemon=True).start()


if __name__ == "__main__":
    main()
