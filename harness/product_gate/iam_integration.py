"""真实数据库与TLS测试环境；秘密仅写入临时私有目录，失败仍清理本轮容器。"""

import base64
import json
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

from environment import command, require

PG = 'postgres@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15'
REDIS = 'redis@sha256:c88d347edef6249a6d2293f926f1eeb48bd40c57cbcd02c07f52e7f1fd2cb46b'


def inspect(code, argv, env, output):
    private = Path(tempfile.mkdtemp(prefix='domain-forge-iam-integration-')).resolve()
    private.chmod(0o700)
    containers = []
    local = dict(env)

    def run(*args):
        return subprocess.check_output(list(map(str, args)), env=local, stderr=subprocess.STDOUT, text=True).strip()

    def secret(name, text):
        path = private / name
        path.write_text(text)
        path.chmod(0o600)
        return path

    try:
        password = secrets.token_urlsafe(32)
        pgfile = secret('postgres.env', 'POSTGRES_USER=iam\nPOSTGRES_DB=iam\nPOSTGRES_PASSWORD=' + password + '\n')
        pg = run('docker', 'run', '-d', '--env-file', pgfile, '-p', '127.0.0.1::5432', '--tmpfs', '/var/lib/postgresql', PG)
        containers.append(pg)
        redis_password = secrets.token_urlsafe(32)
        config = secret('redis.conf', f'requirepass {redis_password}\nmaxmemory 64mb\nmaxmemory-policy noeviction\nsave ""\n')
        redis = run('docker', 'create', '-p', '127.0.0.1::6379', REDIS, 'redis-server', '/data/redis.conf')
        containers.append(redis)
        run('docker', 'cp', config, redis + ':/data/redis.conf')
        run('docker', 'start', redis)
        pgport = run('docker', 'port', pg, '5432/tcp').rsplit(':', 1)[1]
        redisport = run('docker', 'port', redis, '6379/tcp').rsplit(':', 1)[1]
        for _ in range(80):
            if subprocess.run(['docker', 'exec', pg, 'pg_isready', '-U', 'iam', '-d', 'iam'], capture_output=True).returncode == 0:
                break
            time.sleep(.25)
        else:
            raise RuntimeError('temporary PostgreSQL not ready')
        local.update(IAM_DB_URL=f'jdbc:postgresql://127.0.0.1:{pgport}/iam', IAM_DB_USER='iam',
                     IAM_DB_PASSWORD=password, IAM_REDIS_HOST='127.0.0.1', IAM_REDIS_PORT=redisport,
                     IAM_REDIS_PASSWORD=redis_password,
                     IAM_FINGERPRINT_KEY=base64.b64encode(secrets.token_bytes(32)).decode())
        local['ACCESS_TEST_PASSWORD'] = secrets.token_urlsafe(32)
        run('openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', private / 'key.pem',
            '-out', private / 'cert.pem', '-days', '1', '-subj', '/CN=localhost',
            '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1',
            '-addext', 'extendedKeyUsage=serverAuth,clientAuth')
        (private / 'key.pem').chmod(0o600)
        run('openssl', 'pkcs12', '-export', '-in', private / 'cert.pem', '-inkey', private / 'key.pem',
            '-out', private / 'store.p12', '-passout', 'env:ACCESS_TEST_PASSWORD')
        run('keytool', '-importcert', '-noprompt', '-keystore', private / 'trust.p12', '-storetype', 'PKCS12',
            '-storepass:env', 'ACCESS_TEST_PASSWORD', '-alias', 'fixture', '-file', private / 'cert.pem')
        local.update(ACCESS_TEST_STORE=str(private / 'store.p12'), ACCESS_TEST_TRUST=str(private / 'trust.p12'))
        execution = command([*argv, '-f', code / 'pom.xml', '-pl',
            'services/iam,framework/domain-forge-iam-client', '-am',
            '-Dtest=*Test,IdentityPersistenceIT,CurrentStateTransportIT', 'test'], code,
            output / 'integration.log', 600, local)
        counts = {}
        required = json.loads(Path(__file__).with_name('iam-baseline.json').read_text()).get('required_integration_tests', {})
        for relative, name, expected in [
            ('services/iam', 'org.domainforge.iam.infrastructure.IdentityPersistenceIT', 25),
            ('framework/domain-forge-iam-client',
             'org.domainforge.iam.client.internal.CurrentStateTransportIT', 5)]:
            report = code / relative / 'target/surefire-reports' / ('TEST-' + name + '.xml')
            suite = ET.parse(report).getroot()
            require(int(suite.get('tests')) >= expected and all(int(suite.get(key, '0')) == 0
                    for key in ('errors', 'failures', 'skipped')), 'PG_IAM_TESTS', '真实IAM测试遗漏、失败或跳过')
            require(set(required.get(name, ())) <= {case.get('name') for case in suite.findall('testcase')},
                    'PG_IAM_TESTS', '真实传输的必需故障反例未执行')
            counts[name] = int(suite.get('tests'))
        return {'ok': True, 'execution': execution, 'test_counts': counts, 'private': str(private)}
    finally:
        for container in containers:
            subprocess.run(['docker', 'rm', '-f', '-v', container], capture_output=True)
