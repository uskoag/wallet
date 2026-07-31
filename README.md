# uskoag-wallet

The credential wallet for the `uskoag` Google tools. It holds every refresh token and client secret on
this machine and releases neither, ever. Client tools receive an opaque handle and a loopback URL, so a
CLI process never holds a Google credential at any moment.

Two binaries:

- `uskoag-wallet.exe` — the wallet itself. JavaFX, tray-resident. The only process that ever holds a
  refresh token, and the only thing that ever runs the OAuth consent flow.
- `uskoag-walletcli.exe` — everything the window can do, scriptable.

## Why it exists

An app-key on a command line is readable by any other process on Windows through
`Win32_Process.CommandLine`, and it lands in PowerShell history and in AI transcripts. That is the
disclosure path this replaces. The app-key becomes a *profile* — a name that selects a credential and
unlocks nothing — and the thing that actually unlocks is a passphrase typed once per boot into a window.

## How a tool reaches Google

There is exactly one route:

```
uskoag-gsheetscli  ──X-Wallet-Grant──▶  wallet proxy  ──Authorization: Bearer──▶  googleapis.com
                                             │
                                             ├─ classify method + path (+ batchUpdate body)
                                             ├─ check the standing rules
                                             ├─ ask a person if they cannot answer
                                             └─ record what was attempted, allowed or not
```

The handle is worthless anywhere except against this wallet, so a client that points itself at
googleapis.com gets a 401 rather than an unpoliced success. Policy is not something the client is
trusted to respect; it is the only way out.

## Three tiers

The line is reversible versus irreversible, not read versus write.

- **read** — list, search, get, download, export.
- **mutate** — create, update, append cells, draft mail, add slides. A wrong cell value is an
  annoyance, and Sheets has version history.
- **destructive** — delete, trash, move or re-parent, clear an unbounded range, and every permission
  change. Sharing is here even though revoking an ACL is technically a write, because un-sharing does
  not un-copy.

## Permissions

Browsing and search never prompt — they name no document, so there is nothing to approve. The first
touch of a specific document prompts once and the answer becomes a standing rule; this is what each
tool's own XML allow-list used to be, except one place now decides for every tool, expires them, and
audits them.

Destructive approvals are bounded by a **count before a clock**. A time window bounds a human's session
and does not bound a machine's: a runaway loop issues ten thousand operations inside a fifteen-minute
grant and every one is inside what was approved.

Each approval dialog shows a four-character correlation code that the asking client also printed to its
own stderr, so with several agent runs in flight "which one is asking" is answerable.

## Storage

One keyring file, `~/uskoag/wallet/keyring.bin`. AES-GCM under a PBKDF2 key from the passphrase, then
DPAPI-wrapped with that same passphrase as the optional-entropy parameter — so the file is inert on any
other machine *and* inert without the passphrase. DPAPI has no prompt, no PIN and no dialog; it is a
function call, and Windows Hello is a different mechanism that is not involved.

The audit lives separately in ArcadeDB. Timestamp, tool, operation, tier, verdict and counts are in the
clear so anomaly queries work; target paths, file names and peer command lines are encrypted per column,
because that set is a map of what this office is working on. Its key lives inside the keyring, which is
why changing the passphrase never re-encrypts history.

## Forgetting the passphrase

There is no back door, and there should not be. `reset` moves the keyring aside — never deletes it —
and starts a new one. Lost: the refresh tokens, the standing permissions, and the audit's detail
columns. Kept: every `credentials.json`, from a backup beside the keyring, so nothing has to be fetched
from the Cloud console again.

That backup is a development-phase trade with a real cost, on by default while the wallet is new: the
OAuth client secret sits on disk in the clear, which gives back half of what encrypting it bought. Set
`backupCredentialsJson = false` in `wallet.toml` and run `purgebackups` once things are settled.

## Getting started

```
uskoag-wallet                                  # set a passphrase (asked twice on first run)
uskoag-walletcli add you@org.org --profile gsheets --file credentials.json
uskoag-walletcli login you@org.org --profile gsheets
```

Already have tokens under an old app-key? Migrate instead of re-consenting:

```
uskoag-walletcli import --profile gsheets                 # prompts for the old app-key
uskoag-walletcli import --profile gsheets --delete-old    # once verified
```

After `--delete-old`, an app-key that leaked into a transcript protects nothing — a passphrase that
decrypts nothing is not a passphrase. That is cleaner than rotating, which would mean re-consenting
every account everywhere.

## The wallet is never mandatory

`gservices` stays a library that knows nothing about a wallet. It grows one interface,
`CredentialSource`, whose `AppKeyCredentialSource` is the default permanently. A wallet, when installed,
contributes its own implementation through `ServiceLoader` and is preferred. A shaded jar handed to
someone with no wallet works exactly as it does today.

Resolution order in every client, strictly: a running wallet, then the hidden console prompt, then a
dialog, then a clear failure naming what to start. It never silently degrades into a mode that puts a
key back into a log.

## Honest limits

If malware runs as your Windows user while you are logged in, it can do anything you can do. No
arrangement of keys, TPMs, daemons or hardware tokens prevents that. What this buys is the five things
good systems actually buy: the durable secret cannot be copied off the machine and used elsewhere,
irreversible uses need a fresh human act, every use is recorded, the blast radius of what automation
holds is bounded, and one action kills everything.

## Build

Its own reactor, deliberately not a module of `uskoag-gservices-parent`: a library must not depend on an
application. Order on a clean machine:

```
cd uskoag/gservices  && mvn -pl oauth,sheets,drive,slides,gmail install
cd uskoag/gservices/wallet && mvn install
cd uskoag/gservices  && mvn -pl spreadsheet_cli,gmail_cli install
```

The directory is self-contained — moving it to `C:\user\code\uskoag\wallet` as its own git repository
needs nothing but the move.
