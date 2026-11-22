---
title: GeoDB Pull Request guidelines
tags:
    - documentation
related:
    -  # "[[related-note]]"
lang: en-US # switch language
date: 2026-11-22T13:09:31
id: ecc0652c-c0f5-4773-8cc8-d3edf2801fbb
---

# Pull Request Guidelines

A pull request (PR) is the unit of review and integration. For a database where subtle bugs live in rare interleavings, review is a correctness tool, not a formality, even on a solo project: reading your own diff in the PR view, after CI, catches things the editor hides.

## 1. The PR template

The PR template lives in **`.github/PULL_REQUEST_TEMPLATE.md`**. GitHub pre-fills it for every new PR. Fill in every section; write "N/A" with a reason rather than deleting a section. This guide explains _how to think about_ each section; the template is the checklist.

Documentation-only PRs may use a lighter template (for example `.github/PULL_REQUEST_TEMPLATE/docs.md`, selected with `?template=docs.md`), but the ADR and spec references still apply.

## 2. Principles

1. **One concern per PR.** A PR does one thing: one feature slice, one bug fix, one refactor, one ADR. Do not mix a refactor with a behavior change.
2. **Small.** Target **400 changed lines or fewer** (excluding generated code and fixtures). Larger PRs get split, stacked, or justified in the description. Review quality drops sharply with size.
3. **Within one phase.** A PR never mixes work from different roadmap phases.
4. **Self-explanatory.** The reviewer should understand _what_, _why_, and _how it was verified_ without reading the code first.
5. **Green before review.** Open as **Draft** while work is in progress; mark **Ready for review** only when CI is green.

## 3. Title

Same format as a Conventional Commit (see `commit-guidelines.md`):

```
feat(raft): add PreVote phase to leader election
fix(storage): truncate WAL at first record with invalid CRC
docs(adr): record joint consensus decision for membership changes
```

If the PR is squash-merged, the title becomes the commit message, so it must be correct.

## 4. Description: what the template asks and why

| Section                | What to write                                                                                                                                                                                        |
| ---------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Summary**            | One or two sentences: what changes and why now.                                                                                                                                                      |
| **Context / links**    | Related ADR(s), spec section(s), issue, TLA+ action. Example: `ADR-0004`, spec 6.4 item 5.                                                                                                           |
| **Phase**              | Which roadmap phase this belongs to.                                                                                                                                                                 |
| **Approach**           | The design in brief, and the main alternative you rejected. Skip for trivial changes.                                                                                                                |
| **Invariants touched** | Which correctness properties this could affect (election safety, log matching, determinism rules, ordering contract, MVCC key ordering, atomic commit point, etc.) and how you know they still hold. |
| **Tests**              | What was added or changed and at which layer (unit, property, simulation seed, lincheck, fuzz, TLA+). For a bug fix: the failing test or seed that now passes.                                       |
| **Evidence**           | Real numbers only: seeds run, simulated hours, mutation score, benchmark with methodology. Never estimate.                                                                                           |
| **Risk and rollback**  | What could go wrong, what is the blast radius, how to revert. Flag any format or protocol change.                                                                                                    |
| **Docs**               | ADR written/updated, spec updated, guides updated, `docs/bugs/` entry if a bug was found.                                                                                                            |

## 5. Checklist (mirrors the template)

Before requesting review, confirm:

- [ ] Title follows Conventional Commits; commits are small and conventional.
- [ ] CI is green: build, ArchUnit, forbidden-apis, unit and property tests, **all regression seeds**.
- [ ] New behavior is covered by tests written first (TDD) at the right layer.
- [ ] Bug fix includes a regression test; simulation-found bugs add the seed to `sim/corpus/` and carry a `Seed:` footer.
- [ ] No determinism leak: no forbidden APIs in core modules, no unordered-iteration dependence, no new I/O or time source outside `geodb-env`.
- [ ] If a persisted format, wire message, or replicated command changed: versioning/cluster-version handling is included and the change is marked `BREAKING CHANGE`.
- [ ] If a design decision was made or changed: an ADR is included or linked.
- [ ] Spec, guides and `AGENTS.md` updated where the change makes them stale.
- [ ] No CI gate, ArchUnit rule, forbidden-API entry, or invariant was weakened. If one was, the PR explains why and an ADR exists.
- [ ] No fabricated numbers in docs or `docs/EVIDENCE.md`.
- [ ] No secrets, large binaries, or generated artifacts.

