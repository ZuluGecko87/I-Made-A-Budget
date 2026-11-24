# Nutcracker privacy policy

*Effective November 24, 2026*

Nutcracker (formerly IMAB) is a budgeting app for iPhone, iPad, Android, Mac and Windows. This policy
explains what happens to the information you put into it. In short: **Nutcracker doesn't collect any of
your data.** There's no Nutcracker account, no Nutcracker server, no analytics, no advertising and no tracking.
Everything you enter stays on your devices and in cloud storage you choose.

## What Nutcracker stores, and where

**Your budget** (accounts, balances, transactions, payees, categories, goals, notes) is kept on each
device where you use Nutcracker:

- on iPhone, iPad and Android, in the app's private storage;
- on a Mac, in `~/Library/Application Support/IMAB`;
- on Windows, in `%APPDATA%\IMAB`.

**Sync between devices (optional).** If you turn on sync, Nutcracker saves your budget in one file at a
place you pick: for example iCloud Drive, Google Drive, OneDrive or Dropbox. Nutcracker only reads and
writes that file on your device. The cloud service's own app uploads it and downloads it to your
other devices. That copy is handled under the cloud service's privacy policy, not this one. Nutcracker
itself never connects to the internet.

**Encryption (optional).** You can set a passphrase. Nutcracker then encrypts the sync file, its archives and conflict
copies, and each device's own copy with AES-256, using a key made from your passphrase. The passphrase is never stored in the
file or sent anywhere. Without it, nobody can read the file, and that includes the developer, so a
lost passphrase can't be recovered.

**Bank files you import** (QBO, QFX or OFX) are read on your device. Statements
contain full account and card numbers, but Nutcracker keeps only your bank's identifier and the last 4
digits, so it can recognize the account next time.

**Receipt photos.** When you scan a receipt, the photo comes from your camera or from a photo you
pick. Its text (merchant, total, date, card) is read on your device:

- Apple's Vision framework on iPhone, iPad and Mac;
- Google's ML Kit on Android;
- Tesseract, if it's installed, on Windows.

The photo is saved only on the device that took it, encrypted with your passphrase if you've set
one. It isn't put in the sync file.

**Work expense reports.** When you export a report (PDF or CSV), Nutcracker hands the file to your
device's share sheet or save dialog. It goes only where you send it.

## What Nutcracker doesn't do

- It doesn't collect, sell, share or rent any personal information.
- It doesn't use analytics, crash reporting, advertising or tracking services, and it doesn't track
  you across other apps or websites.
- It doesn't send your data to the developer or to anyone else.

**About Google's ML Kit on Android.** Google designs ML Kit to send Google statistics about how the
text recognition is used. The Android version of Nutcracker isn't allowed internet access: it doesn't
request Android's internet permission, so nothing is sent. Text recognition works fully on the
device.

## Permissions

- **Camera** (iPhone and iPad): only to photograph a receipt when you choose to. On Android, Nutcracker
  asks the camera app to take the photo, and uses the system photo picker to choose one, so it needs
  no camera or storage permission.
- **Files:** Nutcracker opens only the sync file, bank files and folders you choose in the system's file
  picker.

## Backups

The Android app keeps your budget out of Android's cloud backup. Your data comes back on a new phone
from your sync file. On iPhone, iPad, Mac and Windows, your own device backups (such as an iCloud or
Time Machine backup) may include Nutcracker's data, under your backup settings.

## Deleting your data

Uninstalling Nutcracker deletes the data it keeps on that device. If you used sync, also delete the sync
file (and the backup copies next to it) from your cloud storage. The developer holds no copy of
your data, so there's nothing else to delete.

## Children

Nutcracker isn't directed at children under 13, and it doesn't knowingly collect information from anyone,
of any age.

## Changes to this policy

If this policy changes, the new version will be posted here with a new effective date. If Nutcracker ever
starts collecting any data, this policy will say so first, and the app stores' privacy labels will
be updated.

## Contact

Questions about privacy: open an issue at
<https://github.com/ZuluGecko87/I-Made-A-Budget/issues>.
