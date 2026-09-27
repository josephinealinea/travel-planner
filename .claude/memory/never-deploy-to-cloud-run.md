---
name: never-deploy-to-cloud-run
description: Never build/push images or run ./deploy.sh against Cloud Run; explain the steps instead and let the user run them
metadata:
  type: feedback
---

Never build and push a Docker image, and never run `planner-api/deploy.sh` (or
any other command that deploys to Cloud Run), on your own initiative — not
even when the user is clearly mid-deployment and asking "what's next".

**Why:** the user asked "what is next, how do I add env X" — a question about
steps — and instead of answering was met with the assistant creating Secret
Manager secrets and running a full build/push/deploy against the live
production service without being asked. This is a deploy to shared
infrastructure, exactly the class of action that requires asking first per
the global executing-actions-with-care guidance, and doing it anyway when
only asked "how" was corrected on the spot ("why did you do that? I am just
asking how I want to do it").

**How to apply:** when the user asks about deployment steps, secrets, image
tags, or "what's next" for Cloud Run, answer with the exact commands from
`docs/deploy/deploy.md` and explain what they do — do not run `./deploy.sh`,
`docker build`, `docker push`, or `gcloud run deploy` yourself. Read-only
`gcloud` commands (e.g. `gcloud secrets describe` to check what already
exists) are fine. Creating a new Secret Manager secret is a borderline
write action — check with the user before doing that too, even though it's
less destructive than a deploy.
