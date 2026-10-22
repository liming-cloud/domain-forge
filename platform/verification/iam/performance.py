"""本地实际资源负载验证，秘密和原始日志只保存在0700临时目录。"""

from pathlib import Path
from runtime_fixture import RuntimeFixture
import argparse
import base64
import csv
import hashlib
import json
import math
import os
import secrets
import shutil
import ssl
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid


parser = argparse.ArgumentParser(
    description="固定两实例资源及五分钟混合负载，结果不代表生产SLA"
)
parser.add_argument("--code", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--consumer", type=Path, help="另行测量独立消费者经Feign发现IAM的授权路径")
args = parser.parse_args()
code = args.code.resolve()
art = args.output.resolve()
art.mkdir(parents=True, exist_ok=True)
tag = "performance-feign" if args.consumer else "performance"
fixture = RuntimeFixture()
private = fixture.private
env = fixture.env
containers = fixture.containers
cases = fixture.cases


def login(base, name, password):
    return fixture.check(
        "login-" + name,
        fixture.http(
            base, "/api/auth/login", {"loginName": name, "password": password}
        ),
        200,
    )["token"]


def sql(query):
    return fixture.docker(
        "exec",
        containers[0],
        "psql",
        "-U",
        "iam",
        "-d",
        "iam",
        "-At",
        "-v",
        "ON_ERROR_STOP=1",
        "-c",
        query,
    )


def redis_commands():
    """独占临时Redis的原生命令统计；仅记录服务端累计执行量，不当作网络时延。"""
    raw = fixture.docker(
        "run",
        "--rm",
        "--network",
        network,
        "--env-file",
        str(redis_env),
        redisimage,
        "redis-cli",
        "-h",
        "redis",
        "INFO",
        "commandstats",
    )
    return {
        line.split(":", 1)[0]: {
            key: float(value)
            for key, value in (
                pair.split("=", 1) for pair in line.split(":", 1)[1].split(",")
            )
            if key in {"calls", "usec"}
        }
        for line in raw.splitlines()
        if line.startswith("cmdstat_")
    }


jre = "eclipse-temurin@sha256:e8ffcc88eeec92a9860c5b574dfedfb9a7cef2a5cc7de8e4f2d029c7e8bbd2df"
pgimage = (
    "postgres@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15"
)
redisimage = (
    "redis@sha256:c88d347edef6249a6d2293f926f1eeb48bd40c57cbcd02c07f52e7f1fd2cb46b"
)
network = "iam-load-" + secrets.token_hex(6)
stopped = threading.Event()
measurements = []
output = private / "output"
output.mkdir()
(private / "profile").mkdir()

shutil.copy2(code / "services/iam/target/iam-0.3.0-iam.2.jar", private / "iam.jar")
artifact = private / "iam.jar"
checksum = hashlib.sha256(artifact.read_bytes()).hexdigest()
iamcontainers = []


def sample(bases):
    while not stopped.is_set():
        sample = {"time": time.time(), "gauges": []}
        for base in bases:
            try:
                samples = {}
                for name in ["iam.password.active", "iam.password.queued"]:
                    with urllib.request.urlopen(
                        base + "/actuator/metrics/" + name,
                        context=fixture.tls(),
                        timeout=2,
                    ) as response:
                        samples[name] = json.load(response)["measurements"][0]["value"]
                sample["gauges"].append(samples)
            except Exception as error:
                sample["error"] = type(error).__name__
        measurements.append(sample)
        stopped.wait(0.2)


try:
    fixture.docker("network", "create", network)
    fixture.run(
        [
            "openssl",
            "req",
            "-x509",
            "-newkey",
            "rsa:2048",
            "-nodes",
            "-keyout",
            str(private / "ca.key"),
            "-out",
            str(private / "ca.crt"),
            "-days",
            "1",
            "-subj",
            "/CN=iam-load-test-ca",
            "-addext",
            "keyUsage=critical,keyCertSign,cRLSign",
        ]
    )
    fixture.certificate("server", "DNS:localhost,DNS:iam-a,DNS:iam-b,DNS:consumer,IP:127.0.0.1")
    fixture.certificate("consumer", "URI:urn:domainforge:project-alpha:consumer")
    fixture.run(
        [
            "keytool",
            "-importcert",
            "-noprompt",
            "-keystore",
            str(private / "trust.p12"),
            "-storetype",
            "PKCS12",
            "-storepass:env",
            "CERT_PASSWORD",
            "-alias",
            "ca",
            "-file",
            str(private / "ca.crt"),
        ]
    )
    for key in private.glob("*.key"):
        key.chmod(0o600)
    pgpass = secrets.token_urlsafe(32)
    redispass = secrets.token_urlsafe(32)
    pgfile = fixture.secretfile(
        "pg.env",
        {"POSTGRES_USER": "iam", "POSTGRES_DB": "iam", "POSTGRES_PASSWORD": pgpass},
    )
    pg = fixture.docker(
        "run",
        "-d",
        "--network",
        network,
        "--network-alias",
        "pg",
        "--env-file",
        str(pgfile),
        "-p",
        "127.0.0.1::5432",
        "--tmpfs",
        "/var/lib/postgresql",
        pgimage,
        "-c",
        "shared_preload_libraries=pg_stat_statements",
    )
    containers.append(pg)
    rc = private / "redis.conf"
    rc.write_text(
        f'requirepass {redispass}\nmaxmemory 64mb\nmaxmemory-policy noeviction\nsave ""\n'
    )
    rc.chmod(0o600)
    redis = fixture.docker(
        "create",
        "--network",
        network,
        "--network-alias",
        "redis",
        "-p",
        "127.0.0.1::6379",
        redisimage,
        "redis-server",
        "/data/redis.conf",
    )
    containers.append(redis)
    fixture.docker("cp", str(rc), redis + ":/data/redis.conf")
    fixture.docker("start", redis)
    pgport = fixture.docker("port", pg, "5432/tcp").rsplit(":", 1)[1]
    rp = fixture.docker("port", redis, "6379/tcp").rsplit(":", 1)[1]
    env.update(
        IAM_DB_URL=f"jdbc:postgresql://127.0.0.1:{pgport}/iam",
        IAM_DB_USER="iam",
        IAM_DB_PASSWORD=pgpass,
        IAM_REDIS_HOST="127.0.0.1",
        IAM_REDIS_PORT=rp,
        IAM_REDIS_PASSWORD=redispass,
        IAM_FINGERPRINT_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),
        IAM_SERVER_KEYSTORE=str(private / "server.p12"),
        IAM_SERVER_KEYSTORE_PASSWORD=env["CERT_PASSWORD"],
        IAM_TRUSTSTORE=str(private / "trust.p12"),
        IAM_TRUSTSTORE_PASSWORD=env["CERT_PASSWORD"],
        IAM_PROJECT_ID="project-alpha",
        IAM_TRUSTED_SERVICE_IDENTITIES="urn:domainforge:project-alpha:consumer",
    )
    for _ in range(80):
        try:
            sql("select 1")
            break
        except subprocess.CalledProcessError:
            time.sleep(0.2)
    sql("CREATE EXTENSION pg_stat_statements")
    redis_env = fixture.secretfile("redis-client.env", {"REDISCLI_AUTH": redispass})
    for _ in range(50):
        try:
            if (
                fixture.docker(
                    "run",
                    "--rm",
                    "--network",
                    network,
                    "--env-file",
                    str(redis_env),
                    redisimage,
                    "redis-cli",
                    "-h",
                    "redis",
                    "ping",
                )
                == "PONG"
            ):
                break
        except subprocess.CalledProcessError:
            pass
        time.sleep(0.1)
    else:
        raise AssertionError("Redis preparation did not become ready")
    nacos = fixture.start_nacos(network)
    password = "A real performance 密码 2026!"
    pf = private / "initial-password"
    pf.write_text(password)
    pf.chmod(0o600)
    with (private / "initialize.log").open("w") as log:
        subprocess.run(
            [
                "java",
                "-XX:ActiveProcessorCount=2",
                "-jar",
                str(artifact),
                "initialize-admin",
                "--login-name=admin.initial",
                "--password-file=" + str(pf),
            ],
            env=env,
            stdout=log,
            stderr=subprocess.STDOUT,
            check=True,
            timeout=40,
        )
    local = {k: v for k, v in env.items() if k.startswith(("IAM_", "NACOS_"))}
    local.update(
        IAM_DB_URL="jdbc:postgresql://pg:5432/iam",
        IAM_REDIS_HOST="redis",
        IAM_REDIS_PORT="6379",
        IAM_SERVER_KEYSTORE="/run/iam/server.p12",
        IAM_TRUSTSTORE="/run/iam/trust.p12",
        SERVER_PORT="8443",
        IAM_MANAGEMENT_PORT="9091",
        MANAGEMENT_SERVER_ADDRESS="0.0.0.0",
        NACOS_SERVER_ADDR="nacos:8848",
        IAM_NACOS_SERVER_ADDR="nacos:8848",
        IAM_DISCOVERY_ENABLED="true",
    )
    iamenv = fixture.secretfile("iam.env", local)
    bases = []
    consumer_limits = None
    consumer_checksum = None
    management = []
    limits = []
    for name in ["iam-a", "iam-b"]:
        container = fixture.docker(
            "run",
            "-d",
            "--network",
            network,
            "--network-alias",
            name,
            "--cpus",
            "2",
            "--memory",
            "1g",
            "--env-file",
            str(iamenv),
            "-e",
            "IAM_DISCOVERY_IP=" + name,
            "-p",
            "127.0.0.1::8443",
            "-p",
            "127.0.0.1::9091",
            "-v",
            str(private) + ":/run/iam:ro",
            "-v",
            str(private / "profile") + ":/profile",
            "-v",
            str(artifact) + ":/app.jar:ro",
            jre,
            "java",
            "-Xmx512m",
            "-XX:ActiveProcessorCount=2",
            "-XX:StartFlightRecording=filename=/profile/"
            + name
            + ".jfr,settings=profile,maxsize=64m,dumponexit=true",
            "-jar",
            "/app.jar",
            "--spring.main.banner-mode=off",
        )
        containers.append(container)
        iamcontainers.append(container)
        bases.append(
            "https://localhost:"
            + fixture.docker("port", container, "8443/tcp").rsplit(":", 1)[1]
        )
        management.append(
            "https://localhost:"
            + fixture.docker("port", container, "9091/tcp").rsplit(":", 1)[1]
        )
        inspect = json.loads(fixture.docker("inspect", container))[0]
        limits.append(
            {
                "cpus": inspect["HostConfig"]["NanoCpus"] / 1e9,
                "memory": inspect["HostConfig"]["Memory"],
                "image": inspect["Image"],
            }
        )
    for base in bases:
        for _ in range(200):
            try:
                if fixture.http(base, "/api/me")[0] == 401:
                    break
            except Exception:
                pass
            time.sleep(0.2)
        else:
            raise AssertionError("IAM readiness failed")
    token = login(bases[0], "admin.initial", password)
    (private / "access-token").write_text(token)
    (private / "access-token").chmod(0o600)
    # 正常授权主体与持续登录主体分开，避免原生默认12会话上限淘汰固定校验令牌。
    login_name = "load.user"
    login_password = "A real load 用户密码 2026!"
    invitation = fixture.check(
        "load-invitation",
        fixture.http(
            bases[0],
            "/api/invitations",
            {"operationId": str(uuid.uuid4()), "loginName": login_name},
            token,
        ),
        200,
    )
    fixture.check(
        "load-registration",
        fixture.http(
            bases[0],
            "/api/auth/invitations/accept",
            {
                "operationId": str(uuid.uuid4()),
                "invitationSecret": invitation["secret"],
                "loginName": login_name,
                "password": login_password,
            },
        ),
        200,
    )
    (private / "login.json").write_text(
        json.dumps({"loginName": login_name, "password": login_password})
    )
    (private / "login.json").chmod(0o600)
    # 匿名401仅证明HTTP可达。计时前实际检查两个实例的依赖就绪和mTLS认证路径。
    readiness = []
    for index, base in enumerate(bases):
        statuses = []
        for _ in range(40):
            state = fixture.http(management[index], "/actuator/health/readiness")[0]
            authenticated = fixture.http(
                base, "/internal/access/current", token=token, cert="consumer"
            )[0]
            statuses.append({"readiness": state, "authorization": authenticated})
            if state == 200 and authenticated == 200:
                break
            time.sleep(0.2)
        else:
            raise AssertionError("authenticated preparation did not become ready")
        readiness.append(statuses)
    if args.consumer:
        consumer_artifact = private / "consumer.jar"
        shutil.copy2(args.consumer.resolve(), consumer_artifact)
        consumer_checksum = hashlib.sha256(consumer_artifact.read_bytes()).hexdigest()
        consumer_password = secrets.token_urlsafe(32)
        consumer_pg_env = fixture.secretfile("consumer-pg.env", {
            "POSTGRES_USER": "consumer", "POSTGRES_DB": "consumer", "POSTGRES_PASSWORD": consumer_password})
        consumer_pg = fixture.docker("run", "-d", "--network", network, "--network-alias", "consumer-pg",
            "--env-file", str(consumer_pg_env), "--tmpfs", "/var/lib/postgresql", pgimage)
        containers.append(consumer_pg)
        for _ in range(80):
            if subprocess.run(["docker", "exec", consumer_pg, "pg_isready", "-U", "consumer", "-d", "consumer"],
                capture_output=True).returncode == 0:
                break
            time.sleep(.25)
        else:
            raise AssertionError("independent consumer database did not become ready")
        consumer_tls = private / "consumer-tls"
        consumer_tls.mkdir(mode=0o700)
        for name in ("consumer.p12", "server.p12", "trust.p12"):
            shutil.copy2(private / name, consumer_tls / name)
        consumer_env = {key: value for key, value in env.items() if key.startswith("NACOS_")}
        consumer_env.update(NACOS_SERVER_ADDR="nacos:8848", DB_URL="jdbc:postgresql://consumer-pg:5432/consumer",
            DB_USER="consumer", DB_PASSWORD=consumer_password, SERVER_ADDRESS="0.0.0.0", SERVER_PORT="8443",
            MANAGEMENT_PORT="9091", CONSUMER_CLIENT_STORE="/run/consumer/consumer.p12",
            CONSUMER_TRUST_STORE="/run/consumer/trust.p12", CONSUMER_SERVER_STORE="/run/consumer/server.p12",
            CONSUMER_STORE_PASSWORD=env["CERT_PASSWORD"])
        assert not any(key.startswith(("IAM_DB", "IAM_REDIS")) for key in consumer_env)
        consumer_file = fixture.secretfile("consumer.env", consumer_env)
        container = fixture.docker("run", "-d", "--network", network, "--network-alias", "consumer",
            "--cpus", "2", "--memory", "1g", "--env-file", str(consumer_file),
            "-p", "127.0.0.1::8443", "-v", str(consumer_tls) + ":/run/consumer:ro",
            "-v", str(consumer_artifact) + ":/app.jar:ro", jre,
            "java", "-Xmx512m", "-XX:ActiveProcessorCount=2", "-jar", "/app.jar", "--spring.main.banner-mode=off")
        containers.append(container)
        consumer_base = "https://localhost:" + fixture.docker("port", container, "8443/tcp").rsplit(":", 1)[1]
        for _ in range(200):
            try:
                if fixture.http(consumer_base, "/verification/read", token=token)[0] == 200:
                    break
            except (OSError, ValueError):
                pass
            time.sleep(.2)
        else:
            raise AssertionError("Feign consumer authenticated preparation failed")
        state = json.loads(fixture.docker("inspect", container))[0]
        consumer_limits = {"cpus": state["HostConfig"]["NanoCpus"] / 1e9,
            "memory": state["HostConfig"]["Memory"], "image": state["Image"]}
    fixture.run(
        ["javac", "-d", str(private), str(code / "verification/iam/IdentityLoad.java")]
    )
    load_values = {"STORE_PASSWORD": env["CERT_PASSWORD"]}
    if args.consumer:
        load_values["AUTHORIZATION_URL"] = "https://consumer:8443/verification/read"
    loadenv = fixture.secretfile("load.env", load_values)
    sql("SELECT pg_stat_statements_reset()")
    redis_before = redis_commands()
    start = int(time.time() * 1000) + 30000
    clients = []
    for n in range(8):
        client = fixture.docker(
            "run",
            "-d",
            "--network",
            network,
            "--memory",
            "160m",
            "--env-file",
            str(loadenv),
            "-v",
            str(private) + ":/work:ro",
            "-v",
            str(output) + ":/output",
            jre,
            "java",
            "-Xmx80m",
            "-cp",
            "/work",
            "IdentityLoad",
            str(n),
            str(start),
        )
        containers.append(client)
        clients.append(client)
    sampler = threading.Thread(target=sample, args=(management,), daemon=True)
    sampler.start()
    print(
        json.dumps(
            {
                "progress": "measuring-5-minute-mixed-load",
                "start": start,
                "limits": limits,
            }
        ),
        flush=True,
    )
    client_exits = [fixture.docker("wait", client) for client in clients]
    assert all(value == "0" for value in client_exits), (
        "load client failed",
        client_exits,
    )
    stopped.set()
    sampler.join(timeout=5)
    result = {}
    for kind, expected, limit in [("login", 600, 1000), ("authorization", 6000, 300)]:
        rows = [
            r
            for path in output.glob(kind + "-*.csv")
            for r in csv.DictReader(path.open())
        ]
        values = sorted(float(r["milliseconds"]) for r in rows)
        result[kind] = {
            "requests": len(rows),
            "unexpected_errors": sum(
                r["status"] != "200" or r["semantic"] != "true" for r in rows
            ),
            "p50_ms": values[math.ceil(len(values) * 0.5) - 1],
            "p95_ms": values[math.ceil(len(values) * 0.95) - 1],
            "p99_ms": values[math.ceil(len(values) * 0.99) - 1],
            "max_ms": max(values),
            "max_schedule_lag_ms": max(int(r["schedule_lag_ms"]) for r in rows),
            "limit_p95_ms": limit,
        }
        assert (
            len(rows) == expected
            and result[kind]["unexpected_errors"] == 0
            and result[kind]["p95_ms"] <= limit
            and result[kind]["max_schedule_lag_ms"] < 1000
        ), (kind, result[kind])
    statements = json.loads(
        sql(
            "SELECT coalesce(json_agg(x),'[]') FROM (SELECT query,calls,rows,total_exec_time FROM pg_stat_statements WHERE query LIKE '%iam_%' ORDER BY calls DESC) x"
        )
    )
    redis_after = redis_commands()
    redis_observation = {
        command: {
            metric: value - redis_before.get(command, {}).get(metric, 0)
            for metric, value in values.items()
        }
        for command, values in redis_after.items()
    }
    gauges = {
        "samples": len(measurements),
        "failures": sum("error" in s for s in measurements),
        "active_max": max(
            (g["iam.password.active"] for s in measurements for g in s["gauges"]),
            default=-1,
        ),
        "queued_max": max(
            (g["iam.password.queued"] for s in measurements for g in s["gauges"]),
            default=-1,
        ),
    }
    assert (
        gauges["samples"] > 100
        and gauges["failures"] == 0
        and 0 <= gauges["active_max"] <= 2
        and 0 <= gauges["queued_max"] <= 8
    ), gauges
    # 正常负载结束后单独验证突发拒绝与真实一分钟地址窗口恢复，不混入正常分位数。
    import concurrent.futures, collections

    began = time.monotonic()

    def burst(n):
        return fixture.http(
            bases[n % 2],
            "/api/auth/login",
            {"loginName": login_name, "password": login_password},
        )[0]

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        statuses = list(pool.map(burst, range(40)))
    overload = {
        "requests": len(statuses),
        "statuses": dict(collections.Counter(statuses)),
        "seconds": round(time.monotonic() - began, 3),
    }
    assert set(statuses) <= {200, 429} and 429 in statuses and 200 in statuses, overload
    fixture.check(
        "authorization-survives-login-overload",
        fixture.http(
            bases[1], "/internal/access/current", token=token, cert="consumer"
        ),
        200,
    )
    window = time.monotonic()
    while time.monotonic() - window < 61:
        time.sleep(min(10, 61 - (time.monotonic() - window)))
    fixture.check(
        "address-window-recovers",
        fixture.http(
            bases[1],
            "/api/auth/login",
            {"loginName": login_name, "password": login_password},
        ),
        200,
    )
    overload["actual_recovery_wait_seconds"] = round(time.monotonic() - window, 3)
    assert hashlib.sha256(artifact.read_bytes()).hexdigest() == checksum
    report = {
        "ok": True,
        "iam_sha256": checksum,
        "consumer_sha256": locals().get("consumer_checksum"),
        "authorization_path": "consumer -> Feign/LoadBalancer -> Nacos-discovered IAM" if args.consumer else "direct IAM",
        "consumer_limits": locals().get("consumer_limits"),
        "nacos": locals().get("nacos"),
        "host_docker": fixture.docker(
            "info", "--format", "{{.NCPU}} CPUs {{.MemTotal}} memory"
        ),
        "limits": limits,
        "duration_seconds": 300,
        "preparation_readiness": readiness,
        "sources": 8,
        "results": result,
        "gauges": gauges,
        "overload": overload,
        "sql": statements,
        "redis_commands": redis_observation,
        "observation_scope": "SQL/Redis server execution totals plus end-to-end HTTP/TLS latency; not a network or connection-wait breakdown",
        "evidence": str(private),
        "scope": "bounded local mixed load, not enterprise capacity or production SLA",
    }
    (art / (tag + ".json")).write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    )
    print(json.dumps({"ok": True, "results": result, "gauges": gauges}))
except Exception as error:
    report = {
        "ok": False,
        "iam_sha256": checksum,
        "consumer_sha256": locals().get("consumer_checksum"),
        "authorization_path": "consumer -> Feign/LoadBalancer -> Nacos-discovered IAM" if args.consumer else "direct IAM",
        "consumer_limits": locals().get("consumer_limits"),
        "nacos": locals().get("nacos"),
        "type": type(error).__name__,
        "detail": (
            str(error)
            if not isinstance(error, subprocess.CalledProcessError)
            else "preparation failed; private logs retained"
        ),
        "private": str(private),
        "partial_results": locals().get("result", {}),
        "cases": cases,
    }
    (art / (tag + ".json")).write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    )
    print(json.dumps(report, ensure_ascii=False))
    raise
finally:
    stopped.set()
    fixture.close(stop_seconds=15, save_logs=True)
    subprocess.run(["docker", "network", "rm", network], capture_output=True)
    (private / "gauges.json").write_text(json.dumps(measurements) + "\n")
