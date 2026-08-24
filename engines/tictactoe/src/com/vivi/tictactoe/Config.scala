package com.vivi.tictactoe

/** How an engine is assembled from its environment, shared by the two ways it runs.
  *
  * `BASE_URL` is the only setting with no sensible default: it is what matchmaker and the players
  * are handed in step 1, and nothing the process can see tells it what url the outside world
  * reaches it on.
  */
object Config {

  def routes(
      env: String => Option[String],
      defaultBaseUrl: Option[String] = None,
      announce: TicTacToeMatch => Unit = _ => ()
  ): Routes = {
    val baseUrl = requiredBaseUrl(env, defaultBaseUrl)
    Routes(engine(env, baseUrl, announce), playAuth(env, baseUrl), matchmakerKey(env))
  }

  private def requiredBaseUrl(env: String => Option[String], default: Option[String]): String =
    env("BASE_URL")
      .orElse(default)
      .getOrElse(throw IllegalStateException("BASE_URL is not set: the engine cannot guess the url matchmaker should use"))
      .stripSuffix("/")

  /** The key matchmaker must present on the two routes that are its own, and that this engine
    * presents on its callbacks — one secret shared by the pair, in both directions.
    *
    * Required in Lambda and optional anywhere else. `AWS_LAMBDA_FUNCTION_NAME` is set only by the
    * runtime, so this cannot be got wrong in the safe-looking direction: a deployed engine whose
    * variable was forgotten fails at its first cold start, where a local one started for five
    * minutes of curl needs no setup. The same signal `playAuth` uses, for the same reason.
    */
  def matchmakerKey(env: String => Option[String]): Option[String] =
    env("MATCHMAKER_API_KEY").map(_.trim).filter(_.nonEmpty) match {
      case None if env("AWS_LAMBDA_FUNCTION_NAME").isDefined =>
        throw IllegalStateException(
          "MATCHMAKER_API_KEY is not set: a deployed engine would serve game creation to anyone"
        )
      case other => other
    }

  private def region(env: String => Option[String]): String =
    env("AWS_REGION").orElse(env("AWS_DEFAULT_REGION")).getOrElse("us-east-1")

  def engine(env: String => Option[String], baseUrl: String, announce: TicTacToeMatch => Unit = _ => ()): Engine = {
    val http = SignedHttp(AwsCredentials.fromEnvironment(env), region(env))

    val store = env("MATCH_TABLE") match {
      case Some(table) => DynamoDbMatchStore(http, table, region(env))
      // Fine for the local server, whose process outlives its matches, and wrong for Lambda,
      // where the next invocation may be a different container — hence the table.
      case None => InMemoryMatchStore()
    }

    val matchmaker: Matchmaker =
      if (env("MATCHMAKER_OFFLINE").contains("true")) RecordingMatchmaker(println)
      // Unsigned: matchmaker's callback routes take an API key now, not a SigV4 signature. The
      // signed client stays for DynamoDB above, which is still AWS and still needs one.
      else HttpMatchmaker(SignedHttp(None, region(env)), matchmakerKey(env), env("GAME_EXTERNAL_ID"))

    Engine(store, matchmaker, baseUrl, announce = announce)
  }

  /** The sign-in the board page offers, when there is a user pool to offer it against.
    *
    * All three settings or none: a client id with no hosted login url is a button that goes
    * nowhere, and failing at startup beats rendering one.
    */
  def loginConfig(env: String => Option[String], baseUrl: String): Option[LoginConfig] =
    (env("HOSTED_LOGIN_URL"), env("COGNITO_CLIENT_ID")) match {
      case (Some(hostedLogin), Some(clientId)) =>
        Some(LoginConfig(hostedLogin.stripSuffix("/"), clientId, s"$baseUrl/auth/callback"))
      case (None, None) => None
      case _ =>
        throw IllegalStateException("HOSTED_LOGIN_URL and COGNITO_CLIENT_ID must be set together, or not at all")
    }

  /** Who a play request is from.
    *
    *   - In Lambda, the JWT authorizer in front of the function has already verified the token, so
    *     the claims are read and trusted. Detected from `AWS_LAMBDA_FUNCTION_NAME`, which only the
    *     runtime sets, rather than from a setting someone could get wrong in the safe-looking
    *     direction.
    *   - Locally with a pool configured, the token is verified here against the pool's public
    *     keys — the same sign-in, checked for real.
    *   - Locally with no pool, the caller says who they are. Zero setup, and the local server
    *     prints a warning saying as much.
    *
    * `PLAY_AUTH` overrides the choice, for testing the other two modes.
    */
  def playAuth(env: String => Option[String], baseUrl: String): PlayAuth = {
    val login = loginConfig(env, baseUrl)
    val issuer = env("COGNITO_ISSUER")
    val inLambda = env("AWS_LAMBDA_FUNCTION_NAME").isDefined

    env("PLAY_AUTH").map(_.toLowerCase).getOrElse(if (inLambda) "gateway" else if (issuer.isDefined) "verify" else "trusted") match {
      case "gateway" => PlayAuth.GatewayClaims(login)
      case "verify" =>
        val clientId = env("COGNITO_CLIENT_ID").getOrElse(
          throw IllegalStateException("COGNITO_CLIENT_ID is required to verify tokens: it is the audience a token must carry")
        )
        PlayAuth.VerifiedToken(
          JwtVerifier(
            issuer.getOrElse(throw IllegalStateException("COGNITO_ISSUER is required to verify tokens")),
            clientId
          ),
          login
        )
      case "trusted" => PlayAuth.Trusted
      case other     => throw IllegalStateException(s"unknown PLAY_AUTH '$other'; expected 'gateway', 'verify' or 'trusted'")
    }
  }
}
