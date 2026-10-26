#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repository_root=$(cd -- "$script_dir/.." && pwd)
gradle_command=${1:-gradle}
if (($# > 0)); then shift; fi

# Publish only into the repository's ignored build directories; no external registry is written.
"$gradle_command" -p "$repository_root" "$@" \
  :jetty-proxy:publishMavenJavaPublicationToVerificationRepository \
  :jetty-proxy-spring:publishMavenJavaPublicationToVerificationRepository

# This directory has its own settings and uses Maven coordinates, without project() or includeBuild().
"$gradle_command" -p "$script_dir/published-consumer" "$@" run
"$gradle_command" -p "$script_dir/published-consumer" "$@" -PpublishedPomOnly=true run
