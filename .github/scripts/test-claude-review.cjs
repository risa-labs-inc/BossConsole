// Exercise the actual github-script blocks without GitHub credentials or API calls.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {test} = require('node:test');
const yaml = fs.readFileSync(path.join(__dirname, '../workflows/claude-code-review.yml'), 'utf8');
const blocks = [...yaml.matchAll(/          script: \|\n((?:            .*\n|\n)+)/g)]
  .map(match => match[1].split('\n').map(line => line.slice(12)).join('\n'));
assert.equal(blocks.length, 2);
const AsyncFunction = Object.getPrototypeOf(async function() {}).constructor;
async function run({jobs, prs, latest, response = {result: 'review result', is_error: false}, publish = false} = {}) {
  const pr = {number: 316, state: 'open', draft: false, head: {sha: 'approved', repo: {full_name: 'author/repo'}},
    base: {ref: 'dev', repo: {full_name: 'org/repo'}}, user: {login: 'author'}};
  const outputs = {}, comments = [], failures = [], files = {};
  const github = {rest: {actions: {listJobsForWorkflowRun: 'jobs'}, repos: {listPullRequestsAssociatedWithCommit: 'prs'},
    pulls: {get: async args => ({data: args.mediaType ? '+ untrusted diff' : latest ?? pr})},
    issues: {createComment: async args => comments.push(args)}},
    paginate: async endpoint => endpoint === 'jobs'
      ? jobs ?? [{started_at: 'now', conclusion: 'success'}] : prs ?? [pr]};
  const context = {repo: {owner: 'org', repo: 'repo'}, payload: {workflow_run: {
    id: 10, head_sha: 'approved', head_repository: {full_name: 'author/repo'}}}};
  const core = {notice() {}, setFailed: message => failures.push(message), setOutput: (key, value) => outputs[key] = value};
  const fakeRequire = () => ({writeFileSync: (name, data) => files[name] = data, readFileSync: () => JSON.stringify(response)});
  const process = {env: {RUNNER_TEMP: '/scratch', PR_NUMBER: '316', REVIEWED_SHA: 'approved'}};
  await new AsyncFunction('github', 'context', 'core', 'require', 'process', 'Buffer', blocks[publish ? 1 : 0])
    (github, context, core, fakeRequire, process, Buffer);
  return {outputs, comments, failures, files, pr};
}
test('approved fork with omitted event PR list resolves through commit API', async () => {
  const result = await run();
  assert.equal(result.outputs.number, 316);
  assert.match(result.files['/scratch/claude-review-prompt.txt'], /untrusted diff/);
});
test('failed executed build remains reviewable', async () => {
  assert.equal((await run({jobs: [{started_at: 'now', conclusion: 'failure'}]})).outputs.number, 316);
});
test('unexecuted and skipped jobs cannot authorize review', async () => {
  for (const jobs of [[], [{conclusion: 'success'}], [{started_at: 'now', conclusion: 'skipped'}]]) {
    assert.deepEqual((await run({jobs})).outputs, {});
  }
});
test('stale, closed, draft, bot and foreign-repository PRs are rejected', async () => {
  const {pr} = await run();
  for (const changes of [{state: 'closed'}, {draft: true}, {user: {login: 'bot[bot]'}},
    {head: {...pr.head, sha: 'new'}}, {head: {...pr.head, repo: {full_name: 'other/repo'}}},
    {base: {...pr.base, repo: {full_name: 'other/repo'}}}]) {
    assert.deepEqual((await run({prs: [{...pr, ...changes}]})).outputs, {});
  }
  assert.deepEqual((await run({prs: [pr, pr]})).outputs, {});
});
test('head changing while diff is fetched aborts preparation', async () => {
  assert.deepEqual((await run({latest: {state: 'open', head: {sha: 'new'}}})).outputs, {});
});
test('only reviewed current head receives a comment', async () => {
  assert.equal((await run({publish: true})).comments.length, 1);
  assert.equal((await run({publish: true, latest: {state: 'open', head: {sha: 'new'}}})).comments.length, 0);
  assert.equal((await run({publish: true, latest: {state: 'closed', head: {sha: 'approved'}}})).comments.length, 0);
});

test('CLI budget/error and empty results are not posted as reviews', async () => {
  for (const response of [{is_error: true, result: 'budget exceeded'}, {result: ''}]) {
    await assert.rejects(run({publish: true, response}), /did not complete/);
  }
});
