# Changelog

The version is set in `gradle.properties` (`imab.versionName`, `imab.versionCode`) and shown at the
bottom of Settings. Versions before 1.4.0 were assigned afterwards, grouping the commits by when
they were made; the builds themselves said 1.0.0.

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
