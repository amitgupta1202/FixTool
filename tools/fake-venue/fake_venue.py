#!/usr/bin/env python3
"""
A deliberately hostile FIX venue: it sends its body in an order QuickFIX would never
re-serialise to.

The point. FixTool's built-in demo acceptor is itself QuickFIX-based, so its wire bytes
already come out ascending-tag-with-groups-at-the-end — exactly what Message.toString()
would produce. Against that venue, toRawString() and toString() are byte-identical, and a
green run proves nothing about which one FixTool read.

This venue sends an ExecutionReport whose body is:

    37, 11, 17, 150, 39, 55, 54, 38, 44, 6, 14, 151,   <- 37 BEFORE 11 (a real venue's order)
    453, 448, 447, 452, 448, 447, 452,                  <- the party group, MID-BODY
    58, 60                                              <- Text AFTER the group

QuickFIX's toString() cannot produce that. It would emit the flat fields ascending
(6, 11, 14, 37, 38, 39, 44, 54, 55, 58, 60, 150, 151) and then append the 453 group at the
END, after 58. So:

  * if FixTool shows 37 before 11, and the 453 group before 58  -> it read the wire. Correct.
  * if FixTool shows 6 before 11 and the group after 58         -> it read toString(). Broken.

Text carries a PIPE — 58=filled|in full. '|' is an ordinary character inside a FIX value, and
anything that establishes a message's delimiter by looking for one shreds this field. It is what
caught GET /messages reporting 58 = "filled" and silently dropping the tail, and it is what a
'|'-rendered log line of this message cannot be read back through.

The two party entries carry DIFFERENT firms (FIRMA, FIRMB). They have to: with the same firm twice,
an entry reorder and a role swap produce byte-identical wire, and the `shape` and `swap` modes below
would be indistinguishable — which is the one distinction this venue exists to make live. The
same-firm-twice case (where the identity does not identify) is covered by the unit fixtures.
"""
import os
import socket
import sys
import time
from datetime import datetime, timezone

# The venue's behaviour, switchable at runtime by writing one of these words to MODE_FILE.
#   golden : the reply a scenario is captured from.
#   shape  : the party ENTRIES swap places (benign), plus a real value regression, a tag added and a tag
#            dropped — the four kinds of failure the reconcile view is organised around.
#   swap   : the two firms swap ROLES. Same tags, same positions, same everything else. This is a
#            behaviour regression and must NEVER be offered as "entry moved".
MODE_FILE = os.environ.get("FAKE_VENUE_MODE_FILE", "/tmp/fake_venue_mode")

# The message log — SOH-delimited, both directions, the way a real venue's log stores them. stdout renders
# SOH as '|' so a human can read it, and that rendering is LOSSY: 58=filled|in full contains a pipe, so a
# '|'-rendered line cannot be read back unambiguously. FixTool's paste box refuses that line, quoting the
# checksum; it reads THIS one. Both halves of the trap, from one message.
LOG_FILE = os.environ.get("FAKE_VENUE_LOG", "/tmp/fake_venue.log")


def mode():
    try:
        with open(MODE_FILE) as f:
            return f.read().strip() or "golden"
    except OSError:
        return "golden"

SOH = "\x01"
HOST, PORT = "127.0.0.1", 19999
SENDER, TARGET = "FAKE_VENUE", "FIXTOOL"


def now():
    return datetime.now(timezone.utc).strftime("%Y%m%d-%H:%M:%S.%f")[:-3]


def frame(msgtype, body_fields, seqnum):
    """Build a FIX message, preserving body_fields order EXACTLY as given."""
    body = f"35={msgtype}{SOH}34={seqnum}{SOH}49={SENDER}{SOH}52={now()}{SOH}56={TARGET}{SOH}"
    body += "".join(f"{t}={v}{SOH}" for t, v in body_fields)
    head = f"9={len(body)}{SOH}"
    msg = f"8=FIX.4.4{SOH}{head}{body}"
    cks = sum(msg.encode()) % 256
    return f"{msg}10={cks:03d}{SOH}".encode()


def log_wire(direction, raw):
    """One message per line: a log prefix, then the venue's bytes, SOH and all."""
    try:
        with open(LOG_FILE, "ab") as f:
            f.write(f"{now()} {direction} ".encode() + raw.rstrip(b"\n") + b"\n")
    except OSError as e:
        print(f"!! could not write {LOG_FILE}: {e}", flush=True)


def send(conn, raw):
    conn.sendall(raw)
    log_wire("OUT", raw)


