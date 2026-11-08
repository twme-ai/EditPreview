# 測試環境：獨立的 Paper（埠 25566）＋借用 CreativeKit 的 1.21.11 客戶端檔案（自己的遊戲資料夾），Xvfb :98
P=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SRV=${PEV_SERVER:-/root/projects/ProjectCollection/EditPreview-testserver}
CLI=${PEV_CLIENT:-/root/projects/ProjectCollection/CreativeKit-ui-prototype-20261103/client}
NAME=PevTester
export DISPLAY=:98
LOG=$SRV/out.log
CLOG=$CLI/client-$NAME.log
