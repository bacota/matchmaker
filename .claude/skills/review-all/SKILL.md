---
name: review-all
description: Runs the five review agents — ui-reviewer, db-performance-reviewer, backend-concurrency-reviewer, ui-concurrency-reviewer, security-reviewer — side by side on the ones whose area a change touches; each writes its report to agents/reviews/<day>/<agent>-<commit>.md, and this summarises them.
argument-hint: "[browser] [uncommitted | sweep | since <hash> | last <n> commits | files… | <reviewer names>]"
disable-model-invocation: true
---

Run the reviewers in `.claude/agents/` on one scope, at the same time. Each writes its own report; you summarise.

Scope: $ARGUMENTS

## Before starting

Run `git status --short` and `git log --oneline -3`, and note `git rev-parse HEAD`, its first seven characters, and
today's date.

Take **`browser`** out of the arguments if it is there, and remember it: it goes to `ui-reviewer` only (below). Take
out any reviewer names too: naming reviewers runs exactly those, with no filtering.

Work out what the rest of the scope means:

- **Nothing given:** each reviewer reviews from its own marker in `agents/review-status/` to `HEAD`, as its definition
  says. If all five markers already hold `HEAD`, there is nothing to review: say so, mention any uncommitted changes
  `git status` shows and that `/review-all uncommitted` reviews them, and stop.
- **`uncommitted`:** the changes in the working tree and the index against `HEAD` — `git diff HEAD` — and the new
  untracked files `git status` lists, which `git diff` does not show. Tell each reviewer both, naming the untracked
  files. If there are none, say so and stop.
- **Anything else** — `sweep`, a range, a commit, files or screens: pass it to each reviewer as given.

## Which reviewers to start

Start only the reviewers whose area the change touches. This filter applies when the scope is nothing or
`uncommitted`; `sweep`, any other scope, and naming reviewers start the reviewers asked for and skip this section.

For each reviewer, list the changed paths in its area: with no scope, `git diff --name-only <its marker>..HEAD --
<pathspecs>`; for `uncommitted`, `git diff --name-only HEAD -- <pathspecs>` plus the untracked files that match. A
marker that is missing, not in `HEAD`'s history or holds conflict markers counts as touched: start that reviewer and
let it say what it needs. A marker already at `HEAD` is not started.

| Reviewer                       | Pathspecs                                                                          |
|--------------------------------|------------------------------------------------------------------------------------|
| `db-performance-reviewer`      | `':(glob)matchmaker/src/**/persistence/**' ':(glob)matchmaker/src/**/service/**' matchmaker/resources/db/migration ':(glob)matchmaker/api/**/Router.scala' ':(glob)matchmaker/ui/**/ApiClient.scala' ':(glob)matchmaker/ui/**/Store.scala'` |
| `backend-concurrency-reviewer` | `matchmaker/src matchmaker/api/src ':(glob)engines/*/src/**' ':(exclude,glob)engines/*/src/**/Html.scala' ':(exclude,glob)engines/common/src/**/SignIn.scala' ':(exclude,glob)engines/common/src/**/PlayLive.scala' ':(exclude,glob)engines/common/src/**/Finale.scala' ':(exclude,glob)engines/common/src/**/TurnTimer.scala'` |
| `ui-reviewer`                  | `matchmaker/ui ':(glob)engines/*/src/**/Html.scala' ':(glob)engines/*/resources/**/*.js' ':(glob)engines/*/resources/**/*.css' ':(glob)engines/common/src/**/SignIn.scala' ':(glob)engines/common/src/**/PlayLive.scala' ':(glob)engines/common/src/**/Finale.scala' ':(glob)engines/common/src/**/TurnTimer.scala'` |
| `ui-concurrency-reviewer`      | `matchmaker/ui/src ':(glob)engines/*/src/**/Html.scala' ':(glob)engines/*/resources/**/*.js' ':(glob)engines/common/src/**/SignIn.scala' ':(glob)engines/common/src/**/PlayLive.scala' ':(glob)engines/common/src/**/TurnTimer.scala'` |
| `security-reviewer`            | `matchmaker/src matchmaker/api/src matchmaker/ui/src matchmaker/mailer/src ':(glob)engines/*/src/**' ':(glob)engines/*/resources/**/*.js' terraform/modules ':(glob)terraform/*.tf'` |

A reviewer with no changed paths has nothing in its area to review. For a review with no scope, write `HEAD`'s full
hash to its marker, so the next run does not diff the same range again, and say so in the summary. For `uncommitted`,
leave its marker alone. If no reviewer is left to start, say so and stop.

## Starting them

Start every reviewer that is left in one message, so they run at the same time. Each one's prompt gives:

- the scope as worked out above;
- the path to write its report to: `agents/reviews/<today>/<agent>-<short hash>.md`, using the short hash of `HEAD`
  noted at the start, with `-uncommitted` before `.md` for an `uncommitted` review, since the same commit plus
  uncommitted changes is a different review;
- for `ui-reviewer` only: whether this is a **browser** run. It is one when `browser` was given, or the user said the
  review comes just before a PR. Otherwise it reviews by reading.

Start each by its agent name. If an agent name is not available as a subagent type — the agents were added or changed
after this session started — start a general-purpose agent instead, tell it to read `.claude/agents/<name>.md` and
follow it exactly as its instructions, including its tool limits and that its only writes are its marker file and its
report, and give it the model the file's frontmatter names (`model: sonnet`), or none if it names none.

Wait for all of them to finish. Don't guess at a report that has not come back.

## Reporting back

Each reviewer answers in a few lines: its report's path, its findings one line each, and its marker. Don't read the
reports whole. For the most serious finding of each, read only that finding's part of the report and the code it
cites, and confirm it before relaying it; say which you confirmed and which you could not. Then give:

- the report paths, and any reviewer that wrote none and why;
- the reviewers skipped as outside the change, and the markers moved for them;
- the most serious findings across all of them, most serious first, one or two lines each with `path:line`;
- which markers each reviewer updated and to what, and that the markers and reports are uncommitted.

Don't fix anything and don't commit anything. Findings are for the user to decide on.
