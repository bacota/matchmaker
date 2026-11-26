package com.vivi.matchmaker.model

/** Which games a cross-game list may show. See design/game-sites-plan.md. */
enum GameScope {
    case All
    case Only(games: Set[GameId])

    /** Narrowed further to one game, where a screen asks about one: the game screen's own list. */
    def narrowedTo(game: Option[GameId]): GameScope = (this, game) match {
        case (_, None)              => this
        case (All, Some(g))         => Only(Set(g))
        case (Only(games), Some(g)) => Only(games.intersect(Set(g)))
    }

    def isShowing(game: GameId): Boolean = this match {
        case All         => true
        case Only(games) => games.contains(game)
    }
}

object GameScope {
    def only(game: GameId): GameScope = Only(Set(game))
}