## 6. Size, splitting and stacking

- Prefer **vertical slices that are individually mergeable** over horizontal layers that only work together.
- If a change needs a preparatory refactor, send the refactor first as its own PR.
- For dependent work, you may **stack** PRs (each targeting the previous branch). State the order in each description and merge bottom-up.
- Pure moves/renames: one PR with no logic changes, so the diff is trivially reviewable.

## 7. Review: what reviewers look for

General:

- Does the change do what the title says, and nothing else?
- Are tests meaningful (would they fail if the code were wrong)? Is anything sleeping, retrying, or weakening an assertion to pass?
- Is the description honest about risks and limitations?

By area:

| Area                | Focus                                                                                                                                                                                                              |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `raft`              | Ordering contract preserved; term/vote persisted before responses; commit rule only counts current-term entries; no I/O, threads, or clock reads; TLA+ action referenced; invariants still asserted in simulation. |
| `storage`           | Atomicity of `WriteBatch`; `fsync` placement; CRC and torn-write handling; recovery truncates at first invalid record; off-heap lifetimes and leaks; crash-at-every-point test updated.                            |
| `mvcc`              | Key encoding order (escape `0x00 0xFF`, terminator `0x00 0x01`, inverted timestamp); intent handling; GC consistency across replicas.                                                                              |
| `txn`               | Commit point is the single transaction record write; ambiguity handled; intent resolution idempotent; clock-offset assumption respected.                                                                           |
| `range` / `cluster` | Deterministic apply; split/merge races; replica-move safety (learner, then promote, then remove); descriptor `generation` bumps.                                                                                   |
| `proto` / `server`  | Additive schema only; reserved fields; translation stays at the boundary; no Protobuf types leaking into core.                                                                                                     |
| `sim` / `lincheck`  | Simulator correctness (bugs here fabricate or hide bugs); checker has an injected-bug test.                                                                                                                        |
| `docs` / `adr`      | Alternatives are real; negatives are stated honestly; template followed; links resolve.                                                                                                                            |

## 8. Author and reviewer etiquette

- Respond to every comment: fix it, or explain why not. Do not resolve a thread you did not address.
- Push fixes as new small commits during review (`fixup!` is fine), then rebase-clean before merge if the team prefers a tidy history.
- Review feedback is about the code and the design, not the person. Distinguish **blocking** from **nit**.
- A reviewer who cannot validate a claim (for example "this is linearizable") asks for the evidence rather than assuming it.

## 9. Merging

- Requirements: CI green, all review threads resolved, branch up to date with `main`.
- Default strategy: **rebase and merge** to keep each small conventional commit. Squash only when the branch history is not worth preserving.
- Delete the branch after merging.
- A PR that finds a bug adds an entry under `docs/bugs/` (see `documentation-guidelines.md`).
- A PR that completes a roadmap phase updates the phase status and references the acceptance evidence.

## 10. Solo workflow note

Even when you are the only contributor, keep the same process: open a PR, let CI run, read the full diff once as a reviewer, and complete the checklist. The value is the pause and the paper trail, which also becomes portfolio evidence.

## 11. For AI agents

Agents open PRs that follow the template completely, link the relevant ADR/spec sections, state which invariants they considered, report only measured evidence, and keep the PR small. An agent never marks a PR ready for review with failing CI, and never merges its own PR unless explicitly instructed.
