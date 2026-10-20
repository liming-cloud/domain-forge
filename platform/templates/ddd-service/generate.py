#!/usr/bin/env python3
"""从固定资产创建独立服务；不覆盖工程，也不执行模板输入提供的命令。"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil

KEYWORDS = set('abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while true false null _ var yield record sealed permits'.split())


def require(valid, message):
    """拒绝非法输入，调用方可据此保持原工程不变。"""
    if not valid:
        raise ValueError(message)


def ordinary_path(path):
    """按词法路径检查全部父级，不能 resolve 后再丢失符号链接事实。"""
    require(not any(part == '..' for part in path.parts), '路径不接受上级跳转')
    require(not any(item.is_symlink() for item in (path, *path.parents)), '路径不接受符号链接')


def render(value, values):
    """替换封闭参数集合；不求值或把参数作为脚本执行。"""
    for key, replacement in values.items():
        value = value.replace('@@' + key + '@@', replacement)
    require('@@' not in value, '模板含未解析参数')
    return value


def load_assets(source, values):
    """先校验全部版本化资产，避免写到一半才发现缺失或污染。"""
    ordinary_path(source)
    ordinary_path(source / 'generate.py')
    ordinary_path(source / 'template.json')
    require(not any(p.is_symlink() for p in (source / 'assets').rglob('*')), '资产不接受符号链接')
    manifest = json.loads((source / 'template.json').read_text())
    require(manifest['schema'] == 1 and manifest['version'] == '0.2.0-2b.1', '模板版本不匹配')
    assets = []
    targets = set()
    for entry in manifest['assets']:
        origin = Path(entry['source'])
        target = Path(render(entry['target'], values))
        require(not origin.is_absolute() and not target.is_absolute(), '资产必须是相对路径')
        ordinary_path(source / origin)
        require('..' not in target.parts and target not in targets, '资产目标冲突或越界')
        data = (source / origin).read_bytes()
        require(hashlib.sha256(data).hexdigest() == entry['sha256'], '资产字节不匹配：' + str(origin))
        if entry['render']:
            data = render(data.decode('utf-8'), values).encode('utf-8')
        assets.append((target, data, entry['executable']))
        targets.add(target)
    expected = {entry['source'] for entry in manifest['assets'] if entry['source'].startswith('assets/')}
    actual = {p.relative_to(source).as_posix() for p in (source / 'assets').rglob('*') if p.is_file()}
    require(expected == actual, '模板含未登记或缺失资产')
    return manifest, assets


def generate(source, output, group, artifact, package):
    """生成一个新目录；参数无副作用，只有本次取得所有权的输出才能回滚。"""
    require(re.fullmatch(r'[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*', group) is not None, 'groupId 格式无效')
    require(re.fullmatch(r'[a-z][a-z0-9]*(?:-[a-z0-9]+)*', artifact) is not None and len(artifact) <= 64, 'artifactId 格式无效')
    require(re.fullmatch(r'[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+', package) is not None and not KEYWORDS.intersection(package.split('.')), '包名格式无效')
    output = output.absolute()
    ordinary_path(output)
    require(output.name == artifact and output.parent.is_dir(), '输出目录名须为 artifactId，且父目录已经存在')
    require(not output.exists(), '输出已存在，禁止覆盖')
    values = {'GROUP': group, 'ARTIFACT': artifact, 'PACKAGE': package, 'PACKAGE_PATH': package.replace('.', '/')}
    manifest, assets = load_assets(source, values)
    output.mkdir()
    try:
        for relative, data, executable in assets:
            path = output / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open('xb') as stream:
                stream.write(data)
            if executable:
                path.chmod(0o755)
        metadata = {'template_version': manifest['version'], 'generator_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), 'template_sha256': hashlib.sha256((source / 'template.json').read_bytes()).hexdigest(), 'group_id': group, 'artifact_id': artifact, 'package': package}
        (output / 'template-origin.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
    except Exception:
        shutil.rmtree(output)
        raise
    return metadata


def main():
    """暴露有限生成参数；失败返回非零，不吞掉缺失或不一致资产。"""
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('group-id', 'artifact-id', 'package', 'output'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    try:
        result = generate(Path(__file__).absolute().parent, Path(args.output), args.group_id, args.artifact_id, args.package)
    except (ValueError, OSError, KeyError) as failure:
        parser.exit(2, str(failure) + '\n')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
