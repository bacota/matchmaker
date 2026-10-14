# Splitting the UI's Main.scala

`matchmaker/ui/src/com/vivi/matchmaker/ui/Main.scala` is 5,099 lines. Only the first 47 are `object Main`. Nearly all
the rest is one `object Views` holding every screen, about 4,950 lines, and the last 100 are `object Format`. The other
UI files show the shape it should take: `Tournaments`, `Account` and `Notifications` are each an object for one area,
and they borrow `Views`' shared pieces with `import Views.{busyButton, field, freshTipId, listing, withTip}`.

The plan applies that pattern to the rest of the file. This is a pure move with no change in behaviour. No method is
renamed, re-signatured or rewritten, so the diff contains only moved blocks, access modifiers and the references that
now need an object name.

## Target layout

Line numbers are those in today's `Main.scala`.

| File / object | Takes | About |
|---|---|---|
| `Main.scala` — `Main` | the entry point only (1–47) | 50 |
| `Views.scala` — `Views` | `app` and the page chrome: header, error banner, signed-in and signed-out bodies, registration, `home` with its page dispatch, `focusNewScreen` and the menu (49–471). Also the shared kit: `busyButton`, `field`, `listing`, `refreshableSection`, the `refresh`/`finished` dimming, the tips (`tipField`, `freshTipId`, `withTip`, `tipWord`, `tipped`), `friendlyMeaning`/`friendlyLabel`/`friendlyHeading`, `countdown`, `openSignedIn`, `scopeAttr` and `currentPlayer` | 600 |
| `MatchSections.scala` | the "Matches" block (473–1090): the `refreshing*` flags, `reloadChallengeSections`/`reloadAfterStart`/`reloadAfterMatchRefresh`, invitations, ready to start, due, my matches, waiting, recently completed, `completedWindow` | 620 |
| `MatchTables.scala` | how a match is shown (1664–2201): `matchTable`, `matchRow`, `eloCell`, `outcomeMark`, `playingStatus`, `playControls`, `matchNotifications`, `resultSummary`, `resultTable`, the review and watch controls, plus `clockTable` and `timeLimitDetail` | 590 |
| `Players.scala` | the "Players" block (1092–1636): find players, the player page, the character page, the links, `inviteControl`, `addToExisting`, `publicGameRow`, `offeredChallenges`, `theirCharacters`, `characterInviteButtons` | 545 |
| `Games.scala` | the game page and the dialogs around it (2213–2490): `gamePage`, the edit panel and dialog, `gameHistory`, `newGamePage`, `disabledGames` | 280 |
| `GameForm.scala` | the game editor (2491–3072): `RoleDraft`/`ParameterDraft`, the role and parameter editors, `rolesOf`/`parametersOf`/`maxTurnsOf`, `gameForm` | 580 |
| `Ratings.scala` | the admin match list, ratings and rankings (3073–3720): `adminMatches*`, `ratingsSection`, `roleRankings`, `ratingsList`, `footRow`, `findPlayer`, `recordText`, `ratingRow`, `ratePlayer`, `ratingInput` | 650 |
| `Challenges.scala` | a game's challenges (3721–4345): `gameChallenges`, `characterPrompt`, `challengePanel`, the challenge dialog, `myChallengeRow`, `invitedList`, `invitePanel`, `openChallengeRow`, `entrantsDetail`, `parameterDetail` | 620 |
| `ChallengeForm.scala` | the new-challenge form (4497–4992 and `characterChooser`, 1637–1663): `ParameterChoice` and the parameter helpers, `newChallengeForm`, `startOf`, `localInputOf`, `amountOf`, `limitProblem`, `durationOf` | 525 |
| `Format.scala` — `Format` | moved as it is (4997–5099) | 100 |

No file is larger than `Store.scala` (1,324 lines) or `Tournaments.scala` (1,237).

### Why the shared kit stays in `Views`

The other option is a new `Widgets` object for the kit, which would leave `Views` holding only the chrome. That would
mean rewriting the imports in `Tournaments` and everything else that already reaches for `Views.field` and similar,
and `Tournaments`' doc comment, which calls them "`Views`' own pieces", would stop being true. Keeping the kit in
`Views` leaves those callers as they are.

