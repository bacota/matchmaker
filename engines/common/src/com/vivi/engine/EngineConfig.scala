package com.vivi.engine

import java.time.Duration
import upickle.default.ReadWriter

/** The settings every engine reads from its environment the same way, whatever its game.
  *
  * `BASE_URL` is the only setting with no sensible default: it is what matchmaker and the players are handed in step 1,
  * and nothing the process can see tells it what url the outside world reaches it on.
  *
  * An engine's own `Config` assembles its game's engine and routes from these.
  */
object EngineConfig {

    /** The url the outside world reaches this engine on, which it hands to matchmaker and the players. */
    def requiredBaseUrl(env: String => Option[String], default: Option[String]): String =
        env("BASE_URL")
            .orElse(default)
            .getOrElse(
              throw IllegalStateException("BASE_URL is not set: the engine cannot guess the url matchmaker should use")
            )
            .stripSuffix("/")

    /** The key matchmaker must present on the two routes that are its own, and that this engine presents on its
      * callbacks — one secret shared by the pair, in both directions.
      *
      * Required in Lambda and optional anywhere else. `AWS_LAMBDA_FUNCTION_NAME` is set only by the runtime, so this
      * cannot be got wrong in the safe-looking direction: a deployed engine whose variable was forgotten fails at its
      * first cold start, where a local one started for five minutes of curl needs no setup. The same signal `playAuth`
      * uses, for the same reason.
      */
    def matchmakerKey(env: String => Option[String]): Option[String] =
        env("MATCHMAKER_API_KEY").map(_.trim).filter(_.nonEmpty) match {
            case None if env("AWS_LAMBDA_FUNCTION_NAME").isDefined =>
                throw IllegalStateException(
                  "MATCHMAKER_API_KEY is not set: a deployed engine would serve game creation to anyone"
                )
            case other => other
        }

    def region(env: String => Option[String]): String =
        env("AWS_REGION").orElse(env("AWS_DEFAULT_REGION")).getOrElse("us-east-1")

    /** Where matches are kept: a DynamoDB table when `MATCH_TABLE` names one, and memory otherwise — archived, once
      * finished, through matchmaker when `MATCHMAKER_URL` says where it is. See [[ArchivingMatchStore]].
      */
    def matchStore[M <: HasMatchId: ReadWriter](env: String => Option[String]): MatchStore[M] = {
        val live = env("MATCH_TABLE") match {
            case Some(table) =>
                DynamoDbMatchStore[M](SignedHttp(AwsCredentials.provider(env), region(env)), table, region(env))
            // Fine for the local server, whose process outlives its matches, and wrong for Lambda,
            // where the next invocation may be a different container — hence the table.
            case None => InMemoryMatchStore[M]()
        }
        // Not when matchmaker is offline: there is nothing to archive through, and the local engine
        // keeps every match in memory for as long as it runs.
        ArchivingMatchStore.around(live, matchmaker(env), matchmakerUrl(env).filterNot(_ => offline(env)))
    }

    private def offline(env: String => Option[String]): Boolean = env("MATCHMAKER_OFFLINE").contains("true")

    /** Where matchmaker's API is, for the calls an engine makes on its own account rather than about one match's moves:
      * archiving a finished match and reading it back once its live copy is gone, which every engine does, and a
      * character game reporting a character a player has made.
      *
      * Every engine that talks to matchmaker needs `MATCHMAKER_URL`. One without it still plays matches and sends their
      * callbacks, which arrive with urls of their own, but archives nothing: its finished matches stay in its own
      * store, and matchmaker's prompts to archive them cannot get them out. The local server says so when it starts.
      */
    def matchmakerUrl(env: String => Option[String]): Option[String] =
        env("MATCHMAKER_URL").map(_.trim).filter(_.nonEmpty)

    /** How matchmaker is called back: over HTTP, or — with `MATCHMAKER_OFFLINE=true` — not at all, the calls printed.
      */
    def matchmaker(env: String => Option[String]): Matchmaker =
        if (offline(env)) RecordingMatchmaker(println)
        // Unsigned: matchmaker's callback routes take an API key now, not a SigV4 signature. The
        // signed client stays for DynamoDB above, which is still AWS and still needs one.
        else HttpMatchmaker(SignedHttp(None, region(env)), matchmakerKey(env), env("GAME_EXTERNAL_ID"))

