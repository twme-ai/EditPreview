# EditPreview

為 WorldEdit / FastAsyncWorldEdit 加上「先看再寫」的方塊預覽：指令試跑、打字即時預覽、筆刷瞄準預覽。
選區邊框請搭配其他插件（WorldEditCUI、WorldEditDisplay 等），本插件不畫選區。
預覽只送給該玩家（顯示實體＋假方塊），不會改到世界，也不會留下 `//undo` 歷史。

- Paper 1.21.x（使用 `AsyncTabCompleteEvent`、`sendMultiBlockChange`），Java 21
- WorldEdit 7.4+ 或 FastAsyncWorldEdit 2.x（擇一）

| 試跑 `//set` | `//replace` 遮罩（綠玻璃） | 筆刷 | 魔法選取（FAWE） |
|---|---|---|---|
| ![](docs/pev-set.png) | ![](docs/replace-glass.png) | ![](docs/brush.png) | ![](docs/fuzzy.png) |

## 功能

- **`-pev` 試跑**：任何會改方塊的 WorldEdit 指令後面加 `-pev`（例如 `//set stone -pev`、`//replace dirt grass_block -pev`），
  指令照常執行但寫入全被攔下，改成預覽，並給出 **[套用]**／**[取消]** 按鈕。黃色框標出受影響範圍。
- **恆預覽**（`/pev always on`）：不用加 `-pev`，WorldEdit 編輯指令一律先試跑，按 [套用] 才真的寫入。
  - **打字即時預覽**：在聊天欄打 `//set <pattern>` 時即時顯示 pattern 結果；
    打 `//replace <遮罩>` 時用綠色玻璃標出會被取代的方塊，接著打 `<pattern>` 就變成真實方塊預覽。
  - **筆刷**：拿著筆刷瞄準時顯示這一刷的結果；真的點下去照常寫入。
- **遮罩**：一律尊重 `//gmask`、`-m` 遮罩與筆刷遮罩。`replace` 類指令、有遮罩時預設用綠色透明玻璃顯示「會被影響的方塊」，
  其餘顯示寫入後的真實方塊（可用 `/pev style` 改）。
- **任何選區都適用**：預覽是攔下指令實際的寫入，與選區形狀無關；FAWE 的魔法選取（`//sel fuzzy`）、polyhedral 等也一樣。
- 預覽外圍有黃色範圍框與「預覽：N 格」標籤，方便找到預覽（`/pev box off` 可關）。
- FAWE 需要 `//confirm` 的大型操作：確認後一樣進入預覽。

## 指令

權限 `editpreview.use`（預設所有人）。設定存在玩家資料（PDC），重進伺服器仍保留。

| 指令 | 說明 |
|---|---|
| `/pev always [on\|off]` | 恆預覽開關 |
| `/pev box [on\|off]` | 預覽外圍的黃框與格數標籤 |
| `/pev brush [on\|off]` | 筆刷預覽開關 |
| `/pev style <auto\|blocks\|glass>` | 預覽樣式：自動／一律真實方塊／一律綠玻璃 |
| `/pev apply` | 套用目前的試跑（重新執行原指令） |
| `/pev clear`（`cancel`） | 清掉預覽 |
| `/pev mask <遮罩>` | 用綠玻璃標出選區裡符合遮罩的方塊 |
| `/pev pattern <pattern>` | 預覽選區套上 pattern 的樣子，可直接 [套用 //set] |
| `/pev help` | 說明 |

不會被攔的指令：`//undo`、`//redo`、選區／筆刷／工具／遮罩設定等不改方塊的指令。

## 設定（config.yml）

```yaml
max-capture: 1000000     # 一次試跑最多收幾格（超過的仍不寫入，只是不顯示）
max-display: 150000      # 最多送幾格假方塊（超過時留離玩家最近的）
live-max-volume: 262144  # 打字預覽：選區超過這個體積就暫停（按 Enter 仍會試跑）
brush-max-size: 32       # 筆刷大小超過這個就不預覽
defaults: { always: false, box: true, brush: true }
```

## 運作方式

- **攔截寫入**
  - 原版 WorldEdit：在 `EditSessionEvent` 的 `BEFORE_HISTORY` 階段包一層 `CaptureExtent`，把寫入記下來、不往下傳；
    讀取時先回傳記下的結果（同一指令後續步驟看得到前面的變更）。遮罩取自下層的 `MaskingExtent`。
  - FAWE：在 `BEFORE_CHANGE` 找到佇列，掛上一個 `IBatchProcessor`（scope `CUSTOM`：排在遮罩之後、歷史之前），
    記下每個 chunk 要寫的方塊後把那批清空，所以世界與歷史都沒有變化。找不到佇列時退回 `CaptureExtent`。
- **觸發**：`PlayerCommandPreprocessEvent` 偵測 `-pev`（或恆預覽），把旗標拿掉後讓指令照常跑；[套用] 以不帶旗標的原指令再執行一次。
- **打字預覽**：客戶端每打一個字都會要補全，從 `AsyncTabCompleteEvent` 讀到目前的聊天內容，停手 250 ms 後用 WorldEdit 的
  遮罩／pattern 解析器在選區上計算。
- **筆刷預覽**：照 `BrushTool` 的流程（瞄準、大小、材質、遮罩）自己建一個 EditSession 呼叫 `brush.build`，寫入全被攔下。
- **顯示**：範圍框與標籤是只對該玩家可見的 `BlockDisplay`／`TextDisplay`；方塊預覽是 `sendMultiBlockChange` 假方塊，
  被完全包住的格子不送，以減少數量。

## 限制

- 預覽是「跑一次指令」，`//set` 這類 pattern 若含隨機成分，[套用] 時會重新隨機。
- 不預覽實體與生物群系的變更。
- 試跑時 WorldEdit／FAWE 仍會印出「Operation completed (N)」「history: N changed」等訊息，那只是指令自己的計數，世界與歷史都沒有變。
- 假方塊在玩家點擊、chunk 重載時可能被客戶端換回真實方塊；`/pev clear` 會還原。

## 建置與測試

```sh
./gradlew build           # build/libs/EditPreview-<版本>.jar
scripts/restart.sh        # 部署到測試伺服器並重開伺服器＋客戶端
scripts/e2e.sh            # 端對端測試（FAWE 2.16.1：26 項，含魔法選取；原版 WorldEdit 會跳過 FAWE 專屬項目）
```

測試腳本需要本機的 Paper 測試伺服器、Minecraft 客戶端、Xvfb 與 xdotool，路徑在 `scripts/env.sh`。
