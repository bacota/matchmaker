#!/usr/bin/env bash
#
# Builds and deploys the Hilo game engine, without redeploying matchmaker: ./deploy-engine.sh with
# the engine named, which says what the flags do.
#
#   ./deploy-hilo.sh dev
#   ./deploy-hilo.sh dev --yes --skip-build

exec "$(dirname "$0")/deploy-engine.sh" hilo "$@"
