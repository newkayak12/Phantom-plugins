#!/usr/bin/env python3
"""Check packaged notice files; this does not establish legal compliance."""

import argparse
import io
import json
from pathlib import Path
import re
import sys
import zipfile


MARKETPLACE = Path(__file__).resolve().parent.parent / '.phantom' / 'marketplace.json'


def plugin_id_for(path):
    """The marketplace id of the plugin a <folder>-<version>.zip was built from, or None."""
    folder = path.stem.rsplit('-', 1)[0]
    try:
        plugins = json.loads(MARKETPLACE.read_text())['plugins']
    except (OSError, ValueError, KeyError):
        return None
    return next((p['id'] for p in plugins if p.get('source') == folder), None)


def descriptor_id(jar):
    match = re.search(r'<id>\s*([^<\s]+)\s*</id>', jar.read('META-INF/plugin.xml').decode('utf-8', 'replace'))
    return match.group(1) if match else None


def check_archive(path, plugin_id=None):
    errors = []
    with zipfile.ZipFile(path) as archive:
        # A dependency can carry its own plugin.xml (java-coverage-rt.jar does). The Gradle distribution names this
        # project's main JAR after the ZIP; a ported plugin keeps its upstream JAR name (groovy's lib/Groovy.jar), so
        # without one named after the ZIP the main JAR is the one whose descriptor has the plugin's id.
        descriptors = {}
        for name in archive.namelist():
            if not name.endswith('.jar'):
                continue
            jar = zipfile.ZipFile(io.BytesIO(archive.read(name)))
            if 'META-INF/plugin.xml' in jar.namelist():
                descriptors[name] = jar
        main_jars = [n for n in descriptors if Path(n).name == path.stem + '.jar']
        if not main_jars and plugin_id:
            main_jars = [n for n, jar in descriptors.items() if descriptor_id(jar) == plugin_id]
        for name in main_jars:
            jar = descriptors[name]
            # A plugin Phantom builds in the IDE's own layout (java-<version>.zip from phantom-libs) carries LICENSE.txt
            # and NOTICE.txt beside lib/, as IntelliJ lays out a plugin, instead of in its main JAR's META-INF.
            beside = [name.split('/', 1)[0] + '/' + notice for notice in ('LICENSE.txt', 'NOTICE.txt')]
            if 'META-INF/LICENSE' not in jar.namelist() and all(n in archive.namelist() for n in beside):
                errors.extend(f'empty {n}' for n in beside if not archive.read(n).strip())
                continue
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
            errors.append(f'no plugin JAR named {path.stem}.jar or with the id {plugin_id} containing META-INF/plugin.xml')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archives', type=Path, nargs='+')
    args = parser.parse_args()
    failed = False
    for path in args.archives:
        try:
            errors = check_archive(path, plugin_id_for(path))
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
