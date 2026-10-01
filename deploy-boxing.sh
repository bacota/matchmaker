#!/usr/bin/env bash
#
# Builds and deploys the boxing game engine, without redeploying matchmaker: ./deploy-engine.sh
# with the engine named, which says what the flags do.
#
#   ./deploy-boxing.sh dev
#   ./deploy-boxing.sh dev --yes --skip-build

exec "$(dirname "$0")/deploy-engine.sh" boxing "$@"
