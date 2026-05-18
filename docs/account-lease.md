# Account Lease And Protection Policy

Naver accounts are managed by the server. Android devices must not store raw account passwords in app preferences, logs, strategy files, or source code.

## Account Table

Suggested table: `naver_accounts`

```text
id
alias
encrypted_login_id
encrypted_password
status
assigned_device_name
group_id
last_used_at
last_login_at
last_success_at
fail_count
protection_detected_at
cooldown_until
memo
created_at
updated_at
```

## Status Values

```text
available
leased
cooldown
protected
invalid
manual_check_required
disabled
```

## Lease Rules

- A leased account belongs to exactly one device until released or expired.
- The server, not the Android app, decrypts account credentials.
- The app should receive only the account currently leased to it.
- A protected or manual-check account must never be leased automatically.
- Prefer sticky assignment: exact `assigned_device_name` first, then the device's
  `group_id` pool, then an unassigned global account.
- Apply cooldown after failures and after high-frequency use.

This means a bot named `z1-1` should receive the account assigned to `z1-1` when one
exists. That is the main production path for per-device Naver ID/password assignment.

## API Contract

### `POST /android/accounts/lease`

Request:

```json
{
  "deviceName": "z1-1",
  "role": "soldier",
  "strategy": "A",
  "appVersion": "0.1.0"
}
```

Response:

```json
{
  "leaseId": "lease_123",
  "accountAlias": "naver_a",
  "loginId": "decrypted-id-for-current-lease",
  "password": "decrypted-password-for-current-lease",
  "expiresAt": "2026-05-03T12:00:00Z"
}
```

If no account is available, return `{}`. The current Android app then falls back to the
on-device Naver ID/password fields only for manual or local smoke testing.

### `POST /android/cookies/load`

Request:

```json
{
  "deviceName": "z1-1",
  "accountAlias": "naver_a"
}
```

Response:

```json
{
  "cookies": "NID_AUT=...; NID_SES=...",
  "accountAlias": "naver_a",
  "savedAt": "2026-05-03T12:00:00Z"
}
```

### `POST /android/cookies/save`

Request:

```json
{
  "deviceName": "z1-1",
  "accountAlias": "naver_a",
  "cookies": "NID_AUT=...; NID_SES=..."
}
```

Cookies are scoped by `deviceName + accountAlias`. When `accountAlias` changes,
Android must not reuse cookies saved for the previous account. A blank
`accountAlias` is reserved for manual/local smoke credentials.

### `POST /android/accounts/report`

Request:

```json
{
  "leaseId": "lease_123",
  "deviceName": "z1-1",
  "result": "protected",
  "signals": ["PROTECTION_TEXT", "LOGIN_STILL_REQUIRED"],
  "lastUrl": "https://nid.naver.com/...",
  "message": "manual verification required"
}
```

### `POST /android/accounts/release`

Request:

```json
{
  "leaseId": "lease_123",
  "deviceName": "z1-1",
  "reason": "task_complete"
}
```

## Protection Handling

Protection detection is a stop signal, not an automatic bypass trigger.

When protection is detected:

1. Stop the current account's work.
2. Report `protected`.
3. Mark the account as `protected` or `manual_check_required`.
4. Do not lease the account again until a human clears it.

## Detection Signals

```text
PROTECTION_TEXT
CAPTCHA_OR_SECURITY_PAGE
PHONE_VERIFICATION_REQUIRED
EMAIL_VERIFICATION_REQUIRED
PASSWORD_RETRY_REQUIRED
LOGIN_STILL_REQUIRED
SESSION_EXPIRED
UNEXPECTED_LOGOUT
SAMSUNG_INTERNET_MISSING
```

## Device Policy

Boss:

- Prefer stable accounts.
- Own group IP rotation.
- Longer cooldown.

Soldier:

- Prefer one account per device.
- Do not rotate IP locally when connected to boss hotspot.
- Stop immediately on protection signals.
