# 手がかり成功時の自動報酬

## 今回の設定

- `cell_floor` を目星などで発見成功すると `監禁部屋の鍵` を自動取得します。
  - 鍵ID: `cell_key`
  - 鍵は使用しても消費されません。
- `storage_tools` を発見成功すると `教団の大型ハンマー` を自動取得します。
  - 登録済みの黒壁を破壊できます。
  - ハンマーは消費・耐久消耗しません。

## 設置

情報ポイント用アーマースタンドを見ながら:

```text
/clue mark cell_floor
```

倉庫の工具ポイントでは:

```text
/clue mark storage_tools
```

その後 `/clue setup <範囲>` で透明化・保護できます。

## 再取得テスト

報酬は探索者×clue-idごとに1回のみです。KPがテストをやり直す場合:

```text
/clue rewardreset <player>
/clue rewardreset <player> <clue-id>
```

取得履歴は `plugins/TRPGCharacter/clue-reward-history.yml` に保存されます。

## clues.yml の汎用設定

鍵:

```yaml
reward:
  enabled: true
  type: key
  key-id: room_key
  display-name: "部屋の鍵"
```

ハンマー:

```yaml
reward:
  enabled: true
  type: hammer
  display-name: "大型ハンマー"
```
