#!/usr/bin/env python3
"""TCP connectivity evidence only: never authenticates or reads application data."""
import argparse
import ipaddress
import json
import os
import pwd
import socket
import sys


def probe(host, port):
    try:
        with socket.create_connection((host, port), timeout=3):
            return "TCP_REACHABLE"
    except OSError as exc:
        # Refusal/timeout can also mean an unavailable service, not a working guard.
        return f"CONNECT_FAILED_NOT_PROOF_OF_ISOLATION:{type(exc).__name__}:{exc.errno}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host", help="Operator-approved target IP or public hostname")
    parser.add_argument("port", type=int)
    args = parser.parse_args()
    if pwd.getpwuid(os.geteuid()).pw_name != "feelm-ci-test":
        parser.error("Run as feelm-ci-test, not root or the operating Runner")
    if not 1 <= args.port <= 65535:
        parser.error("Invalid TCP port")
    addresses = sorted({str(ipaddress.ip_address(x[4][0])) for x in
                        socket.getaddrinfo(args.host, args.port, type=socket.SOCK_STREAM)})
    for address in addresses:
        print(json.dumps({"address": address, "port": args.port,
                          "result": probe(address, args.port)}))
    print("EVIDENCE_ONLY: compare baseline, guard counters and rootless-container probes")


if __name__ == "__main__":
    try:
        main()
    except OSError as exc:
        print(f"PROBE_ERROR: {type(exc).__name__}: {exc}", file=sys.stderr)
        sys.exit(1)
