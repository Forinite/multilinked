# multilinked (Android proof of concept)

1. Install Android Studio, open this folder, let Gradle sync.
2. Plug in a phone (USB debugging on) and press Run.
   Or: Build > Build APK(s), send app-debug.apk to your other phones and install it
   (allow "install unknown apps").
3. Phones need Google Play services, Bluetooth + Wi-Fi ON (no internet needed),
   and Location ON if the phone is Android 11 or older.

## Test the chain with 4 phones in one room
Set these in the app, then press Start on each:

| Phone | Name | Only link with |
|-------|------|----------------|
| 1     | A    | B              |
| 2     | B    | A,C            |
| 3     | C    | B,D            |
| 4     | D    | C              |

Send from A. D should show: "A (3 hops): ..."

## Build without Android Studio (GitHub)
Push this folder to a GitHub repo. The workflow in .github/workflows builds the APK
automatically: open the repo > Actions > latest run > Artifacts > multilinked-apk.
"# multilinked" 
