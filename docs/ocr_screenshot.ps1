# OCR 截图取字 —— 本机（Windows）无设备时读手机截图的可用手段
#
# 为什么需要它：会话里 read_file 读图片时，附件会被判为"非视觉模型"而丢弃，
# 只能拿到一行文字说明。但 Windows 自带 OCR 引擎（Windows.Media.Ocr）是可用的
# （本机装了 zh-Hans-CN），拿它把截图里的文字/数字读出来即可。
#
# 用法：
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File docs\ocr_screenshot.ps1 `
#       -Path "C:\...\截图.jpg" -Out "$env:TEMP\ocr.txt"
#   cat "$env:TEMP\ocr.txt"        # Git Bash 里 cat 能正确显示 UTF-8
#
# 注意：
#   · 输出文件是 UTF-8。在 Windows 控制台直接看可能乱码（控制台是 GBK），
#     用 Git Bash 的 cat，或 Python 指定 encoding='utf-8' 读。
#   · 中文标签识别率一般（项目里的 UI 中文常被认错），但**数字很准** ——
#     缓存命中这类问题看的就是数字。
#   · 首次调用 PowerShell + WinRT 有几百毫秒启动开销。

param(
    [Parameter(Mandatory = $true)][string]$Path,
    [string]$Out = "$env:TEMP\ocr_result.txt"
)

$ErrorActionPreference = 'Stop'
$sb = New-Object System.Text.StringBuilder
function Emit($s) { [void]$sb.AppendLine($s) }

Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]

function Await($WinRtTask, $ResultType) {
    $asTask = $asTaskGeneric.MakeGenericMethod($ResultType)
    $netTask = $asTask.Invoke($null, @($WinRtTask))
    $netTask.Wait(-1) | Out-Null
    $netTask.Result
}

[Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime] | Out-Null
[Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType = WindowsRuntime] | Out-Null
[Windows.Media.Ocr.OcrEngine, Windows.Media.Ocr, ContentType = WindowsRuntime] | Out-Null

$file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($Path)) ([Windows.Storage.StorageFile])
$stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
$decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
$bitmap = Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])

Emit ("== 可用 OCR 语言 ==")
[Windows.Media.Ocr.OcrEngine]::AvailableRecognizerLanguages | ForEach-Object { Emit ("  " + $_.LanguageTag) }
Emit ("== 图片 " + $decoder.PixelWidth + "x" + $decoder.PixelHeight + " ==")

$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
if ($null -eq $engine) {
    $langs = [Windows.Media.Ocr.OcrEngine]::AvailableRecognizerLanguages
    $zh = $langs | Where-Object { $_.LanguageTag -like 'zh*' } | Select-Object -First 1
    if ($zh) { $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage($zh) }
    elseif ($langs.Count -gt 0) { $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage($langs[0]) }
}
if ($null -eq $engine) {
    Emit "!! 没有可用 OCR 引擎（需要装语言包）"
    [System.IO.File]::WriteAllText($Out, $sb.ToString(), (New-Object System.Text.UTF8Encoding $false))
    exit 2
}

$result = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
Emit "== OCR 文本 =="
foreach ($line in $result.Lines) {
    $rect = ""
    $words = @($line.Words)
    if ($words.Count -gt 0) {
        $r = $words[0].BoundingRect
        $rect = "[y=" + [int][double]$r.Y + " x=" + [int][double]$r.X + "] "
    }
    Emit ($rect + $line.Text)
}

[System.IO.File]::WriteAllText($Out, $sb.ToString(), (New-Object System.Text.UTF8Encoding $false))
Write-Output ("written: " + $Out)
