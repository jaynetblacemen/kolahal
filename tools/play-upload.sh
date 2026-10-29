#!/usr/bin/env bash
# Uploads the Play bundle to Google Play as a release on a testing track, with the version's Play
# notes as its "What's new": a draft to roll out in Play Console, or, with PLAY_SEND_FOR_REVIEW,
# rolled out at once, which sends it for Google's review; testers get it once approved (with
# managed publishing on, once it is also published in Play Console). The Release workflow runs it
# after publishing the GitHub release.
#
#   tools/play-upload.sh <bundle.aab> <notes.txt> <version>
#
# No key: it signs in to Google with the run's GitHub identity token (the workflow needs
# `id-token: write`), through Workload Identity Federation, as a service account that Play
# Console lets release Kolahal to testing tracks. docs/release/google-play.org says how that is
# set up. Environment:
#   PLAY_WIF_PROVIDER   projects/<number>/locations/global/workloadIdentityPools/<pool>/providers/<id>
#   PLAY_SERVICE_ACCOUNT  the service account's email
#   PLAY_TRACK          the track (default: alpha, Play's first closed testing track)
#   PLAY_SEND_FOR_REVIEW  true: roll it out and send it for review; otherwise a draft
set -euo pipefail

bundle=$1 notes=$2 version=$3
package=com.mtwabpin.kolahal
track=${PLAY_TRACK:-alpha}
# "completed" is Play's name for rolled out to the whole track; committing it sends it for review.
if [ "${PLAY_SEND_FOR_REVIEW:-}" = true ]; then status=completed; else status=draft; fi
api=https://androidpublisher.googleapis.com/androidpublisher/v3/applications/$package
: "${PLAY_WIF_PROVIDER:?}" "${PLAY_SERVICE_ACCOUNT:?}" "${ACTIONS_ID_TOKEN_REQUEST_URL:?needs id-token: write}"

# Play's limit for "What's new"; a longer text would fail only at the end.
if [ "$(wc -m < "$notes")" -gt 500 ]; then echo "::error::$notes is over Play's 500 characters."; exit 1; fi

# GitHub's token for this run, for Google: the provider checks the repository, the tag and the workflow.
github=$(curl -sSf -H "Authorization: Bearer $ACTIONS_ID_TOKEN_REQUEST_TOKEN" \
  "$ACTIONS_ID_TOKEN_REQUEST_URL&audience=https://iam.googleapis.com/$PLAY_WIF_PROVIDER" | jq -r .value)
echo "::add-mask::$github"
federated=$(jq -n --arg t "$github" --arg a "//iam.googleapis.com/$PLAY_WIF_PROVIDER" '{
  audience: $a, subjectToken: $t,
  grantType: "urn:ietf:params:oauth:grant-type:token-exchange",
  requestedTokenType: "urn:ietf:params:oauth:token-type:access_token",
  subjectTokenType: "urn:ietf:params:oauth:token-type:jwt",
  scope: "https://www.googleapis.com/auth/cloud-platform"}' |
  curl -sS --fail-with-body -H 'Content-Type: application/json' -d @- https://sts.googleapis.com/v1/token | jq -r .access_token)
echo "::add-mask::$federated"
# The service account's own token, for the Play API only, for half an hour.
token=$(curl -sS --fail-with-body -H "Authorization: Bearer $federated" -H 'Content-Type: application/json' \
  -d '{"scope": ["https://www.googleapis.com/auth/androidpublisher"], "lifetime": "1800s"}' \
  "https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/$PLAY_SERVICE_ACCOUNT:generateAccessToken" |
  jq -r .accessToken)
echo "::add-mask::$token"

play() { curl -sS --fail-with-body -H "Authorization: Bearer $token" "$@"; }

# One edit holds every change and applies them together on commit; an edit left open expires.
edit=$(play -X POST "$api/edits" | jq -r .id)
tracks=$(play "$api/edits/$edit/tracks" | jq -r '.tracks[].track')
if ! grep -qx "$track" <<<"$tracks"; then
  echo "::error::Play has no track '$track'; it has: $(tr '\n' ' ' <<<"$tracks")(set the PLAY_TRACK variable)."
  exit 1
fi
language=$(play "$api/edits/$edit/details" | jq -r .defaultLanguage)

# The bundle carries its R8 mapping and native symbols, so Play needs nothing else.
code=$(play -H 'Content-Type: application/octet-stream' --data-binary "@$bundle" \
  "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/$package/edits/$edit/bundles?uploadType=media" |
  jq -r .versionCode)

jq -n --arg track "$track" --arg name "$version" --arg code "$code" --arg status "$status" --arg language "$language" --rawfile text "$notes" '{
  track: $track,
  releases: [{name: $name, versionCodes: [$code], status: $status,
              releaseNotes: [{language: $language, text: ($text | rtrimstr("\n"))}]}]}' |
  play -X PUT -H 'Content-Type: application/json' -d @- "$api/edits/$edit/tracks/$track" > /dev/null
# Play refuses to send changes for review on its own while something else waits in Play Console
# (a rejected update, say): its answer says so, and the release goes up by hand that time.
play -X POST "$api/edits/$edit:commit" > /dev/null

if [ "$status" = completed ]; then
  result="sent for review on Google Play's $track track: testers get it once Google approves it"
else
  result="a draft on Google Play's $track track: Play Console → Test and release → the track → roll it out"
fi
echo "Kolahal $version ($code) is $result. Notes in $language."
{
  echo "### Google Play"
  echo "- $version ($code) is $result."
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
