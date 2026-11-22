<!--
Title format (Conventional Commits): <type>(<scope>): <subject>
Example: feat(raft): add PreVote phase to leader election
Open as Draft until CI is green. See docs/guides/pull-request-guidelines.md
-->

## Summary

<!-- What changes and why now, in one or two sentences. -->

## Context

- Phase: <!-- 0-8 -->
- ADR(s): <!-- e.g. ADR-0004, or N/A -->
- Spec section(s): <!-- e.g. 6.4 item 5, or N/A -->
- Issue: <!-- Closes #123, or N/A -->

## Approach

<!-- The design in brief and the main alternative you rejected. Skip for trivial changes. -->

## Invariants touched

<!--
Which correctness properties could this affect (election safety, log matching, determinism rules,
Ready/advance ordering, MVCC key ordering, atomic commit point, durability before ack...)
and how do you know they still hold? Write "None" with a reason if truly untouched.
-->

## Tests

<!--
What was added or changed, and at which layer (unit, property, simulation seed, lincheck, fuzz, TLA+).
Bug fix: the failing test or seed that now passes.
List exactly what you ran.
-->

## Evidence

<!-- Measured results only: seeds run, simulated hours, mutation score, benchmark with methodology. "N/A" if none. Never estimate. -->

## Risk and rollback

<!-- What could go wrong, blast radius, how to revert. Flag any persisted-format, wire-protocol, or replicated-command change. -->

## Docs

<!-- ADR written or updated, spec/guides/AGENTS.md/GLOSSARY updated, docs/bugs entry if a bug was found. "N/A" with reason. -->

## Checklist

- [ ] Title and commits follow Conventional Commits; commits are small
- [ ] CI is green: build, ArchUnit, forbidden-apis, tests, all regression seeds
- [ ] Tests were written first (TDD) at the right layer
- [ ] Bug fix includes a regression test; simulation-found bugs add the seed to `sim/corpus/` and carry a `Seed:` footer
- [ ] No determinism leak in core modules (no forbidden APIs, no unordered iteration, no I/O or time outside `geodb-env`)
- [ ] Format, protocol, or replicated-command changes are versioned and marked `BREAKING CHANGE`
- [ ] An ADR is included or linked if a design decision was made or changed
- [ ] Spec, guides, and glossary updated where this change makes them stale
- [ ] No CI gate, ArchUnit rule, forbidden-API entry, checker, or invariant was weakened
- [ ] No fabricated numbers in docs or `docs/EVIDENCE.md`
- [ ] No secrets, large binaries, or generated artifacts
