#!/usr/bin/env bash
# Builds, pushes and deploys the API to Cloud Run — docs/deploy.md Part 7.
#
#   ./deploy.sh              build, push and deploy IMAGE_TAG
#   ./deploy.sh --no-build   redeploy IMAGE_TAG as it is, e.g. after a settings change
#
# Every setting comes from three private files beside this script, never from
# the command line:
#   .env.deploy   project, region, domain, image tag, app behaviour
#   .env.neon     DB_URL, DB_USER            (DB_PASSWORD is not read here)
#   .env.r2       R2_ACCOUNT_ID, R2_ACCESS_KEY_ID  (the secret key is not read here)
#
# The service's environment is REPLACED on every run with exactly what this
# script sends, so these files are the one record of what is deployed. Change a
# setting here and rerun; a change made only in the Cloud Run console is undone
# by the next deploy. Every secret comes from ONE Secret Manager secret
# (SECRETS_BUNDLE in .env.deploy, default travel-planner-secrets), built by
# ./secrets-bundle.sh and mounted as a file Spring imports.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

build=true
case "${1:-}" in
  --no-build) build=false ;;
  "") ;;
  *) echo "usage: $0 [--no-build]" >&2; exit 64 ;;
esac

for f in .env.deploy .env.neon .env.r2; do
  [[ -f $f ]] || { echo "missing $f — see docs/deploy.md" >&2; exit 1; }
done
set -a
. ./.env.deploy
. ./.env.neon
. ./.env.r2
set +a

for v in PROJECT_ID REGION SERVICE IMAGE_TAG DOMAIN FEATURE_ENABLE_DATABASE PUBLISH_STORE \
         R2_BUCKET MAIL_MODE DB_URL DB_USER R2_ACCOUNT_ID R2_ACCESS_KEY_ID; do
  [[ -n ${!v:-} ]] || { echo "$v is not set" >&2; exit 1; }
done

image="$REGION-docker.pkg.dev/$PROJECT_ID/planner/planner-api:$IMAGE_TAG"

if $build; then
  echo "==> Building $image"
  docker build -t "$image" .
  echo "==> Pushing"
  gcloud auth configure-docker "$REGION-docker.pkg.dev" --quiet >/dev/null 2>&1
  docker push "$image"
fi

# The default compute service account runs the service and must read the
# secrets. Granting a role it already has changes nothing.
number=$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')
gcloud projects add-iam-policy-binding "$PROJECT_ID" \
  --member="serviceAccount:$number-compute@developer.gserviceaccount.com" \
  --role=roles/secretmanager.secretAccessor --condition=None --quiet >/dev/null

