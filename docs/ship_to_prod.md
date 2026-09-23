## Shipping a new version to Production
The API goes first, then the website. The new website expects the mine and travellers fields that only the new API sends. The old website works fine against the new API, because it ignores extra fields. So in   
that order, nothing breaks at any point.

Each command uses the full folder path, so it works whatever directory your terminal is in.

Before you start (recommended)    

Commit your changes first, so the deployed version matches something in git. Then check that the three tools are ready:

#### Check Docker is running (needed to build the image)
```bash
docker info --format 'docker {{.ServerVersion}}'                                                                                                                                                                 
```

#### Check you're signed in to Google Cloud
```bash
gcloud config get-value account                                                                                                                                                                                  
```

#### Check you're signed in to Cloudflare
```bash
cd "/Users/joeydevivre/Documents/GeekPOC/Personal Site/travel-planner/planner-web" && npx wrangler@4 whoami
```

### Step 1: set the new image tag

The live API is v1. The new build becomes v2, and the registry keeps both, so v1 stays available for a rollback.

#### Show the current tag    
```bash
grep IMAGE_TAG "/Users/joeydevivre/Documents/GeekPOC/Personal Site/travel-planner/planner-api/.env.deploy"                                                                                                       
```

#### Change it to v2
```bash
sed -i '' "s/^IMAGE_TAG=.*/IMAGE_TAG='v2'/" "/Users/joeydevivre/Documents/GeekPOC/Personal Site/travel-planner/planner-api/.env.deploy" && grep IMAGE_TAG "/Users/joeydevivre/Documents/GeekPOC/Personal         
Site/travel-planner/planner-api/.env.deploy"
```

### Step 2: (BE) build and deploy the API (5–10 minutes)

This carries all the API-side changes.

When the new API starts, Flyway applies migration V2 to Neon. 

#### Build, push and deploy

```bash
cd "/Users/joeydevivre/Documents/GeekPOC/Personal Site/travel-planner/planner-api" && ./deploy.sh
```
It should end with health: {"status":"UP"} and ... (expect 403): 403.

#### Confirm migration V2 ran on Neon 
```bash
gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="planner-api" AND textPayload:"schema"' --project=travellingllama --limit=5 --freshness=20m                             
--format='value(textPayload)'
```
Look for Migrating schema "public" to version "2 - travellers" or Current version of schema "public": 2.

### Step 3: (FE) build and deploy the website

#### Build the site
```bash
cd "/Users/joeydevivre/Documents/GeekPOC/Personal Site/travel-planner/planner-web" && npm ci && npm run build                                                                                                    
```
#### Upload it
```bash
cd "/Users/joeydevivre/Documents/GeekPOC/Personal Site/travel-planner/planner-web" && npx wrangler@4 pages deploy --branch=main
```

### Step 4: check it's live

#### Check the site carries the new brand
```bash
curl -s https://travellingllama.fun/login | grep -o 'Travelling Llama' | head -1                                                                                                                                 
```

#### Check the API answers through the proxy (expect 401: not signed in
```bash
curl -s -o /dev/null -w '%{http_code}\n' https://travellingllama.fun/api/v1/auth/me                                                                                                                              
```
#### Check the trip bundle now carries "mine" (run after signing in in the browser; this just confirms the new API is live)
```bash
curl -s https://travellingllama.fun/api/v1/trips -o /dev/null -w '%{http_code}\n'
```
That last one should also give 401 from the terminal, since it has no login. The real check is in the browser: open a trip.

Step 5: Test
```bash
https://travellingllama.fun
```