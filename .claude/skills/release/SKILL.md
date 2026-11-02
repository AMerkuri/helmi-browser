---
name: release
description: Cut a new Helmi release from main. Picks the next version from the last v* tag and the PRs merged since, runs the signed release workflow, and checks the published APK and its signing certificate. Use when asked to release, ship or publish a new version of Helmi.
---

# Release Helmi

A release is a signed APK on the [Releases page](https://github.com/AMerkuri/helmi-browser/releases), built by [`release.yml`](../../../.github/workflows/release.yml). [docs/releases.md](../../../docs/releases.md) has the whole flow and the one-time key setup; this is the checklist for cutting one.

Nothing in the repo needs a version bump. The workflow sets `versionName` from the version it is given and `versionCode` from its own run number (`android/app/build.gradle.kts`), and writes the release notes itself from the PRs merged since the previous release. So a release is one workflow run, with no PR.

A release reaches people who installed Helmi (Obtainium picks it up), and a version number can't be used twice. Being asked to release is the go-ahead for the whole run. Don't start one on your own initiative.

## 1. Check main is ready

- The newest run of the `android` workflow on `main` passed (`actions_list` `list_workflow_runs`, `resource_id: android.yml`, branch `main`). The release build runs no tests of its own; it relies on this run. The workflow skips docs-only pushes, so its newest run can be on an older commit than main's head, which is fine. A red emulator test is often a random timeout: re-run the failed jobs once (`actions_run_trigger` `rerun_failed_jobs`) before calling main broken. If it stays red, stop and tell the person.
- No `release` run is queued or in progress (`list_workflow_runs`, `resource_id: release.yml`).

## 2. Find the last release and what is new

- The last release is the newest **`v*`** release (`list_releases`). Skip the model releases (`npu-qwen3-…`, `qwen3-0.6b-helmi-…`): they share the Releases page, so `get_latest_release` can return one of them.
- List what is merged since: `git fetch origin main --tags && git log --merges --format='%s%n  %b' vLAST..origin/main`, or `list_pull_requests` (state closed, base main) for those merged after the last release's date.
- If nothing merged changes the app (only docs, CI or engine tests), say so and don't release.

## 3. Pick the version

Semver, `MAJOR.MINOR.PATCH`, and it must be higher than the last one:

- The person named a version: use it, after checking it is higher and has no tag yet.
- Any new feature or visible change since the last release: next minor (`0.2.0` → `0.3.0`).
- Only fixes: next patch (`0.3.0` → `0.3.1`).
- A test build for a few people: a pre-release suffix (`0.3.0-beta.1`). The workflow marks it a pre-release.
- `1.0.0` or any major bump is the person's call. Ask with a decision card and don't pick it yourself.

## 4. Run the release workflow

From a cloud session, run it through the GitHub MCP tools rather than pushing a tag, since the session's git access is meant for its own branch:

```
actions_run_trigger
  method: run_workflow
  owner: AMerkuri, repo: helmi-browser
  workflow_id: release.yml
  ref: main
  inputs: { "version": "0.3.0" }
```

With the GitHub CLI on a computer: `gh workflow run release.yml --repo AMerkuri/helmi-browser --ref main -f version=0.3.0`. Pushing a tag (`git tag v0.3.0 origin/main && git push origin v0.3.0`) does the same.

Then find the new run (`list_workflow_runs`, `resource_id: release.yml`, newest first) and keep its id and link. It takes 5 to 8 minutes. Don't poll in a loop: in a project thread, schedule a reminder for about 7 minutes and end the turn.

## 5. Check the release

When the run has finished:

- **Run failed**: read the failed job's log (`get_job_logs`). A missing secret fails in the first step with a message naming it; the secrets are Artur's to set (docs/releases.md). A run started from the Actions tab or `run_workflow` creates the tag only in its last step, so after a failure the same version can be run again once the cause is fixed. A run from a pushed tag is re-run with `rerun_failed_jobs`.
- **Run passed**: `get_release_by_tag` with `vX.Y.Z` and check:
  - it is named `Helmi X.Y.Z`, not a draft, and a pre-release only when the version has a suffix;
  - it has the two assets `helmi-X.Y.Z.apk` and `helmi-X.Y.Z.apk.sha256`;
  - the notes' "Signing certificate SHA-256" is exactly `e83d6b1f5c8f7d115b2173ebcd1db8b48b9c7961b05baed97ed0f69f88373fd0`, the `CN=Helmi` key every release is signed with. Any other value means installed copies can't update to this release: tell the person straight away and leave the release as it is for them to decide. Never change the secrets or the key yourself.

## 6. Report

Reply with the release link, the version, the build number (`versionCode`, the release run's number) and a few lines on what is in it, grouped the way a user would notice (new features, then fixes) rather than the PR list, which the release notes already have. Save the new latest release (version, date, run) to project memory.
