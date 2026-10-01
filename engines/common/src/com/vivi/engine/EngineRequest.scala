package com.vivi.engine

/** A request, however it arrived. Header names are lowercased on the way in, since payload v2 lowercases them and a
  * case-sensitive lookup for "Authorization" would silently never match.
  *
  * `claims` are only ever populated by the Lambda handler, from the block API Gateway's JWT authorizer writes into the
  * event; the local server verifies the bearer token itself instead.
  */
case class EngineRequest(
    method: String,
    path: String,
    query: Map[String, String] = Map.empty,
    body: String = "",
    headers: Map[String, String] = Map.empty,
    claims: Map[String, String] = Map.empty
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
