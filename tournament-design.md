# Goal


Add the capability for users to create and manage competitions between
other users.    

# Preliminaries


First, the  column `game_role.optional` is replaced by `game_role.type` which has value `required`, `optional`, or `recommended`.  All roles in  existing game engines are `required`.

Game_Role also needs an integer coolumn `preference_leverl` which defaults to null

Game needs a `move_type` column with value either `seq` (sequential)
or `sim` (simultaneous) and an optional `max_turns`.  Boxing and RPS
are `sim`.  All others are `seq`.


A game engine should accept a boolean parameter `"ties_allowed"` when
a match is created.  The default value is `true`.  If the parameter is
set to `false` some game engines will attempt to declare a winner
where it would normally have declared a tie.  Boxing will play an
additional round.  If that round is tied a winner of the round will be
chosen randomly.  Stratego will give each side  points for
each piece taken equal to the piece's strength, and 1 point for each bomb or spy.  The Stratego
player with the most points is the winner.  RPS will continually play
rounds until there is a winner.


# Vocabulary

    * "Competition" -- Also called a "tournament".  
    
    * "Participant" -- A player or character in a tournament.  A tournament is played in stages, which may consist of multiple sub-tournaments.  The atomic tournament that is not recursively defined is a "Tournament Match" which is a single match.
         
    * "Pool"--   A group of participants competiting against each other as one part of a tournament.  During a single stage each participant is assigned to exactly one pool (exept for participants who have been eliminated from the tournament).
    
    * "Round" -- A period of time where participants play matches.  All matches in a round have the same start time and same deadline for completion.  Every pool is typically involved in a round and every participant that has not been eliminated is part of a match if possible.  In most cases a participant can participate in at most one match per round.

    * "Seed" -- Rating of a player used to group participants into pools.  Initial seeds are set by elo ranking.  There will be an attempt to match higher seeds against lower seeds.  Seeds change as the tournament progresses.

    * "Stage" -- A part of a tournament where the tournaments played by each pool is resolved.  Pools are formed at the start of a stage, rounds are carried out until all tournaments are complete   Based on the results participants may change seed, be promoted or demoted to a different strata, or eliminated from the tournament.

    * "Stratum" -- A group of participants.  Every pool will, if possible, be constructed fo participants from the same stratum regardless of seeds.  All participants start at stratum 0, but may be promoted to a higher or demoted to a lower stratum due after a stage.
    
    
# Tournament Design

A tournament is designed a sequence of stages (possibly just 1).  Each
stage has the following properties stage is repeated until some
criteria is met.  Possible criteria include:

    * A fixed number of repetitions (often just 1)
    
    * Repeated until a certain number of participants remain.
    
    * Repeated indefinitely until stopped by a the tournament manager
    
These stages are called "direct children" of the tournament.
    
    
# Other Tournament Properties 

    * Owner -- A player with the rights to edit the tournament and  to manually  start and terminate rounds.  Default is the player who created the tournament.
    
    * Automatic or Manual Rounds -- whether rounds are explicitly started by the owner or started automatically when the previous round completes.  Default is Manual

    * Friendly-- whether matches are friendly or not.  Default false
    
    * Inviatational -- whether tournament is open or by default.  Default true
    
    * Late entries allowed -- whether a player can enter the game in the middle of the tournament (at strata 0).  Default false
    
    * Min and Max ratings of players who are allowed to participate.  Defaults are unlimited.
    
    * Number of entries permitted per player.  Default 1
    
    * Round Duration.  This is a default that may be overriden by stages

    
# Stage Design

During each stage all participants are broken up into pools to play mini tournaments.

Participants are ranked based on their performance and may promoted, demoted, or eliminated.

