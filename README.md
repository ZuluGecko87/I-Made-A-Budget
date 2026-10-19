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
- **Goals** on any category, showing how much is allocated against what's needed:
  - *Savings goal*: a target amount, optionally a date and/or a planned monthly amount. Money rolls
    over; shows progress, the monthly amount needed for the date, and an estimated finish month
    (at your plan and at your pace over the last 3 months).
  - *Monthly spending*: an amount needed every month (rent, groceries). Assigned money is spent from
    the category. With *roll over* on, leftovers carry into next month and count toward it; off,
    they go back to Ready to Assign at month end.
  - *Fund goals* fills underfunded goals from Ready to Assign (monthly spending first, then savings).
  - *Across categories*: one savings target for several categories together (e.g. House over Down
    Payment + Closing Costs), with the monthly amount needed and an estimated finish.
  - The **Goals** tab lists them all and is where you add them; Import is on the Accounts page.
- **You can't assign more than you have**: assigning is capped at what's in Ready to Assign
  (lowering an amount is always allowed).
- Tap a category's name to rename it, move it to another group, or set its goal; tap its numbers to assign money.
- Add a transaction from the account list with the **+** next to each account, or inside an account.
- **Bank import** of QuickBooks `.qbo` files (`.qfx` and `.ofx` work too). On the Mac you can drag
  the file onto an account's transactions to import into that account.
  - the first import into an account asks how far back to go (the starting balance adjusts if the account was just created from the file);
  - later imports only look from the last imported date on, unless you tick *Also check older transactions*;
  - already-imported transactions are marked **Duplicate** and ignored (by bank id, or by same date + amount + payee), with a warning that lets you review them or import them anyway;
  - transactions you entered by hand, scheduled ones, and the other side of a transfer are **matched** within ±4 days instead of being added twice;
  - an upcoming scheduled transaction the bank already posted is matched and the schedule moves on;
  - new transactions come in **uncategorized** unless an auto-categorize rule matches (tick *Always use this for payees containing …* on a row, or manage rules in Settings);
  - the bank account is remembered, so the next file from it goes to the right account.
- **Sync** through OneDrive, Google Drive or Dropbox.

## Loans and mortgages

Give a Loan or Mortgage account its principal remaining, interest rate and monthly escrow (when adding it, or later by editing it — editing the principal corrects the balance and re-splits the payments). Loans are always tracked outside the budget, so what you owe never reduces Ready to Assign. Link payments to it
(the *Loan payment* option on a transaction, or a rule such as "main street mortgage" → Mortgage,
payment on Home Loan). Each payment is split:

- interest = principal owed before the payment × rate ÷ 12
- escrow = the monthly escrow you set
- principal = payment − interest − escrow, which reduces the loan balance

The whole payment is still budgeted from its category. Payments are recalculated in date order
whenever one is added, edited or removed; changed loan terms apply from the current month. The
loan screen shows principal left, principal and interest paid so far, the last payment's split, and
an estimated payoff date with the interest still to pay. Set *Extra toward principal each month* to compare payoff with and without the extra — how much sooner, and how much interest it saves.

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
