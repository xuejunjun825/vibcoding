#!/usr/bin/env bash
# Thin build entry used by `make build` so the Maven repo fallback is shared.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
maven_build
