# Email (SMTP)

`app.mail.mode` = `log` | `file` | `smtp`; only `smtp` contacts a server (10 s
timeouts, sent inside the request). Each kind can be switched off with
`app.mail-events.enabled.<event>`: `invited-new-member`, `added-existing-member`,
`removed-from-trip`, `publish-requested`, `publish-approved`, `publish-rejected`,
`trip-published`, `payment-recorded`. One email per event per recipient;
never switch off `invited-new-member`, which carries the temporary password.


Back to the [overview](README.md).
