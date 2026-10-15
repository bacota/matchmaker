#!/usr/bin/env bash
# Accessibility and mobile check of the UI and an engine's play pages; see e2e/a11y/check.mjs. Results in e2e/out/a11y/.
cd "$(dirname "$0")" && { [ -d e2e/a11y/node_modules ] || (cd e2e/a11y && npm ci --no-audit --no-fund); } && mill -j 4 --ticker false matchmaker.ui.fastLinkJS && node e2e/a11y/check.mjs
