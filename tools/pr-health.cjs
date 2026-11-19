// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
'use strict';

const SIZES = [['XS', 0], ['S', 50], ['M', 150], ['L', 500], ['XL', 1000], ['XXL', 3000]];
const CONFLICT = 'conflict';
const LABELS = [
  ['size/XS', 'c2e0c6', '0–49 changed lines'],
  ['size/S', '7bd148', '50–149 changed lines'],
  ['size/M', 'fbca04', '150–499 changed lines'],
  ['size/L', 'f9d0c4', '500–999 changed lines'],
  ['size/XL', 'f29513', '1000–2999 changed lines'],
  ['size/XXL', 'b60205', '3000+ changed lines'],
  [CONFLICT, 'd73a4a', 'Pull request has merge conflicts'],
];
function sizeLabel(lines) {
  return 'size/' + SIZES.filter(([, minimum]) => lines >= minimum).at(-1)[0];
}

async function run({ github, context, core, sleep = ms => new Promise(resolve => setTimeout(resolve, ms)) }) {
  const { owner, repo } = context.repo;
  const repository = (await github.rest.repos.get({ owner, repo })).data;
  if (context.eventName === 'push' && context.ref !== `refs/heads/${repository.default_branch}`) return;
  const requested = context.payload.pull_request?.number ?? context.payload.inputs?.pull_number;
  let numbers;
  if (requested !== undefined && requested !== '') {
    const number = Number(requested);
    if (!Number.isSafeInteger(number) || number < 1) throw new Error('A positive pull request number is required.');
    numbers = [number];
  }
  else {
    numbers = (await github.paginate(github.rest.pulls.list, { owner, repo, state: 'open', per_page: 100 }))
      .map(pr => pr.number);
  }
  if (!numbers.length) return;
  const repositoryLabels = await github.paginate(github.rest.issues.listLabelsForRepo, {
    owner, repo, per_page: 100,
  });
  const known = new Set(repositoryLabels.map(label => label.name.toLowerCase()));
  for (const [name, color, description] of LABELS) {
    if (known.has(name.toLowerCase())) continue;
    try {
      await github.rest.issues.createLabel({ owner, repo, name, color, description });
    }
    catch (error) {
      // Another health or label-sync run may have created the label meanwhile.
      if (error.status !== 422 || !error.response?.data?.errors?.some(detail =>
        detail.resource === 'Label' && detail.code === 'already_exists')) throw error;
    }
  }
  for (const pull_number of numbers) {
    let pr;
    for (let attempt = 0; attempt < 3; attempt++) {
      pr = (await github.rest.pulls.get({ owner, repo, pull_number })).data;
      if (pr.state !== 'open' || pr.mergeable !== null) break;
      if (attempt < 2) await sleep(2000);
    }
    if (pr.state !== 'open') continue;
    const labels = await github.paginate(github.rest.issues.listLabelsOnIssue, {
      owner, repo, issue_number: pull_number, per_page: 100,
    });
    const existing = new Set(labels.map(label => label.name));
    const desiredSize = sizeLabel(pr.additions + pr.deletions);
    const add = existing.has(desiredSize) ? [] : [desiredSize];
    if (pr.mergeable === false && !existing.has(CONFLICT)) add.push(CONFLICT);
    if (add.length) await github.rest.issues.addLabels({ owner, repo, issue_number: pull_number, labels: add });
    const obsolete = SIZES.map(([size]) => 'size/' + size).filter(label => label !== desiredSize && existing.has(label));
    if (pr.mergeable === true && existing.has(CONFLICT)) obsolete.push(CONFLICT);
    for (const name of obsolete) {
      try {
        await github.rest.issues.removeLabel({ owner, repo, issue_number: pull_number, name });
      }
      catch (error) {
        if (error.status !== 404) throw error;
      }
    }
    core.info(`PR #${pull_number}: ${desiredSize}; mergeability ${pr.mergeable === null ? 'unknown (conflict label preserved)' : pr.mergeable ? 'clean' : 'conflicting'}.`);
  }
}
module.exports = { run, sizeLabel };
