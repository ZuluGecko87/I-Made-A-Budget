# IMAB — I Made A Budget

an envelope budgeting app that works offline, importing bank statement files instead of connecting to the bank, for macOS and Android, from one Kotlin
codebase (Kotlin Multiplatform + Compose Multiplatform).

## Features

- **Budget**: category groups and categories, monthly Assigned / Activity / Available, Ready to Assign.
  Leftover money carries forward. At the end of a month an overspent category resets to zero:
  cash overspending comes out of next month's Ready to Assign, credit overspending becomes card debt.
- **Credit cards** are budgeted like cash: spending on a card moves money into its payment category (see below).
- **Accounts**: budget and tracking (off-budget) accounts, cleared / uncleared balances, running balance, search.
- **Every transaction gets a category.** Anything missing one is flagged on the Budget screen and
  listed under "Needs a category".
- **Transfers** between accounts keep both sides in step. Transfers between two budget accounts
  don't need a category. A transfer to a tracking account takes one.
- **Scheduled transactions** (once, weekly … yearly) are entered on their date automatically.
- **Bank import** of QuickBooks `.qbo` files (`.qfx` and `.ofx` work too):
  - already-imported transactions are marked **Duplicate** and skipped (by bank id, or by same date + amount + payee);
  - transactions you entered by hand, scheduled ones, and the other side of a transfer are **matched** within ±4 days instead of being added twice;
  - an upcoming scheduled transaction the bank already posted is matched and the schedule moves on;
  - new transactions get the category you last used for that payee;
  - the bank account is remembered, so the next file from it goes to the right account.
- **Sync** through OneDrive, Google Drive or Dropbox.

## Credit cards

Credit card and line-of-credit accounts on the budget each get a payment category, shown in the
**Credit Card Payments** group at the top of the budget.

- **Spending on the card** in, say, Groceries moves that money from Groceries into the card's
  payment category, so the money to pay the bill is already set aside. A refund moves it back.
- **Credit overspending** (shown in amber): if Groceries doesn't have enough, only the covered part
  moves. The rest is new debt. Cover it in the same month and the payment category gets it; otherwise
  it stays as debt and does *not* come out of next month's Ready to Assign.
- **Paying the card** is a transfer from checking (*Make payment* on the card, or *Record payment*
  on its budget row). It spends from the payment category. Paying more than the category holds is
  cash overspending.
- **Debt you already had** when you added the card is categorized *Pre-existing debt*. It isn't in
  the budget; pay it down by assigning money to the payment category (*Fund whole balance* does it in one step).
- Each card shows what you owe and how much of it isn't covered by the payment category yet.
- Cash advances add to the payment category (you'll owe them back); balance transfers move it
  between cards. Uncategorized card spending and rewards credited as Ready to Assign keep the payment category in step.
- Credit cards marked as tracking (off-budget) accounts have no payment category.

## How sync works

The budget is one JSON file (default `IMAB-budget.json`) that you keep in any cloud folder.
Each change is stored as a versioned record, and devices merge records with "latest edit wins"
per item. So:

- the cloud service only needs to sync a file; IMAB never signs in to it or uses its API;
- if two devices overwrite each other's upload, the next sync from each one puts its changes back;
- conflict copies the service creates next to the file (e.g. `IMAB-budget-MacBook.json`) are merged in on the Mac;
- IMAB refuses to overwrite a file that isn't an IMAB budget.

Each device also keeps its own full copy, so the app works offline and syncs when it can:
on start, about 3 s after a change, every 90 s, when the Android app comes back to the
foreground, and when the Mac app quits.

**Set up**: on the first device go to Settings → *Create sync file…* and save it in your
OneDrive / Google Drive / Dropbox folder. On every other device use *Use existing sync file…*
and pick that same file.

- **Mac**: cloud drives appear in the file dialog sidebar (they live under `~/Library/CloudStorage`).
- **Android**: in the picker, open the ☰ menu and choose OneDrive, Drive or Dropbox. That service's app must be installed and signed in.

## Build

Requires JDK 17 and the Android SDK (`local.properties` → `sdk.dir`).

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17

./gradlew :composeApp:run              # run the Mac app from source
./gradlew :composeApp:packageDmg       # composeApp/build/compose/binaries/main/dmg/IMAB-1.0.0.dmg
./gradlew :composeApp:assembleDebug    # composeApp/build/outputs/apk/debug/composeApp-debug.apk
./gradlew :composeApp:installDebug     # install on a connected phone
./gradlew :composeApp:desktopTest      # unit + sync tests
./gradlew :composeApp:desktopTest -Pscreenshots   # also render screens to composeApp/build/screenshots
```

The splash screen artwork and the app icons are drawn in code (`ui/Splash.kt`). After changing it,
regenerate the icon files with `./gradlew :composeApp:desktopTest -Picons`, then rebuild
`composeApp/icons/IMAB.icns` from `IMAB-1024.png` (sips + iconutil).

To try the Mac app without touching your real budget, set `IMAB_DATA_DIR=/some/folder`.
Real local data lives in `~/Library/Application Support/IMAB` on Mac and in app storage on Android.

## Layout

```
composeApp/src/
  commonMain/   shared code: model, budget math, OFX parser, import matcher, ledger/sync, all UI
  androidMain/  Android activity, file pickers (Storage Access Framework)
  desktopMain/  Mac entry point, file dialogs, cloud-folder file access
  commonTest/   budget, import and parser tests
  desktopTest/  two-device sync tests, screenshot renderer
```

## Not built yet

- Splitting one transaction across several categories.
- Reports, goals/targets, and CSV import.