def parse(raw):
    out = {}
    order = []
    for f in raw.decode(errors="replace").split(SOH):
        if "=" in f:
            t, _, v = f.partition("=")
            out.setdefault(t, v)
            order.append(t)
    return out, order


def main():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((HOST, PORT))
    srv.listen(1)
    print(f"fake venue listening on {HOST}:{PORT}", flush=True)
    print(f"message log (SOH bytes, both directions): {LOG_FILE}", flush=True)

    conn, addr = srv.accept()
    print(f"connected: {addr}", flush=True)
    conn.settimeout(60)
    seq = 1
    buf = b""

    while True:
        try:
            data = conn.recv(8192)
        except socket.timeout:
            print("idle timeout, closing", flush=True)
            break
        if not data:
            break
        buf += data
        # crude framing: split on the checksum trailer
        while b"10=" in buf and buf.endswith(SOH.encode()):
            msg, buf = buf, b""
            fields, _ = parse(msg)
            mt = fields.get("35")
            log_wire("IN", msg)
            print(f"<< {mt}: {msg.decode(errors='replace').replace(SOH, '|')}", flush=True)

            if mt == "A":  # Logon -> reply Logon
                send(conn, frame("A", [("98", "0"), ("108", "30"), ("141", "Y")], seq))
                print(f">> A (logon ack) seq={seq}", flush=True)
                seq += 1

            elif mt == "D":  # NewOrderSingle -> the hostile ExecutionReport
                clordid = fields.get("11", "UNKNOWN")
                m = mode()
                head = [
                    ("37", "VENUE-ORD-9"),      # OrderID  -- BEFORE ClOrdID, as a real venue sends
                    ("11", clordid),            # ClOrdID
                    ("17", "EXEC-1"),           # ExecID
                    ("150", "2"),               # ExecType = FILL
                    ("39", "2"),                # OrdStatus = FILLED
                    ("55", fields.get("55", "EUR/USD")),
                    ("54", fields.get("54", "1")),
                    ("38", fields.get("38", "1000000")),
                    ("44", "1.08510"),
                    ("6", "1.08510"),
                    ("14", fields.get("38", "1000000")),
                ]

                if m == "shape":
                    # A benign ENTRY REORDER (the clearing firm now arrives first) — one-click Accept new
                    # order — mixed with a genuine regression and some shape churn.
                    er = head + [
                        ("151", "250000"),                                  # <- VALUE CHANGED (was 0). Real.
                        ("453", "2"),
                        ("448", "FIRMB"), ("447", "D"), ("452", "4"),       # clearing firm, now FIRST
                        ("448", "FIRMA"), ("447", "D"), ("452", "1"),       # executing firm, now SECOND
                        ("2376", "Y"),                                      # <- TAG ADDED
                        # 58 (Text) is NOT sent                             <- TAG MISSING
                        ("60", now()),
                    ]
                elif m == "swap":
                    # The two firms SWAP ROLES. Nothing moved; FIRMB is now the executing firm. This is a
                    # regression, and the reconcile view must not call it a reorder.
                    er = head + [
                        ("151", "0"),
                        ("453", "2"),
                        ("448", "FIRMB"), ("447", "D"), ("452", "1"),       # <- FIRMB now holds role 1
                        ("448", "FIRMA"), ("447", "D"), ("452", "4"),       # <- FIRMA now holds role 4
                        ("58", "filled|in full"),   # NOTE THE PIPE: a legal FIX value, and the trap this venue exists for
                        ("60", now()),
                    ]
                else:  # golden
                    er = head + [
                        ("151", "0"),
                        ("453", "2"),
                        ("448", "FIRMA"), ("447", "D"), ("452", "1"),       # executing firm
                        ("448", "FIRMB"), ("447", "D"), ("452", "4"),       # clearing firm
                        ("58", "filled|in full"),   # NOTE THE PIPE: a legal FIX value, and the trap this venue exists for
                        ("60", now()),
                    ]
                send(conn, frame("8", er, seq))
                print(f">> 8 (ExecutionReport, mode={m}) seq={seq}", flush=True)
                seq += 1

            elif mt == "1":  # TestRequest -> Heartbeat
                send(conn, frame("0", [("112", fields.get("112", "x"))], seq))
                seq += 1

            elif mt == "5":  # Logout
                send(conn, frame("5", [], seq))
                seq += 1
                print("logged out", flush=True)
                conn.close()
                return

    conn.close()


if __name__ == "__main__":
    main()
