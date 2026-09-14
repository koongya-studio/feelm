#!/usr/bin/env python3
"""Verify that the deployed Nginx-to-backend network is trusted by Tomcat."""

from __future__ import annotations

import ipaddress
import json
from pathlib import Path
import re
import subprocess
import sys


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
BACKEND_CONFIG = REPOSITORY_ROOT / "backend" / "src" / "main" / "resources" / "application.yml"
CONTAINERS = ("feelm-nginx", "feelm-backend")
Network = ipaddress.IPv4Network | ipaddress.IPv6Network
Address = ipaddress.IPv4Address | ipaddress.IPv6Address


def execute(*args: str) -> str:
    return subprocess.run(
        list(args),
        check=True,
        capture_output=True,
        text=True,
        timeout=15,
    ).stdout


def load_trusted_networks(config: str) -> list[Network]:
    match = re.search(r"(?m)^\s+internal-proxies:\s*(?P<ranges>[^#\r\n]+?)\s*$", config)
    if match is None:
        raise ValueError("internal-proxies 설정을 찾지 못했습니다")
    networks = [
        ipaddress.ip_network(value.strip(), strict=True)
        for value in match.group("ranges").split(",")
    ]
    if not networks:
        raise ValueError("internal-proxies 설정이 비어 있습니다")
    return networks


def inspect_shared_networks(
    containers: list[dict], network_details: list[dict], trusted: list[Network]
) -> list[tuple[str, Address, list[Network]]]:
    by_name = {item.get("Name", "").lstrip("/"): item for item in containers}
    if set(by_name) != set(CONTAINERS):
        raise ValueError("Nginx 또는 backend 컨테이너 검사 결과가 없습니다")

    nginx_networks = by_name["feelm-nginx"]["NetworkSettings"]["Networks"]
    backend_networks = by_name["feelm-backend"]["NetworkSettings"]["Networks"]
    shared = sorted(set(nginx_networks) & set(backend_networks))
    if not shared:
        raise ValueError("Nginx와 backend가 공유하는 Docker 네트워크가 없습니다")

    details_by_name = {item.get("Name"): item for item in network_details}
    results = []
    for name in shared:
        address = ipaddress.ip_address(nginx_networks[name]["IPAddress"])
        details = details_by_name.get(name)
        if details is None:
            raise ValueError("공유 Docker 네트워크 상세 정보가 없습니다")
        subnets = [
            ipaddress.ip_network(item["Subnet"], strict=True)
            for item in details.get("IPAM", {}).get("Config", [])
            if item.get("Subnet")
        ]
        if not subnets or not any(address in subnet for subnet in subnets):
            raise ValueError("Nginx 주소가 공유 Docker 네트워크 대역에 속하지 않습니다")
        if not any(address in network for network in trusted):
            raise ValueError("Nginx 주소가 backend 신뢰 목록에 속하지 않습니다")
        for subnet in subnets:
            same_version = [network for network in trusted if network.version == subnet.version]
            if not any(subnet.subnet_of(network) for network in same_version):
                raise ValueError("공유 Docker 네트워크 대역 전체가 backend 신뢰 목록에 속하지 않습니다")
        results.append((name, address, subnets))
    return results


def main() -> int:
    trusted = load_trusted_networks(BACKEND_CONFIG.read_text(encoding="utf-8"))
    containers = json.loads(execute("docker", "inspect", *CONTAINERS))
    network_names = sorted(
        set(containers[0]["NetworkSettings"]["Networks"])
        & set(containers[1]["NetworkSettings"]["Networks"])
    )
    if not network_names:
        raise ValueError("Nginx와 backend가 공유하는 Docker 네트워크가 없습니다")
    network_details = json.loads(execute("docker", "network", "inspect", *network_names))
    results = inspect_shared_networks(containers, network_details, trusted)
    for name, address, subnets in results:
        print(
            "NGINX_CLIENT_IP_TRUST: PASS "
            f"network={name} nginx_address={address} "
            f"subnets={','.join(str(subnet) for subnet in subnets)}"
        )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (KeyError, ValueError, json.JSONDecodeError, OSError, subprocess.SubprocessError) as exc:
        print(f"NGINX_CLIENT_IP_TRUST: FAIL ({type(exc).__name__})", file=sys.stderr)
        raise SystemExit(2)
