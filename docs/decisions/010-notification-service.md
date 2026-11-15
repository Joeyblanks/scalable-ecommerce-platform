# 010. Notification service: where the email address comes from, and never emailing twice

- Status: Accepted
- Date: 2026-11-15

## Context

notification-service sends the customer an email for three events: `OrderPlaced` (order received),
`OrderPaid` (payment confirmed) and `OrderCancelled` (cancelled, with the reason). Two questions shaped the
design:

1. The events carry only `userId`. Where does the email address come from?
2. RabbitMQ delivers at least once, and sending an email cannot be undone or made part of a database
   transaction. How do we avoid sending the same email twice, without ever losing one?

## Decisions

### 1. The address is asked from user-service when the email is sent

| Option | Verdict |
|---|---|
| **A. Put the email in the events** | No. It copies personal data into every event and into every message log and dead-letter queue, it goes stale when the customer changes address, and order-service does not have the address anyway. |
| **B. Ask user-service at send time** | **Chosen.** One source of truth, always current, nothing personal stored or published, no change to any event contract. |
| **C. Keep a local copy fed by `UserRegistered` events** | No, for now. user-service has no broker or outbox, so this is a much bigger change, and it would still copy personal data and need a way to follow changes. |

How B works:

- user-service has one internal endpoint, `GET /api/internal/users/{id}/contact`, returning `userId`, `email`
  and `fullName` and nothing else. Only a service token with the scope `users:read-contact` and the audience
  `user-service` is accepted (ADR 008, amendment). Customer and admin tokens are refused.
- notification-service gets its own token from user-service (OAuth2 client credentials, client
  `notification-service`, scope requested explicitly) and caches it until 30 seconds before it expires, so the
  token endpoint is called every few minutes, not once per email.
- The address is **never stored**: the `notifications` table holds the event as received (user id, order
  details) and nothing else about the person. It is also never logged (`Contact.toString` hides it).
- The cost is a runtime dependency: if user-service is down, the email waits and is retried (see section 4).
  Nothing is lost, because the notification is already recorded durably when the event arrives.
- A user that does not exist (any more) is a permanent failure: the notification becomes `FAILED` at once,
  with an ERROR log, and is not retried.

### 2. Receiving an event only records it; a worker sends

The listener inserts one row into `notifications` and acknowledges. It sends nothing and calls nobody. So a slow
mail server or user-service can never block or back up the queue, and a message is acknowledged only after its
row is committed.

The three events are independent, so their order does not matter (an `OrderCancelled` that arrives before its
`OrderPlaced` simply produces its email first).

### 3. Never the same email twice: four layers

1. **Unique `event_id`.** A redelivery, a duplicate, or 16 consumers handling the same event at once record
   exactly one row (`INSERT ... ON CONFLICT DO NOTHING`).
2. **Claim with a lease.** A worker claims due rows in one statement (`FOR UPDATE SKIP LOCKED`, sets a 2-minute
   lease and counts the attempt). Another worker cannot take a row whose lease is valid. If a worker dies, the
   lease expires and another takes over.
3. **Conditional completion.** `SENT`, retry and `FAILED` are all `UPDATE ... WHERE status = 'PENDING'`, so a
   finished row never changes again.
4. **A stable `Message-ID`**, derived from the event id (`<eventId@notification-service>`). It does not prevent
   a duplicate, but if one ever happens it is recognisable as the same message.

No database transaction is open while talking to user-service or the mail server.

**The limit, stated plainly.** SMTP has no idempotency key, and neither does any email API we know of. The row is
marked `SENT` *after* the mail server accepted the message. If the service crashes (or the connection drops)
between "accepted" and "marked", the lease expires and the email is sent once more. We chose that rare duplicate
over the alternative, marking `SENT` first, which would lose an email whenever the send fails. A duplicate is an
annoyance; a lost "your order was cancelled" email is a worse failure. A test documents this case.

### 4. Failures: retries, then a visible dead end

- A failed attempt (user-service down, token endpoint down, mail server down or refusing) is retried with
  backoff (1 s, doubling, up to 1 minute) until **10 attempts or 1 hour since the event**, whichever comes
  first. Then the row becomes `FAILED`, an ERROR is logged, and it is never retried again. `FAILED` rows are
  never deleted, so they are the record a person (or a later tool) looks at.
