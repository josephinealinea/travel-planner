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
# by the next deploy. Secrets are referenced from Secret Manager by name.
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
  yaml PUBLIC_BASE_URL "https://$DOMAIN/p"
  yaml CORS_ORIGINS "https://$DOMAIN"
  yaml PUBLISH_STORE "$PUBLISH_STORE"
  yaml R2_ACCOUNT_ID "$R2_ACCOUNT_ID"
  yaml R2_BUCKET "$R2_BUCKET"
  yaml R2_ACCESS_KEY_ID "$R2_ACCESS_KEY_ID"
  yaml MAIL_MODE "$MAIL_MODE"
} >"$envfile"

echo "==> Deploying $SERVICE ($image)"
gcloud run deploy "$SERVICE" \
  --project="$PROJECT_ID" --region="$REGION" --image="$image" \
  --allow-unauthenticated \
  --min-instances=0 --max-instances=1 --cpu=1 --memory=1Gi --cpu-boost \
  --env-vars-file="$envfile" \
  --set-secrets=JWT_SECRET=jwt-secret:latest,DB_PASSWORD=db-password:latest,R2_SECRET_ACCESS_KEY=r2-secret-access-key:latest,PROXY_SECRET=proxy-secret:latest

url=$(gcloud run services describe "$SERVICE" --project="$PROJECT_ID" --region="$REGION" --format='value(status.url)')
echo "==> $url"
echo -n "health: "; curl -s --max-time 60 "$url/actuator/health"; echo
echo -n "anything else without the proxy secret (expect 403): "
curl -s -o /dev/null -w '%{http_code}\n' --max-time 60 "$url/api/v1/auth/me"
