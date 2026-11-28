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

## How the network is kept a tree
- Every phone carries a group id (shown in the status line and logs). Two phones in the same
  group never link, because that link would close a loop. Linking two different groups merges
  them (the smaller id wins). When a link drops, each side gets a fresh group id so the halves
  can rejoin later. A phone also refuses more than 5 links.
- Because the network is always a tree, audio is simply passed to every neighbour except the one
  it came from. There is no pruning or healing: if a middle phone drops, the phones beyond it
  lose the audio until you search and link them again.
- Playback keeps about 240 ms buffered, skips frames if it falls more than 400 ms behind, and
  pauses to rebuild the cushion if it runs dry.
- Log screen buttons: Text (share as text), File (share the whole log as a file), Clear.

## Test the chain with 4 phones
| Phone | Name | Only link with | Role     |
|-------|------|----------------|----------|
| 1     | A    | B              | Speaker  |
| 2     | B    | A,C            | Listener |
| 3     | C    | B,D            | Listener |
| 4     | D    | C              | Listener |
