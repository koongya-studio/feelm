#!/usr/bin/env python3
"""Temporary host OUTPUT guard. Default is a non-mutating plan; never edits UFW."""
import argparse
import ipaddress
import json
import os
import subprocess
import sys

TABLE = "feelm_ci_222"
ACCOUNT = "feelm-ci-test"
MARKER = "S15P21E106-222 temporary guard v1"


def run(*args, input=None):
    return subprocess.run(args, input=input, text=True, capture_output=True, check=True)


def rules(uid, denied):
    if uid < 1000:
        raise ValueError("Refusing system/root UID")
    addresses = sorted({str(ipaddress.ip_address(a)) for a in denied})
    if not addresses:
        raise ValueError("Supply every service/distributed public IP with --deny-ip")
    # Attach userdata at creation, not in a later declaration of an existing table.
    lines = [f'create table inet {TABLE} {{ comment "{MARKER}"; }}', f"table inet {TABLE} {{",
             " chain output { type filter hook output priority -10; policy accept;",
             f"  meta skuid {uid} jump restricted", " }", " chain restricted {"]
    # Deny explicit host addresses before allowing DNS. No ESTABLISHED bypass.
    for address in addresses:
        family = "ip" if ipaddress.ip_address(address).version == 4 else "ip6"
        lines.append(f"  {family} daddr {address} counter reject")
    lines += [
        "  ip daddr 127.0.0.53 udp dport 53 counter return",
        "  ip daddr 127.0.0.53 tcp dport 53 counter return",
        # Approved VPC resolver used by rootless bridge containers; DNS ports only.
        "  ip daddr 172.26.0.2 udp dport 53 counter return",
        "  ip daddr 172.26.0.2 tcp dport 53 counter return",
        # All IPv6 blocked in this initial pilot, including metadata and mapped paths.
        "  meta nfproto ipv6 counter reject",
        "  ip daddr { 0.0.0.0/8, 10.0.0.0/8, 100.64.0.0/10, 127.0.0.0/8, "
        "169.254.0.0/16, 172.16.0.0/12, 192.168.0.0/16, 224.0.0.0/4, 240.0.0.0/4 } counter reject",
        "  fib daddr type local counter reject",
        "  tcp dport { 80, 443 } counter return",
        "  counter reject", " }", "}"]
    return "\n".join(lines) + "\n"


def account_uid():
    import pwd
    import grp
    account = pwd.getpwnam(ACCOUNT)
    groups = {grp.getgrgid(g).gr_name for g in os.getgrouplist(ACCOUNT, account.pw_gid)}
    if groups != {ACCOUNT} or account.pw_dir != f"/home/{ACCOUNT}":
        raise ValueError("Unexpected account groups/home; review before proceeding")
    return account.pw_uid


def existing_table():
    data = json.loads(run("nft", "-j", "list", "tables").stdout)
    return any(x.get("table", {}).get("family") == "inet" and
               x.get("table", {}).get("name") == TABLE for x in data["nftables"])


def owned_table():
    data = json.loads(run("nft", "-j", "list", "table", "inet", TABLE).stdout)
    tables = [x["table"] for x in data["nftables"] if "table" in x]
    if not tables or tables[0].get("comment") != MARKER:
        raise ValueError("Table ownership mismatch; refusing to change it")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["plan", "check", "apply", "status", "rollback"], nargs="?", default="plan")
    parser.add_argument("--deny-ip", action="append", default=[], help="Explicit service/distributed public IP; repeat")
    parser.add_argument("--confirm", action="store_true", help="Confirm temporary apply or rollback")
    args = parser.parse_args()
    if args.action == "plan":
        print(rules(account_uid(), args.deny_ip), end="")
        return
    if os.geteuid() != 0:
        parser.error("This action requires sudo")
    if args.action == "status":
        print(run("nft", "list", "table", "inet", TABLE).stdout, end="")
        return
    if args.action == "rollback":
        if not args.confirm:
            parser.error("Rollback requires --confirm; pause test Runner first")
        if existing_table():
            owned_table()
            run("nft", "delete", "table", "inet", TABLE)
        print("GUARD_REMOVED: test account network isolation is no longer enforced")
        return
    source = rules(account_uid(), args.deny_ip)
    if existing_table():
        raise ValueError("Guard already exists; inspect it. No implicit replacement allowed")
    run("nft", "--check", "--file", "-", input=source)
    if args.action == "check":
        print("NFT_SYNTAX_CHECK: PASS (no rules installed)")
        return
    if not args.confirm:
        parser.error("Apply requires --confirm; stop test workloads first")
    # One nft transaction: no half-installed IPv4/IPv6 policy.
    run("nft", "--file", "-", input=source)
    try:
        owned_table()
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as exc:
        raise ValueError("POST_APPLY_VERIFICATION_FAILED: rules may be installed; "
                         "keep test workloads paused and inspect the table. "
                         "No automatic deletion or ownership-check bypass performed") from exc
    print("TEMPORARY_GUARD_APPLIED: runtime only; not a complete isolation proof")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        if isinstance(exc, subprocess.CalledProcessError):
            print(exc.stderr, file=sys.stderr)
        sys.exit(1)
