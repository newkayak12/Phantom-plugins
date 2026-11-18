#!/usr/bin/env node
// Lightweight checks for the Phantom delta; IDE compilation and tests remain separate.
import { spawnSync } from 'node:child_process';
import { lstatSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const DEFAULT_BASE = 'origin/main';
// The supported macOS/Linux environments provide Git here; do not resolve it through PATH.
const GIT = '/usr/bin/git';

function git(cwd, args) {
  const result = spawnSync(GIT, args, { cwd, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
  if (result.error || result.status !== 0) {
    throw new Error(result.error?.message ?? result.stderr.trim());
  }
  return result.stdout;
}

export function changedFiles(cwd, base) {
  // Resolve refs before using them in diff, and refuse missing/shallow bases.
  const commit = git(cwd, ['rev-parse', '--verify', `${base}^{commit}`]).trim();
  const ancestor = git(cwd, ['merge-base', commit, 'HEAD']).trim();
  const tracked = git(cwd, ['diff', '--name-only', '-z', '--diff-filter=ACMR', ancestor, '--']);
  const untracked = git(cwd, ['ls-files', '--others', '--exclude-standard', '-z']).split('\0').filter(Boolean);
  const files = [...new Set([...tracked.split('\0').filter(Boolean), ...untracked])].sort((a, b) => a.localeCompare(b, 'en'));
  return { ancestor, files, untracked };
}

function whitespaceFailures(cwd, ancestor, untracked) {
  const commands = [{ args: ['diff', '--check', ancestor, '--'], allowedStatus: 0 }];
  for (const file of untracked) {
    if (!lstatSync(resolve(cwd, file)).isFile()) continue;
    // --no-index returns 1 for a clean new file and 3 for whitespace errors.
    commands.push({ args: ['diff', '--no-index', '--check', '--', '/dev/null', resolve(cwd, file)], allowedStatus: 1 });
  }
  const failures = [];
  for (const { args, allowedStatus } of commands) {
    const result = spawnSync(GIT, ['-c', 'core.whitespace=blank-at-eol,blank-at-eof', ...args], { cwd, encoding: 'utf8' });
    if (result.error || result.status > allowedStatus) failures.push(result.error?.message ?? result.stdout + result.stderr);
  }
  return failures;
}

export function syntaxCommand(file, source = '') {
  if (file.endsWith('.sh')) return [/^#![^\n]*\bzsh\b/.test(source) ? 'zsh' : 'bash', ['-n', '--', file]];
  if (/\.(mjs|cjs)$/.test(file)) return [process.execPath, ['--check', '--', file]];
  if (file.endsWith('.json')) {
    return [process.execPath, ['--input-type=module', '-e', String.raw`import { readFileSync } from "node:fs"; JSON.parse(readFileSync(process.argv[1], "utf8").replace(/^\uFEFF/, ""));`, '--', file]];
  }
  if (file.endsWith('.py')) {
    // Parse without importing, executing, or leaving __pycache__ in the checkout.
    return ['python3', ['-c', 'import ast, pathlib, sys; p = pathlib.Path(sys.argv[1]); ast.parse(p.read_bytes(), filename=str(p))', file]];
  }
  return null;
}

function fileCommands(cwd, file, lint) {
  const command = syntaxCommand(file, file.endsWith('.sh') ? readFileSync(resolve(cwd, file), 'utf8') : '');
  const commands = command ? [command] : [];
  if (lint && command?.[0] === 'bash') commands.push(['shellcheck', ['--severity=error', '--', file]]);
  if (lint && /^\.github\/workflows\/[^/]+\.ya?ml$/.test(file)) commands.push(['actionlint', ['-shellcheck=', file]]);
  return commands;
}

export function check(cwd, base = DEFAULT_BASE, lint = false) {
  const { ancestor, files, untracked } = changedFiles(cwd, base);
  const failures = whitespaceFailures(cwd, ancestor, untracked);
  let checked = 0;
  for (const file of files) {
    // Never follow changed symlinks outside the checkout.
    if (!lstatSync(resolve(cwd, file)).isFile()) continue;
    for (const [executable, args] of fileCommands(cwd, file, lint)) {
      checked++;
      const result = spawnSync(executable, args, { cwd, encoding: 'utf8' });
      if (result.error || result.status !== 0) {
        failures.push(`${file}: ${result.error?.message ?? result.stdout + result.stderr}`);
      }
    }
  }
  return { files: files.length, checked, failures };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const lint = args[0] === '--lint';
  if (lint) args.shift();
  if (args.length && (args.length !== 2 || args[0] !== '--base' || args[1].startsWith('-'))) {
    console.error('Usage: node tools/phantom-quality.mjs [--lint] [--base <git-ref>]');
    process.exitCode = 2;
  }
  else {
    try {
      const cwd = git(process.cwd(), ['rev-parse', '--show-toplevel']).trim();
      const result = check(cwd, args[1], lint);
      console.log(`Phantom quality: ${result.files} changed files, ${result.checked} syntax/lint checks.`);
      for (const failure of result.failures) console.error(failure);
      process.exitCode = result.failures.length ? 1 : 0;
    }
    catch (error) {
      console.error(`Phantom quality: ${error.message}\nFetch the base commit and enough history to compute its merge base.`);
      process.exitCode = 2;
    }
  }
}
