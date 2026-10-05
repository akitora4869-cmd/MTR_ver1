# MCTRPG Rebuild Phase 1

旧 TRPGCharacter 1.20.1 を「シナリオ固有プラグイン」から MCTRPG 制作・運営基盤へ整理する最初の実装です。

## 実装済み
- 技能の共通クールダウン: デフォルト10秒 (`skill-system.global-cooldown-ms`)
- PCの通常技能、戦闘技能、回避、および敵攻撃に共通CTを適用
- リソースパック不要のダイス演出 (`DiceAnimationManager`)
- `PUBLIC / SECRET / HIDDEN` のロール公開範囲基盤
- SECRETでは探索者側を `🔒 SECRET ROLL 〈？？？〉` に固定し、技能名・目標値・出目・成否を非公開
- SECRET詳細はKP/OP/adminのみに通知
- `/mctrpgtool` で KP TOOL を配布
- KP TOOL GUIから朝/昼/夕/深夜、晴雨、雷雨を操作
- KP TOOLから EDITOR WAND を配布
- EDITOR WANDの編集GUI基盤
- Dreamlandsの設定、コマンド、起動処理、KPブック項目を撤去

## 次段階
- 既存の自動技能（暗所目星、水泳、登攀等）を共通 Skill Engine に完全統合
- Clue/Lock/NPC/Enemy を EDITOR WAND から実際に設置・編集
- イベントノード、TPポイント、演出ノードの実装
- Paper最新版系へのAPI移行
