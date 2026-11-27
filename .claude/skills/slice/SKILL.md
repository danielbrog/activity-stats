---
name: slice
description: The workflow for one slice of work in Activity Stats, from plan to cleanup after merge. Use when starting, continuing or finishing a feature, fix or docs slice, or when the owner says "plan it", "start it", "push it" or "merged".
---

# One slice, start to finish

Each step waits for the owner where it says so. Never skip ahead.

## 1. Plan, then wait
- Read what you need, then show a short plan: what changes, which files, what's left for later
  slices, and anything risky (schema change, deploy order, new AWS resources).
- Stop and wait for a yes. If the owner changes the plan, show the new plan unless the change
  is trivial.

## 2. Branch
- From an up-to-date `main`: `git checkout main && git pull --prune`, then
  `git checkout -b <type>/<short-name>` (`feature/`, `fix/`, `docs/`, `chore/`).

## 3. Implement
- Keep to the plan. Add or update unit tests for any behaviour change.
- Kotlin style: `ktlintFormat` is fine to run. Don't run the build, tests or lint yourself.
- Backend: quick `npm --prefix backend run build` and targeted `npx jest --runInBand <file>`
  runs are fine.
- Update `docs/database/*.md` and the READMEs when behaviour they describe changes.

## 4. Review with code-improver (code slices only)
- Skip this for slices that only change docs or config.
- Run the `code-improver` agent on the branch. Show its findings as a table: number, file,
  finding, and your pick (fix, skip, or later) with a one-line reason.
- Wait for the owner to choose (for example "Fix 1 to 5"), then apply exactly those.

## 5. Check with release-checker
- Run the `release-checker` agent before every commit. It picks its checks from what changed:
  only the secret scan for docs and config, the backend type check and tests for `backend/`,
  and all five Gradle steps for the app. It says which checks it skipped and why.
- Fix anything it reports and run it again. Report the verdict and the test counts.

## 6. Commit (after the owner says to, usually "Push it")
- `python -I scripts/scan_secrets.py` must end in `CLEAN`.
- Stage files by name, never `.idea/`, never `git add -A`.
- Write the message to a file in the scratchpad and commit with `git commit -F <file>`:
  a title that describes the change (no milestone numbers), a blank line, then
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## 7. Push and open the PR
- `git push -u origin <branch>`.
- Write the PR body to a scratchpad file, from release-checker's draft:
  - **What and why**, **Changes**, and **Checks** as a ticked list of what really passed,
    with "Tried on the Pixel 7" unticked. Never claim anything ran in CI unless it did.
  - The deploy order, when both the app and the backend changed.
  - End with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- `gh pr create --base main --title "<title>" --body-file <file>`. If `gh` isn't installed or
  signed in, print a pre-filled compare link instead
  (`https://github.com/danielbrog/activity-stats/compare/main...<branch>?expand=1&title=…&body=…`,
  URL-encoded with Python's `urllib.parse.urlencode`).

## 8. The owner deploys, tests and merges
- Backend changes: the owner runs `cdk diff` and `cdk deploy`. Remind them of the deploy
  order if it matters.
- The owner tests on the Pixel 7 and squash-merges on GitHub.

## 9. Clean up (after "merged")
- Note the last commit the owner tested: `git rev-parse HEAD` on the branch.
- `git checkout main && git pull --prune`.
- `git diff --stat <tested commit> main` must be empty. If it isn't, show what differs and stop.
- `git branch -D <branch>`, and delete the remote branch if GitHub didn't.
- Mention any later slices that came out of this one.
