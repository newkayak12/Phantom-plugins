#!/usr/bin/env python3
"""Regression tests with synthetic ZIPs and isolated download/publishing mocks."""

import importlib.util
import hashlib
import io
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest
import zipfile


TOOLS = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location('notice_check', TOOLS / 'check-plugin-notices.py')
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


class NoticeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.archive = Path(self.temp.name) / 'plugin.zip'

    def make_archive(self, omitted=None, extra=None):
        entries = {
            'META-INF/plugin.xml': '<idea-plugin/>',
            'META-INF/LICENSE': 'Apache 2.0',
            'META-INF/NOTICE': 'Attribution',
            'META-INF/THIRD-PARTY-NOTICES.txt': 'MIT (META-INF/licenses/MIT.txt)',
            'META-INF/licenses/MIT.txt': 'MIT license',
        }
        if omitted:
            entries.pop(omitted)
        entries.update(extra or {})
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as jar:
            for name, text in entries.items():
                jar.writestr(name, text)
        with zipfile.ZipFile(self.archive, 'w') as archive:
            archive.writestr('plugin/lib/plugin.jar', data.getvalue())

    def test_complete_archive(self):
        self.make_archive()
        self.assertEqual(CHECK.check_archive(self.archive), [])

    def test_required_notices(self):
        for name in ('LICENSE', 'NOTICE', 'THIRD-PARTY-NOTICES.txt'):
            with self.subTest(name=name):
                self.make_archive(omitted='META-INF/' + name)
                self.assertIn('missing META-INF/' + name, '\n'.join(CHECK.check_archive(self.archive)))

    def test_referenced_license_missing(self):
        self.make_archive(omitted='META-INF/licenses/MIT.txt')
        self.assertIn('missing META-INF/licenses/MIT.txt', '\n'.join(CHECK.check_archive(self.archive)))

    def test_empty_license(self):
        self.make_archive(extra={'META-INF/LICENSE': ' \n'})
        self.assertIn('empty META-INF/LICENSE', '\n'.join(CHECK.check_archive(self.archive)))

    def test_no_plugin_descriptor(self):
        self.make_archive(omitted='META-INF/plugin.xml')
        self.assertIn('no plugin JAR', '\n'.join(CHECK.check_archive(self.archive)))

    def test_cli_fails_on_corrupt_archive(self):
        self.archive.write_text('not a zip')
        result = subprocess.run(['python3', str(TOOLS / 'check-plugin-notices.py'), str(self.archive)], capture_output=True)
        self.assertEqual(result.returncode, 1)

    def test_cli_checks_every_archive(self):
        self.make_archive()
        missing = self.archive.with_name('missing.zip')
        result = subprocess.run(['python3', str(TOOLS / 'check-plugin-notices.py'), str(self.archive), str(missing)], capture_output=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn(b'missing.zip', result.stderr)

    def test_dependency_descriptor_does_not_replace_project_notices(self):
        self.make_archive()
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as jar:
            jar.writestr('META-INF/plugin.xml', '<idea-plugin/>')
        with zipfile.ZipFile(self.archive, 'a') as archive:
            archive.writestr('plugin/lib/dependency.jar', data.getvalue())
        self.assertEqual(CHECK.check_archive(self.archive), [])


    def test_ported_main_jar_found_by_plugin_id(self):
        # groovy-0.1.2.zip: the main JAR keeps its upstream name, groovy/lib/Groovy.jar
        self.make_archive(extra={'META-INF/plugin.xml': '<idea-plugin><id>dev.phantom.groovy</id></idea-plugin>'})
        ported = Path(self.temp.name) / 'groovy-0.1.2.zip'
        with zipfile.ZipFile(self.archive) as source, zipfile.ZipFile(ported, 'w') as archive:
            archive.writestr('groovy/lib/Groovy.jar', source.read('plugin/lib/plugin.jar'))
        self.assertEqual(CHECK.check_archive(ported, 'dev.phantom.groovy'), [])
        self.assertEqual(CHECK.plugin_id_for(ported), 'dev.phantom.groovy')
        self.assertIn('no plugin JAR', '\n'.join(CHECK.check_archive(ported, 'dev.phantom.other')))


class SourceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'tools').mkdir()
        self.script = self.root / 'tools/attach-sources.sh'
        shutil.copyfile(TOOLS / 'attach-sources.sh', self.script)
        shutil.copyfile(TOOLS / 'languagetool-release.sh', self.root / 'tools/languagetool-release.sh')
        for module, dependencies in {
            'grammar': ['org.jetbrains.intellij.deps.languagetool:languagetool-core:6.8.27',
                        'edu.washington.cs.knowitall:openregex:1.1.1', 'org.languagetool:english-pos-dict:0.6'],
            'coverage': ['org.jacoco:org.jacoco.ant:0.8.15'],
        }.items():
            (self.root / module).mkdir()
            (self.root / module / 'build.gradle.kts').write_text('\n'.join(f'implementation("{d}")' for d in dependencies))
        descriptor = self.root / 'grammar/src/main/kotlin/com/intellij/grazie/remote/LanguageToolDescriptor.kt'
        descriptor.parent.mkdir(parents=True)
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as jar:
            jar.writestr(zipfile.ZipInfo('source/Example.java'), 'class Example {}')
        checksum = hashlib.md5(data.getvalue()).hexdigest()
        descriptor.write_text('LanguageISO.EN\nLanguageISO.DE\n' + '\n'.join(
            f'private const val {iso}_CHECKSUM = "{checksum}"' for iso in ('EN', 'DE')))
        (descriptor.parent.parent / 'GraziePlugin.kt').write_text('const val version = "6.8.27"')
        (self.root / 'licenses').mkdir()
        for spdx in ('LGPL-2.1-only', 'LGPL-3.0-only', 'GPL-2.0-only', 'GPL-3.0-only', 'MPL-2.0', 'CC-BY-SA-4.0',
                     'Unicode-3.0'):
            (self.root / f'licenses/{spdx}.txt').write_text(spdx)
        (self.root / 'LICENSE').write_text('Apache 2.0')
        shutil.copyfile(TOOLS / 'languagetool-release-notes.md', self.root / 'tools/languagetool-release-notes.md')
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        curl = self.bin / 'curl'
        curl.write_text('''#!/usr/bin/env python3
import io, json, os, sys, tarfile, zipfile
from pathlib import Path
if os.environ.get('FAIL_DOWNLOAD') == 'true':
    sys.exit(22)
if os.environ.get('FAIL_SOURCE_DOWNLOAD') == 'true' and '-sources.jar' in sys.argv[-1]:
    sys.exit(22)
if '-o' not in sys.argv:
    # GitHub API and raw files of the LibreOffice dictionaries
    if '/git/trees/' in sys.argv[-1]:
        print(json.dumps({'tree': [{'path': d + '/README', 'type': 'blob'} for d in ('it_IT', 'da_DK', 'sl_SI', 'gl')]}))
    else:
        print('dictionary data')
    sys.exit(0)
path = Path(sys.argv[sys.argv.index('-o') + 1])
if os.environ.get('CORRUPT_DOWNLOAD') == 'true':
    path.write_text('not a jar')
elif path.name.endswith('.tar.gz'):
    with tarfile.open(path, 'w:gz') as archive:
        info = tarfile.TarInfo('project/README')
        info.size = 7
        archive.addfile(info, io.BytesIO(b'project'))
else:
    with zipfile.ZipFile(path, 'w') as jar:
        jar.writestr(zipfile.ZipInfo('source/Example.java'), 'class Example {}')
''')
        curl.chmod(0o755)
        gh = self.bin / 'gh'
        gh.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$GH_CALLS"\n')
        gh.chmod(0o755)
        self.calls = self.root / 'gh-calls'
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'], GH_CALLS=str(self.calls))

    def run_script(self, *args, **env):
        return subprocess.run(['bash', str(self.script), *args], env=dict(self.env, **env), capture_output=True, text=True)

    def test_prepare_all_supported_sources_without_publishing(self):
        for tag, jars, archives in [('grammar-0.2.1', 3, 1), ('coverage-0.1.2', 4, 0), ('languagetool-6.8.27', 5, 10)]:
            with self.subTest(tag=tag):
                destination = self.root / tag
                result = self.run_script(tag, '--download-dir', str(destination))
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(len(list(destination.glob('*.jar'))), jars)
                self.assertEqual(len(list(destination.glob('*.tar.gz'))), archives)
        self.assertIn('english-pos-dict-2c68994244.tar.gz', [f.name for f in (self.root / 'grammar-0.2.1').iterdir()])
        with tarfile.open(self.root / 'languagetool-6.8.27/dictionaries-libreoffice-7.0.0.1-it_IT.tar.gz') as archive:
            self.assertEqual(archive.getnames(), ['dictionaries-libreoffice-7.0.0.1/it_IT/README'])
        self.assertFalse(self.calls.exists())

    def test_download_failure_does_not_publish(self):
        result = self.run_script('grammar-0.2.1', FAIL_DOWNLOAD='true')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.calls.exists())

    def test_invalid_jar_does_not_publish(self):
        result = self.run_script('coverage-0.1.2', CORRUPT_DOWNLOAD='true')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.calls.exists())

    def test_upload_only_after_downloads(self):
        result = self.run_script('coverage-0.1.2')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('release upload coverage-0.1.2', self.calls.read_text())

    def test_dry_run_never_downloads_or_publishes(self):
        result = self.run_script('grammar-0.2.1', '--dry-run', FAIL_DOWNLOAD='true')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(self.calls.exists())

    def test_missing_version_fails_before_publication(self):
        (self.root / 'coverage/build.gradle.kts').write_text('')
        self.assertNotEqual(self.run_script('coverage-0.1.2').returncode, 0)
        self.assertFalse(self.calls.exists())

    def test_language_release_includes_sources(self):
        result = subprocess.run(['bash', str(self.root / 'tools/languagetool-release.sh')], env=self.env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        call = self.calls.read_text()
        self.assertIn('release create languagetool-6.8.27', call)
        self.assertIn('languagetool-core-6.8.27-sources.jar', call)
        self.assertIn('language-de-6.8.27-sources.jar', call)
        self.assertIn('asturian-pos-dict-c587ea3843.tar.gz', call)
        self.assertIn('LICENSE-MPL-2.0.txt', call)

    def test_language_source_failure_prevents_release(self):
        result = subprocess.run(['bash', str(self.root / 'tools/languagetool-release.sh')], env=dict(self.env, FAIL_SOURCE_DOWNLOAD='true'), capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.calls.exists())


if __name__ == '__main__':
    unittest.main()
