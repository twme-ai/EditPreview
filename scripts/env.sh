# Test environment: a dedicated Paper server (port 25566) + a dedicated 1.21.11 client directory (own logs and natives), Xvfb :98
P=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SRV=${PEV_SERVER:-/root/projects/ProjectCollection/EditPreview-testserver}
CLI=${PEV_CLIENT:-/root/projects/ProjectCollection/EditPreview-client}
NAME=PevTester
export DISPLAY=:98
LOG=$SRV/out.log
CLOG=$CLI/client-$NAME.log
