# Publishing to Google Play and the App Store

What's already set up in the code, and the steps that need you (accounts, signing, forms).
The privacy policy is [PRIVACY.md](../PRIVACY.md).

## The privacy policy's web address

Both stores need a public address for the privacy policy. Pick one:

- **Repository is public:** use
  `https://github.com/ZuluGecko87/Nutcracker-Budget/blob/main/PRIVACY.md`.
- **Repository is private:** publish `PRIVACY.md` anywhere public, for example a public GitHub Gist
  or a GitHub Pages site, and use that address.

## Google Play

Already in the code:

- **Targets Android 16 (API 36)**, which Play requires for new apps.
- **No internet permission.** ML Kit's request for it is removed, so its usage statistics can't be
  sent. Receipt reading still works on the device.
- **Release signing** with the existing key (`keystore.properties`). Use this key as Play's
  *upload key*; Google then signs what users download (Play App Signing).
- **The Play bundle (.aab):**
  - built by `./gradlew :composeApp:bundleRelease`, which writes
    `composeApp/build/outputs/bundle/release/composeApp-release.aab`;
  - also built by the GitHub release workflow, as the `android-play` artifact.

Your steps:

1. Create a developer account at play.google.com/console. It costs $25 once and asks for ID
   verification.
2. **Create app:** name *Nutcracker: Envelope Budget*, App, Free.
3. **App content.** Answers for Nutcracker:
   - **Privacy policy:** the address above.
   - **Ads:** No.
   - **App access:** all features work without signing in.
   - **Content rating:** fill in the questionnaire. It's a utility app with no user-generated
     content, so it ends up rated Everyone.
   - **Target audience:** 18 and over.
   - **Data safety:** *No* to "collects or shares any of the required user data types". Data is
     stored on the device and in the user's own cloud storage, and nothing is sent to the developer.
   - **Financial features:** choose *Personal finance → Budgeting / money management*. Nutcracker moves
     no money and offers no loans or trading.
   - **Government app:** No. **Health:** none.
4. **Testing → Closed testing:** create a track, add at least 12 testers (by email or a Google
   Group), and upload the `.aab`. New personal accounts need 12 testers opted in for 14 days before
   they can apply for production.
5. **Store listing:**
   - short description (80 characters at most);
   - full description;
   - icon (512×512 PNG);
   - feature graphic (1024×500);
   - at least 2 phone screenshots (the Budget, Accounts, Reports and Scan-receipt screens work
     well).
6. After the 14 days, apply for production access, then create a production release.

Each upload needs a higher `imab.versionCode` in `gradle.properties`.

**Your own phone:** the Nutcracker on it now is a debug build signed on this Mac. Before installing from
Play, uninstall it. The budget comes back when you choose your sync file again in Settings → Sync →
*Use existing sync file…*.

## App Store (iPhone and iPad)

Already in the code:

- **Bundle ID** `com.z.imab`, for iPhone and iPad.
- **Camera explanation**, shown when Nutcracker first asks for the camera.
- **Encryption setting:** `ITSAppUsesNonExemptEncryption = NO`. Nutcracker's AES encryption only protects
  the user's own data, which is exempt, so there's no export paperwork.
- **Privacy manifest** (`iosApp/iosApp/PrivacyInfo.xcprivacy`): no tracking and no data collected.
  It also declares file modification dates (to notice sync file changes) and elapsed time
  (animations).
- **Your team for signing:** create `iosApp/Team.xcconfig` (git ignores it) containing
  `DEVELOPMENT_TEAM = <your team id>`.

Your steps:

1. Join the Apple Developer Program at developer.apple.com/programs ($99 a year). The same
   membership covers the Mac.
2. Put your Team ID (shown under developer.apple.com/account → Membership details) in
   `iosApp/Team.xcconfig`.
3. In App Store Connect, go to **Apps → + → New App**:
   - platform: iOS;
   - name: *Nutcracker: Envelope Budget*;
   - bundle ID: com.z.imab, registered under Certificates, IDs & Profiles → Identifiers if it isn't
     offered;
   - SKU: `imab`.
4. **App Privacy:**
   - **Data Not Collected.**
   - **Privacy Policy URL:** the address above.
5. **Age rating questionnaire:** all *None*, which gives 4+.
6. **Upload a build:** open `iosApp/IMAB.xcodeproj` in Xcode, choose *Any iOS Device*, then
   **Product → Archive → Distribute App → App Store Connect**. You can test it with TestFlight first.
7. **Screenshots:** 6.9″ iPhone (1320×2868) and 13″ iPad (2064×2752), taken in the simulator.
8. **Submit for review.** In the review notes, say that sync needs a file in iCloud Drive and that
   everything else works without it.

### On the Mac App Store

In App Store Connect, open **Pricing and Availability** and leave **iPhone and iPad Apps on Apple
Silicon Macs** turned on. The iPad version is then offered in the Mac App Store, on M1 and newer
Macs. It needs no separate build, and it reads receipts with Apple's Vision directly.

The Mac (desktop) version isn't set up for the Mac App Store. The store requires a "sandboxed" app,
and two things it relies on don't work inside the sandbox:
- reopening the sync file by its path;
- running receipt text recognition through `osascript`.

## Mac download, signed and notarized

The `.dmg` on the GitHub releases page can be signed and notarized, so macOS opens it without
*Open Anyway*. This uses the same Apple Developer membership.

1. Create a *Developer ID Application* certificate: in Xcode, **Settings → Accounts → Manage
   Certificates → + → Developer ID Application**.
2. Make an app-specific password at account.apple.com → Sign-In and Security.
3. To sign and notarize on this Mac, create `mac-signing.properties` in the project folder (git
   ignores it):

   ```properties
   identity=Your Name (TEAMID)
   appleId=you@example.com
   password=xxxx-xxxx-xxxx-xxxx
   teamId=TEAMID
   ```

   Then run `./gradlew :composeApp:notarizeDmg`.
4. To have the GitHub release do it, export the certificate from Keychain Access as a `.p12` file
   and add these repository secrets:
   - `IMAB_MAC_CERT_P12_BASE64` (the output of `base64 -i cert.p12`);
   - `IMAB_MAC_CERT_PASSWORD`;
   - `IMAB_MAC_SIGN_IDENTITY`;
   - `IMAB_NOTARY_APPLE_ID`;
   - `IMAB_NOTARY_PASSWORD`;
   - `IMAB_TEAM_ID`.

   Without these secrets, the release builds an unsigned `.dmg`, as before.
