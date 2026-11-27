package com.vivi.engine

import scala.util.control.NonFatal
import upickle.default.write
import Protocol.given

/** What became of a sign-in code an engine's page was opened with. */
enum HandOffAnswer {

    /** Matchmaker redeemed it: the player's tokens. */
    case Redeemed(tokens: Protocol.HandedOff)

    /** Matchmaker would not: spent, expired, another game's, or made up. */
    case Unknown

    /** There is no matchmaker to ask, or it could not be reached or failed. */
    case Unavailable
}

/** Redeeming the one-time code matchmaker's UI opens a play page with (`#handoff=CODE`), for the player's tokens
  * (design/sign-in-handoff.md in matchmaker).
  *
  * The engine's server redeems it, not its page, because the call carries the game's API key: a code made for one game
  * cannot be spent by another game's engine, or by anyone holding the code alone. The tokens travel only in request and
  * response bodies, never in a url.
  *
  * `clients` are matchmaker's clients and the urls they call, tried in turn until one redeems the code. An engine has
  * one. A server with several registrations, each with its own key, has one each: a client whose key is not the code's
  * game's gets a 404, which spends the code, so a server with many should put which registration in the url instead of
  * trying each.
  *
  * Nothing here logs the code or the tokens.
  */
class HandOffs(clients: Seq[(Matchmaker, String)], log: String => Unit = message => System.err.println(message)) {

    def redeem(code: String): HandOffAnswer =
        if (clients.isEmpty) HandOffAnswer.Unavailable
        else
            try
                clients.iterator
                    .map((matchmaker, url) => matchmaker.redeemHandOff(url, code))
                    .collectFirst { case Some(tokens) => HandOffAnswer.Redeemed(tokens) }
                    .getOrElse(HandOffAnswer.Unknown)
            catch {
                case NonFatal(e) =>
                    // The class alone: a message could quote the request, and the request carries the code.
                    log(s"redeeming a sign-in code failed: ${e.getClass.getName}")
                    HandOffAnswer.Unavailable
            }

    /** `POST /api/handoff`, body `{code}`: 200 with the tokens, 404 for a code matchmaker does not redeem, 503 when it
      * could not be asked. Never kept by a cache: it carries a credential.
      */
    def respond(body: String): EngineResponse =
        scala.util.Try(upickle.default.read[Protocol.HandOffRedeem](body)).toOption.filter(_.code.nonEmpty) match {
            case None => HandOffs.unstored(400, HandOffs.errorBody(ErrorText.request.unreadableBody))
            case Some(request) =>
                redeem(request.code) match {
                    case HandOffAnswer.Redeemed(tokens) => HandOffs.unstored(200, write(tokens))
                    case HandOffAnswer.Unknown => HandOffs.unstored(404, HandOffs.errorBody(ErrorText.handOff.unknown))
                    case HandOffAnswer.Unavailable =>
                        HandOffs.unstored(503, HandOffs.errorBody(ErrorText.handOff.unavailable))
                }
        }
}

object HandOffs {

    /** No matchmaker to redeem codes with: every code is answered 503, and the page asks the player to sign in. */
    val none: HandOffs = HandOffs(Nil)

    private def errorBody(message: String): String = ujson.write(ujson.Obj("error" -> message))

    private def unstored(status: Int, body: String): EngineResponse =
        EngineResponse(status = status, body = body, headers = Map("cache-control" -> "no-store"))
}
