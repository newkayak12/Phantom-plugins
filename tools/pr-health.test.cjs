// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { run, sizeLabel } = require('./pr-health.cjs');

function fixture({ mergeable = true, labels = [], sequence, numbers = [1] } = {}) {
  const calls = { add: [], remove: [], info: [], sleep: [], list: [], create: [] };
  let reads = 0;
  const github = {
    rest: {
      repos: { get: async () => ({ data: { default_branch: 'main' } }) },
      pulls: {
        list: 'pulls',
        get: async ({ pull_number }) => ({ data: {
          number: pull_number, state: 'open', draft: true, additions: 100, deletions: 50,
          mergeable: sequence ? sequence[Math.min(reads++, sequence.length - 1)] : mergeable,
        } }),
      },
      issues: {
        listLabelsForRepo: 'repositoryLabels',
        createLabel: async args => calls.create.push(args),
        listLabelsOnIssue: 'labels',
        addLabels: async args => calls.add.push(args),
        removeLabel: async args => calls.remove.push(args),
      },
    },
    paginate: async (method, args) => {
      calls.list.push({ method, args });
      if (method === 'repositoryLabels') return ['size/XS', 'size/S', 'size/M', 'size/L', 'size/XL', 'size/XXL', 'conflict'].map(name => ({ name }));
      return method === 'pulls' ? numbers.map(number => ({ number })) : labels.map(name => ({ name }));
    },
  };
  const context = { repo: { owner: 'someone', repo: 'any-repo' }, eventName: 'pull_request_target',
    payload: { pull_request: { number: 1 } } };
  return { calls, github, context, core: { info: message => calls.info.push(message) },
    sleep: async ms => calls.sleep.push(ms) };
}

test('size boundaries use total changed lines', () => {
  for (const [lines, size] of [[0, 'XS'], [49, 'XS'], [50, 'S'], [149, 'S'], [150, 'M'],
    [499, 'M'], [500, 'L'], [999, 'L'], [1000, 'XL'], [2999, 'XL'], [3000, 'XXL']]) {
    assert.equal(sizeLabel(lines), 'size/' + size);
  }
});
test('replace only owned size labels, preserve all unrelated labels', async () => {
  const f = fixture({ labels: ['bug', 'size/S', 'size/custom'] });
  await run(f);
  assert.deepEqual(f.calls.add[0].labels, ['size/M']);
  assert.deepEqual(f.calls.remove.map(call => call.name), ['size/S']);
});
test('add conflict on conflicting PR including drafts', async () => {
  const f = fixture({ mergeable: false, labels: ['size/M'] });
  await run(f);
  assert.deepEqual(f.calls.add[0].labels, ['conflict']);
});
test('remove conflict after clean mergeability and remain idempotent', async () => {
  const f = fixture({ labels: ['size/M', 'conflict'] });
  await run(f);
  assert.equal(f.calls.add.length, 0);
  assert.deepEqual(f.calls.remove.map(call => call.name), ['conflict']);
});
test('unknown mergeability retries boundedly and preserves conflict', async () => {
  const f = fixture({ mergeable: null, labels: ['conflict'] });
  await run(f);
  assert.deepEqual(f.calls.sleep, [2000, 2000]);
  assert.equal(f.calls.remove.length, 0);
  assert.match(f.calls.info[0], /unknown/);
});
test('retry resolves unknown status', async () => {
  const f = fixture({ sequence: [null, false] });
  await run(f);
  assert.deepEqual(f.calls.sleep, [2000]);
  assert.deepEqual(f.calls.add[0].labels, ['size/M', 'conflict']);
});
test('push paginates all open PRs without filtering base branches', async () => {
  const f = fixture({ numbers: [1, 2] });
  f.context = { ...f.context, eventName: 'push', ref: 'refs/heads/main', payload: {} };
  await run(f);
  assert.equal(f.calls.add.length, 2);
  assert.deepEqual(f.calls.list[0].args, { owner: 'someone', repo: 'any-repo', state: 'open', per_page: 100 });
});
test('empty open PR list is a no-op', async () => {
  const f = fixture({ numbers: [] });
  f.context = { ...f.context, eventName: 'workflow_dispatch', payload: { inputs: { pull_number: '' } } };
  await run(f);
  assert.equal(f.calls.add.length, 0);
});
test('manual selection validates the PR number', async () => {
  const f = fixture();
  f.context.payload = { inputs: { pull_number: 'invalid' } };
  await assert.rejects(run(f), /positive pull request/);
});
test('non-default branch pushes do nothing', async () => {
  const f = fixture();
  f.context = { ...f.context, eventName: 'push', ref: 'refs/heads/other', payload: {} };
  await run(f);
  assert.equal(f.calls.list.length, 0);
});

test('workflow runs only pinned trusted default-branch automation', () => {
  const { readFileSync } = require('node:fs');
  const workflow = readFileSync(require('node:path').join(__dirname, '../.github/workflows/pr-health.yml'), 'utf8');
  assert.match(workflow, /branch: repository.default_branch/);
  assert.match(workflow, /ref: \$\{\{ steps.trusted.outputs.sha \}\}/);
  assert.match(workflow, /sparse-checkout: tools\/pr-health.cjs/);
  assert.match(workflow, /persist-credentials: false/);
  assert.ok(workflow.includes("group: pr-health-labels-${{ github.event.pull_request.number || inputs.pull_number || 'all' }}"));
  assert.doesNotMatch(workflow, /pull_request\.head|head\.sha|npm install|npm ci/);
});

test('missing repository labels are created once without changing existing ones', async () => {
  const f = fixture({ numbers: [1, 2] });
  const paginate = f.github.paginate;
  f.github.paginate = async (method, args) => method === 'repositoryLabels'
    ? [{ name: 'size/XS' }] : paginate(method, args);
  f.context.payload = {};
  await run(f);
  assert.equal(f.calls.create.length, 6);
  assert.equal(f.calls.create.some(label => label.name === 'size/XS'), false);
  assert.deepEqual(f.calls.create.find(label => label.name === 'conflict'), {
    owner: 'someone', repo: 'any-repo', name: 'conflict', color: 'd73a4a',
    description: 'Pull request has merge conflicts',
  });
});
test('only already-existing label creation races are ignored', async () => {
  const f = fixture();
  const paginate = f.github.paginate;
  f.github.paginate = async (method, args) => method === 'repositoryLabels' ? [] : paginate(method, args);
  f.github.rest.issues.createLabel = async () => {
    throw { status: 422, response: { data: { errors: [{ resource: 'Label', code: 'already_exists' }] } } };
  };
  await run(f);
  f.github.rest.issues.createLabel = async () => { throw new Error('other failure'); };
  await assert.rejects(run(f), /other failure/);
  f.github.rest.issues.createLabel = async () => { throw { status: 422 }; };
  await assert.rejects(run(f), error => error.status === 422);
});
