#!/usr/bin/env bash
#
# Builds and deploys the rock-paper-scissors game engine, without redeploying matchmaker: ./deploy-engine.sh
# with the engine named, which says what the flags do.
#
#   ./deploy-rps.sh dev
#   ./deploy-rps.sh dev --yes --skip-build

exec "$(dirname "$0")/deploy-engine.sh" rps "$@"
