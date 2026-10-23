"""M2 本地组合反例入口；生产 DAO 不切换，原纯原生失败单独保留。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

from runtime_fixture import RuntimeFixture


PG = "postgres@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15"
REDIS = "redis@sha256:c88d347edef6249a6d2293f926f1eeb48bd40c57cbcd02c07f52e7f1fd2cb46b"


def suite(project, name):
    """保留每个原始用例结果；不把已知失败改为 skip 或预期成功。"""
    path = project / "target/surefire-reports" / ("TEST-" + name + ".xml")
    root = ET.parse(path).getroot()
    return {
        "counts": {key: int(root.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")},
        "cases": [{"name": case.get("name"), "failure": case.find("failure") is not None,
                   "error": case.find("error") is not None, "skipped": case.find("skipped") is not None}
                  for case in root.findall("testcase")],
        "xml_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--code", type=Path, required=True)
    parser.add_argument("--maven", type=Path, required=True)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    code = args.code.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    fixture = RuntimeFixture()
    local = fixture.env
    result = {"target_subset_passed": False, "m2_complete": False,
              "production_dao_changed": False, "human_acceptance": False,
              "images": {"postgres": PG, "redis": REDIS}, "private": str(fixture.private)}
    maven = [str(args.maven.resolve()), "-B", "-ntp", "-Dmaven.repo.local=" + str(args.repository.resolve())]
    access = code / "framework/domain-forge-access-control"
    iam = code / "services/iam"

    def execute(name, project, arguments, timeout=600):
        with (output / (name + ".log")).open("w") as log:
            return subprocess.run(maven + ["-f", str(project / "pom.xml")] + arguments,
                                  env=local, stdout=log, stderr=subprocess.STDOUT, timeout=timeout).returncode

    try:
        password = secrets.token_urlsafe(32)
        settings = fixture.secretfile("postgres.env", {
            "POSTGRES_USER": "domainforge", "POSTGRES_DB": "domainforge", "POSTGRES_PASSWORD": password})
        postgres = fixture.docker("run", "-d", "--env-file", str(settings), "-p", "127.0.0.1::5432",
                                  "--tmpfs", "/var/lib/postgresql", PG)
        fixture.containers.append(postgres)
        redis_password = secrets.token_urlsafe(32)
        config = fixture.private / "redis.conf"
        config.write_text(f'requirepass {redis_password}\nmaxmemory 64mb\nmaxmemory-policy noeviction\nsave ""\n')
        config.chmod(0o600)
        redis = fixture.docker("create", "-p", "127.0.0.1::6379", REDIS, "redis-server", "/data/redis.conf")
        fixture.containers.append(redis)
        fixture.docker("cp", str(config), redis + ":/data/redis.conf")
        fixture.docker("start", redis)
        postgres_port = fixture.docker("port", postgres, "5432/tcp").rsplit(":", 1)[1]
        redis_port = fixture.docker("port", redis, "6379/tcp").rsplit(":", 1)[1]
        for _ in range(80):
            ready = subprocess.run(["docker", "exec", postgres, "pg_isready", "-U", "domainforge"],
                                   env=local, capture_output=True)
            if ready.returncode == 0:
                break
            time.sleep(.25)
        else:
            raise RuntimeError("本次 PostgreSQL 未就绪")
        local.update(POSTGRES_PORT=postgres_port, POSTGRES_PASSWORD=password,
                     REDIS_PORT=redis_port, REDIS_PASSWORD=redis_password, REDIS_CONTAINER=redis,
                     IAM_DB_URL=f"jdbc:postgresql://127.0.0.1:{postgres_port}/domainforge",
                     IAM_DB_USER="domainforge", IAM_DB_PASSWORD=password,
                     IAM_REDIS_PORT=redis_port, IAM_REDIS_PASSWORD=redis_password)
        for owner, project in (("access", access), ("iam", iam)):
            assert execute(owner + "-classpath", project, ["org.apache.maven.plugins:maven-dependency-plugin:3.9.0:build-classpath", "-DincludeScope=test",
                           "-Dmdep.outputFile=target/m2-test-classpath.txt"]) == 0, owner + " classpath failed"
        access_cp = os.pathsep.join([str(access / "target/test-classes"), str(access / "target/classes"),
                                    (access / "target/m2-test-classpath.txt").read_text().strip()])
        local["M2_WIRE_CLASSPATH"] = access_cp
        classpath = (iam / "target/m2-test-classpath.txt").read_text().strip().split(os.pathsep)
        result["target_native_artifacts"] = {}
        for name in ("sa-token-core-1.46.0.jar", "sa-token-redisson-1.46.0.jar", "redisson-4.8.0.jar"):
            files = [Path(path) for path in classpath if Path(path).name == name]
            assert len(files) == 1, "固定原生组合缺失或重复：" + name
            result["target_native_artifacts"][name] = hashlib.sha256(files[0].read_bytes()).hexdigest()
        result["native_exit"] = execute("native", access, ["-Dtest=SerializedSessionSafetyIT", "test"])
        result["native"] = suite(access, "org.domainforge.access.backend.internal.SerializedSessionSafetyIT")
        result["target_exit"] = execute("target", iam, ["-Dtest=CompositeSessionSafetyIT", "test"])
        result["target"] = suite(iam, "org.domainforge.iam.infrastructure.CompositeSessionSafetyIT")
        for project, name in ((access, "org.domainforge.access.backend.internal.SerializedSessionSafetyIT"),
                              (iam, "org.domainforge.iam.infrastructure.CompositeSessionSafetyIT")):
            report = project / "target/surefire-reports" / ("TEST-" + name + ".xml")
            shutil.copy2(report, output / report.name)
        result["target_subset_passed"] = result["target_exit"] == 0 and all(
            result["target"]["counts"][key] == 0 for key in ("failures", "errors", "skipped"))
        result["scope"] = "真实授权谓词与迟到帧组合；SQL前置状态由测试资源设置，不冒充完整业务命令或生产接线"
        assert result["target_subset_passed"], "组合子集失败；保留原始报告"
    finally:
        fixture.close()
        result["owned_containers_removed"] = all(subprocess.run(
            ["docker", "container", "inspect", container], capture_output=True, env=local).returncode != 0
            for container in fixture.containers)
        (output / "report.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
