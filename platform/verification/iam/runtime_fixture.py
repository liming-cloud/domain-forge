"""IAM 验证共享的临时资源、证书和 HTTP 夹具，不承担业务断言决策。"""

import base64
import json
import os
from pathlib import Path
import secrets
import socket
import ssl
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request


class RuntimeFixture:
    """每次运行独立拥有秘密目录、进程和容器；调用方在 finally 关闭。"""

    def __init__(self):
        self.private = Path(tempfile.mkdtemp(prefix="domain-forge-iam-http-")).resolve()
        self.private.chmod(0o700)
        self.env = dict(os.environ)
        self.env["CERT_PASSWORD"] = secrets.token_urlsafe(32)
        self.containers = []
        self.processes = []
        self.cases = []

    def secretfile(self, name, values):
        path = self.private / name
        path.write_text(
            "".join(key + "=" + str(value) + "\n" for key, value in values.items())
        )
        path.chmod(0o600)
        return path

    def close(self, stop_seconds=None, save_logs=False):
        for process, log in self.processes:
            if process.poll() is None:
                process.terminate()
                try:
                    # Nacos原生排空10秒后还需收尾；强杀仅用于最终清理。
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            log.close()
        for index, container in reversed(list(enumerate(self.containers))):
            if save_logs:
                with (self.private / f"container-{index}.log").open("w") as log:
                    subprocess.run(
                        ["docker", "logs", container],
                        stdout=log,
                        stderr=subprocess.STDOUT,
                    )
            if stop_seconds is not None:
                subprocess.run(
                    ["docker", "stop", "-t", str(stop_seconds), container],
                    capture_output=True,
                )
            subprocess.run(["docker", "rm", "-f", "-v", container], capture_output=True)

    def run(self, cmd, **kw):
        return subprocess.check_output(
            cmd, env=self.env, stderr=subprocess.STDOUT, **kw
        )

    def docker(self, *args):
        return self.run(["docker", *args], text=True).strip()

    def start_nacos(self, network=None):
        """独占临时注册中心，启用原生鉴权；HTTP和gRPC端口遵守原生+1000约定。"""
        image = "nacos/nacos-server@sha256:13e74786507abbacebe080fae2fc9c05aa2591eb99c97a0a78ff0e72beb21b13"
        inspection = subprocess.run(["docker", "image", "inspect", image], env=self.env,
            capture_output=True, text=True)
        pulled = False
        if inspection.returncode:
            if "No such image" not in inspection.stderr:
                (self.private / "nacos-image-inspect.log").write_text(inspection.stderr)
                raise RuntimeError("Nacos image inspection failed; private native log retained")
            with (self.private / "nacos-image-pull.log").open("w") as log:
                preparation = subprocess.run(["docker", "pull", image], env=self.env,
                    stdout=log, stderr=subprocess.STDOUT, timeout=300)
            if preparation.returncode:
                raise RuntimeError("Nacos fixed-digest image preparation failed; private native log retained")
            pulled = True
            inspected = json.loads(self.docker("image", "inspect", image))[0]
        else:
            inspected = json.loads(inspection.stdout)[0]
        assert image in inspected["RepoDigests"], "Nacos image digest differs"
        for _ in range(100):
            first = secrets.randbelow(30000) + 18000
            sockets = [socket.socket(), socket.socket()]
            try:
                for connection, port in zip(sockets, (first, first + 1000)):
                    connection.bind(("127.0.0.1", port))
                break
            except OSError:
                pass
            finally:
                for connection in sockets:
                    connection.close()
        else:
            raise AssertionError("no free Nacos HTTP/gRPC port pair")
        password = secrets.token_urlsafe(32)
        settings = self.secretfile("nacos.env", {
            "MODE": "standalone", "NACOS_AUTH_ENABLE": "true",
            "NACOS_AUTH_ADMIN_ENABLE": "true", "NACOS_AUTH_CONSOLE_ENABLE": "true",
            "NACOS_AUTH_TOKEN": base64.b64encode(secrets.token_bytes(48)).decode(),
            "NACOS_AUTH_IDENTITY_KEY": "verification",
            "NACOS_AUTH_IDENTITY_VALUE": secrets.token_urlsafe(32),
            "JVM_XMS": "256m", "JVM_XMX": "512m", "JVM_XMN": "128m",
        })
        command = ["run", "-d", "--label", "org.domainforge.verification=iam-nacos",
            "--env-file", str(settings), "-p", f"127.0.0.1:{first}:8848",
            "-p", f"127.0.0.1:{first + 1000}:9848"]
        if network:
            command += ["--network", network, "--network-alias", "nacos"]
        container = self.docker(*command, image)
        self.containers.append(container)
        self.nacos = {"container": container, "address": f"127.0.0.1:{first}",
            "image": image, "image_pulled": pulled, "namespace": "public", "group": "IAM_VERIFY_" + secrets.token_hex(6)}
        for _ in range(180):
            try:
                status, body = self.nacos_request("POST", "/v3/auth/user/admin", {"password": password}, authenticated=False)
                if status == 200:
                    break
            except (OSError, ValueError):
                pass
            time.sleep(.25)
        else:
            raise AssertionError("Nacos initialization timeout; private logs retained")
        status, body = self.nacos_request("POST", "/v3/auth/user/login",
            {"username": "nacos", "password": password}, authenticated=False)
        assert status == 200 and "accessToken" in body, "Nacos authenticated login failed"
        self.nacos_token = body["accessToken"]
        rejected, _ = self.nacos_request("GET", "/v3/admin/ns/instance/list",
            {"serviceName": "iam"}, authenticated=False)
        assert rejected in (401, 403), "Nacos admin API permits anonymous discovery"
        self.env.update(NACOS_SERVER_ADDR=self.nacos["address"], NACOS_USERNAME="nacos",
            NACOS_PASSWORD=password, NACOS_NAMESPACE="public", NACOS_GROUP=self.nacos["group"],
            IAM_NACOS_SERVER_ADDR=self.nacos["address"], IAM_NACOS_USERNAME="nacos",
            IAM_NACOS_PASSWORD=password, IAM_NACOS_NAMESPACE="public", IAM_NACOS_GROUP=self.nacos["group"])
        self.nacos["anonymous_status"] = rejected
        return dict(self.nacos)

    def nacos_request(self, method, path, values=None, authenticated=True):
        """测试控制面只使用原生API；访问令牌不进入URL或公共报告。"""
        encoded = urllib.parse.urlencode(values or {}).encode()
        url = "http://" + self.nacos["address"] + "/nacos" + path
        headers = {"accessToken": self.nacos_token} if authenticated else {}
        data = None
        if method == "GET":
            url += "?" + encoded.decode()
        else:
            data = encoded
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        request = urllib.request.Request(url, headers=headers, data=data, method=method)
        try:
            with urllib.request.urlopen(request, timeout=3) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read())

    def nacos_instances(self, service="iam"):
        status, body = self.nacos_request("GET", "/v3/admin/ns/instance/list", {
            "namespaceId": self.nacos["namespace"], "groupName": self.nacos["group"],
            "serviceName": service})
        if status == 404 and "not found" in str(body.get("data", "")):
            return []
        assert status == 200 and body.get("code") == 0, "Nacos instance read failed"
        return body["data"]

    def nacos_enable(self, instance, enabled, service="iam"):
        status, body = self.nacos_request("PUT", "/v3/admin/ns/instance/partial", {
            "namespaceId": self.nacos["namespace"], "groupName": self.nacos["group"],
            "serviceName": service, "ip": instance["ip"], "port": instance["port"],
            "clusterName": instance.get("clusterName", "DEFAULT"), "ephemeral": "true",
            "enabled": str(enabled).lower()})
        assert status == 200 and body.get("code") == 0, "Nacos instance update failed"

    def certificate(self, name, san):
        self.run(
            [
                "openssl",
                "req",
                "-new",
                "-newkey",
                "rsa:2048",
                "-nodes",
                "-keyout",
                str(self.private / f"{name}.key"),
                "-out",
                str(self.private / f"{name}.csr"),
                "-subj",
                f"/CN={name}",
            ]
        )
        (self.private / f"{name}.ext").write_text(
            f"subjectAltName={san}\nextendedKeyUsage=serverAuth,clientAuth\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\n"
        )
        self.run(
            [
                "openssl",
                "x509",
                "-req",
                "-in",
                str(self.private / f"{name}.csr"),
                "-CA",
                str(self.private / "ca.crt"),
                "-CAkey",
                str(self.private / "ca.key"),
                "-CAcreateserial",
                "-out",
                str(self.private / f"{name}.crt"),
                "-days",
                "1",
                "-extfile",
                str(self.private / f"{name}.ext"),
            ]
        )
        self.run(
            [
                "openssl",
                "pkcs12",
                "-export",
                "-in",
                str(self.private / f"{name}.crt"),
                "-inkey",
                str(self.private / f"{name}.key"),
                "-certfile",
                str(self.private / "ca.crt"),
                "-out",
                str(self.private / f"{name}.p12"),
                "-passout",
                "env:CERT_PASSWORD",
            ]
        )

    def tls(self, name=None):
        context = ssl.create_default_context(cafile=str(self.private / "ca.crt"))
        if name:
            context.load_cert_chain(
                str(self.private / f"{name}.crt"), str(self.private / f"{name}.key")
            )
        return context

    def http(
        self, base, path, body=None, token=None, method=None, cert=None, headers=None
    ):
        headers = dict(headers or {})
        if token:
            headers["Authorization"] = "Bearer " + token
        if body is not None:
            headers.setdefault("Content-Type", "application/json")
        request = urllib.request.Request(
            base + path,
            headers=headers,
            data=json.dumps(body).encode() if body is not None else None,
            method=method,
        )
        try:
            with urllib.request.urlopen(
                request, context=self.tls(cert), timeout=6
            ) as response:
                status = response.status
                payload = response.read()
                response_headers = dict(response.headers)
        except urllib.error.HTTPError as error:
            status = error.code
            payload = error.read()
            response_headers = dict(error.headers)
        try:
            data = json.loads(payload)
        except json.JSONDecodeError:
            data = {"invalid_json": True}
        return (status, data, response_headers)

    def check(self, case, response, expected):
        status, data, headers = response
        self.cases.append(
            {
                "case": case,
                "actual": status,
                "expected": expected,
                "code": data.get("code"),
            }
        )
        assert status == expected, (case, status, expected, data.get("code"))
        if pathcheck := ("code" in data):
            assert set(data) == {"code", "message", "data"}, (case, "envelope")
        return data.get("data")
