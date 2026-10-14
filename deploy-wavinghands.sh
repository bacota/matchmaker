#!/usr/bin/env bash
#
# Builds and deploys the Waving Hands game engine, without redeploying matchmaker: ./deploy-engine.sh
# with the engine named, which says what the flags do.
#
#   ./deploy-wavinghands.sh dev
#   ./deploy-wavinghands.sh dev --yes --skip-build

exec "$(dirname "$0")/deploy-engine.sh" wavinghands "$@"
