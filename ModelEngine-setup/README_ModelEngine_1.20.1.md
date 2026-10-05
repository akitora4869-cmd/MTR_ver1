# Model Engine連携（Paper 1.20.1 / Java 17）

この版のTRPGCharacterはModel Engine 4を**任意依存**として検出します。
ModelEngineが無い場合・API連携失敗・model idが見つからない場合でも、深きものは既存ItemDisplay表示へ自動フォールバックします。

## 方針

- TRPGCharacter: HP/SAN/装甲/技能/CoC戦闘/神話生物ID
- 元Minecraft Mob: AI/移動/当たり判定
- Model Engine: 見た目とアニメーション
- MythicMobs: 現時点では必須にしません

## 深きもの

`mythos-creatures.yml` ではModel Engineのmodel idを `deep_one` としてあります。
Model Engine側にも同じIDでモデルを登録してください。

元の `deep_one-source.bbmodel` は静的なOptiFine Entity形式で、アニメーションデータがありません。
そのため、そのままでは `idle / walk / attack / hurt / death` のアニメーションは存在しません。
BlockbenchでModel Engine向けボーン構造へ整理し、上記5アニメーションを作成してからModel Engineへ取り込んでください。

TRPGCharacter側は `attack / hurt / death` をModel Engineへ通知できるようにしてあります。
`idle / walk` はModel Engine側の標準状態/モデル設定で使う想定です。

## 確認

サーバー内で `/mythos modelstatus` を実行すると、Model Engine連携状態を確認できます。
