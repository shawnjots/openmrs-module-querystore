---
name: pr-review
description: Review a GitHub pull request with empirical verification and clearly-labeled review comments, optionally posting them inline on GitHub. Use when asked to review a PR or post review findings as PR comments. Trigger phrases include "review PR", "review this pull request", "post review comments".
argument-hint: <pr-number-or-url> [--post]
version: 0.6.0
---

# PR review — verified findings, unambiguous comments

Arguments: `$ARGUMENTS` — a PR number or URL (if omitted, run `gh pr list` and ask which one), plus optional `--post` to publish the review to GitHub after composing it.

## Step 1 — Gather

- `gh pr view <n> --json title,body,author,baseRefName,headRefName,state,additions,deletions,changedFiles,mergeable,mergeStateStatus,statusCheckRollup,url,createdAt`
- `gh pr diff <n>` for the diff; fetch the branch locally: `git fetch origin <base> 'pull/<n>/head:pr-<n>'`
- Check whether the PR is behind its base: `git diff origin/<base>...pr-<n> --stat` vs `git diff origin/<base> pr-<n> --stat` (if they differ, note the drift).
- **Read the existing review conversation before reviewing** — `gh api repos/<owner>/<repo>/pulls/<n>/comments --paginate` (inline threads, with `in_reply_to_id` and author replies) and `gh pr view <n> --json reviews,comments`. A PR is often mid-conversation: a prior round may have raised your finding already, and the author may have replied/fixed it on a newer commit. For each candidate finding, check it against those threads — if it's already open, **reply in that thread** (build on it, or note the sub-point still unaddressed) instead of posting a fresh top-level comment. Duplicating a live thread is exactly the noise that makes review a bottleneck.

## Step 2 — Verify, don't just read

Findings must be grounded in evidence, scaled to what the change touches:

- Always read the full files being changed (not just the hunks), and `git grep` the whole tree for symbols/properties the PR renames or removes — silent breakage hides outside the diff (e.g. filtered resources referencing a renamed Maven property).
- Build-config changes (pom.xml, Gradle, CI): build both the base branch and the PR branch in throwaway worktrees (`git worktree add --detach /tmp/<name> <ref>`), then compare: test counts per module, produced and installed artifacts (watch for attached artifacts like `-tests.jar` that downstream consumers depend on), `mvn dependency:tree` diffs, and packaged-archive entry lists plus filtered resources (`unzip -Z1`, `unzip -p ... | diff`). Remove the worktrees afterwards (`git worktree remove --force`).
- Parent-POM or dependency upgrades: download the new parent/dependency POM and confirm that everything deleted locally is actually provided upstream (dependencies vs dependencyManagement, plugin executions, properties, distributionManagement). Check published artifacts on the repository (e.g. `curl -sL .../maven-metadata.xml`) when removal of an attached artifact would break consumers.
- Claims about runtime behavior: run the relevant tests instead of asserting from code.

### Dimensions to sweep

Cover each of these, scaled to what the PR touches — an unnamed dimension is one you'll silently skip: **correctness** (verified, not eyeballed), **test coverage** (section below), **security** (input validation, authorization checks, secrets in diffs or CI config, injection, unsafe deserialization), **performance** (N+1 queries, work added to hot paths or loops, blocking calls, unbounded growth), and **project conventions** (the change should read like the surrounding code — but don't flag what CI formatters already enforce).

### Trace outward — the bugs live outside the diff

The diff is a piece of a bigger machine; review the machine, not the patch. Follow each thread at least one level out from the changed lines:

- **Unchanged neighbors.** Re-read the unchanged code, comments, Javadoc, and docs adjacent to or depending on the change — anything stating an assumption ("the only caller", "X is the only path that…"). Ask of each: does this PR make that statement false? Diff-scoped review structurally cannot catch these, because the now-stale line never appears in the diff.
- **Consumers and published artifacts.** For everything the changed code produces — events fired, attached/published artifacts (e.g. `-tests.jar` classifiers), API responses, filtered resources — identify who consumes it, in-repo and downstream, and verify the contract still holds.
- **Optional dependencies and lifecycle.** For `provided`-scope deps, soft module references (`aware_of_module`), and registration-order-sensitive code (Spring beans, listeners, schedulers), walk through what happens when the dependency is absent or the ordering shifts at runtime.
- **The merged result, not the commit stack.** Review the final combined state of the PR — and re-check it against the current base tip if the branch is behind — rather than commit-by-commit. Individually sensible commits can compose into a contradiction.