Each stage has the following properties

    * Allow Ties -- whether this flag is set on matches during this stage.  Default `true`
    
    * Brackets -- boolean.  
    
    
    * Demote -- if set this is the rank (or lower) at which participants are dmoted to a lower stratum.  Must be greater than 1.  Default is 2.

    * Max Stratum -- no participant is promoted above this stratum.  Default 0
    
    * Min Stratum -- when demoted from this stratum a participant is eliminated from the tournament.  Default 0
    
    * Points -- integer used for ranking players in a round robin pool.  Default 2

    * Points for Draw -- integer, also used for ranking players in round robin.  Default 1
    
    * Points Bonus -- boolean.  If set gives winner a 50% points bonus.
    
    * Pool Size - The size of each pool.  Default -- the number of required and recommended roles in the game.
    
    * Promote - if set, this is the rank (or higher) at which participants are promoted to a higher stratum.  Default is null
    
    * Round Duration
    
    * Concurent -- positive.  Default 1`.  Only used for round robin tournaments.
    
    * Rotate Roles -- boolean.  Default `false`. Only used for round robin tournaments.
    
    * Strict Strata -- if this is `true` then participants from different strata cannot be placed in the same pool.  Otherwise, they may be placed in the same pool when necessary to fill a pool.  Default `true`
    
    * Tournament Design -- If this is set the stage is carried out using this tournament for each pool.  If it is NOT set then the tournament is round robin.
    
    * Tie Breaker Stage -- If this is `true` then seedings after this stage are used as a tie breaker for seeding the tournament that contains it.  Default `false`.
    
    * Seed Policy -- How seeds are computed after the stage.  The possible policies are
    
        * Constant -- seeds are the same as initial seeds
        
        * Re-initialize -- seeds are computed from elo rankings
        
        * Previous -- seeds are based on performance from previous stage using ranking score, total match score differentials, total score and finally elo as tie breakers.
        
        * Winner takes Seed -- The seeds that entered the pervious pool are reassigned to participants in the pool based on their rank.  For example, suppose participants A, B, C, and D entered a pool at with seeds 1, 8, 9, and 16.  If they finish with ranks 1, 4, 3, and 2 then the computed seeds after thestage would be A:1, B:16, C:9, D:8   This option is the default.
        
    *  Tiebreaker -- the critiera used to resolve a tied ranking between participants in a pool.   Either 'Rubber' for an additional round robin among tied players or `scores` for total score differntials.  If scores is the criteria and total score differentials are not sufficient then the highest total score without regard to opposing scores is used.  If that is not sufficient than ranks remain tied.
    


# Round Robin Tournaments

A Round Robin tournament is a tournament with no stages -- a leaf on the tournament design tree.

If `rotate_roles` is false then the number of matches in a pool of N participants playing a game with
R recommended and required roles is M = (N choose R).  But if N is at least as
large as the number of required roles in the game there can still be 1
match.  If N is smaller than the number of required roles in the game
then there will be no matches.  No participants are demoted or
promoted in that case.  

Matches in a round robin must be scheduled to occur sequentially in
separate rounds, but any two matches that do not share any
participants may be scheduled to run concurrently (in the same round).

If `rotate_roles` is true then every participant must play every role.
In that case the scheduling must be repeated R times and each
participant assigned a role in each match, cycling through every role
exactly once.

If `concurrent > 1`  then multiple matches with the same participant may be scheduled to be played in the same round, with the value of 
`concurrent` as a per-participant limit.

There also needs to be some reasonable limit on the value of M, probably 1000.






    
# Ranking

A round robin stage is ranked by assigning points to each participant
based on the ranks achieved in matches played.
r
Ranking higher than one opponent in one match scores `Stage.Points` points.   Tying with an opponent in one match scores  `Stage.Draw` points.  If the stage has `Points Bonus` set  then ranking #1 in a match earns a 50% bonus.  

For example, if `Stage.Points` is 2, `Stage.Draw` is 1 , and `Points Bonus` is `true`, then in a four player match the #participant ranked #1 would earn 9 points, #2 4 points, #3 2 points and #4 0 points.   If there was a tie for 1st and a tie for 3rd, the two 3rd place winners would each get 1 point and the two first place winners would each get 7.5 points.

A  tournament uses the ranking of stages.  

The first stage ranked is the final stage played.   If pools were played at multiple strata then participants in a higher stratum rank higher than participants in any lower stratum.  Tied rankings are determined by ranking in tie breaker stages, with the most recent tie breaker stages as the most important.  Any tie that cannot be resolved in this way remains a tie.

If some participants had been eliminated before the final stage, then the second to last stage rankings are used to rank them.   All participants who were eliminated in the second to last stage are ranked below all participants in the last stage.  This is repeated going backwards through stages until all participants have been ranked.

Note that for purposes of ranking a tournament, only ranking in stages that are direct children of the tournament are considered.  
    
# Rounds

A round is a set of matches played concurrently during a stage.  It may be manually started by the tournament owner or automatically started.

The duration of a round is a pararameter associated with a stage All
matches in a round start at the same time and must be completed by the
end of the round.

In games with sequential movement each seat has a chess clock.  IF the
round duration is `d` and the number of seats in a match is `n` then
each seat's chess clock is is `d/n`.  If a game has simultaneous
movement with `max_turns` of `t` then each turn has a time limit of
`d/t`.  If the game has a simultaneous_movement and `max_turns` is not
set then it must be supplied as a tournament parameter.

                
# Creating Pools from Seeds and Strata

Let `r` be the number of participants in a strata, `n` the desired number of participants in each pool. and `p` the number of pools in the strata.  Let `a` be the number of participants who advance from each pool so `a = Stage.Demote - 1`.  
`a` must divide `n` or the brackets cannot be constructed.

If `Stage.Brackets` is `false` then `p = ceiling(r/n)`

If `Stage.Brackets` is `true` then `p = (n/a)^(ceiling( (log(r) - log(n)) / (log(n) - log(a)) )`

If `r < n` there will be at most a single pool and then only if  there are at least enough participants for all required roles.


Pools within each strata are then numbered from 0 to `p-1` .  The seeds for participants in each strata are then ordered within each strata and assigned an index `ix` starting from `0`.
Participants for a strata can be assigned to pools using a snaking
algorithm: 
The following code can be used to assign a participant with 
seed index `ix` to a pool from among `npools` pools.  

```
def pool(s: Int, p: Int): Int = {
    val n = (s) % p
    val l = (s/p)%2
    if (l == 0)  n  else p-n-1
}
```

If the stage is using strict strata then participants from different strata may never be placed in the same pool.    

However if the stage is NOT using strict strata them pools are created for strata in order from highest strat to lowest seed.  Any vacancies in a pool at one position are filled with participants from the next lowest strata, lowest seed first.  

# Miscellaneous Issues

## Multiple entrants from same player

Some tournaments may allow a player to have multiple entries.  If the same player appears in the same match with more than one role, all but the highest seeded participant owned by that player forfeits.  


## Ties 

If a tournament is set to not allow ties, but there is a tie and no tie breaker resolves the tie the win goes to the lower seeded team.


## Withdrawals

A participant may withdraw from a tournament at any time.  That
participant just does not participate in future in future stages.

## Late Entries

If a participant first enters a tournament after it is started it enters at stratum 0 and is seeded according to ELO against other participants at stratum 0.  If the participant withdraws and then re-enters the participant retains its previous  stratum.

## Choice of Roles 

During a stage in which `rotate_roles` is `false` and  `Game.unimportant_roles` is `false` then participants must choose roles.  If the roles have preference levels then the highest preference roles are distributed to the highest seeded team.  Otherwise the teams choose roles in order by seed.   If the game turn type is simultaneous then each player has up to the turn time limit to choose a role.   The turn time limits need to be adjusted down to allow for this.  
If the game turn type is sequential then the time each participant
spends choosing a role counts against each player's clock.


# Standard Tournament Designs

There are many types of standard tournaments that this framework should cover.  These include

In all of the following `R` is the number of required and recommended roles in the Game.

    
## Elimination Match

A single stage played once with

    allow_ties: false
    brackets: false
    rotate_roles: false
    demote: 2


## SIngle Elimination

A single stage played until one participant remains with the following properties:

    brackets: true
    tournament_design: Elimination Match


## Single Elimination with Consolation

This can only be played with games where required roles = 2

Three stages.

The first stage is played until there are 4 participants
 tournament_design: elimination round

    brackets: true
    pool_size: 2
    tournament_design: Elimination Match

The second stage is  played once

    brackets: true
    pool_size: 2
    min_stratum: -1
    strict_stratum: true
    Tournament Design:  Elimination Match


The third stage is  played once

    brackets: false
    pool_size: 2
    min_stratum: -1
    strict_stratum: true
    demote: null
    Tournament Design:  
        allow_ties: false
        brackets: false
        rotate_roles: false
        demote: null

## Modified Double Elimination


Also requires that pool_size be 2

One stage played until there are two participants left

    pool_size: 2
    allow_ties: false
    brackets: true
    demote: 2
    min_stratum: -1
    strict_stratum: true

    tournament_design: Elimination Match


## Double Elimination

Like modified double elimination but with a  second  stage played once 

    Tournament_design: Elimination Match
    promote: 1
    demote: 2
    strict_stratum: false


## Heats with Single Elimination

The first stage is a Round Robin stage played once with `Previous` seed policy.     The creator will have to set the pool size when the tournament is created.

The second stage is a Single Elimination. If R is 2 then it is a single elimination with consolation.
    
## Ladder

One stage repeated infinitely. 

    brackets: false
    max_stratum: null
    min_stratum: null
    pool_size: R
    promote: 1
    demote: R
    strict_strata: false
    seed_policy: re-intialize
    tournament_design: round_robin with rotate_roles=false


## Brazilian Repachage 

Can only be used with required roles = 2

First stage is a single elimination until there are 8 participants.

Second stage is a modified double elimination.


## Zero Elimination

Can only be used with required roles = 2

Single Elimination tournament but with infinite minimum and maximum stratum 

Stage is run  ceil(log(N)/log(2)) times  where N is the number of participants.  (This isn't possible right now but a future enhancemento may allow it)

## Soccer World Cup

Stage 1 is run one time:

    pool_size: R+2
    allow_ties; true
    brackets: false
    demote: (R+4)/2
    points: 2
    points_bonus: true
    points_for_draw: 1
    rotate_roles: false
    seed_policy re-initialize:
    tournament_design: round_robin
        
Stage 2: Single Eliminationi (if R=2 then with consolation)

## Fencing

Stage 1 is run one time:

    pool_size: R+3
    allow_ties; true
    brackets: false
    demote: (R+3)/2
    points: 1
    points_for_draw: 0
    rotate_roles: false
    seed_policy re-initialize:
    tournament_design: round_robin
        
Stage 2: Single Eliminationi (if R=2 then with consolation)

## NCAA Football Style

Stage 1 is run one time:

    pool_size: R+8
    allow_ties; true
    brackets: false
    demote: 2
    points: 2
    points_for_draw: 1
    rotate_roles: true
    seed_policy re-initialize:
    tournament_design: round_robin

        
Stage 2: Single Eliminationi (if R=2 then with consolation)

