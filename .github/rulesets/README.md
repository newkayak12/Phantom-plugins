# Branch rules

`main.json` uses the supplied `default-ruleset.json` as the common baseline: require a PR with zero approving reviews, resolve review threads, block deletion and force pushes, and allow repository administrators (role ID 5) to bypass. The Kotlin `check` status from main is retained as a required check in the prepared JSON. Repository merge settings may further restrict the allowed merge methods.

This JSON does not apply itself. On 2026-11-18, the private repository rulesets API returned HTTP 403 requiring GitHub Pro or public visibility. Visibility was not changed. The prepared rules remain unapplied.

When the feature is available, list existing rules with `gh api repos/newkayak12/Phantom-plugins/rulesets`. Update an existing `protect-main` rule with `gh api --method PUT repos/newkayak12/Phantom-plugins/rulesets/ID --input .github/rulesets/main.json`, or create it with `gh api --method POST repos/newkayak12/Phantom-plugins/rulesets --input .github/rulesets/main.json`. Read it back and check `repos/newkayak12/Phantom-plugins/rules/branches/main` before claiming enforcement.

PR author assignment and collaborator review requests are configured separately in [pr-routing.yml](../workflows/pr-routing.yml). They become active on the default branch after merge.
