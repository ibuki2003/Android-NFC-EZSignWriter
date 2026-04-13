# Android NFC-EZSign Writer

NFCを経由して電子ペーパー(EZSign)サイネージに画像を書き込むAndroidアプリです。

## 概要

スマートフォンのギャラリーから画像を選択し、NFC通信で電子ペーパーディスプレイに転送・表示します。  
画像は4色パレット（黒・白・黄・赤）へ変換され、Atkinsonディザリングにより自然な階調表現が得られます。

## 動作フロー

```
画像選択 → 4色変換(ディザリング) → ブロック分割 → LZO圧縮 → NFC送信 → 電子ペーパー書き込み
```

1. ユーザーがデバイスのギャラリーから画像を選択する
2. 画像を 400×300px にリサイズ（アスペクト比維持・白背景で中央配置）
3. ガンマ補正（γ=0.9）を適用
4. Atkinson ディザリングで4色インデックスマップへ変換
5. 2bit/px でパッキングし 2,000byte × 15ブロックに分割
6. 各ブロックを LZO1X-1 で圧縮
7. NFC APDU コマンド（F0D3）で250byteずつ分割送信
8. 書き込み命令（F0D4）を発行後、ポーリング（F0DE）で完了を確認

## 対応カラーパレット

| インデックス | 色 |
|:-----------:|:--:|
| 0 | 黒 |
| 1 | 白 |
| 2 | 黄 |
| 3 | 赤 |

## 要件

| 項目 | 内容 |
|------|------|
| OS | Android 13（API 33）以上 |
| ハードウェア | NFC対応端末（ISO 14443-A / IsoDep） |
| 対応ディスプレイ | 400×300px 4色 電子ペーパー（NFC APDU通信対応） <br>購入リンク: https://www.amazon.co.jp/dp/B0FZRXFNTC|

## 技術スタック

- **言語**: Kotlin
- **NFC**: `android.nfc.tech.IsoDep`（ISO 14443-A）
- **圧縮**: LZO1X-1（`org.anarres.lzo:lzo-core:1.0.6`）
- **ディザリング**: Atkinson Dithering
- **Min SDK**: 33 / **Target SDK**: 36
- **Build System**: Gradle (Kotlin DSL)

## セットアップ

```bash
# リポジトリをクローン
git clone <repository-url>
cd ez_sign_writer_app

# Android Studio で開く、または Gradle でビルド
./gradlew assembleDebug
```

Android Studio（最新版推奨）で直接プロジェクトを開いてもビルドできます。

## 使い方

1. アプリを起動する
2. 「画像を選択」ボタンをタップしてギャラリーから画像を選ぶ
3. スマートフォンを電子ペーパーディスプレイに近づける
4. 自動的に転送が開始され、進捗がステータステキストに表示される
5. "Writing complete!!" と表示されたら完了

## プロジェクト構成

```
app/src/main/
├── java/com/example/ez_sign_writer_app/
│   └── MainActivity.kt       # メイン処理（UI・NFC・画像変換・LZO圧縮）
├── res/
│   ├── layout/activity_main.xml
│   └── values/
└── AndroidManifest.xml       # NFC権限・ハードウェア要件の宣言
```

### 主要クラス

| クラス / オブジェクト | 役割 |
|----------------------|------|
| `MainActivity` | UIの構築、NFCリーダの制御、APDU通信 |
| `ImageConverter` | 画像のリサイズ・ガンマ補正・ディザリング・パッキング |
| `LzoService` | LZO1X-1による圧縮 |

## パーミッション

```xml
<uses-permission android:name="android.permission.NFC" />
<uses-feature android:name="android.hardware.nfc" android:required="true" />
```

