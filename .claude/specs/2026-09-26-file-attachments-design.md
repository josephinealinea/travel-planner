# File attachments on itinerary plans: design spec

Status: DRAFT. Three open questions at the end must be answered before this is
approved. No code written. After approval, the next document is an
implementation plan in `.claude/plans/`.

## Goal

A member can attach a document (flight ticket, booking confirmation) to a plan
so it is at hand on their phone, and only the people it concerns can see it.

## Scope

In: upload, list, download and delete of image and PDF files on a plan, from a
phone or a desktop, in the planner.

Out (not v1): offline caching, email-in, ticket parsing, attachments on
published pages, attachments on anything but plans (see open question 2).

## Behaviour

- A plan's card and detail view list its attachments the member may see.
- Two add buttons: **Choose file** (`accept="image/*,application/pdf"`, so the
  phone offers library, Files and camera) and **Take photo**
  (`capture="environment"`, camera directly). `capture` is deliberately not on
  the first button: it would block PDFs and existing photos.
- Before upload the browser converts HEIC to JPEG and downscales large photos.
- A PDF opens in a new tab or downloads. It is never embedded in an iframe
  (unreliable on mobile).
- Limits: images and PDF only, about 10 MB per file, about 5 files per plan.
  Enforced on the server; the form checks first only to save a round trip.

## Permissions: the traveller's own data only

Enforced on the server. Hiding things in the page would be theatre.

- **Who sees and downloads a file:** the resolved travellers of the plan
  (`Travellers`: destination -> checklist item -> plan), plus the uploader.
  An empty resolved list means the whole trip, as everywhere else.
- **The attachment list is filtered per member**, like `budget.shares`: someone
  who is not a traveller does not learn that a file exists.
- **Download:** the API checks membership and traveller status, then returns an
  R2 URL that expires in about 60 seconds. Non-member -> 404 (trip ids cannot
  be probed); member who is not a traveller -> 403.
- **Access is resolved per request and never stored**, so removing a traveller
  from a plan revokes access at once, and re-adding them restores it.
- **Upload and delete:** any current traveller of the plan, plus the uploader.
- Owner: see open question 1.

## Data

- **Metadata** (id, file name, content type, size, uploader user id, plan id,
  created-at) is stored with the plan's per-trip data: YAML locally, Postgres in
  the cloud. It follows the audit rule: a user id, never a name or email.
  Shape (a field on `ItineraryItem` or its own entity): open question 3.
- **Bytes** are not in the database. `bytea` in Neon's small free tier would
  hurt storage, egress and backups.
- **Attach to the plan's own row, not each night of a stay.** `planId` is
  absent on the plan's row and set on the spread days; attachments follow the
  same rule as cost, so one booking is one set of documents.
  `adoptOrphanedDays` must carry attachments to the promoted row.

## Storage

- `AttachmentStore` interface in the same style as `PageStore`, with two
  implementations: filesystem (YAML mode, a directory beside `data/`) and R2.
- R2 bucket stays private. Key: `attachments/<trip>/<itemId>/<fileId>`, a
  third prefix beside `published/` and `pending/`.
- The Cloudflare `/p/*` Function never reads `attachments/`. Its route
  validation is untouched.
- **Upload path: presigned PUT straight to R2.** The API checks the member and
  the limits, records the metadata, and issues a short-lived upload URL; the
  browser sends the bytes directly. R2 CORS must allow the frontend origin.
  Considered and rejected: streaming through Cloud Run (32 MB request limit,
  cold starts, bandwidth) and links-only (no offline, links break).
- R2 free tier (10 GB, no egress fees) is ample for tickets.

## Deletion (the easy cascades to forget)

Orphaned files that keep serving are a privacy problem, the same class as an
orphaned published page.

- Deleting an attachment deletes its object.
- `DELETE /itinerary/{itemId}` (one day) and `/plan` (the plan and all days)
  delete the objects of every plan row removed.
- Deleting a trip deletes every attachment of the trip from the store, in
  `TripService.delete` beside `renderer.remove(slug)`. In Postgres the
  metadata goes by `ON DELETE CASCADE`; the objects do not, so the service
  must remove them.
- If the object delete fails, log and continue; do not leave the plan
  undeletable. A periodic sweep of unreferenced keys is a follow-up.

## Publishing

Independent of publish state: attachments work the same on a draft, pending or
live trip. A publish never includes them, on the trip page or any personal page
(`StaticSiteRenderer` builds `PublishedTrip` by hand and never names them).

## Hardening

Server-side content-type check (not the client's word), `X-Content-Type-Options:
nosniff`, `Content-Disposition`, unguessable file ids, R2 origin separate from
the app's, so a hostile file cannot run in the app's origin.

## Other rules to follow

- The importer copies and verifies the metadata; it does not move files, and
  the report lists trips with attachments to re-upload.
- Every-field round-trip tests fail until the new fields are mapped.
- All new text in message files (`i18n/en.js`, `messages_en.properties`), no
  prose in `ApiException`.
- New dialogs carry `x-dialog` and `aria-labelledby`; new detail surfaces carry
  `panel-switchable`.

## Testing

- Permission matrix: non-member 404; member non-traveller 403 on download and
  absent from the list; traveller and uploader allowed; whole-trip plan visible
  to all; removing a traveller revokes; per-buddy plan hidden from others.
- Store contract test run against filesystem and R2 (MinIO Testcontainers, as
  the R2 page store does). Check `skipped` is 0.
- Deletion: plan delete, single-day delete and trip delete leave no objects.
- `noAttachmentReachesAPublishedFile`: search every rendered file, including a
  personal page, for the file name and id.
- Limits and type check reject an oversized file and a renamed executable.
- Frontend: check the two buttons on a real phone width (iframe method in
  CLAUDE.md Traps), including HEIC.

## Open questions

1. Does the trip owner always see every attachment, or only if they are a
   traveller on the plan?
2. Plans only, or checklist items too?
3. Metadata as a field on `ItineraryItem`, or a seventh per-trip entity? My
   lean: a field, unless attachments later attach to other record types.

## Later

Offline via a service worker; email-in; ticket parsing; an orphan sweep.
