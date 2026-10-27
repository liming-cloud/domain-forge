#!/usr/bin/env python3
"""在本仓库 platform/services/<name> 生成一个业务服务，并加入 services 聚合；同时在 deploy/ 写入其部署文件。

生成结果继承内部 parent，自动执行架构规则与质量门禁；生成后代码归该服务维护，模板升级不覆盖已有服务。
"""

import argparse
import json
from pathlib import Path
import re
import sys

TEMPLATE = Path(__file__).resolve().parent
KEYWORDS = set(
    'abstract assert boolean break byte case catch char class const continue default do double else enum extends '
    'final finally float for goto if implements import instanceof int interface long native new package private '
    'protected public return short static strictfp super switch synchronized this throw throws transient try void '
    'volatile while true false null var yield record sealed permits'.split())
RESERVED_NAMES = {'iam', 'services', 'framework', 'shared', 'platform'}


def require(valid, message):
    """拒绝非法输入，调用方据此保持仓库不变。"""
    if not valid:
        raise ValueError(message)


def render(text, values):
    """替换封闭参数集合；不求值，也不把参数作为脚本执行。"""
    for key, replacement in values.items():
        text = text.replace('@@' + key + '@@', replacement)
    require('@@' not in text, '模板含未解析参数')
    return text


def postgres_image(root):
    """部署文件中的数据库初始化任务使用与 deploy/compose.yml 相同的 postgres 镜像。"""
    compose = root / 'deploy' / 'compose.yml'
    require(compose.is_file(), '找不到 deploy/compose.yml')
    match = re.search(r'^  postgres:\n    image: (\S+)$', compose.read_text(encoding='utf-8'), re.MULTILINE)
    require(match is not None, 'deploy/compose.yml 中没有 postgres 镜像')
    return match.group(1)


def values_for(name, package, namespace):
    """校验输入并推导类名、环境变量前缀、数据库 schema 与权限命名空间。"""
    require(re.fullmatch(r'[a-z][a-z0-9]*(?:-[a-z0-9]+)*', name) is not None and len(name) <= 40, '服务名须为小写字母、数字与 -')
    require(name not in RESERVED_NAMES, '服务名与仓库已有模块冲突')
    require(re.fullmatch(r'[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+', package) is not None, '包名格式无效')
    require(not KEYWORDS.intersection(package.split('.')), '包名不能包含 Java 关键字')
    namespace = namespace or re.sub(r'-service$', '', name)
    require(re.fullmatch(r'[a-z][a-z0-9-]{1,31}', namespace) is not None and namespace != 'iam', '权限命名空间无效')
    return {
        'ARTIFACT': name,
        'PACKAGE': package,
        'PACKAGE_PATH': package.replace('.', '/'),
        'APP_CLASS': ''.join(part.capitalize() for part in name.split('-')) + 'Application',
        'SCHEMA': name.replace('-', '_'),
        'ENV': name.replace('-', '_').upper(),
        'NAMESPACE': namespace,
    }


def load_assets(with_example, values):
    """先读取并渲染全部登记资产，避免写到一半才发现缺失；未登记或缺失的资产直接拒绝。"""
    manifest = json.loads((TEMPLATE / 'template.json').read_text(encoding='utf-8'))
    require(manifest['schema'] == 2, '模板清单版本不匹配')
    listed = {entry['source'] for entry in manifest['assets']}
    actual = {p.relative_to(TEMPLATE).as_posix() for p in (TEMPLATE / 'assets').rglob('*') if p.is_file()}
    require(listed == actual, '模板含未登记或缺失资产')
    assets = []
    for entry in manifest['assets']:
        if entry['example'] and not with_example:
            continue
        target = Path(render(entry['target'], values))
        require(not target.is_absolute() and '..' not in target.parts, '资产目标越界')
        require(entry.get('root', 'service') in ('service', 'repository'), '资产根目录无效')
        assets.append((entry.get('root', 'service'), target,
                       render((TEMPLATE / entry['source']).read_text(encoding='utf-8'), values)))
    return manifest['version'], assets


def add_module(services_pom, name):
    """把新服务加入 services 聚合；已登记则拒绝。"""
    text = services_pom.read_text(encoding='utf-8')
    require('<module>' + name + '</module>' not in text, '服务已在聚合中登记')
    marker = '    </modules>'
    require(marker in text, 'services/pom.xml 中没有 modules 段')
    services_pom.write_text(text.replace(marker, '        <module>' + name + '</module>\n' + marker), encoding='utf-8')


def generate(root, name, package, namespace=None, with_example=False):
    """生成服务目录与部署文件并登记聚合；目标已存在时拒绝，失败时删除本次创建的文件。"""
    values = values_for(name, package, namespace)
    services = root / 'platform' / 'services'
    require((services / 'pom.xml').is_file(), '找不到 platform/services/pom.xml，请在仓库根目录运行或传入 --root')
    output = services / name
    require(not output.exists(), '服务目录已存在，禁止覆盖')
    values['POSTGRES_IMAGE'] = postgres_image(root)
    version, assets = load_assets(with_example, values)
    bases = {'service': output, 'repository': root}
    for kind, relative, _ in assets:
        if kind == 'repository':
            require(not (root / relative).exists(), str(relative) + ' 已存在，禁止覆盖')
            require((root / relative).parent.is_dir(), str(relative.parent) + ' 不存在')
    created = []
    try:
        for kind, relative, text in assets:
            path = bases[kind] / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open('x', encoding='utf-8') as stream:
                stream.write(text)
            created.append(path)
        add_module(services / 'pom.xml', name)
    except Exception:
        for path in reversed(created):
            path.unlink()
        for directory in sorted({p for c in created for p in c.parents if output in (p, *p.parents)}, reverse=True):
            if directory.exists() and not any(directory.iterdir()):
                directory.rmdir()
        raise
    return {'template_version': version, 'service': name, 'package': package, 'namespace': values['NAMESPACE'],
            'example': with_example, 'files': len(created)}


def main():
    """暴露有限生成参数；失败返回非零。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--name', required=True, help='服务名，即模块目录与 artifactId，例如 order-service')
    parser.add_argument('--package', required=True, help='服务根包，例如 com.company.order')
    parser.add_argument('--permission-namespace', help='IAM 权限命名空间，默认为去掉 -service 的服务名')
    parser.add_argument('--with-example', action='store_true', help='附带便笺示例：命令、纯查询、编排查询与 IAM 数据范围')
    parser.add_argument('--root', default=str(TEMPLATE.parents[2]), help='仓库根目录，默认为本模板所在仓库')
    args = parser.parse_args()
    try:
        result = generate(Path(args.root).resolve(), args.name, args.package, args.permission_namespace,
                          args.with_example)
    except (ValueError, OSError, KeyError) as failure:
        parser.exit(2, str(failure) + '\n')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    sys.exit(main())
