import importlib.util
import ipaddress
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location(
    "verify_nginx_client_ip_trust",
    Path(__file__).with_name("verify-nginx-client-ip-trust.py"),
)
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class NginxClientIpTrustTest(unittest.TestCase):
    def setUp(self) -> None:
        self.containers = [
            {
                "Name": "/feelm-nginx",
                "NetworkSettings": {
                    "Networks": {"feelm_default": {"IPAddress": "172.24.0.5"}}
                },
            },
            {
                "Name": "/feelm-backend",
                "NetworkSettings": {
                    "Networks": {"feelm_default": {"IPAddress": "172.24.0.6"}}
                },
            },
        ]
        self.details = [
            {
                "Name": "feelm_default",
                "IPAM": {"Config": [{"Subnet": "172.24.0.0/16"}]},
            }
        ]
        self.trusted = [ipaddress.ip_network("172.16.0.0/12")]

    def test_loads_the_committed_internal_proxy_ranges(self) -> None:
        config = verify.BACKEND_CONFIG.read_text(encoding="utf-8")
        self.assertIn(ipaddress.ip_network("172.16.0.0/12"), verify.load_trusted_networks(config))

    def test_accepts_a_shared_network_fully_inside_the_trust_range(self) -> None:
        result = verify.inspect_shared_networks(self.containers, self.details, self.trusted)
        self.assertEqual(result[0][0], "feelm_default")
        self.assertEqual(str(result[0][1]), "172.24.0.5")

    def test_rejects_a_shared_network_outside_the_trust_range(self) -> None:
        self.containers[0]["NetworkSettings"]["Networks"]["feelm_default"][
            "IPAddress"
        ] = "192.168.10.5"
        self.containers[1]["NetworkSettings"]["Networks"]["feelm_default"][
            "IPAddress"
        ] = "192.168.10.6"
        self.details[0]["IPAM"]["Config"][0]["Subnet"] = "192.168.10.0/24"

        with self.assertRaisesRegex(ValueError, "신뢰 목록"):
            verify.inspect_shared_networks(self.containers, self.details, self.trusted)

    def test_rejects_a_partially_trusted_shared_subnet(self) -> None:
        self.details[0]["IPAM"]["Config"][0]["Subnet"] = "172.0.0.0/8"

        with self.assertRaisesRegex(ValueError, "대역 전체"):
            verify.inspect_shared_networks(self.containers, self.details, self.trusted)

    def test_rejects_containers_without_a_shared_network(self) -> None:
        self.containers[1]["NetworkSettings"]["Networks"] = {
            "other": {"IPAddress": "172.24.1.6"}
        }

        with self.assertRaisesRegex(ValueError, "공유"):
            verify.inspect_shared_networks(self.containers, self.details, self.trusted)


if __name__ == "__main__":
    unittest.main()
