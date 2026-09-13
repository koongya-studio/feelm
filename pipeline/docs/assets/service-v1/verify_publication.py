"""Verify the selected v1 handoff without loading models or contacting services."""
import argparse
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[4]
ASSETS = Path(__file__).resolve().parent


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def contained(base, relative):
    path = (base / relative).resolve()
    if not path.is_relative_to(base.resolve()):
        raise ValueError('Path escapes supplied root: ' + relative)
    return path


def check_file(root, record):
    path = contained(root, record['path'])
    if not path.is_file():
        raise ValueError('Missing file: ' + record['path'])
    if path.stat().st_size != record['bytes'] or digest(path) != record['sha256']:
        raise ValueError('Byte/hash mismatch: ' + record['path'])
    return path


def xml_text_blocks(element):
    """Join rendered OOXML runs, preserving paragraph and line boundaries."""
    drawing = 'http://schemas.openxmlformats.org/drawingml/2006/main'
    word = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
    if element.tag in {f'{{{drawing}}}p', f'{{{word}}}p'}:
        text_tags = {f'{{{drawing}}}t', f'{{{word}}}t'}
        break_tags = {f'{{{drawing}}}br', f'{{{word}}}br', f'{{{word}}}tab'}
        run_tags = {f'{{{drawing}}}r', f'{{{drawing}}}fld', f'{{{word}}}r',
                    f'{{{word}}}hyperlink', f'{{{word}}}fldSimple'}
        parts = []
        if element.text:
            yield element.text
        for child in element:
            if child.tag in run_tags:
                for node in child.iter():
                    if node.tag in text_tags:
                        parts.append(node.text or '')
                    elif node.tag in break_tags:
                        parts.append('\n')
                    elif node.text:
                        yield node.text
                    if node is not child and node.tail:
                        yield node.tail
            else:
                yield ''.join(parts)
                parts.clear()
                yield from xml_text_blocks(child)
            if child.tail:
                yield child.tail
        yield ''.join(parts)
        return
    if element.text:
        yield element.text
    for child in element:
        yield from xml_text_blocks(child)
        if child.tail:
            yield child.tail


def check_pptx_paths(path):
    """Check decoded OOXML text/attributes, including notes and relationships."""
    machine_path = re.compile(r'(?<![\w])(?:[A-Za-z]:[/\\]|file:/|\\\\[^\\\s]+\\)', re.I)
    with ZipFile(path) as package:
        for name in package.namelist():
            if not name.endswith(('.xml', '.rels')):
                continue
            element = ET.fromstring(package.read(name))
            values = list(xml_text_blocks(element))
            values.extend(value for node in element.iter() for value in node.attrib.values())
            if any(machine_path.search(value) for value in values):
                raise ValueError('Machine-specific path in PPTX: ' + path.name + ' / ' + name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    roots = parser.add_mutually_exclusive_group()
    roots.add_argument('--source-root', type=Path, help='Original research snapshot: copied sources and external files')
    roots.add_argument('--artifact-root', type=Path, help='Transferred external files only, using source_path layout')
    args = parser.parse_args()
    publication = json.loads((ASSETS / 'publication-files.json').read_text('utf-8'))
    paths = [check_file(ROOT, item) for item in publication['files']]
    if len(paths) != len(set(paths)):
        raise ValueError('Duplicate publication file')
    expected = set()
    for folder in ['pipeline/docs/service-v1', 'pipeline/docs/assets/service-v1',
                   'pipeline/configs/service-v1']:
        for path in (ROOT / folder).rglob('*'):
            if path.is_file() and path.name != 'publication-files.json' and '__pycache__' not in path.parts:
                expected.add(path.resolve())
    for name in ['pipeline/artifacts/service-v1.json', 'pipeline/docs/README.md',
                 'pipeline/configs/README.md', 'pipeline/artifacts/README.md']:
        expected.add((ROOT / name).resolve())
    if set(paths) != expected:
        raise ValueError('Publication inventory does not match handoff files')
    for path in paths:
        if path.suffix.lower() == '.pptx':
            check_pptx_paths(path)
        if path.suffix == '.json':
            json.loads(path.read_text('utf-8'))
        if path.suffix == '.md':
            for link in re.findall(r'\]\(([^)]+)\)', path.read_text('utf-8')):
                if re.match(r'https?://|mailto:|#', link):
                    continue
                target = contained(ROOT, str((path.parent / link.split('#')[0]).relative_to(ROOT)))
                if not target.is_file():
                    raise ValueError('Broken link: ' + str(path.relative_to(ROOT)) + ' -> ' + link)
    provenance = json.loads((ASSETS / 'provenance.json').read_text('utf-8'))
    for item in provenance['files']:
        check_file(ROOT, item)
        if args.source_root:
            source = check_file(args.source_root, item['source'])
            if item['path'].startswith('pipeline/configs/service-v1/'):
                actual = json.loads((ROOT / item['path']).read_text('utf-8'))
                expected = json.loads(source.read_text('utf-8-sig'))
                if actual != expected:
                    raise ValueError('Configuration values changed: ' + item['path'])
    external = json.loads((ROOT / 'pipeline/artifacts/service-v1.json').read_text('utf-8'))
    for item in external['artifacts']:
        if item['availability'] != 'LOCAL_ONLY' or item['shared_uri'] is not None:
            raise ValueError('Unexpected artifact sharing claim')
        artifact_root = args.artifact_root or args.source_root
        if artifact_root:
            check_file(artifact_root, dict(item, path=item['source_path']))
    print(json.dumps({'status': 'PASS', 'published_files': len(paths),
                      'source_files': len(provenance['files']),
                      'external_files': len(external['artifacts']),
                      'source_root_verified': args.source_root is not None,
                      'external_files_verified': bool(args.artifact_root or args.source_root),
                      'service_runtime_tested': False}, indent=2))


if __name__ == '__main__':
    main()
