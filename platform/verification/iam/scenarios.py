"""实际产品验证入口；所有凭据和故障只作用于本次临时资源。"""

from pathlib import Path
from runtime_fixture import RuntimeFixture
import argparse
import base64
import hashlib
import json
import os
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import urllib.parse
import uuid


parser = argparse.ArgumentParser(description="真实IAM、独立消费与故障恢复验证")
parser.add_argument("--code", type=Path, required=True)
parser.add_argument("--consumer", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--m2-safety", action="store_true", help="只执行 M2 当前生产装配的组合使用路径")
args = parser.parse_args()
code = args.code.resolve()
art = args.output.resolve()
art.mkdir(parents=True, exist_ok=True)
tag = "m2-runtime" if args.m2_safety else "iam-runtime"
iam_artifact = code / "services/iam/target/iam-0.3.0-iam.2.jar"
consumer_artifact = args.consumer.resolve()
artifact_inputs = {
    str(p): hashlib.sha256(p.read_bytes()).hexdigest()
    for p in [iam_artifact, consumer_artifact]
}
fixture = RuntimeFixture()
private = fixture.private

shutil.copy2(iam_artifact, private / "iam.jar")
shutil.copy2(consumer_artifact, private / "consumer.jar")
iam_artifact = private / "iam.jar"
consumer_artifact = private / "consumer.jar"
artifact_inputs = {
    str(p): hashlib.sha256(p.read_bytes()).hexdigest()
    for p in [iam_artifact, consumer_artifact]
}
env = fixture.env
containers = fixture.containers
processes = fixture.processes
cases = fixture.cases
management = {}
running = {}


def port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def start(name, overrides=None):
    local = env | {
        "IAM_DISCOVERY_ENABLED": "true",
        "IAM_DISCOVERY_IP": "127.0.0.1",
        "SERVER_ADDRESS": "127.0.0.1",
        "SERVER_PORT": str(port()),
        "IAM_MANAGEMENT_PORT": str(port()),
    }
    local.update(overrides or {})
    log = (private / f"{name}.log").open("w")
    process = subprocess.Popen(
        [
            "java",
            "-Xmx512m",
            "-XX:ActiveProcessorCount=2",
            "-jar",
            str(iam_artifact),
            "--spring.main.banner-mode=off",
        ],
        env=local,
        stdout=log,
        stderr=subprocess.STDOUT,
    )
    processes.append((process, log))
    base = "https://localhost:" + local["SERVER_PORT"]
    management[name] = "https://localhost:" + local["IAM_MANAGEMENT_PORT"]
    running[name] = process
    for _ in range(160):
        if process.poll() is not None:
            raise AssertionError(name + " failed to start; private log retained")
        try:
            if (
                fixture.http(base, "/api/me")[0] == 401
                and fixture.http(management[name], "/actuator/health/readiness")[0]
                == 200
            ):
                return base
        except (OSError, ValueError):
            pass
        time.sleep(0.15)
    raise AssertionError(name + " readiness timeout; private log retained")


def start_consumer():
    artifact = {"artifact": str(consumer_artifact)}
    pgpass = secrets.token_urlsafe(32)
    pgfile = private / "consumer-pg.env"
    pgfile.write_text(
        f"POSTGRES_USER=consumer\nPOSTGRES_DB=consumer\nPOSTGRES_PASSWORD={pgpass}\n"
    )
    pgfile.chmod(0o600)
    database = fixture.docker(
        "run",
        "-d",
        "--label",
        "org.domainforge.verification=phase3-consumer",
        "--env-file",
        str(pgfile),
        "-p",
        "127.0.0.1::5432",
        "--tmpfs",
        "/var/lib/postgresql",
        "postgres@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15",
    )
    containers.append(database)
    pgport = fixture.docker("port", database, "5432/tcp").rsplit(":", 1)[1]
    local = {key: value for key, value in env.items() if not key.startswith("IAM_")}
    local.update(
        DB_URL=f"jdbc:postgresql://127.0.0.1:{pgport}/consumer",
        DB_USER="consumer",
        DB_PASSWORD=pgpass,
        SERVER_PORT=str(port()),
        MANAGEMENT_PORT=str(port()),
        CONSUMER_CLIENT_STORE=str(private / "consumer.p12"),
        CONSUMER_TRUST_STORE=str(private / "trust.p12"),
        CONSUMER_SERVER_STORE=str(private / "server.p12"),
        CONSUMER_STORE_PASSWORD=env["CERT_PASSWORD"],
    )
    assert not any(
        key.startswith("IAM_DB") or key.startswith("IAM_REDIS") for key in local
    )
    log = (private / "consumer.log").open("w")
    process = subprocess.Popen(
        [
            "java",
            "-Xmx512m",
            "-XX:ActiveProcessorCount=2",
            "-jar",
            artifact["artifact"],
            "--spring.main.banner-mode=off",
        ],
        env=local,
        stdout=log,
        stderr=subprocess.STDOUT,
    )
    processes.append((process, log))
    base = "https://localhost:" + local["SERVER_PORT"]
    for _ in range(160):
        if process.poll() is not None:
            raise AssertionError("consumer failed to start; private log retained")
        try:
            if fixture.http(base, "/verification/me")[0] == 401:
                return base
        except (OSError, ValueError):
            pass
        time.sleep(0.15)
    raise AssertionError("consumer readiness timeout; private log retained")


def login(base, name, password):
    return fixture.check(
        "login-" + name,
        fixture.http(
            base, "/api/auth/login", {"loginName": name, "password": password}
        ),
        200,
    )["token"]


def policy_command(name, arguments, expected, changes=None):
    local = env | (changes or {})
    with (private / (name + ".log")).open("w") as log:
        result = subprocess.run(
            [
                "java",
                "-XX:ActiveProcessorCount=2",
                "-jar",
                str(iam_artifact),
                "login-policy",
                *arguments,
            ],
            env=local,
            stdout=log,
            stderr=subprocess.STDOUT,
            timeout=40,
        )
    assert result.returncode == expected, (name, result.returncode)
    cases.append({"case": name, "exit_code": result.returncode})


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


def session_checks(prefix, token, expected):
    """每次重新发起请求，覆盖双 IAM、下游只登录和权限入口。"""
    for name, base, path in (("iam1", iam1, "/api/me"), ("iam2", iam2, "/api/me"),
                             ("login-only", consumer, "/verification/me"),
                             ("permission", consumer, "/verification/read")):
        fixture.check(prefix + "-" + name, fixture.http(base, path, token=token), expected)


def session_account(name):
    """通过真实邀请、注册和权限命令建立每项前置，避免直接种一个能授权的账号。"""
    invited = fixture.check(name + "-invite", fixture.http(iam1, "/api/invitations", {
        "operationId": str(uuid.uuid4()), "loginName": name}, admin), 200)
    credential = "M2 private 测试密码 2026!"
    registered = fixture.check(name + "-register", fixture.http(iam2, "/api/auth/invitations/accept", {
        "operationId": str(uuid.uuid4()), "loginName": name,
        "invitationSecret": invited["secret"], "password": credential}), 200)
    identity = registered["targetId"]
    granted = fixture.check(name + "-grant", fixture.http(iam1, "/api/accounts/" + identity + "/permissions", {
        "operationId": str(uuid.uuid4()), "expectedVersion": 1,
        "permissions": ["iam:account:read"]}, admin, "PUT"), 200)
    return identity, credential, granted["version"]


def session_native_valid(token):
    """直接观察本次 Redis 原生映射仍存在；令牌和原始值不写报告。"""
    value = session_redis("GET", "Authorization:login:token:" + token)
    assert value, "被检验的原生映射应仍存在"


def session_redis(*arguments):
    local = env | {"REDISCLI_AUTH": redispass}
    return subprocess.check_output(["docker", "exec", "-e", "REDISCLI_AUTH", redis, "redis-cli",
        "--no-auth-warning", "--raw", *arguments], env=local, text=True).strip()


def session_wire():
    """复用已有原始帧夹具，产品 JVM 只改变本次隔离 Redis 的网络路径。"""
    module = code / "framework/domain-forge-access-control"
    classpath = os.pathsep.join([str(module / "target/test-classes"), str(module / "target/classes"),
                                (module / "target/m2-test-classpath.txt").read_text().strip()])
    log = (private / "m2-wire.log").open("w")
    process = subprocess.Popen(["java", "-cp", classpath,
        "org.domainforge.access.backend.internal.RedisWireFault", redisport], env=env,
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log, text=True, bufsize=1)
    processes.append((process, log))
    ready = session_wire_read(process)
    assert ready.startswith("READY "), "复用故障夹具未启动"
    return process, ready.split()[1]


def session_wire_read(process):
    import select
    readable, _, _ = select.select([process.stdout], [], [], 15)
    assert readable, "测试控制管道超时"
    return process.stdout.readline().strip()


def session_wire_control(command, expected="RESULT OK"):
    m2_wire.stdin.write(command + "\n")
    m2_wire.stdin.flush()
    assert session_wire_read(m2_wire) == expected, "故障控制结果不符合预期"


def session_delayed_cleanup():
    """当前生产 Logout 的 SQL 先提交、原生清理失败，再释放真正迟到的 SET。"""
    import concurrent.futures
    identity, credential, _ = session_account("m2.late")
    first = login(iam1, "m2.late", credential)
    retained = login(iam2, "m2.late", credential)
    key = "Authorization:login:session:" + identity
    session_wire_control("ARM SET HOLD_WRITE " + key)
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        pending = pool.submit(fixture.http, iam1, "/api/auth/logout", {"operationId": str(uuid.uuid4())}, first)
        session_wire_control("CAPTURED", "RESULT true")
        fixture.check("late-real-logout-sql-committed", pending.result(timeout=6), 200)
    metric = fixture.http(management["iam1"], "/actuator/metrics/iam.session.cleanup.failures")
    assert metric[0] == 200 and metric[1]["measurements"][0]["value"] == 1
    newer = login(iam2, "m2.late", credential)
    assert newer in session_redis("GET", key), "先证明新登录确实进入终端索引"
    session_wire_control("RELEASE")
    for _ in range(100):
        snapshot = session_redis("GET", key)
        if newer not in snapshot and retained in snapshot:
            break
        time.sleep(.02)
    else:
        raise AssertionError("未观察到原始迟到 SET 覆盖新索引")
    session_native_valid(newer)
    session_checks("late-logged-out-a-denied", first, 401)
    session_checks("late-valid-b-kept", newer, 200)
    session_checks("late-unrelated-session-kept", retained, 200)
    fixture.check("late-real-logout-b", fixture.http(iam2, "/api/auth/logout",
        {"operationId": str(uuid.uuid4())}, newer), 200)
    session_checks("late-logout-b-denied", newer, 401)
    session_checks("late-retained-c-kept", retained, 200)
    cases.append({"case": "actual-production-delayed-session-set", "late_index_overwrite_observed": True,
                  "cleanup_failure_counter": 1, "sql_revocation_preserved": True})


def session_runtime():
    """当前 RedisTemplate 打包组合；与目标 Redisson 隔离装配分别给证据。"""
    for change in ("missing", "revoked", "expired"):
        name = "m2." + change
        _, credential, _ = session_account(name)
        token = login(iam1, name, credential)
        session_checks(change + "-before", token, 200)
        digest = hashlib.sha256(token.encode()).hexdigest()
        statement = {"missing": "DELETE FROM iam_session_grant",
                     "revoked": "UPDATE iam_session_grant SET revoked=true",
                     "expired": "UPDATE iam_session_grant SET expires_at=clock_timestamp()-interval '1 second'"}[change]
        sql(statement + " WHERE token_digest='" + digest + "'")
        session_native_valid(token)
        session_checks(change + "-after", token, 401)
        session_checks(change + "-repeat", token, 401)
        if change == "missing":
            assert sql("SELECT count(*) FROM iam_session_grant WHERE token_digest='" + digest + "'") == "0"
    session_delayed_cleanup()
    session_lost_login_response()
    session_logout_and_account_changes()
    # 服务端统计区分预算设置与身份联合查询，不能把两次 SQL 说成一次数据库往返。
    sql("SELECT pg_stat_statements_reset()")
    session_checks("cost-four-fresh-requests", admin, 200)
    joined = int(sql("SELECT coalesce(sum(calls),0)::bigint FROM pg_stat_statements WHERE query LIKE 'SELECT a.id::text AS account_id%iam_session_grant%'") )
    budget = int(sql("SELECT coalesce(sum(calls),0)::bigint FROM pg_stat_statements WHERE query LIKE 'SELECT set_config(%'"))
    assert joined == 4 and budget == 4, "完整检查应逐请求读取当前 SQL；预算另计"
    cases.append({"case": "four-fresh-request-cost", "identity_join_queries": joined,
                  "budget_queries": budget, "capacity_acceptance": False})
    sql("ALTER TABLE iam_session_grant RENAME TO iam_session_grant_m2_unavailable")
    try:
        session_checks("sql-unavailable-after-warm-success", admin, 503)
    finally:
        sql("ALTER TABLE iam_session_grant_m2_unavailable RENAME TO iam_session_grant")
    session_checks("sql-restored", admin, 200)
    for name in ("iam1", "iam2"):
        running[name].terminate()
        running[name].wait(timeout=20)
    for name, path in (("login-only", "/verification/me"), ("permission", "/verification/read")):
        fixture.check("facts-http-unavailable-" + name, fixture.http(consumer, path, token=admin), 503)


def session_lost_login_response():
    """真实登录请求发完即丢弃应用响应；这不等于 PostgreSQL COMMIT 应答丢失。"""
    import http.client
    identity, credential, _ = session_account("m2.response")
    prefix = "Authorization:login:token:"
    before = set(session_redis("--scan", "--pattern", prefix + "*").splitlines())
    address = urllib.parse.urlparse(iam2)
    connection = http.client.HTTPSConnection(address.hostname, address.port, context=fixture.tls(), timeout=6)
    try:
        connection.request("POST", "/api/auth/login",
            json.dumps({"loginName": "m2.response", "password": credential}), {"Content-Type": "application/json"})
        # 不读取 HTTP 响应；独立夹具确认提交后关闭连接，明确限定为应用层响应被丢弃。
        for _ in range(100):
            added = set(session_redis("--scan", "--pattern", prefix + "*").splitlines()) - before
            committed = sql("SELECT count(*) FROM iam_session_grant WHERE account_id='" + identity + "'")
            if len(added) == 1 and committed == "1":
                break
            time.sleep(.05)
        else:
            raise AssertionError("没有观察到一次真实登录的原生与 SQL 副作用")
    finally:
        connection.close()
    token = next(iter(added))[len(prefix):]
    session_checks("lost-response-known-committed-identity", token, 200)
    assert sql("SELECT count(*) FROM iam_session_grant WHERE account_id='" + identity + "'") == "1"
    cases.append({"case": "actual-login-application-response-discarded", "client_read_response_bytes": 0,
                  "observed_grants": 1, "observed_new_native_tokens": 1, "postgres_commit_ack_loss_tested": False})


def session_logout_and_account_changes():
    name = "m2.logout"
    _, credential, _ = session_account(name)
    first, second = login(iam1, name, credential), login(iam2, name, credential)
    fixture.check("current-logout-a", fixture.http(iam1, "/api/auth/logout",
        {"operationId": str(uuid.uuid4())}, first), 200)
    session_checks("logout-a-rejected", first, 401)
    session_checks("logout-b-still-valid", second, 200)
    fixture.check("current-logout-b", fixture.http(iam2, "/api/auth/logout",
        {"operationId": str(uuid.uuid4())}, second), 200)
    session_checks("logout-b-rejected", second, 401)
    for change in ("password", "disable"):
        name = "m2." + change
        identity, credential, version = session_account(name)
        first, second = login(iam1, name, credential), login(iam2, name, credential)
        if change == "password":
            fixture.check("real-password-change", fixture.http(iam1, "/api/auth/password", {
                "operationId": str(uuid.uuid4()), "oldPassword": credential,
                "newPassword": credential + "changed"}, first), 200)
            credential += "changed"
        else:
            result = fixture.check("real-disable", fixture.http(iam1, "/api/accounts/" + identity + "/state", {
                "operationId": str(uuid.uuid4()), "expectedVersion": version, "enabled": False}, admin, "PUT"), 200)
        session_native_valid(first)
        session_native_valid(second)
        session_checks(change + "-first-old-denied", first, 401)
        session_checks(change + "-second-old-denied", second, 401)
        if change == "disable":
            fixture.check("real-reenable", fixture.http(iam2, "/api/accounts/" + identity + "/state", {
                "operationId": str(uuid.uuid4()), "expectedVersion": result["version"], "enabled": True}, admin, "PUT"), 200)
            session_checks("reenable-does-not-revive-old", second, 401)
        session_checks(change + "-fresh-valid", login(iam2, name, credential), 200)
        session_checks(change + "-unrelated-admin-valid", admin, 200)


try:
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
            "/CN=iam-test-ca",
            "-addext",
            "keyUsage=critical,keyCertSign,cRLSign",
        ]
    )
    fixture.certificate("server", "DNS:localhost,IP:127.0.0.1")
    fixture.certificate("consumer", "URI:urn:domainforge:project-alpha:consumer")
    fixture.certificate("other", "URI:urn:domainforge:project-beta:consumer")
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
    for path in private.glob("*.key"):
        path.chmod(0o600)
    pgpass = secrets.token_urlsafe(32)
    redispass = secrets.token_urlsafe(32)
    pgfile = private / "pg.env"
    pgfile.write_text(
        f"POSTGRES_USER=iam\nPOSTGRES_DB=iam\nPOSTGRES_PASSWORD={pgpass}\n"
    )
    pgfile.chmod(0o600)
    containers.append(
        fixture.docker(
            "run",
            "-d",
            "--label",
            "org.domainforge.verification=phase3-http",
            "--env-file",
            str(pgfile),
            "-p",
            "127.0.0.1::5432",
            "--tmpfs",
            "/var/lib/postgresql",
            "postgres@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15",
            "-c",
            "shared_preload_libraries=pg_stat_statements",
        )
    )
    redis_config = private / "redis.conf"
    redis_config.write_text(
        f'requirepass {redispass}\nmaxmemory 64mb\nmaxmemory-policy noeviction\nsave ""\n'
    )
    redis_config.chmod(0o600)
    redis_host_port = port()
    redis = fixture.docker(
        "create",
        "--label",
        "org.domainforge.verification=phase3-http",
        "--mount",
        "type=volume,destination=/data",
        "-p",
        f"127.0.0.1:{redis_host_port}:6379",
        "redis@sha256:c88d347edef6249a6d2293f926f1eeb48bd40c57cbcd02c07f52e7f1fd2cb46b",
        "redis-server",
        "/data/redis.conf",
    )
    containers.append(redis)
    fixture.docker("cp", str(redis_config), redis + ":/data/redis.conf")
    fixture.docker("start", redis)
    pgport = fixture.docker("port", containers[0], "5432/tcp").rsplit(":", 1)[1]
    redisport = fixture.docker("port", redis, "6379/tcp").rsplit(":", 1)[1]
    env.update(
        IAM_DB_URL=f"jdbc:postgresql://127.0.0.1:{pgport}/iam",
        IAM_DB_USER="iam",
        IAM_DB_PASSWORD=pgpass,
        IAM_REDIS_HOST="127.0.0.1",
        IAM_REDIS_PORT=redisport,
        IAM_REDIS_PASSWORD=redispass,
        IAM_FINGERPRINT_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),
        IAM_SERVER_KEYSTORE=str(private / "server.p12"),
        IAM_SERVER_KEYSTORE_PASSWORD=env["CERT_PASSWORD"],
        IAM_TRUSTSTORE=str(private / "trust.p12"),
        IAM_TRUSTSTORE_PASSWORD=env["CERT_PASSWORD"],
        IAM_PROJECT_ID="project-alpha",
        IAM_TRUSTED_SERVICE_IDENTITIES="urn:domainforge:project-alpha:consumer",
        IAM_API_DOCS_ENABLED="true",
        # 仅本次隔离回环管理端采集原生元数据，不为产品入口添加匿名豁免。
        SPRINGDOC_USE_MANAGEMENT_PORT="true",
        MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE="health,metrics,openapi",
    )
    for _ in range(100):
        try:
            sql("select 1")
            break
        except subprocess.CalledProcessError:
            time.sleep(0.1)
    sql("CREATE EXTENSION pg_stat_statements")
    password = "Initial administrator 安全密码 2026!"
    pf = private / "initial-password"
    pf.write_text(password)
    pf.chmod(0o600)
    # 两个真实CLI进程竞争空库，不能用同进程线程结果替代部署入口验证。
    initialization = []
    command = [
        "java",
        "-XX:ActiveProcessorCount=2",
        "-jar",
        str(iam_artifact),
        "initialize-admin",
        "--login-name=admin.initial",
        "--password-file=" + str(pf),
    ]
    for n in range(2):
        log = (private / f"initialize-{n}.log").open("w")
        process = subprocess.Popen(
            command, env=env, stdout=log, stderr=subprocess.STDOUT
        )
        processes.append((process, log))
        initialization.append(process)
    exits = [process.wait(timeout=40) for process in initialization]
    assert sorted(exits) == [0, 1], ("concurrent-initialization", exits)
    assert sql("SELECT count(*) FROM iam_account") == "1"
    with (private / "initialize-repeat.log").open("w") as log:
        repeated = subprocess.run(
            command, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=40
        )
    assert repeated.returncode == 1 and sql("SELECT count(*) FROM iam_account") == "1"
    cases.append(
        {
            "case": "two-process-initialization-and-repeat",
            "exits": exits,
            "repeat": repeated.returncode,
            "account_count": 1,
        }
    )
    nacos = fixture.start_nacos()
    if args.m2_safety:
        m2_wire, m2_wire_port = session_wire()
    iam1 = start("iam1", {"IAM_REDIS_PORT": m2_wire_port} if args.m2_safety else None)
    iam2 = start("iam2")
    consumer = start_consumer()
    expected_ports = {int(urllib.parse.urlparse(base).port) for base in (iam1, iam2)}
    for _ in range(100):
        instances = fixture.nacos_instances()
        if {instance["port"] for instance in instances} == expected_ports:
            break
        time.sleep(.2)
    else:
        raise AssertionError("two IAM instances did not register with Nacos")
    assert all(instance["healthy"] and instance["enabled"] and
        instance["metadata"].get("secure") == "true" for instance in instances), instances
    cases.append({"case": "nacos-authenticated-two-instance-registration", "instances": instances,
        "anonymous_status": nacos["anonymous_status"], "image": nacos["image"]})
    admin = login(iam1, "admin.initial", password)
    me = fixture.check(
        "cross-instance-current-self", fixture.http(iam2, "/api/me", token=admin), 200
    )
    admin_id = me["id"]
    admin_version = me["version"]
    if args.m2_safety:
        session_runtime()
        assert all(hashlib.sha256(Path(path).read_bytes()).hexdigest() == checksum
                   for path, checksum in artifact_inputs.items())
        report = {"ok": True, "artifact_inputs": artifact_inputs, "cases": cases,
                  "proof_scope": "当前生产 RedisTemplate DAO 的双 IAM 与独立下游组合使用路径；非目标 DAO 接线或性能验收",
                  "production_dao_changed": False, "m2_complete": False, "private_logs": str(private)}
        (art / (tag + ".json")).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps({"ok": True, "cases": len(cases), "scope": "m2-current-production"}))
        raise SystemExit(0)
    assert (
        fixture.check(
            "independent-consumer-current-subject",
            fixture.http(consumer, "/verification/read", token=admin),
            200,
        )["accountId"]
        == admin_id
    )
    # 注册发现是异步控制面；记录真实收敛过程，不把其缓存误写成身份许可缓存。
    import signal

    def consumer_eventually(case, expected):
        began = time.monotonic()
        observations = []
        for _ in range(100):
            response = fixture.http(consumer, "/verification/read", token=admin)
            observations.append(response[0])
            if response[0] == expected:
                fixture.check(case, response, expected)
                cases.append({"case": case + "-discovery-convergence", "statuses": observations,
                    "seconds": round(time.monotonic() - began, 3)})
                return
            time.sleep(.2)
        raise AssertionError((case, "discovery did not converge", observations))

    def client_discovery_eventually(case, expected):
        began = time.monotonic()
        observations = []
        while time.monotonic() - began < 30:
            response = fixture.http(consumer, "/verification/discovery", token=admin)
            view = response[1].get("data") if response[0] == 200 else None
            ports = {instance["port"] for instance in view} if isinstance(view, list) else None
            observations.append({"status": response[0], "instances": view})
            if ports == expected:
                assert all(instance["secure"] for instance in view), (case, "non-TLS discovered instance")
                cases.append({"case": case, "probe": "IAM LoadBalancerClientFactory child ServiceInstanceListSupplier Flux",
                    "seconds": round(time.monotonic() - began, 3), "observations": observations})
                return
            time.sleep(.2)
        raise AssertionError((case, "native client discovery did not converge", observations))

    client_discovery_eventually("consumer-native-discovery-two-instances", expected_ports)
    for selected, paused in (("iam1", "iam2"), ("iam2", "iam1")):
        unselected = next(instance for instance in instances if
            instance["port"] == int(urllib.parse.urlparse({"iam1": iam1, "iam2": iam2}[paused]).port))
        fixture.nacos_enable(unselected, False)
        selected_port = int(urllib.parse.urlparse({"iam1": iam1, "iam2": iam2}[selected]).port)
        client_discovery_eventually("consumer-native-discovery-only-" + selected, {selected_port})
        os.kill(running[paused].pid, signal.SIGSTOP)
        try:
            consumer_eventually("consumer-nacos-selected-" + selected, 200)
            for attempt in range(3):
                fixture.check("consumer-only-selected-instance-" + selected,
                    fixture.http(consumer, "/verification/read", token=admin), 200)
        finally:
            os.kill(running[paused].pid, signal.SIGCONT)
            fixture.nacos_enable(unselected, True)
        time.sleep(.3)
    for instance in instances:
        fixture.nacos_enable(instance, False)
    try:
        consumer_eventually("consumer-nacos-no-enabled-instance-denied", 503)
        for name, base in (("iam1", iam1), ("iam2", iam2)):
            fixture.check("iam-directly-live-with-no-enabled-discovery-instance-" + name,
                fixture.http(base, "/api/me", token=admin), 200)
    finally:
        for instance in instances:
            fixture.nacos_enable(instance, True)
    consumer_eventually("consumer-nacos-instance-recovery", 200)
    client_discovery_eventually("consumer-native-discovery-both-restored", expected_ports)

    fixture.check(
        "consumer-native-cookie-rejected",
        fixture.http(
            consumer, "/verification/me", headers={"Cookie": "Authorization=" + admin}
        ),
        401,
    )
    fixture.check(
        "consumer-native-query-rejected",
        fixture.http(consumer, "/verification/me?Authorization=" + admin),
        401,
    )
    fixture.check(
        "consumer-native-wrong-prefix-rejected",
        fixture.http(consumer, "/verification/me", headers={"Authorization": admin}),
        401,
    )
    fixture.check(
        "consumer-default-deny-undeclared",
        fixture.http(consumer, "/verification/undeclared", token=admin),
        403,
    )
    fixture.check(
        "native-cookie-rejected",
        fixture.http(iam2, "/api/me", headers={"Cookie": "Authorization=" + admin}),
        401,
    )
    fixture.check(
        "native-query-rejected",
        fixture.http(iam2, "/api/me?Authorization=" + admin),
        401,
    )
    fixture.check(
        "native-wrong-prefix-rejected",
        fixture.http(iam2, "/api/me", headers={"Authorization": admin}),
        401,
    )
    fixture.check(
        "internal-missing-certificate",
        fixture.http(iam2, "/internal/access/current", token=admin),
        403,
    )
    fixture.check(
        "internal-wrong-project",
        fixture.http(iam2, "/internal/access/current", token=admin, cert="other"),
        403,
    )
    subject = fixture.check(
        "internal-native-mtls-current",
        fixture.http(iam2, "/internal/access/current", token=admin, cert="consumer"),
        200,
    )
    assert subject["accountId"] == admin_id
    issue = {"operationId": str(uuid.uuid4()), "loginName": "invited.user"}
    invited = fixture.check(
        "create-invitation", fixture.http(iam1, "/api/invitations", issue, admin), 200
    )
    replay = fixture.check(
        "invitation-replay-does-not-recover-secret",
        fixture.http(iam2, "/api/invitations", issue, admin),
        200,
    )
    assert replay["secret"] is None
    userpassword = "A real invited 用户密码 2026!"
    registration = {
        "operationId": str(uuid.uuid4()),
        "invitationSecret": invited["secret"],
        "loginName": "INVITED.USER",
        "password": userpassword,
    }
    registered = fixture.check(
        "register-real-account",
        fixture.http(iam2, "/api/auth/invitations/accept", registration),
        200,
    )
    assert (
        fixture.check(
            "registration-same-operation",
            fixture.http(iam1, "/api/auth/invitations/accept", registration),
            200,
        )
        == registered
    )
    user = login(iam1, "invited.user", userpassword)
    user_me = fixture.check(
        "self-cannot-be-selected-by-client",
        fixture.http(iam2, "/api/me?id=" + admin_id, token=user),
        200,
    )
    assert user_me["id"] == registered["targetId"] and user_me["permissions"] == []
    fixture.check(
        "new-user-no-management", fixture.http(iam2, "/api/accounts", token=user), 403
    )
    fixture.check(
        "consumer-no-management-permission",
        fixture.http(consumer, "/verification/read", token=user),
        403,
    )
    grant = {
        "operationId": str(uuid.uuid4()),
        "expectedVersion": user_me["version"],
        "permissions": ["iam:account:read"],
    }
    granted = fixture.check(
        "grant-account-read",
        fixture.http(
            iam1, "/api/accounts/" + user_me["id"] + "/permissions", grant, admin, "PUT"
        ),
        200,
    )
    fixture.check(
        "new-permission-seen-cross-instance",
        fixture.http(iam2, "/api/accounts", token=user),
        200,
    )
    fixture.check(
        "consumer-current-grant",
        fixture.http(consumer, "/verification/read", token=user),
        200,
    )
    sql("SELECT pg_stat_statements_reset()")
    before_query = sql(
        "SELECT string_agg(id::text||':'||xmin::text,',' ORDER BY id) FROM iam_account"
    )
    page = fixture.check(
        "bounded-account-page",
        fixture.http(iam2, "/api/accounts?limit=1", token=user),
        200,
    )
    assert len(page["items"]) == 1 and page["nextCursor"] == page["items"][0]["id"]
    calls = sql(
        "SELECT sum(calls) FROM pg_stat_statements WHERE query LIKE 'SELECT id::text,login_name%ORDER BY id LIMIT%'"
    )
    assert calls == "1", ("business-projection-sql-count", calls)
    cases.append(
        {
            "case": "single-business-projection-sql",
            "calls": int(calls),
            "authentication_sql_counted_separately": True,
        }
    )
    next_page = fixture.check(
        "stable-account-cursor",
        fixture.http(
            iam1, "/api/accounts?limit=1&after=" + page["nextCursor"], token=user
        ),
        200,
    )
    assert (
        len(next_page["items"]) == 1
        and next_page["items"][0]["id"] > page["items"][0]["id"]
    )
    fixture.check(
        "account-page-above-bound",
        fixture.http(iam1, "/api/accounts?limit=101", token=user),
        400,
    )
    assert before_query == sql(
        "SELECT string_agg(id::text||':'||xmin::text,',' ORDER BY id) FROM iam_account"
    )
    revoke = {
        "operationId": str(uuid.uuid4()),
        "expectedVersion": granted["version"],
        "permissions": [],
    }
    fixture.docker("pause", nacos["container"])
    nacos_outage_started = time.monotonic()
    try:
        try:
            fixture.nacos_request("GET", "/v3/admin/ns/instance/list", {"serviceName": "iam"})
        except (TimeoutError, OSError) as unavailable:
            cases.append({"case": "paused-nacos-api-unavailable", "error": type(unavailable).__name__})
        else:
            raise AssertionError("paused Nacos unexpectedly answered native API")
        fixture.check("consumer-nacos-short-outage-cached-route-live-facts",
            fixture.http(consumer, "/verification/read", token=user), 200)
        revoked = fixture.check(
            "revoke-account-read",
            fixture.http(
                iam1,
                "/api/accounts/" + user_me["id"] + "/permissions",
                revoke,
                admin,
                "PUT",
            ),
            200,
        )
        fixture.check(
            "revocation-seen-cross-instance",
            fixture.http(iam2, "/api/accounts", token=user),
            403,
        )
        fixture.check(
            "consumer-current-revocation",
            fixture.http(consumer, "/verification/read", token=user),
            403,
        )
    finally:
        fixture.docker("unpause", nacos["container"])
    cases.append({"case": "nacos-short-outage-still-observes-revocation",
        "seconds": round(time.monotonic() - nacos_outage_started, 3),
        "cached": "discovered route only; current authorization fetched from IAM"})
    fixture.check(
        "last-admin-protected",
        fixture.http(
            iam1,
            "/api/accounts/" + admin_id + "/state",
            {
                "operationId": str(uuid.uuid4()),
                "expectedVersion": admin_version,
                "enabled": False,
            },
            admin,
            "PUT",
        ),
        409,
    )
    # 真实账号在两实例轮换累计五次失败，第六次即使密码正确也受保护；旧RDB保留该失败窗口。
    guarded = fixture.check(
        "guard-invitation",
        fixture.http(
            iam1,
            "/api/invitations",
            {"operationId": str(uuid.uuid4()), "loginName": "guard.user"},
            admin,
        ),
        200,
    )
    fixture.check(
        "guard-registration",
        fixture.http(
            iam1,
            "/api/auth/invitations/accept",
            {
                "operationId": str(uuid.uuid4()),
                "invitationSecret": guarded["secret"],
                "loginName": "guard.user",
                "password": userpassword,
            },
        ),
        200,
    )
    for attempt_number in range(5):
        fixture.check(
            "failed-login-" + str(attempt_number + 1),
            fixture.http(
                iam1 if attempt_number % 2 == 0 else iam2,
                "/api/auth/login",
                {"loginName": "guard.user", "password": userpassword + "wrong"},
            ),
            401,
        )
    limited = fixture.http(
        iam2, "/api/auth/login", {"loginName": "guard.user", "password": userpassword}
    )
    fixture.check("sixth-valid-login-is-rate-limited", limited, 429)
    assert int({k.lower(): v for k, v in limited[2].items()}["retry-after"]) > 0
    other_session = login(iam2, "invited.user", userpassword)

    def rediscli(*args):
        return fixture.docker(
            "exec", "-e", "REDISCLI_AUTH=" + redispass, redis, "redis-cli", *args
        )

    assert rediscli("SAVE") == "OK"
    fixture.docker("cp", redis + ":/data/dump.rdb", str(private / "old-snapshot.rdb"))
    newpassword = userpassword + "changed"
    fixture.check(
        "password-change",
        fixture.http(
            iam1,
            "/api/auth/password",
            {
                "operationId": str(uuid.uuid4()),
                "oldPassword": userpassword,
                "newPassword": newpassword,
            },
            user,
        ),
        200,
    )
    fixture.check(
        "password-invalidates-current", fixture.http(iam1, "/api/me", token=user), 401
    )
    fixture.check(
        "password-invalidates-other-instance-session",
        fixture.http(iam2, "/api/me", token=other_session),
        401,
    )
    fixture.check(
        "consumer-password-invalidates-old-session",
        fixture.http(consumer, "/verification/me", token=other_session),
        401,
    )
    fresh = login(iam2, "invited.user", newpassword)
    fixture.check(
        "logout-current",
        fixture.http(
            iam1, "/api/auth/logout", {"operationId": str(uuid.uuid4())}, fresh
        ),
        200,
    )
    fixture.check(
        "logout-cross-instance-rejection",
        fixture.http(iam2, "/api/me", token=fresh),
        401,
    )
    fixture.check("audit-query", fixture.http(iam1, "/api/audit", token=admin), 200)
    fixture.docker("pause", redis)
    fixture.check(
        "consumer-redis-failure-denied",
        fixture.http(consumer, "/verification/read", token=admin),
        503,
    )
    fixture.check(
        "liveness-during-redis-fault",
        fixture.http(management["iam2"], "/actuator/health/liveness"),
        200,
    )
    fixture.docker("unpause", redis)
    time.sleep(0.5)
    fixture.check(
        "consumer-redis-recovery",
        fixture.http(consumer, "/verification/read", token=admin),
        200,
    )
    # 明确补录未知事实，普通用户不能补录或核对他人结果。
    backfill = {
        "operationId": str(uuid.uuid4()),
        "originalOperationId": str(uuid.uuid4()),
        "targetId": admin_id,
        "action": "account.state",
        "occurredAt": None,
        "provenance": "ticket:IAM-HTTP",
        "missingFields": ["occurredAt", "originalActor", "originalOutcome"],
    }
    recovered = fixture.check(
        "explicit-manual-audit",
        fixture.http(iam1, "/api/audit/backfill", backfill, admin),
        200,
    )
    assert (
        fixture.check(
            "manual-audit-idempotent",
            fixture.http(iam2, "/api/audit/backfill", backfill, admin),
            200,
        )
        == recovered
    )
    assert fixture.check(
        "own-operation-confirmed",
        fixture.http(iam1, "/api/operations/" + backfill["operationId"], token=admin),
        200,
    )["confirmed"]
    assert not fixture.check(
        "absent-operation-unconfirmed",
        fixture.http(iam1, "/api/operations/" + str(uuid.uuid4()), token=admin),
        200,
    )["confirmed"]
    plain = login(iam1, "invited.user", newpassword)
    fixture.check(
        "audit-backfill-denies-plain-user",
        fixture.http(iam1, "/api/audit/backfill", backfill, plain),
        403,
    )
    fixture.check(
        "operation-other-actor-denied",
        fixture.http(
            iam1,
            "/api/operations/" + backfill["operationId"] + "?actorId=" + admin_id,
            token=plain,
        ),
        403,
    )
    for media in ["application/json", "application/vnd.test+json", "text/plain"]:
        fixture.check(
            "oversized-" + media,
            fixture.http(
                iam1,
                "/api/auth/login",
                {"loginName": "admin.initial", "password": "z" * 50000},
                headers={"Content-Type": media},
            ),
            400,
        )
    # 原生PostgreSQL故障时当前身份不允许；恢复后无需重新部署即可校验。
    fixture.docker("pause", containers[0])
    try:
        fixture.check(
            "consumer-sql-fault-denied",
            fixture.http(consumer, "/verification/read", token=admin),
            503,
        )
        fixture.check(
            "liveness-during-sql-fault",
            fixture.http(management["iam2"], "/actuator/health/liveness"),
            200,
        )
    finally:
        fixture.docker("unpause", containers[0])
    time.sleep(1)
    fixture.check(
        "consumer-sql-recovery",
        fixture.http(consumer, "/verification/read", token=admin),
        200,
    )
    # 暂停本轮IAM进程制造真实在线鉴权超时；恢复同一连接端点后消费者无需重启。
    import signal

    for name in ("iam1", "iam2"):
        os.kill(running[name].pid, signal.SIGSTOP)
    try:
        fixture.check(
            "consumer-iam-timeout-denied",
            fixture.http(consumer, "/verification/read", token=admin),
            503,
        )
    finally:
        for name in ("iam1", "iam2"):
            os.kill(running[name].pid, signal.SIGCONT)
    time.sleep(0.3)
    fixture.check(
        "consumer-iam-timeout-recovery",
        fixture.http(consumer, "/verification/read", token=admin),
        200,
    )
    # 在真实账号行锁处暂停登录，使Redis已签发而SQL尚未登记；两类故障均不修改产品实现。
    import concurrent.futures

    account_id = registered["targetId"]

    def split_store_failure(mode):
        paused = False
        if mode == "sql-and-cleanup":
            sql(
                "CREATE FUNCTION fail_grant() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled grant failure'; END $$"
            )
            sql(
                "CREATE TRIGGER fail_grant BEFORE INSERT ON iam_session_grant FOR EACH ROW EXECUTE FUNCTION fail_grant()"
            )
        holder = subprocess.Popen(
            [
                "docker",
                "exec",
                "-i",
                containers[0],
                "psql",
                "-X",
                "-U",
                "iam",
                "-d",
                "iam",
                "-At",
                "-v",
                "ON_ERROR_STOP=1",
            ],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        holder.stdin.write(
            "BEGIN; SELECT id FROM iam_account WHERE id='"
            + account_id
            + "' FOR UPDATE;\n\\echo row-held\n"
        )
        holder.stdin.flush()
        while holder.stdout.readline().strip() != "row-held":
            if holder.poll() is not None:
                raise AssertionError("account lock holder failed")
        prefix = "Authorization:login:token:"
        before = set(rediscli("--scan", "--pattern", prefix + "*").splitlines())

        def interrupted_login():
            try:
                return fixture.http(
                    iam1,
                    "/api/auth/login",
                    {"loginName": "invited.user", "password": newpassword},
                )[0]
            except (OSError, ValueError):
                return "connection-interrupted"

        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
                attempt = pool.submit(interrupted_login)
                newkeys = set()
                for _ in range(30):
                    newkeys = (
                        set(rediscli("--scan", "--pattern", prefix + "*").splitlines())
                        - before
                    )
                    if newkeys:
                        break
                    time.sleep(0.05)
                assert (
                    len(newkeys) == 1
                ), "native session was not observed before SQL commit"
                orphan = next(iter(newkeys))[len(prefix) :]
                assert (
                    sql(
                        "SELECT count(*) FROM iam_session_grant WHERE token_digest='"
                        + hashlib.sha256(orphan.encode()).hexdigest()
                        + "'"
                    )
                    == "0"
                )
                if mode == "sql-and-cleanup":
                    fixture.docker("pause", redis)
                    paused = True
                    holder.stdin.write("ROLLBACK;\n")
                    holder.stdin.flush()
                    assert attempt.result(timeout=6) == 503
                else:
                    running["iam1"].kill()
                    running["iam1"].wait(timeout=5)
                    assert attempt.result(timeout=6) == "connection-interrupted"
        finally:
            holder.stdin.write("ROLLBACK;\n\\q\n")
            holder.stdin.close()
            holder.wait(timeout=5)
            if paused:
                fixture.docker("unpause", redis)
            if mode == "sql-and-cleanup":
                sql("DROP TRIGGER fail_grant ON iam_session_grant")
                sql("DROP FUNCTION fail_grant()")
        if mode == "process-kill":
            assert rediscli("EXISTS", prefix + orphan) == "1"
        fixture.check(
            mode + "-orphan-session-denied",
            fixture.http(iam2, "/api/me", token=orphan),
            401,
        )
        if mode == "process-kill":
            # 先确认原生发现已排除停机实例，再单次核验孤儿会话的身份拒绝。
            client_discovery_eventually(
                "consumer-native-discovery-after-process-kill",
                {int(urllib.parse.urlparse(iam2).port)},
            )
        fixture.check(
            mode + "-consumer-orphan-denied",
            fixture.http(consumer, "/verification/me", token=orphan),
            401,
        )

    split_store_failure("sql-and-cleanup")
    split_store_failure("process-kill")
    iam1 = start("iam1-restarted")
    fixture.check(
        "process-kill-service-recovery", fixture.http(iam1, "/api/me", token=admin), 200
    )
    # 真实客户端发送完整命令后关闭连接且不读取响应；以SQL收据确认落库，再由新连接重放。
    from http.client import HTTPSConnection
    import urllib.parse

    lost = {"operationId": str(uuid.uuid4()), "loginName": "response.loss"}
    address = urllib.parse.urlparse(iam1)
    connection = HTTPSConnection(
        address.hostname, address.port, context=fixture.tls(), timeout=6
    )
    try:
        connection.request(
            "POST",
            "/api/invitations",
            json.dumps(lost),
            {"Authorization": "Bearer " + admin, "Content-Type": "application/json"},
        )
        for _ in range(50):
            if (
                sql(
                    "SELECT count(*) FROM iam_operation WHERE operation_id='"
                    + lost["operationId"]
                    + "'"
                )
                == "1"
            ):
                break
            time.sleep(0.1)
        else:
            raise AssertionError("discarded-response operation did not commit")
    finally:
        connection.close()
    replayed = fixture.check(
        "discarded-response-reconciles",
        fixture.http(iam2, "/api/invitations", lost, admin),
        200,
    )
    assert (
        replayed["secret"] is None
        and sql("SELECT count(*) FROM iam_invitation WHERE login_name='response.loss'")
        == "1"
    )
    # 恢复真实旧RDB快照；账号密码纪元来自SQL，旧原生令牌不得复活。
    fixture.docker("stop", redis)
    fixture.docker("cp", str(private / "old-snapshot.rdb"), redis + ":/data/dump.rdb")
    fixture.docker(
        "run",
        "--rm",
        "--volumes-from",
        redis,
        "--user",
        "0",
        "--entrypoint",
        "chown",
        "redis@sha256:c88d347edef6249a6d2293f926f1eeb48bd40c57cbcd02c07f52e7f1fd2cb46b",
        "redis:redis",
        "/data/dump.rdb",
    )
    fixture.docker("start", redis)
    assert fixture.docker("port", redis, "6379/tcp").rsplit(":", 1)[1] == redisport
    for _ in range(40):
        try:
            if (
                rediscli("PING") == "PONG"
                and fixture.http(iam1, "/api/me", token=admin)[0] == 200
            ):
                break
        except (subprocess.CalledProcessError, OSError):
            pass
        time.sleep(0.25)
    else:
        raise AssertionError("restored Redis and native client did not become ready")
    fixture.check(
        "restored-old-redis-token-rejected",
        fixture.http(iam1, "/api/me", token=other_session),
        401,
    )
    fixture.check(
        "restored-old-redis-consumer-rejected",
        fixture.http(consumer, "/verification/me", token=other_session),
        401,
    )
    fixture.check(
        "restored-redis-enters-closed-login-window",
        fixture.http(
            iam1,
            "/api/auth/login",
            {"loginName": "admin.initial", "password": password},
        ),
        503,
    )
    window_started = time.monotonic()
    remaining = float(
        sql(
            "SELECT EXTRACT(EPOCH FROM blocked_until-clock_timestamp()) FROM iam_login_protection"
        )
    )
    assert 598 < remaining <= 600, remaining
    fixture.check(
        "other-instance-cannot-bypass-window",
        fixture.http(
            iam2,
            "/api/auth/login",
            {"loginName": "admin.initial", "password": password},
        ),
        503,
    )
    # 显式停全部IAM并排空请求后切换；复用尚在恢复中的真实窗口，不修改时钟或缩短等待。
    # Nacos原生排空10秒后还需关闭资源；等待真实退出，超时仍失败。
    for name in ("iam1-restarted", "iam2"):
        running[name].terminate()
        running[name].wait(timeout=15)
    old_digest = sql("SELECT policy_digest FROM iam_login_protection WHERE id=1")
    policy_command(
        "policy-invalid-startup", ["status"], 1, {"IAM_LOGIN_ACCOUNT_ATTEMPTS": "0"}
    )
    assert (
        sql("SELECT policy_digest FROM iam_login_protection WHERE id=1") == old_digest
    )
    env.update(
        IAM_LOGIN_ACCOUNT_ATTEMPTS="4",
        IAM_LOGIN_HASH_WORKERS="1",
        IAM_LOGIN_HASH_QUEUE_CAPACITY="2",
        IAM_LOGIN_HASH_WAIT_MILLIS="500",
    )
    policy_command(
        "policy-status-before",
        ["status"],
        0,
        {
            "SPRING_MAIN_WEB_APPLICATION_TYPE": "servlet",
            "SERVER_PORT": "invalid-maintenance-port",
        },
    )
    policy_command(
        "policy-missing-stop-confirmation",
        ["apply", "--expected-digest=" + old_digest],
        1,
    )
    policy_command(
        "policy-wrong-expected",
        ["apply", "--expected-digest=" + "0" * 64, "--confirm-login-stopped"],
        1,
    )
    assert (
        sql("SELECT policy_digest FROM iam_login_protection WHERE id=1") == old_digest
    )
    policy_window_started = time.monotonic()
    policy_command(
        "policy-explicit-apply",
        ["apply", "--expected-digest=" + old_digest, "--confirm-login-stopped"],
        0,
    )
    assert (
        590
        < float(
            sql(
                "SELECT EXTRACT(EPOCH FROM blocked_until-clock_timestamp()) FROM iam_login_protection WHERE id=1"
            )
        )
        <= 600
    )
    new_digest = sql("SELECT policy_digest FROM iam_login_protection WHERE id=1")
    assert new_digest != old_digest
    deadline = sql("SELECT blocked_until FROM iam_login_protection WHERE id=1")
    policy_command(
        "policy-replay-rejected",
        ["apply", "--expected-digest=" + old_digest, "--confirm-login-stopped"],
        1,
    )
    assert sql("SELECT blocked_until FROM iam_login_protection WHERE id=1") == deadline
    # 一个旧配置实例不会覆盖SQL策略；两台目标配置实例重启也仍拒绝恢复期登录。
    env["IAM_LOGIN_ACCOUNT_ATTEMPTS"] = "5"
    stale = start("iam-stale-policy")
    fixture.check(
        "stale-policy-instance-rejected",
        fixture.http(
            stale,
            "/api/auth/login",
            {"loginName": "admin.initial", "password": password},
        ),
        503,
    )
    running["iam-stale-policy"].terminate()
    running["iam-stale-policy"].wait(timeout=15)
    env["IAM_LOGIN_ACCOUNT_ATTEMPTS"] = "4"
    iam1, iam2 = start("iam-policy-one"), start("iam-policy-two")
    for base in (iam1, iam2):
        fixture.check(
            "policy-recovery-rejects-after-restart",
            fixture.http(
                base,
                "/api/auth/login",
                {"loginName": "admin.initial", "password": password},
            ),
            503,
        )
    assert (
        sql("SELECT policy_digest FROM iam_login_protection WHERE id=1") == new_digest
    )
    assert sql("SELECT blocked_until FROM iam_login_protection WHERE id=1") == deadline
    # CLI启动与重启消耗的是真实窗口时间；记录并等待数据库仍要求的剩余时间。
    remaining = float(
        sql(
            "SELECT EXTRACT(EPOCH FROM blocked_until-clock_timestamp()) FROM iam_login_protection WHERE id=1"
        )
    )
    assert 0 < remaining <= 600
    window_started = time.monotonic()
    print(
        json.dumps(
            {"progress": "real-ten-minute-recovery-window", "seconds": remaining}
        ),
        flush=True,
    )
    # 不修改阈值或数据库时钟；实际等待同一批准的十分钟，然后验证恢复。
    while time.monotonic() - window_started < remaining + 1:
        time.sleep(min(10, remaining + 1 - (time.monotonic() - window_started)))
    fixture.check(
        "login-recovers-after-full-window",
        fixture.http(
            iam2,
            "/api/auth/login",
            {"loginName": "admin.initial", "password": password},
        ),
        200,
    )
    fixture.check(
        "failed-account-window-recovers",
        fixture.http(
            iam1,
            "/api/auth/login",
            {"loginName": "guard.user", "password": userpassword},
        ),
        200,
    )
    cases.append(
        {
            "case": "actual-recovery-wait",
            "seconds": round(time.monotonic() - window_started, 3),
            "minimum": remaining,
        }
    )
    assert time.monotonic() - policy_window_started >= 600
    cases.append(
        {
            "case": "policy-actual-full-window",
            "seconds": round(time.monotonic() - policy_window_started, 3),
            "window_seconds": 600,
        }
    )
    # 收紧后的四次账号额度确实跨两个进程共享；429返回剩余等待而非统一常量。
    for attempt in range(4):
        fixture.check(
            "configured-four-attempts",
            fixture.http(
                (iam1, iam2)[attempt % 2],
                "/api/auth/login",
                {"loginName": "configured.unknown", "password": password},
            ),
            401,
        )
    limited = fixture.http(
        iam2,
        "/api/auth/login",
        {"loginName": "configured.unknown", "password": password},
    )
    fixture.check("configured-fifth-attempt-rejected", limited, 429)
    retry = int(limited[2]["Retry-After"])
    assert 590 <= retry <= 600
    time.sleep(1.2)
    later = fixture.http(
        iam1,
        "/api/auth/login",
        {"loginName": "configured.unknown", "password": password},
    )
    fixture.check("retry-after-decreases-with-real-time", later, 429)
    assert 0 < int(later[2]["Retry-After"]) < retry
    fixture.check(
        "business-port-has-no-anonymous-document-route",
        fixture.http(iam1, "/v3/api-docs"),
        404,
    )
    spec_response = fixture.http(management["iam-policy-one"], "/actuator/openapi")
    fixture.check("loopback-native-openapi-capture", spec_response, 200)
    specification = spec_response[1]
    assert {"/api/auth/login", "/api/me", "/internal/access/current"} <= set(
        specification["paths"]
    )
    (art / (tag + "-openapi.json")).write_text(
        json.dumps(spec_response[1], ensure_ascii=False, indent=2) + "\n"
    )
    assert all(
        hashlib.sha256(Path(path).read_bytes()).hexdigest() == checksum
        for path, checksum in artifact_inputs.items()
    )
    report = {
        "ok": True,
        "artifact_inputs": artifact_inputs,
        "iam_jar_sha256": hashlib.sha256(iam_artifact.read_bytes()).hexdigest(),
        "consumer_artifact": hashlib.sha256(consumer_artifact.read_bytes()).hexdigest(),
        "proof_scope": "actual two-IAM plus independently generated own-parent consumer from published artifacts, with separate consumer DB and no IAM private credentials; not capacity acceptance",
        "cases": cases,
        "metadata_capture_scope": "same packaged IAM JAR; native Springdoc via isolated loopback management port; no production anonymous documentation claim",
        "private_logs": str(private),
    }
    (art / (tag + ".json")).write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    )
    print(json.dumps({"ok": True, "cases": len(cases)}))
except Exception as error:
    for number, container in enumerate(containers):
        with (private / f"container-{number}.log").open("w") as log:
            subprocess.run(
                ["docker", "logs", container], stdout=log, stderr=subprocess.STDOUT
            )
    report = {
        "ok": False,
        "artifact_inputs": artifact_inputs,
        "type": type(error).__name__,
        "detail": (
            str(error)
            if not isinstance(error, subprocess.CalledProcessError)
            else "preparation failed; private output retained"
        ),
        "cases": cases,
        "private_logs": str(private),
    }
    (art / (tag + ".json")).write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    )
    print(json.dumps(report, ensure_ascii=False))
    raise
finally:
    fixture.close()
