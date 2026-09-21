#!/bin/bash
# Celica Dash setup - macOS and Linux. Double-click on a Mac, or: bash "RUN ME.command"
cd "$(dirname "$0")"
echo
echo "  Celica Dash setup"
echo "  -----------------"
echo "  Starting the helper; your browser opens on the wizard."
echo "  Keep this window open while the head unit fetches."
echo
PY=$(command -v python3 || command -v python)
if [ -z "$PY" ]; then
  echo "  Python 3 is needed. On a Mac: xcode-select --install, or python.org/downloads. Then run this again."
  exit 1
fi
"$PY" setup/helper.py