- A **malformed or unknown message** is dead-lettered at once (`notification-service.order-events.dlq` via
  `ecommerce.events.dlx`). One whose handler keeps failing (for example the database is down) is retried a few
  times with backoff by the listener, then dead-lettered, never retried forever.
- The service's health does not depend on the mail server: an outage delays emails, it does not take the
  service down.

### 5. The email sender is an interface

`EmailSender.send(Email)` throws on any failure. The default is `SmtpEmailSender`, pointing at **Mailpit** in
Docker Compose: a local mail catcher that accepts every message and shows it at http://localhost:8025, so the
demo needs no account and sends nothing to the outside world. Each email is `multipart/alternative` (plain text
and HTML).

An optional **SendGrid** sender is used only when `SENDGRID_API_KEY` is set (keys are never committed); see the
amendment at the end.

### 6. The emails

| Event | Subject | Content |
|---|---|---|
| `OrderPlaced` | We received your order #ABCD1234 | items, quantities, line totals, total |
| `OrderPaid` | Payment confirmed for order #ABCD1234 | confirmation and time; no amount (the event does not carry one, and events may arrive in any order) |
| `OrderCancelled` | Your order #ABCD1234 was cancelled | the reason in plain language, items released, refund note |

The order number is the first 8 characters of the order id. Reasons: `CUSTOMER_REQUEST` (you asked us to cancel),
`PAYMENT_FAILED` (we could not complete your payment), `PAYMENT_TIMEOUT` (we did not receive your payment in
time); a reason code added later gets a safe generic sentence instead of leaking the code. English only.

