#!/usr/bin/env bash
# Start the client on Xvfb :98 and connect straight to the test server
. "$(dirname "$0")/env.sh"
pgrep -f "Xvfb :98" >/dev/null || { setsid nohup Xvfb :98 -screen 0 1280x720x24 >/dev/null 2>&1 < /dev/null & sleep 2; }
cd "$CLI"
GAME=game-pev
mkdir -p "$GAME" natives
[ -f "$GAME/options.txt" ] || { [ -f game/options.txt ] && sed -e 's/^renderDistance:.*/renderDistance:6/' -e 's/^maxFps:.*/maxFps:20/' -e 's/^pauseOnLostFocus:.*/pauseOnLostFocus:false/' game/options.txt > "$GAME/options.txt"; }
CP=$(ls libs/*.jar | grep -v natives | tr '\n' ':')client.jar
export LIBGL_ALWAYS_SOFTWARE=1
: > "$CLOG"
setsid nohup bash -c 'exec -a pevclient "$@"' _ java -Xmx2G -Djava.library.path=natives -Dorg.lwjgl.librarypath=$PWD/natives -cp "$CP" net.minecraft.client.main.Main \
  --username "$NAME" --uuid 318b39707ee1372ab79a6e350d520d62 --accessToken 0 --userType legacy \
  --version 1.21.11 --gameDir "$GAME" --assetsDir assets --assetIndex 29 \
  --width 1280 --height 720 --quickPlayMultiplayer localhost:25566 >> "$CLOG" 2>&1 < /dev/null &
for i in $(seq 180); do grep -aq "$NAME joined the game" "$LOG" && break; sleep 1; done
grep -a "$NAME joined" "$LOG" | tail -1
