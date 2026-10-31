# Changelog

The version is set in `gradle.properties` (`imab.versionName`, `imab.versionCode`) and shown at the
bottom of Settings. Versions before 1.4.0 were assigned afterwards, grouping the commits by when
they were made; the builds themselves said 1.0.0.

## 1.6.0 — 2026-10-31

- **Encryption.** Settings → Encryption sets a passphrase that encrypts the budget files: the sync
  file, each device's own copy, archives, and conflict copies the cloud service left next to the
  sync file (AES-256-GCM; key from the passphrase with PBKDF2-HMAC-SHA256, 600,000 rounds). Each
  device asks for the passphrase once and keeps the key in the Mac Keychain, the Android Keystore or
  Windows data protection. Changing it on one device makes the others ask for the new one. A device
  that can't read the file never overwrites it. A banner suggests setting one until you do.
- **No full account numbers.** Bank files carry the full account or card number; IMAB now keeps
  only the bank's id and the last 4 digits to recognize the account. Numbers saved by earlier
  versions are replaced when the app opens.

## 1.5.1 — 2026-10-31

Security update. Every library in the app and the build tools was checked against the OSV database
(CVEs and GitHub advisories); none now has a known vulnerability.

- Kotlin 2.1.0 → 2.4.21 (fixes CVE-2026-53914 in the Kotlin Gradle plugin), Compose Multiplatform
  1.7.3 → 1.11.1, Android Gradle plugin 8.7.3 → 8.13.2, Gradle 8.11.1 → 8.14.6 (download checksum
  pinned), kotlinx-datetime 0.6.1 → 0.7.1.
- Build tools' libraries pinned to fixed releases: Bouncy Castle 1.86, Netty 4.1.139, protobuf
  3.25.9, Guava 33.7.2, commons-io 2.22.0, commons-compress 1.28.0, jose4j 0.9.7, JDOM 2.0.6.1.
- The Mac app bundles Java 17.0.21 (October 2026 security update).
- Android: the budget is excluded from cloud backup and device transfer (it's restored from the
  sync file instead).
- Release workflow: dependency audit before building, actions pinned to commit SHAs, read-only
  permissions except publishing, Gradle wrapper validation, and no caches in release builds.

## 1.5.0 — 2026-10-31

- Windows version (an `.msi` installer). It keeps its data in `%APPDATA%\IMAB` and finds the OneDrive,
  Dropbox and Google Drive folders for the sync file.
- Installers for Android, Mac (Apple Silicon and Intel) and Windows are published on GitHub
  Releases for each version.
- Android release builds are signed with a release key.
- The Mac app no longer asks to find devices on the local network (it looked up the computer's
  name over the network; it now reads it locally).

## 1.4.1 — 2026-10-27

- Settings → Payees shows the 5 most-used payees under the search bar; *Show all* lists them a page
  at a time (10, 25 or 50 per page).

## 1.4.0 — 2026-10-27

- Dragging a bank file onto the Mac window works (it had never received the drop).
- Import: **Add transaction** and **Merge with what you entered** act straight away and take the row
  off the list; merged transactions are labelled **Merged** in the account.
- Calculations in assign amounts (`120 + 5`, or `+5` to add to what's assigned).
- Credit card rows: Assigned includes money moved in from card spending, Activity is payments, and
  Available is what's set aside minus what's owed (negative when short). What isn't set aside is
  split into starting debt and overspending.
- The payee is chosen in a pop-up with search and *+ Add as a new payee*, so the phone keyboard no
  longer covers it.
- *+ New category* in every category list, and *New category in this group…* in a category's menu.
- The Budget tab has a dollar icon.

## 1.3.0 — 2026-10-26

- Reconcile accounts against the bank's balance using cleared transactions, with an adjustment if
  needed; reconciled transactions are locked.
- Every account appears in the transfer list; long lists open as a searchable dialog.
- Transfers are always categorized as Transfer; no second category to choose.

## 1.2.0 — 2026-10-19 to 2026-10-23

- Payee suggestions while typing, and renaming or merging payees in Settings.
- Click an Available amount to cover overspending or move money; add transactions from the Budget screen.
- Show what's owed on credit cards and loans.
- Move money with explicit From and To, a month warning and undo; then as its own tab.
- Fresh start, start from scratch, and restore from archive.
- Assigning takes money from Ready to Assign by default; click Ready to Assign to assign to many categories.
- Reports: spending, income, recurring payments, and debt to income.

## 1.1.0 — 2026-10-19

- Drag-and-drop import, a date window for the first import, and a duplicate alert.
- Merge hand-entered transactions with their bank copies.
- Imports come in uncategorized unless an auto-categorize rule matches; loan payments split into
  principal, interest and escrow.
- Goal types (savings, monthly spending), the assign cap, fund goals, and goals across categories.
- Loans are tracked outside the budget; a Mortgage account type; budget vs tracking is an explicit choice.
- Extra monthly principal in loan payoff estimates.
- Faster, more reliable sync, including fixes for OneDrive on Android.

## 1.0.0 — 2026-10-19

- Envelope budgeting for macOS and Android: accounts, categories, Ready to Assign, transfers,
  scheduled transactions, QuickBooks (.qbo) import, and sync through a file in OneDrive, Google
  Drive or Dropbox.
- Credit card payments: spending on a card moves money from its category into the card's payment category.
- Light blue and grey theme, splash screen and app icons.