## Cross-file references

These references were found by mapping which member uses which. Everything below changes from `private` to
`private[ui]`, because a caller now sits in another file:

- **Reloads shared across screens.** The flags `refreshingChallenges`, `refreshingDue`, `refreshingActive` and
  `refreshingCompleted` are used by the menu's `reloadGamePage` and by `challengePanel`. The same goes for
  `reloadChallengeSections`, `reloadAfterStart`, `challengeStale` and `challengeTerms`, which challenge rows on the
  main page, the player page and the game page all call.
- **Sections the game page reuses.** `readyToStartSection`, `dueSection`, `myMatchesSection` and `waitingSection`
  take an optional game and appear on both the main page and the game page.
- **Match display.** `matchTable`, `matchRow`, `waitingFor`, `reviewControl`, `watchControl`, `playerOutcome` and
  `publicOutcome` are used by the player, character, main and game pages.
- **Links and invites.** `playerLink` is called from the rating rows. `inviteControl` and `characterInviteButtons`
  are called from the invite panel, and `adminMatchesSection` from `publicGameRow`.
- **Format helpers.** `recordText` is used by the character page.

Four names already reached from `Tournaments` move to a new object, so their call sites in `Tournaments.scala`
change:

| Today | Becomes |
|---|---|
| `Views.characterInviteButtons` | `Players.characterInviteButtons` |
| `Views.startingChoices`, `Views.settingsOf`, `Views.parameterFields`, `Views.startOf`, `Views.localInputOf` | `ChallengeForm.…` |

`Views.refreshableSection` and `Views.tipField` stay where they are.

Inside each new object, the kit is imported the way `Tournaments` does it. Members of the other screen objects are
called by their qualified name, as in `MatchTables.matchTable(...)`. That shows at the call site that the code lives
in another file.

**Initialisation cycles.** The objects refer to each other in cycles: `Views.home` → `Games.gamePage` →
`MatchSections` → `Views.refresh`. Scala.js initialises an object on first use, so a cycle is only a problem when one
object's initialiser reads a `val` of another. None does. The object-level state is `Var(false)` flags, `var`s holding
an element or a counter, a string and `scopeAttr`, and no initialiser reaches into another object. A new `val` must
keep it that way.

## Steps

1. Cut each block into its new file, in the order of the table. Start each file with the same imports as
   `Main.scala`, and add `import Views.{…}` with just the kit members that file uses. Keep the doc comments with their
   code. Turn the `// ----- Matches -----` style section banners into each object's doc comment.
2. Widen the modifiers listed above and qualify the calls that now cross files, including the ones in
   `Tournaments.scala`.
3. Remove unused imports. Run `scalafmt --config .scalafmt.conf` on the new files and on `Tournaments.scala`.
4. Leave `Main.scala` holding only `object Main`.

It went in as one commit. The files call each other in both directions, so a single file cannot move out on its own
without temporary qualifiers that the next step would undo. The moved-line check below is what makes the single commit
reviewable.

## Checking it is only a move

- Run `mill -j 4 --ticker false matchmaker.ui.compile + matchmaker.ui.test + matchmaker.ui.fastLinkJS`.
- Moved-line check: once indentation and the `private`/`private[ui]` difference are normalised, the multiset of
  non-import lines in the old `Main.scala` should equal that of the new files. The only differences allowed are the
  added `object` headers, the imports and the `Object.` qualifiers. A short script in the scratchpad can show this, so
  that review does not depend on eye-diffing 5,000 lines.
- Run the UI locally and click through every page that `home` dispatches to: the main page, a game, a new game, find
  players, a player, a character, a tournament, a new tournament. Open the challenge and edit-game dialogs too. A
  missed initialisation-order problem shows up only at runtime, as an `undefined` in the console.

## Out of scope

These are left for later so that the move stays pure:

- `scopeAttr` is defined in both `Views` and `Tournaments`. Folding them into one is a one-line follow-up.
- Splitting `gameForm` (300 lines), `newChallengeForm` (335) or `ratingsList` (270) internally.
- Any renaming. The new object names are the only new names.