    /** The sign-in the board page offers, when there is a user pool to offer it against.
      *
      * All three settings or none: a client id with no hosted login url is a button that goes nowhere, and failing at
      * startup beats rendering one.
      */
    def loginConfig(env: String => Option[String], baseUrl: String): Option[LoginConfig] =
        (env("HOSTED_LOGIN_URL"), env("COGNITO_CLIENT_ID")) match {
            case (Some(hostedLogin), Some(clientId)) =>
                Some(LoginConfig(hostedLogin.stripSuffix("/"), clientId, s"$baseUrl/auth/callback", region(env)))
            case (None, None) => None
            case _ =>
                throw IllegalStateException(
                  "HOSTED_LOGIN_URL and COGNITO_CLIENT_ID must be set together, or not at all"
                )
        }

    /** Who a play request is from.
      *
      *   - In Lambda, the JWT authorizer in front of the function has already verified the token, so the claims are
      *     read and trusted. Detected from `AWS_LAMBDA_FUNCTION_NAME`, which only the runtime sets, rather than from a
      *     setting someone could get wrong in the safe-looking direction.
      *   - Locally with a pool configured, the token is verified here against the pool's public keys — the same
      *     sign-in, checked for real.
      *   - Locally with no pool, the caller says who they are. Zero setup, and the local server prints a warning saying
      *     as much.
      *
      * `PLAY_AUTH` overrides the choice, for testing the other two modes.
      */
    def playAuth(env: String => Option[String], baseUrl: String): PlayAuth = {
        val login = loginConfig(env, baseUrl)
        val issuer = env("COGNITO_ISSUER")
        val inLambda = env("AWS_LAMBDA_FUNCTION_NAME").isDefined

        env("PLAY_AUTH")
            .map(_.toLowerCase)
            .getOrElse(if (inLambda) "gateway" else if (issuer.isDefined) "verify" else "trusted") match {
            case "gateway" => PlayAuth.GatewayClaims(login)
            case "verify" =>
                val clientId = env("COGNITO_CLIENT_ID").getOrElse(
                  throw IllegalStateException(
                    "COGNITO_CLIENT_ID is required to verify tokens: it is the audience a token must carry"
                  )
                )
                PlayAuth.VerifiedToken(
                  JwtVerifier(
                    issuer.getOrElse(throw IllegalStateException("COGNITO_ISSUER is required to verify tokens")),
                    clientId
                  ),
                  login
                )
            case "trusted" => PlayAuth.Trusted
            case other =>
                throw IllegalStateException(s"unknown PLAY_AUTH '$other'; expected 'gateway', 'verify' or 'trusted'")
        }
    }

    /** Play Live, deployed: when `LIVE_URL` names the WebSocket API the page connects to, the stage's management url to
      * push through (`LIVE_ENDPOINT`) and the table connections are kept in (`LIVE_TABLE`). Without `LIVE_URL`, none —
      * and the play page offers no Play Live switch. The local server makes its own; see [[LocalEngineServer]].
      */
    def live(env: String => Option[String], baseUrl: String): Option[Live] =
        env("LIVE_URL").map(_.trim).filter(_.nonEmpty).map { url =>
            def required(name: String) =
                env(name).getOrElse(throw IllegalStateException(s"LIVE_URL is set but $name is not"))
            val http = SignedHttp(AwsCredentials.provider(env), region(env))
            Live(
              url,
              liveAuth(env, baseUrl),
              DynamoDbSubscriptions(http, required("LIVE_TABLE"), region(env)),
              // A push that has not landed in two seconds is not going to be worth waiting for: the
              // page checks for itself within the minute. See `Live`'s deadline.
              ApiGatewayChannel(
                SignedHttp(AwsCredentials.provider(env), region(env), timeout = Duration.ofSeconds(2)),
                required("LIVE_ENDPOINT")
              )
            )
        }

    /** Who is opening a Play Live connection.
      *
      * The play routes' own auth, except where that is the gateway's: a WebSocket API has no JWT authorizer, so no
      * claims arrive with a connection, and the token the page sends with it is verified here instead — the same checks
      * against the same pool, done by the function rather than in front of it. Without a pool to verify against there
      * is nobody to admit, and the gateway's auth, finding no claims, refuses every player; a public board's watchers
      * need no identity and are admitted either way.
      */
    def liveAuth(env: String => Option[String], baseUrl: String): PlayAuth =
        playAuth(env, baseUrl) match {
            case gateway: PlayAuth.GatewayClaims =>
                (env("COGNITO_ISSUER"), env("COGNITO_CLIENT_ID")) match {
                    case (Some(issuer), Some(clientId)) =>
                        PlayAuth.VerifiedToken(JwtVerifier(issuer, clientId), gateway.login)
                    case _ => gateway
                }
            case other => other
        }
}
