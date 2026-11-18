import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { changedFiles, check, syntaxCommand } from './phantom-quality.mjs';

function repository(t) {
  const cwd = mkdtempSync(join(tmpdir(), 'phantom-quality-'));
  t.after(() => rmSync(cwd, { recursive: true, force: true }));
  const git = (...args) => execFileSync('git', args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  git('init', '-q');
  git('config', 'user.name', 'Quality Test');
  git('config', 'user.email', 'quality@example.invalid');
  git('config', 'commit.gpgsign', 'false');
  writeFileSync(join(cwd, 'unchanged.mjs'), 'invalid javascript!\n');
  writeFileSync(join(cwd, 'deleted.sh'), 'echo old\n');
  git('add', '.');
  git('commit', '-qm', 'Fixture baseline');
  return { cwd, git, base: git('rev-parse', 'HEAD'), write: (file, text) => writeFileSync(join(cwd, file), text) };
}

test('checks changed and untracked scripts without linting unchanged upstream files', t => {
  const repo = repository(t);
  repo.write('script with spaces.mjs', 'export const value = 1;\n');
  repo.write('untracked.sh', 'echo ready\n');
  repo.git('add', 'script with spaces.mjs');
  repo.git('rm', 'deleted.sh');
  assert.deepEqual(changedFiles(repo.cwd, repo.base).files, ['script with spaces.mjs', 'untracked.sh']);
  assert.deepEqual(check(repo.cwd, repo.base), { files: 2, checked: 2, failures: [] });
});

test('reports shell, Python and JavaScript parse failures, then accepts corrected files', t => {
  const repo = repository(t);
  repo.write('broken.sh', 'if then\n');
  repo.write('broken.py', 'def broken(\n');
  repo.write('broken.mjs', 'export const = ;\n');
  const result = check(repo.cwd, repo.base);
  assert.equal(result.checked, 3);
  assert.equal(result.failures.length, 3);
  for (const suffix of ['sh', 'py', 'mjs']) assert.ok(result.failures.some(failure => failure.startsWith(`broken.${suffix}:`)));
  repo.write('broken.sh', 'echo fixed\n');
  repo.write('broken.py', 'print("fixed")\n');
  repo.write('broken.mjs', 'export const value = 1;\n');
  assert.deepEqual(check(repo.cwd, repo.base).failures, []);
});

test('rejects whitespace errors on changed tracked lines', t => {
  const repo = repository(t);
  repo.write('unchanged.mjs', 'export const value = 1;  \n');
  assert.match(check(repo.cwd, repo.base).failures.join('\n'), /trailing whitespace/);
});

test('rejects untracked whitespace errors before staging, then accepts corrected files', t => {
  const repo = repository(t);
  repo.write('new script.mjs', 'export const value = 1;  \n\n');
  const failures = check(repo.cwd, repo.base).failures.join('\n');
  assert.match(failures, /trailing whitespace/);
  assert.match(failures, /new blank line at EOF/);
  repo.write('new script.mjs', 'export const value = 1;\n');
  assert.deepEqual(check(repo.cwd, repo.base).failures, []);
});

test('parses changed JSON without importing its contents', t => {
  const repo = repository(t);
  repo.write('rules.json', '{ "rules": [ }\n');
  assert.match(check(repo.cwd, repo.base).failures.join('\n'), /rules.json:/);
  repo.write('rules.json', '\uFEFF{ "rules": [] }\n');
  assert.deepEqual(check(repo.cwd, repo.base).failures, []);
});

test('uses the common ancestor when a target branch has advanced', t => {
  const repo = repository(t);
  repo.write('feature.sh', 'echo feature\n');
  repo.git('add', '.');
  repo.git('commit', '-qm', 'Feature');
  const feature = repo.git('rev-parse', 'HEAD');
  repo.git('checkout', '-q', '--detach', repo.base);
  repo.write('target.sh', 'echo target\n');
  repo.git('add', '.');
  repo.git('commit', '-qm', 'Target advanced');
  const target = repo.git('rev-parse', 'HEAD');
  repo.git('checkout', '-q', '--detach', feature);
  assert.deepEqual(changedFiles(repo.cwd, target).files, ['feature.sh']);
});

test('rejects missing base refs instead of silently checking nothing', t => {
  const repo = repository(t);
  assert.throws(() => check(repo.cwd, 'missing-base'));
});

test('CLI reports successful checks, invalid arguments and unavailable history', t => {
  const repo = repository(t);
  repo.write('new.mjs', 'export const value = 1;\n');
  const runner = fileURLToPath(new URL('./phantom-quality.mjs', import.meta.url));
  const run = (...args) => spawnSync(process.execPath, [runner, ...args], { cwd: repo.cwd, encoding: 'utf8' });
  const success = run('--base', repo.base);
  assert.equal(success.status, 0, success.stderr);
  assert.match(success.stdout, /1 changed files, 1 syntax\/lint checks/);
  const invalid = run('--unknown');
  assert.equal(invalid.status, 2);
  assert.match(invalid.stderr, /Usage:/);
  const missing = run('--base', 'unavailable-history');
  assert.equal(missing.status, 2);
  assert.match(missing.stderr, /Fetch the base commit/);
});

test('skips symlinks and preserves filenames as command arguments', t => {
  const repo = repository(t);
  symlinkSync('unchanged.mjs', join(repo.cwd, 'link.mjs'));
  assert.equal(check(repo.cwd, repo.base).checked, 0);
  assert.deepEqual(syntaxCommand('file; echo unsafe.sh'), ['bash', ['-n', '--', 'file; echo unsafe.sh']]);
  assert.equal(syntaxCommand('Source.kt'), null);
  assert.deepEqual(syntaxCommand('bench.sh', '#!/bin/zsh\n'), ['zsh', ['-n', '--', 'bench.sh']]);
});
