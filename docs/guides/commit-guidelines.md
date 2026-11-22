---
title: GeoDB Commit Guidelines
tags:
    - documentation
related:
    -  # "[[related-note]]"
lang: en-US # switch language
date: 2026-11-22T13:08:36
id: 819431e2-b029-478c-8289-3d93965b8e54
---

# Commit Guidelines

GeoDB uses **Conventional Commits 1.0.0**, with **small, frequent commits** that each carry a meaningful description. The history is a project artifact: it is how we bisect a failing seed, explain a design change months later, and show the evolution of the system.

## 1. Principles

1. **Small.** One logical change per commit. If you need "and" to describe it, split it.
2. **Frequent.** Commit every time you reach a coherent, green step (a TDD red-green-refactor cycle usually ends in one or two commits). Do not accumulate a day of work.
3. **Described.** The subject says _what_; the body says _why_. A commit with a non-trivial change and no body is incomplete.
4. **Always green.** Every commit on a shared branch must build and pass the fast test suite. This keeps `git bisect` usable, which matters a lot when hunting a protocol bug.
5. **Behavior and structure never mix.** A refactor (no behavior change) and a feature or fix go in separate commits.

## 2. Format

```
<type>(<scope>)<!>: <subject>

<body>

<footer(s)>
```

- Blank line between subject, body and footers.
- **Subject:** imperative mood ("add", not "added" or "adds"), lowercase after the colon, no trailing period, **72 characters or fewer**.
- **Body:** wrap at 72 columns. Explain motivation, the approach, trade-offs, and how it was verified. Reference the ADR or spec section when relevant.
- **Footers:** `Refs:`, `Closes:`, `BREAKING CHANGE:`, `Seed:`, `ADR:` (see section 5).

## 3. Types

| Type       | Use for                                                                             |
| ---------- | ----------------------------------------------------------------------------------- |
| `feat`     | New capability or behavior                                                          |
| `fix`      | Bug fix                                                                             |
| `test`     | Adding or improving tests only (new property, new nemesis fault, checker test)      |
| `refactor` | Restructuring with no behavior change                                               |
| `perf`     | Performance improvement (include before/after numbers in the body, from a real run) |
| `docs`     | Documentation, ADRs, spec, guides, comments-only changes                            |
| `build`    | Maven, dependencies, toolchain                                                      |
| `ci`       | GitHub Actions, hooks, quality gates                                                |
| `chore`    | Maintenance that fits nowhere else                                                  |
| `style`    | Formatting only                                                                     |
| `revert`   | Reverts a previous commit (body must say which commit and why)                      |

## 4. Scopes

The scope is the **module or area** touched. Use one scope; if a change genuinely spans several modules, omit the scope or split the commit.

`common`, `env`, `raft`, `storage`, `mvcc`, `range`, `txn`, `cluster`, `proto`, `server`, `client`, `sim`, `lincheck`, `bench`, `chaos`, `tla`, `adr`, `spec`, `deps`, `ci`.

## 5. Footers and project conventions

- **`Seed: <long>`**: required on any commit that fixes a bug found by simulation. The same commit adds the seed to `sim/corpus/`.
- **`ADR: ADR-0004`**: when the change implements or is constrained by a decision.
- **`Refs: #123`** / **`Closes: #123`**: issue links.
- **`BREAKING CHANGE: <description>`**: for incompatible changes to persisted formats, wire protocol, or public client API. Also add `!` after the scope (`feat(proto)!: ...`). Changes to on-disk formats (WAL, SSTable, MANIFEST, Raft log) or to replicated command semantics are **always** breaking and need cluster-version handling (see ADR-0013).

## 6. Examples

Good:

```
feat(raft): add PreVote phase to leader election

A node isolated from the cluster kept incrementing its term and, on
rejoining, deposed a healthy leader. PreVote makes a candidate first
ask whether peers would grant a vote without bumping the term.

Implements the PreVote extension described in the Raft thesis
(section 9.6). TLA+ model updated in a separate commit.

ADR: ADR-0002
```

```
fix(storage): truncate WAL at first record with invalid CRC

Recovery stopped at the first short read but accepted a torn record
whose length field was intact. Check the CRC before accepting a record
and truncate the segment at the first invalid one.

Seed: 4821903377112
```

```
refactor(raft): extract log matching check into LogView
```

```
test(sim): add asymmetric partition fault to nemesis
```

```
docs(adr): record decision to use joint consensus for membership changes
```

Bad:

```
fix stuff
update raft
WIP
feat(raft): added PreVote, fixed a WAL bug, and bumped gRPC     <- three changes, wrong tense
Fix: Election.                                                  <- vague, capitalized, period
```

## 7. Small and frequent: practical rules

- Aim for commits under roughly **200 changed lines** (excluding generated code and test fixtures). Larger is allowed only when it cannot be split without breaking the build; explain why in the body.
- With TDD, a typical rhythm is: failing test, minimal implementation, refactor. Commit the test and the implementation **together** when they land (so every commit is green), and commit the refactor separately.
- **Do not commit a failing test on its own** to a shared branch. The one exception is local work you will clean up before pushing.
- A bug fix includes its regression test (and seed, if found by simulation) in the **same** commit.
- Mechanical changes (renames, formatting, moves) get their own commit, with no logic changes mixed in.
- Spec/ADR changes are separate `docs` commits from the code that implements them, unless the code change would be meaningless without it.

## 8. Local history hygiene

- `WIP`, `fixup!` and `squash!` commits are fine **locally**. Clean them up with an interactive rebase (`git rebase -i`) before pushing or opening a PR.
- You may force-push **your own feature branch** (prefer `--force-with-lease`). Never force-push `main`.
- Never commit secrets, credentials, large binaries, or generated artifacts (`target/`, JMH output, heap dumps).
- Do not commit reformatting of files you did not otherwise change.

## 9. Branches and merging

- Branch names: `<type>/<short-kebab-description>`, e.g. `feat/raft-prevote`, `fix/wal-torn-write`, `docs/adr-joint-consensus`.
- `main` is always green and always releasable in the "all gates pass" sense.
- The default merge strategy is **rebase and merge** (linear history), so every commit on a branch must itself be a good Conventional Commit. Use squash only for a PR whose commits are not worth keeping, and then the squash message must follow this guide.
- The PR title follows the same format (see `pull-request-guidelines.md`).

## 10. Tooling (planned, to be added in Phase 0)

- `commitlint` (or equivalent) in a `commit-msg` hook and in CI, validating type, scope list, subject length and tense where checkable.
- A commit message template (`.gitmessage`) with the format above.
- Changelog generation from Conventional Commits at release time.

## 11. For AI agents

Agents follow exactly the same rules: small commits, correct type and scope, a body explaining _why_, and the `Seed:`/`ADR:` footers when applicable. Agents commit only when the user asks them to, and never rewrite published history.
