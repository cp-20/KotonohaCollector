# Windows版 ことのは収集IME

公式OSS版MozcのWindows TSFクライアントをベースにした、x64向けの試作版です。通常のMozcとは別のIMEとしてインストールされ、Mozcが評価した入力・候補・確定・削除・前後文脈をAndroid版と同じJSONL schema v3で収集します。

`Caps Lock` で日本語入力と半角英数入力を切り替えます。候補ウィンドウはWindows 11のMicrosoft IMEに近い配色と余白に調整しています。

収集は初期状態でOFFです。有効化後もパスワード欄は除外し、イベントはユーザーごとのWindows DPAPIで暗号化して端末内だけに保存します。入力処理中はキューへ積むだけで、暗号化とファイル書き込みは別スレッドで行います。

## ビルド

Windows 10 1809以降、Git、Visual Studio 2022のC++/ATL/Windows 11 SDK、Python 3.12以降、.NET 6以降、Bazeliskが必要です。PowerShellでリポジトリのルートから実行します。

```powershell
.\windows\scripts\Build-WindowsIme.ps1
```

MozcはAndroid版と同じコミット `851c3fe33060d2a6090363e4d7ec44fafde2c03d` に固定されます。生成先は `windows\dist\KotonohaCollector-Windows-x64.msi` です。GitHub Actionsの `windows-ime` ワークフローからも同名のMSI artifactを取得できます。

## インストールと収集

1. MSIを管理者として実行する
2. Windowsの「時刻と言語」→「言語と地域」→日本語の言語オプションで「ことのは収集IME」を追加・選択する
3. PowerShellで収集を有効にする

```powershell
.\windows\tools\KotonohaCollector.ps1 enable
.\windows\tools\KotonohaCollector.ps1 status
```

収集データは `%LOCALAPPDATA%\KotonohaCollector\kotonoha-events.bin` に暗号化保存されます。JSONLへの書き出し、停止、削除は次のとおりです。

```powershell
.\windows\tools\KotonohaCollector.ps1 export
.\windows\tools\KotonohaCollector.ps1 disable
.\windows\tools\KotonohaCollector.ps1 clear
```

`export` の第2引数に任意の出力先を指定できます。省略時はダウンロードフォルダーへ出力します。

```powershell
.\windows\tools\KotonohaCollector.ps1 export C:\data\kotonoha-events.jsonl
```

## 現在の制約

- MSIは署名していないため、Windowsの警告が表示される場合があります。
- 前後文脈を取得できる範囲は入力先アプリのTSF実装に依存します。
- 確定済み文字の削除は現時点ではUnicodeコードポイント単位の近似で、複合絵文字を完全な1文字として扱えない場合があります。
- 設定GUIは未実装で、収集操作にはPowerShellを使います。
