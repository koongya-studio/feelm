#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <runtime-image-tag> <dotenv-output>" >&2
  exit 2
fi
IMAGE=$1
OUTPUT=$2
if [[ ! "$IMAGE" =~ ^[^[:space:]@]+:[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]] \
  || [[ -z "$OUTPUT" || -L "$OUTPUT" ]]; then
  echo "An explicit image tag and non-symlink output path are required" >&2
  exit 2
fi
# A failed retry must never leave a previous digest available to a caller.
rm -f -- "$OUTPUT"
docker build --pull --platform linux/amd64 --tag "$IMAGE" \
  --file infra/spark/Dockerfile infra/spark
image_id=$(docker image inspect --format '{{.Id}}' "$IMAGE")
[[ "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]]
# Test the exact local image that will be pushed, as its default spark user.
docker run --rm --network none --entrypoint python3 "$image_id" -m pip check
docker run --rm --network none --entrypoint python3 \
  --mount "type=bind,src=$(pwd)/infra/spark/runtime-smoke.py,dst=/tmp/runtime-smoke.py,readonly" \
  "$image_id" /tmp/runtime-smoke.py
[[ "$(docker image inspect --format '{{.Id}}' "$IMAGE")" == "$image_id" ]]
docker push "$IMAGE"

repository=${IMAGE%:*}
mapfile -t digests < <(
  docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$IMAGE" \
    | awk -v prefix="${repository}@" 'index($0, prefix) == 1'
)
if [[ ${#digests[@]} -ne 1 || ! "${digests[0]}" =~ ^[^[:space:]@]+@sha256:[0-9a-f]{64}$ ]]; then
  echo "Pushed runtime must resolve to one repository-matching digest" >&2
  exit 1
fi
# Confirm that the published immutable reference contains the tested image.
docker pull "${digests[0]}"
[[ "$(docker image inspect --format '{{.Id}}' "${digests[0]}")" == "$image_id" ]]
mkdir -p -- "$(dirname -- "$OUTPUT")"
temporary=$(mktemp "${OUTPUT}.XXXXXX")
trap 'rm -f -- "$temporary"' EXIT
printf 'SPARK_RUNTIME_IMAGE=%s\n' "${digests[0]}" > "$temporary"
chmod 0600 "$temporary"
mv -f -- "$temporary" "$OUTPUT"
trap - EXIT
