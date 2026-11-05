# Changelog

The version is set in `gradle.properties` (`imab.versionName`, `imab.versionCode`) and shown at the
bottom of Settings. Versions before 1.4.0 were assigned afterwards, grouping the commits by when
they were made; the builds themselves said 1.0.0.

## 1.13.1 — 2026-11-05

- **Split while importing:** each new row in a bank file has a **Split** button, so one purchase can
  be shared between categories before it's added. Split rows don't create payee rules, since a rule
  holds one category.

## 1.13.0 — 2026-11-02

- **iPhone and iPad:** IMAB builds for iOS from the same code (`iosApp/`, open in Xcode). It keeps
  the budget in the app's storage, syncs through a file picked in the Files app (iCloud Drive,
  OneDrive, Google Drive, Dropbox), imports bank files, and encrypts with the same format as the
  other platforms (AES-GCM from CryptoKit, key in the iPhone Keychain, this device only).
  Not downloadable yet: installing on other people's iPhones needs an Apple Developer account.
- The shared tests also run on the iPhone simulator, plus Xcode tests for encryption and the Keychain.

## 1.12.2 — 2026-11-02

- **Pie charts** show even when only one category (or income source) has money in the period, as
  the whole ring at 100%. Before, they quietly switched to bars, which happens early in a month.

## 1.12.1 — 2026-11-02

- **Reports on phones:** the ‹ Sep – Nov 2026 › range sits beside the Period menu again for Last 3
  months and Last 12 months: the menu is slimmer (156dp, less padding inside) and the arrows smaller.

## 1.12.0 — 2026-11-02

- **Reports:** **YTD** replaces Year: January through this month of the current year.
- The Period menu is a fixed width on every platform and for every choice; so are Custom's From
  and To. If the text is set large, the ‹ period › control moves under the menu.

## 1.11.1 — 2026-11-02

- **Reports on phones:** the period beside the Period menu stays on one line: "Nov 2026",
  "Sep – Nov 2026", or "Dec 25 – Nov 26" across years. The ‹ › buttons are smaller and the menu
  takes the room that's left. Checked at 360dp wide with text at 130%.

## 1.11.0 — 2026-11-02

- **Spending by chosen categories:** the spending report covers all categories by default; the
  **All categories** button picks just some (whole groups at once, with what each spent). It then
  shows what they spent and their share of all spending. **Show all** goes back.
- **Pie or bars:** the spending and income breakdowns can show as a pie: the five largest, the rest
  as "Other", with a legend of amounts and shares. Tap a slice or legend row to read it. Each
  category keeps its color when the filter changes.
- The README describes IMAB in its own terms.

## 1.10.0 — 2026-11-02

- **Net worth report:** a new Reports tab with what you own, what you owe and your net worth at the
  end of each month of the chosen period, and how much it changed since the month before.
  - Every account counts, budget and tracking alike; cards, loans and overdrawn accounts count
    against it. Investments count at the balance recorded in IMAB.
  - The chart shows negative months below a zero line; tap a month for what you owned and owed.
  - Lists the accounts you own and owe, largest first.
- Report charts handle negative amounts, and axis labels read "-$3.0K".

## 1.9.0 — 2026-11-02

- **Reports:** a **Period** menu replaces the row of chips, with two new choices:
  - **Last 3 months**, stepped a month at a time with ‹ › like the others.
  - **Custom:** pick a From and To month, as far back as the month the budget started. It opens on
    the whole budget, and "Since the budget started" goes back to that.
- Monthly charts over long ranges label as many months as fit, with the year ("Jan 26").

## 1.8.1 — 2026-11-02

- **Account screen on phones:** only the account name and the cleared, uncleared and working
  balances stay at the top. The buttons and notices scroll away with the transactions, so the list
  gets most of the screen, also with the keyboard up. The search bar is slimmer and stays at the
  top of the list while you scroll.
- The **Select** button is now **Merge duplicates**, and a long press on a transaction starts
  picking duplicates with it ticked.
- Merging two bank copies always takes the earliest one's date.

## 1.8.0 — 2026-11-02

- **Merge duplicates yourself:** in an account, **Select**, tick the copies of the same transaction,
  then **Merge…** and choose which one to keep. The others are deleted; the kept one is marked
  Merged and fills a missing payee, category or memo from the others. A duplicated transfer's other
  leg goes too.
- When a merged copy came from the bank, its bank id is remembered, so importing the same file
  again doesn't bring the duplicate back.

## 1.7.0 — 2026-11-02

- **Split transactions:** "Split across categories" in the transaction form shares one transaction
  between several categories. Add as many categories as you need; the amounts must add up to the
  transaction's total before it can be saved, and "Fill in the rest" puts what's left in the last
  line. Amounts can be calculations, like "40 + 5".
- Each category's activity, the spending and income reports, and credit card payments count each
  share separately. A split shows as "Split: Groceries, Household" in the account.
- Merging with a bank import keeps your split. Deleting a category leaves its share uncategorized.

## 1.6.1 — 2026-10-31

- The reminder to encrypt is optional: **Dismiss** turns it off for good, and Settings → Encryption
  has a switch to bring it back.

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
