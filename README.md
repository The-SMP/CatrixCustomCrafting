# CatrixCustomCrafting

自訂物品合成框架，適用於 Paper 1.21.11，使用 Java 21 與 Maven。初始版本為 `1.0.0`，作者識別為 `me.cat6666`。

## 建置與安裝

在本專案根目錄執行 `mvn package`，成品會輸出至 `target/CatrixCustomCrafting-1.0.0.jar`。將 JAR 放入伺服器 `plugins` 資料夾並重新啟動。首次啟動會建立 `plugins/CatrixCustomCrafting/config.yml`、`messages.yml` 與 `custom` 資料夾。

## 建立與使用配方

- `/ccraft`：打開配方總覽箱，可建立配方、選擇配方進入合成介面，並從合成介面編輯或刪除配方。
- `/ccraft create`：直接開啟 3×3 配方編輯箱。可左鍵點取放置，也可按住左鍵拖放到材料格；輸出物品放在中央輸出格。保存後會自動打開該配方的合成介面。
- `/ccraft edit <UUID>`：直接編輯指定配方。
- `/ccraft list`：列出配方 UUID。
- `/ccraft open <UUID>`：開啟配方介面，左鍵點擊輸出物品即可嘗試合成。
- `/ccraft reload`：補齊設定預設值並重新載入設定、訊息與配方。
- `/ccraftversion`（別名 `/ccversion`）：非同步檢查 GitHub Releases。結果快取 10 分鐘，不會定時輪詢。

GUI 合成會比對物品種類與完整物品資料（名稱、附魔等），材料可分散於背包不同欄位。工作台合成會比對 3×3 格子中的形狀，允許配方在格子內平移與左右鏡像，並比對物品資料；兩種方式都依配方設定扣除材料。工作台支援左鍵或右鍵逐次合成，背包空間不足時不會扣除材料。

配方存放於 `plugins/CatrixCustomCrafting/custom/<UUID>.yml`。檔案使用 YAML，材料索引 0–8 對應編輯介面左上至右下，輸出存於 `result`。

## 權限

| 權限 | 預設 | 用途 |
| --- | --- | --- |
| `catrixcustomcrafting.admin` | OP | 管理員總權限標籤 |
| `catrixcustomcrafting.create` | OP | 建立配方 |
| `catrixcustomcrafting.use` | 所有人 | 查看及使用配方 |
| `catrixcustomcrafting.reload` | OP | 重載設定 |
| `catrixcustomcrafting.version` | OP | 檢查更新及登入提醒 |

所有指令在 `plugin.yml` 註冊；權限名稱可在 `config.yml` 調整。GitHub 儲存庫可透過 `settings.github-repository` 設定為 `owner/repository`。Release 標籤接受 `v` 前綴，例如 `v1.2.0`；高於目前插件版本時回報新版本，低於目前版本時回報開發版。具有更新權限的管理員登入 3 秒後會收到有新版提醒。

## 設定與訊息

`config.yml` 保存基本設定、更新檢查選項、儲存間隔、指令名稱與權限節點。每次啟動及重載都會將預設檔缺少的鍵補回，既有值保留。配方採記憶體快取；修改配方後以 Dirty Flag 批次存檔，並在關服時等待寫入。

`messages.yml` 管理聊天訊息、GUI 標題、按鈕名稱和 Lore，預設繁體中文。訊息支援 `&` 色碼，以及 `%id%`、`%version%`、`%current%` 等變數。新增缺漏訊息鍵會自動補齊。配方材料格和輸出格以單件物品作為配方樣本，左鍵與右鍵點擊或拖曳都可放置樣本，不會消耗背包物品。配方可選擇完整比對（物品種類及資料）或只比對物品種類。

GUI 由專屬 `InventoryHolder` 與 PersistentDataContainer 標記識別，其他插件可透過 holder 類型或標籤辨識本插件介面。
