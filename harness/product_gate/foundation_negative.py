"""对真实生成副本施加可证伪变体，不改发布模板或降低原检查。"""

import json
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

import build
import calibrate_maven
import environment
from environment import ProbeFailure, command, digest, require
import foundation_build
import foundation_runtime
import web_build


def copy_case(consumer, workspace, name):
    directory = workspace / name; directory.mkdir()
    app = directory / Path(consumer['project']).name
    shutil.copytree(consumer['project'], app, ignore=shutil.ignore_patterns('target'))
    output = directory / 'evidence'; output.mkdir()
    return app, output


def compile_case(root, consumer, app, output, home, env):
    extension = calibrate_maven.observer(home, output); inventory = output / 'inventory.xml'
    argv = [str(app / 'mvnw'), *consumer['argv'][1:]]
    execution = command([*argv, '-Dmaven.ext.class.path=' + str(extension), '-Ddomainforge.scope.output=' + str(inventory), 'verify'],
                        app, output / 'verify.log', 600, env)
    require(not env.get('DB_PASSWORD') or env['DB_PASSWORD'] not in (output / 'verify.log').read_text(),
            'PG_DIAGNOSTIC_SECRET', '反例构建日志包含数据库秘密')
    observed = web_build.observe(root, app, ET.parse(inventory).getroot(), Path(consumer['repository']), True,
                                foundation_build.required_tests(True))
    return execution, observed


def format_source(source, tools):
    """坏行为仍使用规范格式，避免把格式拒绝误当作语义拒绝。"""
    formatter = Path(tools) / 'google-java-format-1.37.0-all-deps.jar'
    command(['java', '-jar', formatter, '--replace', source], source.parent, source.parent / 'format.log', 30)
    (source.parent / 'format.log').unlink()


def structural(root, code, consumer, publication, workspace, home, env, tools):
    base = 'src/main/java/' + foundation_build.PACKAGE.replace('.', '/') + '/'
    cases = [('application-orm', 'application/command/WritePair.java',
              'private com.baomidou.mybatisplus.core.mapper.BaseMapper<?> misplaced;', 'SG_TECHNICAL_DEPENDENCY'),
             ('controller-mapper', 'interfaces/http/RecordController.java',
              'private ' + foundation_build.PACKAGE + '.infrastructure.persistence.RecordMapper misplaced;', 'SG_ROLE_DEPENDENCY'),
             ('reverse-bootstrap', 'infrastructure/query/SqlRecords.java',
              'private ' + foundation_build.PACKAGE + '.bootstrap.Application misplaced;', 'SG_ROLE_DEPENDENCY'),
             ('private-component', 'bootstrap/FixturePersistence.java',
              'private org.domainforge.web.autoconfigure.WebSupportAutoConfiguration misplaced;', 'SG_INTERNAL_TYPE')]
    result = {}
    for name, relative, addition, expected in cases:
        app, output = copy_case(consumer, workspace, name)
        source = app / base / relative; text = source.read_text()
        offset = text.index('{', text.index('public class')) + 1
        source.write_text(text[:offset] + '\n' + addition + '\n' + text[offset:]); format_source(source, tools)
        execution, observed = compile_case(root, consumer, app, output, home, env)
        declaration = foundation_build.module_contract(code, publication, output, True)
        quality = build.quality(root, app, observed, tools, declaration, digest(declaration))
        (output / 'quality.json').write_text(json.dumps(quality, ensure_ascii=False, indent=2) + '\n')
        require(not quality['ok'] and expected in {item['code'] for item in quality['diagnostics']},
                'PG_NEGATIVE_CAUSE', '结构反例未被预期规则拒绝：' + name)
        result[name] = {'execution': execution, 'expected': expected, 'diagnostics': quality['diagnostics']}
    output = workspace / 'wrong-identity'; output.mkdir()
    declaration = foundation_build.module_contract(code, publication, output, True)
    document = json.loads(declaration.read_text())
    next(module for module in document['modules'] if module['id'] == 'persistence')['artifact']['sha256'] = '0' * 64
    declaration.write_text(json.dumps(document, indent=2))
    try:
        build.quality(root, Path(consumer['project']), consumer['observed'], tools, declaration, digest(declaration))
    except ProbeFailure as failure:
        require(failure.diagnostic['code'] == 'SG_COMPONENT_ARTIFACT', 'PG_NEGATIVE_CAUSE', '错身份没有在制品绑定处拒绝')
        result['wrong-identity'] = failure.diagnostic
    else:
        require(False, 'PG_NEGATIVE_MISSED', '错误组件身份仍被接受')
    return result


def transactions(root, consumer, workspace, home, env, tools):
    result = {}
    for name, replacement in [('no-transaction', ''), ('wrong-rollback', '@Transactional(noRollbackFor = java.io.IOException.class)')]:
        app, output = copy_case(consumer, workspace, name)
        source = app / 'src/main/java' / foundation_build.PACKAGE.replace('.', '/') / 'application/command/WritePair.java'
        source.write_text(source.read_text().replace('@Transactional\n', replacement + '\n')); format_source(source, tools)
        try:
            compile_case(root, consumer, app, output, home, env)
        except ProbeFailure as failure:
            text = (output / 'verify.log').read_text()
            require(failure.diagnostic['code'] == 'PG_NATIVE_FAILURE' and
                    'checkedFailureRollsBackBothWrites' in text and 'expected: <0> but was: <2>' in text,
                    'PG_NEGATIVE_CAUSE', '事务反例没有证明两条记录错误提交：' + name)
            result[name] = failure.diagnostic
        else:
            require(False, 'PG_NEGATIVE_MISSED', '错误事务策略未失败：' + name)
    return result


def queries(root, consumer, workspace, home, env, tools, database):
    result = {}
    variants = {'n-plus-one': 'for (var row : rows) { mapper.selectById(row.getId()); }',
                'query-write': 'var unexpected = new RecordPo(); unexpected.setId(9000L); unexpected.setValue("side-effect"); mapper.insert(unexpected);'}
    for name, statement in variants.items():
        app, output = copy_case(consumer, workspace, name)
        source = app / 'src/main/java' / foundation_build.PACKAGE.replace('.', '/') / 'infrastructure/query/SqlRecords.java'
        source.write_text(source.read_text().replace('    return rows.stream()', '    ' + statement + '\n    return rows.stream()'))
        format_source(source, tools)
        execution, observed = compile_case(root, consumer, app, output, home, env)
        database.sql("DELETE FROM probe_entry; INSERT INTO probe_entry SELECT n,'match' FROM generate_series(1,60) n")
        with foundation_runtime.Application(Path(observed['artifact']['path']), output, env, name) as application:
            application.ready()
            try:
                foundation_runtime.query_case(application, database, output, name)
            except ProbeFailure as failure:
                observed_query = json.loads((output / (name + '.json')).read_text())
                require(failure.diagnostic['code'] in {'PG_QUERY_SQL', 'PG_QUERY_WRITE'} and
                        (len(observed_query['statements']) > 1 if name == 'n-plus-one' else not observed_query['rows_unchanged']),
                        'PG_NEGATIVE_CAUSE', 'SQL 反例未观察到实际补查或写入')
                result[name] = {'execution': execution, 'actual': observed_query, 'rejection': failure.diagnostic}
            else:
                require(False, 'PG_NEGATIVE_MISSED', 'SQL 错误行为未被拒绝：' + name)
    return result
