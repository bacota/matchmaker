package com.vivi.rps

/** What a player throws. Rock blunts scissors, scissors cut paper, paper wraps rock. */
enum Shape {
    case Rock, Paper, Scissors

    /** Whether this shape beats that one. The relation is a cycle, so each shape beats exactly one other and loses to
      * exactly one; equal shapes beat nothing, which is the draw.
      */
    def beats(other: Shape): Boolean = (this, other) match {
        case (Rock, Scissors)  => true
        case (Scissors, Paper) => true
        case (Paper, Rock)     => true
        case _                 => false
    }
}

object Shape {

    /** Accepted from the wire, where a player's page sends a name and matchmaker's `settings` may one day send one too.
      * Initials are accepted as well, since a one-letter move is what a scripted client will reach for:
      * `{"shape":"r"}`.
      */
    def parse(s: String): Option[Shape] =
        s.trim.toLowerCase match {
            case "r"  => Some(Rock)
            case "p"  => Some(Paper)
            case "s"  => Some(Scissors)
            case name => values.find(_.toString.toLowerCase == name)
        }
}

/** Which of the two seats a player holds.
  *
  * Unlike tic-tac-toe's X and O, this says nothing about how the game is played: both seats do exactly the same thing
  * at exactly the same time. It exists because matchmaker seats players by role — a challenge is accepted *into* a role
  * — and because a page has to call the two seats something. The engine's `role` names are these, spelled as
  * matchmaker's `game_role.name` rows.
  */
enum Side {
    case One, Two

    def other: Side = this match {
        case One => Two
        case Two => One
    }
}

object Side {
    def parse(s: String): Option[Side] =
        s.trim.toLowerCase match {
            case "1" | "one" | "p1" | "player 1" | "player one" => Some(One)
            case "2" | "two" | "p2" | "player 2" | "player two" => Some(Two)
            case _                                              => None
        }
}

/** How a pair of throws came out, from the point of view of nobody in particular. */
enum Outcome {
    case Win, Loss, Draw

    def label: String = toString.toLowerCase
}