### Test coverage is a review dimension

For each behavior change the PR introduces, name the test that exercises it — one that would fail on the base branch, passes on the PR, and asserts the **runtime effect**, not a proxy ("the generated string looks right" or "mirrors an existing pattern" verifies the shape or the sibling, not the behavior). A behavior change with no such test is a finding, even when the code looks obviously correct. If part of the path genuinely can't be tested, name the specific blocked sub-path — and confirm the adjacent runnable path is still exercised.

### Convergence

A pass that surfaced a substantive finding cannot be your last pass: one real find is evidence of unexplored ground, so sweep again until a pass turns up nothing substantive. The converse also holds — don't invent findings to fill a quota; a clean PR earns a short review.

Verify thoroughly — but the evidence belongs *inside the finding it supports*, not in a standalone recap, and a cleared concern usually needs no words at all (see Step 3).

## Step 3 — Compose the review

The review's job is to surface what needs action. Reviewer and author attention is the scarce resource — PR review is a bottleneck, and every line that doesn't change what the author does is pure cost. Lead with action.

Structure of the summary body:

1. Verdict first — **disposition + one headline reason + scope**, nothing else. Disposition is "merge", "merge after X", or "needs work"; the headline is the single biggest reason (point at the finding numbers); scope is the bar you're judging against (e.g. "as a POC none block it; as production code they do"). Don't tour the architecture or grade it ("the design is coherent", "the happy path is plausible") — that's narration-plus-praise the author can't act on. A maintainer scanning the PR should get the call and the one reason in a sentence or two.
2. Findings, each carrying its own evidence (its failure-mode sentence, or the verification that grounds it).
3. Close with a **"Needs action before merge:"** checklist — or explicitly "none".

Evidence lives *inside the finding it supports*, not in a standalone "what I verified" section — a real finding is self-justifying once the reader checks it against the code, so a separate verification recap is redundant overhead. **Checked it and it's fine → say nothing.** Do not note cleared concerns "so they aren't re-raised": if the thing is trivially checkable, a later reviewer checks it as fast as you did and your note saves no round; if it isn't, a one-line reply when/if someone actually raises it costs less than pre-empting it on every review.

The one exception that earns a terse line in the body: **cross-cutting verification that is itself the deliverable** — a result with no home in any single finding that materially changes the verdict (e.g. on a pom.xml PR, "built both branches — installed artifacts byte-identical"; the whole question was "did the output change"). That is the substantive output of the review, not a recap of diligence. A trivially-checkable detail (an FK name, a constant value) is not that.

Rules for every inline comment, no exceptions (this is the part PR authors complained about when missing):

- Every inline comment must either request an action or ask a question. The **first line is a label plus a one-line imperative or verdict**:
  - `issue (blocking):` — must be fixed before merge
  - `suggestion (non-blocking):` — recommended change the author may decline
  - `question:` — a genuine question whose answer affects the verdict. Also use this for uncertainty about an observed side effect ("this changes X — intended?"); a question invites confirmation from people with more context far better than an FYI does.
  - `nit:` — trivial, take or leave
  - `note (no action needed):` — exceptional. Allowed only when it does one of these jobs:
    1. records that a behavior change was seen and deliberately accepted, so the audit trail shows it wasn't missed if something breaks later; or
    2. warns a specific future reader at the line they will be looking at (e.g. whoever cuts the next release).
    If it does neither and isn't really a question in disguise, it's narration — don't post it anywhere.
