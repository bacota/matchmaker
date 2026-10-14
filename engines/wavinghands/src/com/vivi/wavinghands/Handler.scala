package com.vivi.wavinghands

import com.vivi.engine.{EngineRequest, EngineResponse, LambdaHandler}

/** Lambda entry point. Handler string: `com.vivi.wavinghands.Handler::handleRequest`, which the terraform names — so
  * this class stays here, under this name. The event handling is [[LambdaHandler]]'s.
  */
class Handler extends LambdaHandler {
    protected def respond(request: EngineRequest): EngineResponse = Handler.routes(request)
}

object Handler {

    /** Built on first use and kept for the life of the container. */
    lazy val routes: Routes = Config.routes(env = k => Option(System.getenv(k)))
}
