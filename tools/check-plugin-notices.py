#!/usr/bin/env python3
"""Check packaged notice files; this does not establish legal compliance."""

import argparse
import io
from pathlib import Path
import re
import sys
import zipfile


def check_archive(path):
    errors = []
    with zipfile.ZipFile(path) as archive:
        main_jars = []
        for name in archive.namelist():
            # A dependency can carry its own plugin.xml (java-coverage-rt.jar does).
            # The Gradle distribution names this project's main JAR after the ZIP.
            if Path(name).name != path.stem + '.jar':
                continue
            with zipfile.ZipFile(io.BytesIO(archive.read(name))) as jar:
                if 'META-INF/plugin.xml' not in jar.namelist():
                    continue
                main_jars.append(name)
                required = {'META-INF/LICENSE', 'META-INF/NOTICE', 'META-INF/THIRD-PARTY-NOTICES.txt'}
                notices = 'META-INF/THIRD-PARTY-NOTICES.txt'
                if notices in jar.namelist():
                    required.update(re.findall(
                        r'META-INF/licenses/[A-Za-z0-9.+_-]+\.txt',
                        jar.read(notices).decode('utf-8'),
                    ))
                for target in sorted(required):
                    if target not in jar.namelist():
                        errors.append(f'{name}: missing {target}')
                    elif not jar.read(target).strip():
                        errors.append(f'{name}: empty {target}')
        if not main_jars:
            errors.append(f'no plugin JAR named {path.stem}.jar containing META-INF/plugin.xml')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archives', type=Path, nargs='+')
    args = parser.parse_args()
    failed = False
    for path in args.archives:
        try:
            errors = check_archive(path)
        except (OSError, ValueError, UnicodeError, zipfile.BadZipFile) as error:
            errors = [str(error)]
        for error in errors:
            print(f'{path}: {error}', file=sys.stderr)
        failed |= bool(errors)
        if not errors:
            print(f'{path}: packaged notice checks passed')
    return int(failed)


if __name__ == '__main__':
    sys.exit(main())