- An `issue (blocking):` must contain a concrete failure-mode sentence: **"if merged as-is, X breaks because Y."** If you cannot write that sentence, it is not blocking — relabel or drop it. "Low risk" without naming the risk, "matches the existing pattern", "probably fine" are dodges, not verdicts: name what breaks, who notices, and how.
- Silent-failure upgrade: when the failure mode produces wrong behavior without throwing (a swapped repo id, a dropped field, a silently replaced MockMaker), treat it one level more seriously than a crash — CI cannot catch what doesn't throw, so the cost of discovery lands on users.
- When presenting alternatives, always state the recommended default. Never end on "either option is defensible" without picking one.
- Do not narrate what the diff does — the author wrote it.
- Keep each inline comment to its finding and the fix. Cross-cutting verification or context that doesn't attach to one line goes in the summary body (terse — see Step 3), never repeated inline.
- Use GitHub ```suggestion blocks when the fix is a concrete replacement of added lines (RIGHT side only — suggestions cannot attach to deleted lines).
- Scale to the requested effort: by default, fewer high-confidence findings. At high effort, widen the sweep and surface uncertain findings too — explicitly as `question:`, never as unverified `issue`s.
- Fewer, sharper comments beat exhaustive ones.

## Step 4 — Post (only with consent)

Posting publishes under the user's own GitHub account. Post only when the user passed `--post` or explicitly asked; otherwise present the full review in the conversation and offer to post.

**Pre-posting gate** — before presenting or posting, write these three as three explicit, standalone lines in your report to the user — verbatim, not paraphrased, not woven into prose narration. If any cannot be said truthfully, go back and fix the review first:

> "Every finding was verified by [building / running / tracing / upstream check], not just read off the diff."

> "Every inline comment leads with a label and either requests an action or asks a question; every `issue (blocking)` contains its failure-mode sentence."

> "Needs action before merge: [list, or 'none']."

Mechanics that are easy to get wrong:

- Submit one review: `gh api repos/<owner>/<repo>/pulls/<n>/reviews --input payload.json` with `"event": "COMMENT"` (never APPROVE or REQUEST_CHANGES unless the user explicitly chose that), a `body`, and a `comments` array.
- Build `payload.json` with `python3` + `json.dump` — raw tabs/newlines in hand-written JSON break the API call.
- Anchoring: `"side": "LEFT"` with line numbers from the **merge-base version** of the file for deleted lines; `"side": "RIGHT"` with line numbers from the **PR-head version** for added/context lines. Get exact numbers via `git show <ref>:<path> | cat -n`. Confirm the merge-base first (`git merge-base origin/<base> pr-<n>`); if it equals the base tip, base-branch line numbers are safe for LEFT.
- Multi-line comments use `start_line`/`start_side` plus `line`/`side`, and the whole span must fall inside a single diff hunk.
- Comments appear under the user's name — write them in first person, collegial tone.
- Threaded replies (Step 1 may send you here to build on an existing thread instead of posting fresh): `gh api repos/<owner>/<repo>/pulls/<n>/comments/<comment_id>/replies -f body=...`. These post as **standalone** review comments — they won't appear in any review payload's `comments` array and carry their own ids, so don't expect them in the main-review verification below.
- Verify after posting — but **trust content, not counts**: `gh api repos/<owner>/<repo>/pulls/<n>/comments --paginate --jq '.[] | select(.pull_request_review_id == <id>) | {path, line, side, body}'`. The `--paginate` is mandatory: `gh api` returns 30 comments per page, and a busy PR (a prior round + replies) easily exceeds that, so without it the query gives **false negatives**. Reads can also lag a few seconds behind the write. Diff what comes back against what you submitted by `{path, line}`; if something looks missing, re-query (allowing for lag) and inspect — **never re-post on a mismatch**, that is exactly how duplicate comments get created. If duplicates already exist, delete the extras with `gh api -X DELETE repos/<owner>/<repo>/pulls/comments/<comment_id>`. Then report the review URL.

## Anti-patterns

- **Don't invent concerns to look thorough.** Review depth should track risk; a clean PR gets a short, confident review. Diminishing returns are a real signal, not a failure.
- **Don't post unverified suspicion as an `issue`.** If you couldn't verify it, it's a `question:` — or more homework for you, not the author.
- **Don't critique an inflated version of the PR.** When disagreeing with an approach, respond to the author's actual scope; larger redesigns are follow-up `suggestion`s, not blockers on this PR.
- **Don't let severity labels substitute for analysis.** A finding is blocking because of its written failure mode, not because it pattern-matches "correctness". Small-looking code patterns (an unclosed resource, a swapped identifier) are routinely under-labeled.
- **Don't restore confidence the author already has.** A PR author shipped because they believe the change is right; a recap of what they got right is cost without benefit. The review's value is the delta — what must change, plus the rare verified-clean item a later reviewer would re-raise. Passing CI is not a reason to soften the action items: both the expensive bugs and the bottleneck live in what the tests already wave through.