Anything that comes from outside (the customer's name, product names) is HTML-escaped in the HTML part and
stripped of line breaks in the text part, and **never appears in the subject**. The recipient is parsed as a
single strict address, so a stored name or address cannot add headers or recipients.

### 7. Retention

`SENT` rows are deleted after 30 days. The row is also what prevents a second email for its event, so the period
must outlive any redelivery or dead-letter replay (the same reasoning and value as `processed_events` in ADR 007
and 009). `PENDING` and `FAILED` rows are never deleted.

## Failure cases

| What goes wrong | What happens |
|---|---|
| Duplicate or redelivered event | Same `event_id`: nothing new is recorded |
| Many consumers get the same event at once | One row, one email |
| Two workers | One claims the row; the other sees the lease |
| Worker crashes before sending | The lease expires; another worker sends it |
| Crash after the mail server accepted, before `SENT` | Sent again once, same Message-ID (accepted limit, section 3) |
| user-service or its token endpoint down | Retried with backoff; the email is late, not lost |
| Mail server down or refusing | Retried; after 10 attempts / 1 hour `FAILED`, logged at ERROR |
| User no longer exists | `FAILED` at once (nothing to retry) |
| Customer changes address between event and send | The new address is used (looked up at send time) |
| Malformed message | Dead-lettered |
| `OrderCancelled` before `OrderPlaced` | Independent emails, each sent |

## Consequences

Positive:
- Personal data lives in one place. No copy to keep in sync or to leak from a queue.
- The consumer is fast and cannot be blocked by a slow dependency.
- Demo and CI need no external account.

Negative:
- user-service is needed at send time (softened by retries and the token cache).
- A rare duplicate is possible after a crash in the window of section 3.
- A notification that is `FAILED` is only visible in the database and the logs; there is no tool to resend one
  yet, nor to replay dead letters.
- One language, no per-user preferences or unsubscribe yet.

Not done yet: templates per language, a resend tool, and email preferences.

## How each protection was proven

Each protection was switched off on its own (one change at a time), the full suite was run, and the source was
restored afterwards and checked identical. Every one made at least one test fail:

| Protection removed | Tests that failed |
|---|---|
| unique `event_id` | 2 (16 concurrent deliveries; redelivery after sending) |
| claim lease | 2 (crashed worker takeover, competing workers) |
| `SKIP LOCKED` | 1 (competing workers) |
| conditional completion (`WHERE status = 'PENDING'`) | 1 |
| retrying a failed attempt (treating it as final) | 5 (user-service down, mail server failing, lost response, ...) |
| unknown user is permanent (not retried) | 1 |
| maximum attempts | 1 |
| give-up time | 1 |
| mark `SENT` only after the send succeeded | 4 |
| HTML escaping of customer text | 1 |
| stripping line breaks from customer text | 1 |
| stable `Message-ID` | 1 |
| token requested with the scope | 1 |
| token cache | 2 |
| 404 means the user is gone | 1 |
| a rejected token is dropped | 1 |
| retention deletes only `SENT` rows | 1 |
| a contact without an address is not sent to | 1 |

Two of these first survived and exposed weak tests (retention: the old rows had no `sent_at`; the blank-address
guard had no test), which were fixed and the mutations re-run. Honest limits: the "delivered but the response was
lost" test documents the accepted duplicate rather than preventing it (section 3); SMTP is tested against Mailpit,
a real SMTP server, but not against a provider that rejects recipients; user-service is played by a stub that
speaks its wire format, and the real endpoint is covered by user-service's own tests (ADR 008, amendment).

## Amendment (2026-11-15): the SendGrid sender

`SendGridEmailSender` is the second implementation of `EmailSender`.

- **Selection.** `EMAIL_PROVIDER` is `auto` (default), `smtp` or `sendgrid`. `auto` uses SendGrid when
  `SENDGRID_API_KEY` is set and SMTP (Mailpit in Compose) otherwise. `smtp` ignores a key; `sendgrid` without a
  key stops startup. Exactly one sender is active. The key comes only from the environment (compose passes
  `${SENDGRID_API_KEY:-}` from the gitignored `.env`) and the settings object hides it in `toString`.
- **Key check.** A key that does not start with `SG.` (or is too short, or has whitespace) stops startup, and the
  message does not contain it. SendGrid has no separate test-mode keys, so this guard only catches the wrong kind
  of secret (a Stripe key, a typo), not a live key used by mistake.
- **Real mail versus sandbox.** Unlike Mailpit and Stripe test mode, a SendGrid key means emails are **really
  delivered** to customers' addresses. The startup log says so. `SENDGRID_SANDBOX=true` adds SendGrid's
  `mail_settings.sandbox_mode`, so SendGrid validates every request and delivers nothing; that is the way to try the
  integration. `MAIL_FROM` must be a sender verified in the SendGrid account.
- **The call.** One JSON `POST /v3/mail/send` with the plain-text and HTML parts, no SDK, so there are no retries
  behind our back (a test asserts one request per attempt). Only 202 Accepted (200 in sandbox mode) counts as sent:
  every other answer, including a redirect or a 204, and any network trouble, throws, and the worker retries
  with backoff under its lease exactly as for SMTP. Error messages contain the status code only: SendGrid's error body
  can echo the recipient's address.
- **Identity of an email.** SendGrid creates the Message-ID itself, so unlike SMTP it cannot be set. Our stable id
  (derived from the event id) is sent as the `X-Notification-Id` header and as a custom argument, which appear in
  SendGrid's activity and webhooks. SendGrid has no idempotency key either, so the limit in section 3 is unchanged.

How it was checked, and the limits:

- A local stub speaking the Mail Send API covers the request (key, both parts, our id, sandbox flag), every kind of
  answer, and unreachable SendGrid. An integration test runs the whole flow with SendGrid selected (event, user
  lookup, request) and a SendGrid outage that recovers: two 503 answers, then one email accepted, 3 attempts.
- **Not verified against SendGrid itself**, which needs an account, a key and a verified sender that this repository and
  CI do not have. The README has a smoke check. To confirm there: that the verified-sender requirement and the
  sandbox answer (200) behave as documented, and how the `X-Notification-Id` header and custom argument show up.
- Mutations (each made at least one test fail; source restored identical): key shape (3 tests), authorization header
  (1), sandbox flag (1), swallowing errors (3), echoing the response body (1), echoing the key (2), both senders active
  at once (1), our id header (1), HTML part (1), accepting any non-error status (1).
