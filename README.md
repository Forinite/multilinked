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

## How audio travels (tree routing)
All links stay connected, but audio only flows along one path per phone:
1. A broadcast is first sent to everyone for ~0.4 s.
2. Each phone picks the neighbour that delivered it in the fewest hops as its PARENT and sends
   PRUNE ("don't send me this stream") to its other neighbours.
3. Phones only forward audio to neighbours that have not pruned them. Extra links sit idle.
4. If the parent link drops, or no new frames arrive for 0.8 s, the phone sends GRAFT
   ("send it to me again") to all neighbours and picks a new parent.
The log shows TREE / PRUNE / GRAFT / HEAL lines and per-neighbour new vs duplicate counts.

## Test the chain with 4 phones
| Phone | Name | Only link with | Role     |
|-------|------|----------------|----------|
| 1     | A    | B              | Speaker  |
| 2     | B    | A,C            | Listener |
| 3     | C    | B,D            | Listener |
| 4     | D    | C              | Listener |
