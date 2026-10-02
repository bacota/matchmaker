#!/usr/bin/env bash
#
# Builds and deploys the Stratego game engine, without redeploying matchmaker: ./deploy-engine.sh
# with the engine named, which says what the flags do.
#
#   ./deploy-stratego.sh dev
#   ./deploy-stratego.sh dev --yes --skip-build

exec "$(dirname "$0")/deploy-engine.sh" stratego "$@"