# A YAML file rather than --set-env-vars: DB_URL carries '?' and '=', and a
# file needs no escaping. Written to a private temp file and removed on exit.
envfile=$(mktemp)
trap 'rm -f "$envfile"' EXIT
yaml() { printf "%s: '%s'\n" "$1" "${2//\'/\'\'}"; }
{
  yaml FEATURE_ENABLE_DATABASE "$FEATURE_ENABLE_DATABASE"
  yaml DB_URL "$DB_URL"
  yaml DB_USER "$DB_USER"
  yaml COOKIE_SECURE true
  yaml SPRING_CONFIG_IMPORT file:/secrets/app.properties
  yaml PUBLIC_BASE_URL "https://$DOMAIN/p"
  yaml CORS_ORIGINS "https://$DOMAIN"
  yaml PUBLISH_STORE "$PUBLISH_STORE"
  yaml R2_ACCOUNT_ID "$R2_ACCOUNT_ID"
  yaml R2_BUCKET "$R2_BUCKET"
  yaml R2_ACCESS_KEY_ID "$R2_ACCESS_KEY_ID"
  yaml MAIL_MODE "$MAIL_MODE"
  # Optional: MAIL_EVENT_<NAME>='false' in .env.deploy switches that email off.
  # Unset means on. Names: docs/deploy.md, Part 11.
  for v in MAIL_EVENT_INVITED_NEW_MEMBER MAIL_EVENT_ADDED_EXISTING_MEMBER MAIL_EVENT_REMOVED_FROM_TRIP \
           MAIL_EVENT_PUBLISH_REQUESTED MAIL_EVENT_PUBLISH_APPROVED MAIL_EVENT_PUBLISH_REJECTED \
           MAIL_EVENT_TRIP_PUBLISHED MAIL_EVENT_PAYMENT_RECORDED; do
    if [[ -n ${!v:-} ]]; then yaml "$v" "${!v}"; fi
  done
  # Flight-lookup and circuit-breaker tunables are not environment settings: they
  # are plain values in application.yml and ship with the image. Only the two
  # flight keys reach the service, as secrets, below.
  # Only meaningful in smtp mode; the password comes from Secret Manager below.
  if [[ $MAIL_MODE == smtp ]]; then
    for v in SMTP_HOST SMTP_PORT SMTP_USER MAIL_FROM; do
      [[ -n ${!v:-} ]] || { echo "MAIL_MODE=smtp needs $v in .env.deploy" >&2; exit 1; }
    done
    yaml SMTP_HOST "$SMTP_HOST"
    yaml SMTP_PORT "$SMTP_PORT"
    yaml SMTP_USER "$SMTP_USER"
    yaml SMTP_AUTH true
    yaml SMTP_SSL "${SMTP_SSL:-false}"
    yaml SMTP_STARTTLS "${SMTP_STARTTLS:-false}"
    yaml MAIL_FROM "$MAIL_FROM"
  fi
} >"$envfile"

# Every secret travels in ONE Secret Manager secret, mounted as a properties
# file that Spring imports (SPRING_CONFIG_IMPORT above). One secret is one billed
# version instead of nine. The required keys are checked here so a missing one
# stops the deploy; an optional key left out keeps that feature off, as an
# unattached secret used to (flights say "unavailable", news is simply absent).
bundle="${SECRETS_BUNDLE:-travel-planner-secrets}"
gcloud secrets describe "$bundle" --project="$PROJECT_ID" >/dev/null 2>&1 \
  || { echo "secret $bundle not found: run ./secrets-bundle.sh --apply first" >&2; exit 1; }
keys=$(gcloud secrets versions access latest --secret="$bundle" --project="$PROJECT_ID" | sed -n 's/^\([A-Z0-9_]*\)=.*/\1/p')
need="JWT_SECRET PROXY_SECRET DB_PASSWORD R2_SECRET_ACCESS_KEY"
[[ $MAIL_MODE == smtp ]] && need+=" SMTP_PASSWORD"
for k in $need; do
  grep -qx "$k" <<<"$keys" || { echo "$bundle has no $k: run ./secrets-bundle.sh --apply" >&2; exit 1; }
done
for k in AERODATABOX_KEY AVIATIONSTACK_KEY NEWSDATA_KEY NEWSCURRENTS_KEY; do
  grep -qx "$k" <<<"$keys" || echo "note: $k is not in $bundle, that service stays off"
done
secrets="/secrets/app.properties=$bundle:latest"

echo "==> Deploying $SERVICE ($image)"
gcloud run deploy "$SERVICE" \
  --project="$PROJECT_ID" --region="$REGION" --image="$image" \
  --allow-unauthenticated \
  --min-instances=0 --max-instances=1 --cpu=1 --memory=1Gi --cpu-boost \
  --env-vars-file="$envfile" \
  --set-secrets="$secrets"

url=$(gcloud run services describe "$SERVICE" --project="$PROJECT_ID" --region="$REGION" --format='value(status.url)')
echo "==> $url"
echo -n "health: "; curl -s --max-time 60 "$url/actuator/health"; echo
echo -n "anything else without the proxy secret (expect 403): "
curl -s -o /dev/null -w '%{http_code}\n' --max-time 60 "$url/api/v1/auth/me"
