# Boolean names that read awkwardly

Every boolean column starts with `is_`, `has_` or `can_`, and every boolean field with `is`, `has` or `can`. The first
pass (PR #152) put `is` on every one of them, without regard to how it read. These are the names where that reads
badly, each with a suggestion.

All of them have now been applied. Where two are offered, the first was taken. `isDraw` was already a method on every
engine's match state, meaning the same thing, so it became `isDrawn` as well. `MatchNews.isYourTurn` means "it is your
turn", not a notification preference, so it kept its name.

## Verb phrases, where `is` makes no sense

| Now | Suggestion |
|---|---|
| `is_notify_*` / `isMatchStarted`, `isTurnTaken`, `isYourTurn`, `isMatchEnded`, `isChallengeAccepted`, `isInvitationReceived` and the rest (12 columns, the same names on `NotificationPreferences`/`SeatNotifications`) | `is_notified_of_*` / `isNotifiedOfYourTurn` |
| `isApplyToGames`, `isApplyToMatches` | `isAppliedToGames`, `isAppliedToMatches` |
| `isChooseRoles` / `is_choose_roles` | `hasRoleChoice`, or `isRoleChosen` |
| `isRotateRoles` / `is_rotate_roles` | `isRotatingRoles` |
| `isAutoStart` / `is_auto_start` | `isAutoStarted` |
| `isConcede` (stratego's move request) | `isConcession` |
| `isFail`, `isFailCallbacks`, `isFailRegistrations`, `isFailStateSaves` (test doubles) | `isFailing`, `isFailingCallbacks`, … |
| `isRefuseArchiving` (test double) | `isRefusingArchiving` |
| `isFocusNextStage` (UI) | `isNextStageFocused` |
| `isPlays` (`ChallengeRequest`) | `isPlaying` |
| `isNeedsPoolSize` | `hasPoolSize`, or `isPoolSizeNeeded` |
| `isArchiveOnStatus` (test double) | `isArchivingOnStatus` |

## Plural nouns or noun phrases, which read better with `has`

| Now | Suggestion |
|---|---|
| `isTiesAllowed` / `is_ties_allowed` | `canTie` |
| `isLateEntries` / `is_late_entries` | `hasLateEntries` |
| `isConcurrentRounds` / `is_concurrent_rounds` | `hasConcurrentRounds` |
| `isAutoRounds`, `is_automatic_rounds` | `hasAutomaticRounds` (this also makes the Scala and SQL names match) |
| `isPointsBonus` / `is_points_bonus` | `hasPointsBonus` |
| `isRubberMatch` / `is_rubber_match` (stage) | `hasRubberMatch` |
| `isTieBreakerStage` / `is_tie_breaker_stage` | `hasTieBreakerStage` |
| `isStrictStrata` / `is_strict_strata` | `hasStrictStrata` |
| `isBrackets` (`StageDesign`) | `hasBrackets`, or `isBracketed` to match the column `is_bracketed` |
| `isUnimportantRoles` / `is_unimportant_roles` | `hasUnimportantRoles` |
| `isSignificantRoles` | `hasSignificantRoles` |
| `isTwoRolesOnly` | `hasTwoRolesOnly` |
| `isMore` (search and leaderboard pages) | `hasMore` |
| `isExtraRound` (boxing) | `hasExtraRound` |
| `isDraw` | `isDrawn` |

## Grammatical but odd

| Now | Suggestion |
|---|---|
| `isStopRequested` / `is_stop_requested` | fine as it is; `hasStopRequest` is the alternative |
| `isArchiveFriendly` / `is_archive_friendly` | `isArchivedFriendly`, or `isInFriendlyBucket`, which says what it means |
| `isAfterCompletion` (`LaterSeatRow`) | `isAfterCompleted`, or leave it |
| `isNesting` (`WalkedPool`) | `isNested` |
| `isWarning` (`Problem`) | fine |
| `is_complete` (`round`) | `is_completed`, to match `isCompleted` elsewhere |

The rest read naturally: `isActive`, `isLive`, `isFriendly`, `isCancelled`, `isPending`, `isRevealed`, and so on.

## Methods

The same rule now covers methods that return a boolean, local defs included. Renamed:

| Was | Now |
|---|---|
| `ranOut` (every engine's match state, `TurnClock`) | `hasRunOut` |
| `completed` (`Match`, `MatchSummary`), `finished` (tournament state) | `isCompleted`, `isFinished` |
| `begun`, `withdrawn`, `optional`, `running` (`PlayerClock`), `inProgress`, `inPlay`, `movable`, `onBoard`, `stuck` | `hasBegun`, `isWithdrawn`, `isOptional`, `isRunning`, `isInProgress`, `isInPlay`, `isMovable`, `isOnBoard`, `isStuck` |
| `decidedByLot`, `decidedOnPoints`, `levelAfterSchedule`, `extraRound` (`Bout`) | `isDecidedByLot`, `isDecidedOnPoints`, `isLevelAfterSchedule`, `hasExtraRound` |
| `wouldBeDrawn` (stratego) | `isDrawByRules` |
| `rubberApplies` | `isRubberApplicable` |
| `plays` (a pool) | `hasPlay` |
| `Protocol.tiesAllowed(request)` | `Protocol.canTie(request)` |
| `allowed`, `active` (`EmailSuppression`), `verified`, `offline`, `inRange`, `roundComplete`, `runPlayed`, `wants`, `lockFailure`, `fromMatchmaker`, `headerAuth`, `wellFormed`, `resolved` | `isAllowed`, `isActive`, `isVerified`, `isOffline`, `isInRange`, `isRoundComplete`, `isRunPlayed`, `isWanted`, `isLockFailure`, `isFromMatchmaker`, `isHeaderAuth`, `isWellFormed`, `isResolved` |
| `playersOnce`, `sameSeats`, `archivedHint` | `hasDistinctPlayers`, `hasSameSeats`, `hasArchivedHint` |
| `beats` (rps), `mayHaveExpired`, `mayClear` | `canBeat`, `canHaveExpired`, `canClear` |
| `shuttles` (stratego) | `isShuttling` |
| UI: `stillSignedInAs`, `newest`, `endsSession`, `unexpired`, `plausibleEmail`, `invitedTo`, `waitsOnMe`, `keeping`, `seatedNow` | `isStillSignedInAs`, `isNewest`, `isSessionEnding`, `isUnexpired`, `isPlausibleEmail`, `isInvitedTo`, `isWaitingOnMe`, `isKeeping`, `isSeatedNow` |

Fields the first pass missed, being in nested classes: `LocalLiveServer`'s `open` → `isOpen`, and `Coalescer`'s
`running`/`timerSet` → `isRunning`/`isTimerSet`.

Left alone on purpose:
- `send` (Play Live's channels) and `text`, which act and report whether it arrived; a predicate name would hide the
  side effect.
- `apply`, whose name is what makes `Notifications(kind)` work.
- Local vals and vars, which are not fields.
