# multilinked (Android proof of concept, live one-way voice)

One phone is the SPEAKER, the rest LISTEN, in near real time, relayed phone to phone.

1. Open this folder in Android Studio and Run, or push to GitHub and download the APK
   from Actions > latest run > Artifacts.
2. Phones need Google Play services, Bluetooth + Wi-Fi ON (no internet needed),
   Location ON, and the app kept open with the screen on (relay phones too).

## Test the chain with 4 phones in one room
Set these in the app, then press Start on each. Set Role to SPEAKER on phone A only.

| Phone | Name | Only link with | Role     |
|-------|------|----------------|----------|
| 1     | A    | B              | Speaker  |
| 2     | B    | A,C            | Listener |
| 3     | C    | B,D            | Listener |
| 4     | D    | C              | Listener |

On A press "Start broadcasting" and talk. B, C and D play it; D's log shows "A is speaking (3 hops)".

## Build without Android Studio (GitHub)
Push this folder to a GitHub repo. The workflow in .github/workflows builds the APK automatically.
