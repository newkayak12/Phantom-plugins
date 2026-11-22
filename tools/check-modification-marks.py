#!/usr/bin/env python3
"""Check that every IntelliJ Community file a plugin changed carries the Phantom modification line.

Apache 2.0 section 4(b) asks for a notice in each modified file. This compares every .java/.kt/.groovy file under a
plugin's src/<set>/(java|kotlin|groovy)/ and testData/ (main, test and test data) with an IntelliJ Community checkout at
the imported revision. A source file is derived when an upstream file has its name and package (upstream source roots
often drop the package prefix from the directory path); a test data file when its path below testData/ and an upstream
file's path below its testData/ are one a suffix of the other. When a derived file equals none of its upstream
counterparts (ignoring the modification line itself), it is changed and must carry the line, whether or not it has a
JetBrains header. Files with no upstream match (Phantom's own code, or code moved to a new package) are not checked.
--fix adds the line below the leading copyright comment, or as the first line when there is none.

    tools/check-modification-marks.py --upstream /path/to/intellij-community [--rev 58acf7643d] [--fix]
"""

import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import sys


MARK = '// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.'
MARK_TEXT = 'Modified by Phantom contributors'
# Phantom's import of IntelliJ Community idea/262.10968.63, unchanged.
DEFAULT_REV = '58acf7643d'
EXTENSIONS = ('.java', '.kt', '.groovy')
SOURCE_ROOT = re.compile(r'/src/[A-Za-z0-9]+/(?:java|kotlin|groovy)/')
ROOT = Path(__file__).resolve().parent.parent


PACKAGE = re.compile(rb'^\s*package\s+([\w.`]+)', re.MULTILINE)


def package_of(data):
    match = PACKAGE.search(data)
    return match.group(1).replace(b'`', b'') if match else None


class Blobs:
    """Reads upstream blobs through one git cat-file process."""

    def __init__(self, upstream):
        self.process = subprocess.Popen(['git', '-C', str(upstream), 'cat-file', '--batch'],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE)
        self.cache = {}

    def read(self, blob):
        if blob not in self.cache:
            self.process.stdin.write(blob.encode() + b'\n')
            self.process.stdin.flush()
            size = int(self.process.stdout.readline().split()[2])
            self.cache[blob] = self.process.stdout.read(size)
            self.process.stdout.read(1)
        return self.cache[blob]


def without_mark(data):
    return b''.join(line for line in data.splitlines(keepends=True) if MARK_TEXT.encode() not in line)


def blob_id(data):
    return hashlib.sha1(b'blob %d\0' % len(data) + data).hexdigest()


def upstream_index(upstream, rev):
    """Basename -> [(path, blob id)] of the source files in the upstream tree."""
    out = subprocess.run(['git', '-C', str(upstream), 'ls-tree', '-r', '--full-tree', rev],
                         check=True, capture_output=True, text=True).stdout
    index = {}
    for line in out.splitlines():
        meta, path = line.split('\t', 1)
        if path.endswith(EXTENSIONS):
            index.setdefault(path.rsplit('/', 1)[-1], []).append((path, meta.split()[2]))
    return index


def local_files(root):
    out = subprocess.run(['git', '-C', str(root), 'ls-files', '-z', '--', *('*' + e for e in EXTENSIONS)],
                         check=True, capture_output=True, text=True).stdout
    return [p for p in out.split('\0') if p]


def upstream_candidates(path, data, index, blobs):
    """Blob ids of the upstream files the local file at path is derived from."""
    same_name = index.get(path.rsplit('/', 1)[-1], ())
    if '/testData/' in '/' + path:
        # Test data of several upstream modules can sit under one testData/ with an extra folder, or the reverse.
        key = '/' + ('/' + path).split('/testData/', 1)[1]
        return [blob for upstream, blob in same_name
                if (upstream_key := '/' + upstream.rsplit('/testData/', 1)[-1]).endswith(key) or key.endswith(upstream_key)]
    match = SOURCE_ROOT.search('/' + path)
    if not match:
        return []
    package = package_of(data)
    if package is None:
        key = ('/' + path)[match.end():]
        return [blob for upstream, blob in same_name if ('/' + upstream).endswith('/' + key)]
    return [blob for _, blob in same_name if package_of(blobs.read(blob)) == package]


def changed_derived_files(root, index, blobs):
    """(path, marked) of every derived file that differs from all of its upstream counterparts."""
    result = []
    for path in local_files(root):
        data = (root / path).read_bytes()
        candidates = upstream_candidates(path, data, index, blobs)
        if not candidates:
            continue
        if blob_id(without_mark(data)) not in candidates:
            result.append((path, MARK_TEXT.encode() in data))
    return result


def add_mark(text):
    """The text with the modification line under its leading copyright comment, or first when there is none."""
    bom = '\ufeff' if text.startswith('\ufeff') else ''
    lines = text[len(bom):].splitlines(keepends=True)
    end = 0
    if lines and lines[0].lstrip().startswith('/*'):
        while end < len(lines) and '*/' not in lines[end]:
            end += 1
        end += 1
    else:
        while end < len(lines) and lines[end].lstrip().startswith('//'):
            end += 1
    if 'Copyright' not in ''.join(lines[:end]):
        end = 0
    newline = '\r\n' if text.count('\r\n') * 2 > text.count('\n') else '\n'
    return bom + ''.join(lines[:end]) + MARK + newline + ''.join(lines[end:])


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n', 1)[0])
    parser.add_argument('--upstream', required=True, type=Path, help='IntelliJ Community Git checkout')
    parser.add_argument('--rev', default=DEFAULT_REV, help=f'upstream revision (default {DEFAULT_REV})')
    parser.add_argument('--root', default=ROOT, type=Path, help='Phantom-plugins checkout (default: this one)')
    parser.add_argument('--fix', action='store_true', help='add the modification line where it is missing')
    args = parser.parse_args()

    changed = changed_derived_files(args.root, upstream_index(args.upstream, args.rev), Blobs(args.upstream))
    unmarked = [path for path, marked in changed if not marked]
    print(f'{len(changed)} derived files differ from upstream {args.rev}; {len(unmarked)} without the modification line')
    if args.fix:
        for path in unmarked:
            file = args.root / path
            file.write_bytes(add_mark(file.read_bytes().decode('utf-8')).encode('utf-8'))
        print(f'marked {len(unmarked)} files')
        return 0
    for path in unmarked:
        print(f'unmarked: {path}')
    return 1 if unmarked else 0


if __name__ == '__main__':
    sys.exit(main())
