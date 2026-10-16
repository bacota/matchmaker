---
name: review-all
description: Runs the four review agents — ui-reviewer, db-performance-reviewer, backend-concurrency-reviewer, ui-concurrency-reviewer — side by side, saves their reports in agents/reviews/<day>/ as <agent>-<commit>.md, and summarises them.
argument-hint: "[uncommitted | sweep | since <hash> | last <n> commits | files…]"
disable-model-invocation: true
---

Run all four reviewers in `.claude/agents/` on one scope, at the same time, and save what they report.

Scope: $ARGUMENTS

## Before starting

Run `git status --short` and `git log --oneline -3`, and note `git rev-parse HEAD` and its first seven characters.

Work out what the scope means:

- **Nothing given:** each reviewer reviews from its own marker in `agents/review-status/` to `HEAD`, as its definition
  says. If all four markers already hold `HEAD`, there is nothing to review: say so, mention any uncommitted changes
  `git status` shows and that `/review-all uncommitted` reviews them, and stop.
- **`uncommitted`:** the changes in the working tree and the index against `HEAD` — `git diff HEAD` — and the new
  untracked files `git status` lists, which `git diff` does not show. Tell each reviewer both, naming the untracked
  files. If there are none, say so and stop.
- **Anything else** — `sweep`, a range, a commit, files or screens: pass it to each reviewer as given.

## Starting the four

Start all four in one message, so they run at the same time. Each one's prompt gives the scope as worked out above and
says that the reviewer's report comes back to you, not into a file.

Start each by its agent name. If an agent name is not available as a subagent type — the agents were added or changed
after this session started — start a general-purpose agent instead, tell it to read `.claude/agents/<name>.md` and
follow it exactly as its instructions, including its tool limits and that its only write is its marker file, and give
it the model the file's frontmatter names (`model: sonnet`), or none if it names none.

| Agent                          | File name under `agents/reviews/<today>/`   |
|--------------------------------|---------------------------------------------|
| `ui-reviewer`                  | `ui-reviewer-<short hash>.md`               |
| `db-performance-reviewer`      | `db-performance-reviewer-<short hash>.md`   |
| `backend-concurrency-reviewer` | `backend-concurrency-reviewer-<short hash>.md` |
| `ui-concurrency-reviewer`      | `ui-concurrency-reviewer-<short hash>.md`   |

Wait for all four to finish. Don't guess at a report that has not come back.

## Saving the reports

Save each report, unedited, as `agents/reviews/<today>/<agent>-<short hash>.md`, using the short hash of `HEAD` noted
at the start. For an `uncommitted` review, add `-uncommitted` before `.md`, since the same commit plus uncommitted
changes is a different review. If the file already exists, it holds an earlier run of the same review: say so before
overwriting it.

A reviewer that answered that it has no usable review point, or nothing new to review, gets no file; say so in the
summary.

## Reporting back

For the most serious finding of each report, read the code it cites and confirm it before relaying it; say which you
confirmed and which you could not. Then give:

- the saved file names;
- the most serious findings across all four, most serious first, one or two lines each with `path:line`;
- which markers each reviewer updated and to what, and that they are uncommitted.

Don't fix anything and don't commit anything. Findings are for the user to decide on.
