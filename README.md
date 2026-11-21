# multilinked (Android proof of concept, live one-way voice)

One phone is the SPEAKER, the rest LISTEN, in near real time, relayed phone to phone.

1. Push this folder to GitHub; the workflow in .github/workflows builds the APK
   (Actions > latest run > Artifacts). Or open it in Android Studio and Run.
2. Phones need Google Play services, Bluetooth + Wi-Fi ON (no internet needed),
   Location ON, and the app kept open with the screen on (relay phones too).
3. app/debug.keystore must stay in the repo: it keeps every build signed with the
   same key so a new APK installs over the old app (no uninstall needed).

## Using it
- "Start session" fixes the name, role and "only link with" list, and starts a search.
- "Search for devices" advertises and looks for new links. It stops at the first new link
  or after 30 s, and can be pressed again at any time without dropping existing links.
  Both phones that should link must be searching at the same time.
  A middle phone that needs two links has to search twice.
- "Show logs" opens a timestamped log (Share sends it as text, Clear empties it).

## Test the chain with 4 phones
| Phone | Name | Only link with | Role     |
|-------|------|----------------|----------|
| 1     | A    | B              | Speaker  |
| 2     | B    | A,C            | Listener |
| 3     | C    | B,D            | Listener |
| 4     | D    | C              | Listener |
