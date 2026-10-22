"""阶段 2b 同一产品入口：固定模板、独立消费、真实数据库和必要反例。"""

import json
from pathlib import Path
import shutil

import environment
from environment import require, product_root
import foundation_build as build
import foundation_runtime as runtime
import foundation_negative
import web_advisory


def preserve(workspace, output):
    """失败同样保留原生证据；不把缓存、数据库秘密与整个临时工程归档。"""
    target = output / 'foundation-evidence'; target.mkdir(exist_ok=True)
    for directory in workspace.glob('*-evidence'):
        shutil.copytree(directory, target / directory.name, dirs_exist_ok=True)
    negatives = workspace / 'foundation-negative'
    for path in negatives.rglob('*'):
        if path.is_file() and ('evidence' in path.parts or 'surefire-reports' in path.parts):
            destination = target / 'negative' / path.relative_to(negatives)
            destination.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(path, destination)
    for path in workspace.glob('*/target/surefire-reports/*'):
        if path.is_file():
            destination = target / path.relative_to(workspace)
            destination.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(path, destination)


def inspect(root, project, observed, web, argv, env, home, repository, workspace, output, args):
    result = {'ok': False, 'human_acceptance': False, 'remote_delivery_proven': False}
    directory = output / 'foundation'; directory.mkdir()
    try:
        code = product_root(project)
        result['inputs'] = environment.foundation_sources(code.parent)
        result['component'] = build.component(root, code, observed, repository, args.tools, directory)
        result['domain'] = build.domain_component(root, code, observed, repository, args.tools, directory)
        publication = build.publish(root, code, result['component'], result['domain'], web['publication'], argv, env, directory)
        result['publication'] = publication
        result['consumers'] = {}
        with runtime.Database(root, workspace) as database:
            result['database'] = {'image': database.image, 'image_id': database.image_id, 'server_version': database.sql('SHOW server_version')}
            for name, own in (('bare-service', False), ('owned-service', True)):
                app, metadata = build.generate(code, workspace, name, own)
                consumer = build.consume(root, code, app, workspace, publication, repository, home,
                                         database.environment(env), args.tools)
                consumer['origin'] = metadata
                consumer['runtime'] = runtime.bare(Path(consumer['observed']['artifact']['path']), directory, env, database, name)
                result['consumers'][name] = consumer
            app, metadata = build.generate(code, workspace, 'fixture-service')
            build.inject(code, app)
            consumer = build.consume(root, code, app, workspace, publication, repository, home,
                                     database.environment(env), args.tools, True)
            consumer['origin'] = metadata
            result['fixture'] = consumer
            artifact = Path(consumer['observed']['artifact']['path'])
            result['queries'] = runtime.queries(artifact, directory, env, database)
            result['startup_failures'] = runtime.startup_failures(artifact, directory, env, database)
            result['resource_mismatch'] = runtime.resource_mismatch(artifact, directory, env, database)
            negatives = workspace / 'foundation-negative'; negatives.mkdir()
            if args.check in ('all', 'build-gate'):
                result['structure_counterexamples'] = foundation_negative.structural(root, code, consumer,
                    publication, negatives, home, database.environment(env), args.tools)
            result['transaction_counterexamples'] = foundation_negative.transactions(root, consumer, negatives,
                home, database.environment(env), args.tools)
            result['query_counterexamples'] = foundation_negative.queries(root, consumer, negatives,
                home, database.environment(env), args.tools, database)
            database.sql("DELETE FROM probe_entry; INSERT INTO probe_entry SELECT n,'match' FROM generate_series(1,60) n")
            result['migrations'] = runtime.migrations(artifact, code / environment.FOUNDATION_FIXTURE, directory, env, database)
        if args.check in ('all', 'dependency-risk-gate'):
            result['advisory'] = {}
            scan = directory / 'persistence-advisory'; scan.mkdir()
            result['advisory']['persistence'] = web_advisory.scan(code, environment.PERSISTENCE, result['component'], argv, env, args.advisory_data, scan)
            for name, consumer in {**result['consumers'], 'fixture-service': result['fixture']}.items():
                scan = directory / (name + '-advisory'); scan.mkdir()
                if name == 'owned-service':
                    parent = Path(consumer['project']).parent / 'company-parent/pom.xml'
                    require(environment.digest(parent) == consumer['origin']['company_parent_sha256'],
                            'PG_INPUT_CHANGED', '自有 parent 与实际构建输入不一致')
                    target = scan / 'company-parent'; target.mkdir()
                    shutil.copy2(parent, target / 'pom.xml')
                result['advisory'][name] = web_advisory.scan(Path(consumer['project']), Path('.'), consumer['observed'],
                    consumer['argv'], env, args.advisory_data, scan)
        require(environment.foundation_sources(code.parent) == result['inputs'], 'PG_INPUT_CHANGED', '模板或夹具执行期间变化')
        result['ok'] = True
        return result
    finally:
        preserve(workspace, output)
        (directory / 'report.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
