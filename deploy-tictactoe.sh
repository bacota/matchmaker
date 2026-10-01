#!/usr/bin/env bash
#
# Builds and deploys the tic-tac-toe game engine, without redeploying matchmaker: ./deploy-engine.sh
# with the engine named, which says what the flags do.
#
#   ./deploy-tictactoe.sh dev
#   ./deploy-tictactoe.sh dev --yes --skip-build

exec "$(dirname "$0")/deploy-engine.sh" tictactoe "$@"
