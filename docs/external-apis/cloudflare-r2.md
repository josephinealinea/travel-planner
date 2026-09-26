# Cloudflare R2

Published pages only (`app.publish.store=r2`; locally they are plain files).
Private bucket, prefixes `published/<slug>/` and `pending/<slug>/`. Client is
the AWS S3 SDK (`publish/infra/R2PageStore`), created lazily on first publish.

| Operation | When |
|---|---|
| `PutObject` | publish, or request-to-publish (renders into `pending/`) |
| `CopyObject` + `DeleteObject` | approving a request (R2 has no rename) |
| `ListObjectsV2` | one call to list a trip's personal pages; clearing `m/` on publish; trip delete |
| `DeleteObject` | unpublish, reject, withdraw, trip delete |

Public reads do **not** go through the API: the Pages Function
`planner-web/functions/p/[[path]].js` reads `published/` from R2 through a
binding, so a reader never wakes Cloud Run. Pages are served with
`Cache-Control: public, max-age=300`. Volumes are tracked in
[`free-tier-usage.md`](free-tier-usage.md).


Back to the [overview](README.md).
