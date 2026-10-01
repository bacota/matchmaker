package com.vivi.engine

/** A request, however it arrived. Header names are lowercased on the way in, since payload v2 lowercases them and a
  * case-sensitive lookup for "Authorization" would silently never match.
  *
  * `claims` are only ever populated by the Lambda handler, from the block API Gateway's JWT authorizer writes into the
  * event; the local server verifies the bearer token itself instead.
  *
  * `connectionId` is set only on a Play Live connection's own events — its opening and its closing, whose `method` is
  * `CONNECT` and `DISCONNECT` — and names the connection a push is later sent down. An HTTP request never has one,
  * which is what keeps a request made with one of those methods from passing for a connection.
  */
case class EngineRequest(
    method: String,
    path: String,
    query: Map[String, String] = Map.empty,
    body: String = "",
    headers: Map[String, String] = Map.empty,
    claims: Map[String, String] = Map.empty,
    connectionId: Option[String] = None
) {
    def segments: List[String] = path.split('/').iterator.filter(_.nonEmpty).toList

    def bearerToken: Option[String] =
        headers
            .get("authorization")
            .map(_.trim)
            .collect {
                case value if value.toLowerCase.startsWith("bearer ") => value.drop(7).trim
            }
            .filter(_.nonEmpty)
}

case class EngineResponse(status: Int, body: String, contentType: String = "application/json")
