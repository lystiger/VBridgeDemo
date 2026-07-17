<#
  fetch_models.ps1 - download sherpa-onnx models for VBridge (VN<->EN) and lay them
  out under app\src\main\assets with the filenames the Kotlin pipeline expects.

  Run from the Android module root (the folder containing app\src\main\assets):
      powershell -ExecutionPolicy Bypass -File fetch_models.ps1
  Or pass a custom assets path:
      powershell -ExecutionPolicy Bypass -File fetch_models.ps1 -Assets "app\src\main\assets"

  Requires Windows 10 (1803+) or 11 - uses the built-in curl.exe and tar.exe.
#>
param([string]$Assets = "app\src\main\assets")

$ErrorActionPreference = 'Stop'

$Rel  = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
$Root = Join-Path (Get-Location) $Assets
$Work = New-Item -ItemType Directory -Force -Path (Join-Path $env:TEMP ("vbridge-" + [guid]::NewGuid()))

foreach ($d in @("vad","asr-vi","asr-en","tts-en","tts-vi")) {
    New-Item -ItemType Directory -Force -Path (Join-Path $Root $d) | Out-Null
}

Push-Location $Work
try {
    Write-Host ">> VAD (language-agnostic)"
    curl.exe -L --progress-bar "$Rel/asr-models/silero_vad.onnx" -o (Join-Path $Root "vad\silero_vad.onnx")

    Write-Host ">> Vietnamese ASR (offline zipformer transducer, int8)"
    $vi = "sherpa-onnx-zipformer-vi-30M-int8-2026-02-09"
    curl.exe -L --progress-bar "$Rel/asr-models/$vi.tar.bz2" -o "$vi.tar.bz2"
    tar.exe -xf "$vi.tar.bz2"
    Copy-Item "$vi\encoder.int8.onnx" (Join-Path $Root "asr-vi\encoder.onnx")
    Copy-Item "$vi\decoder.onnx"      (Join-Path $Root "asr-vi\decoder.onnx")
    Copy-Item "$vi\joiner.int8.onnx"  (Join-Path $Root "asr-vi\joiner.onnx")
    Copy-Item "$vi\tokens.txt"        (Join-Path $Root "asr-vi\tokens.txt")

    Write-Host ">> English ASR (small zipformer, int8 only)"
    $en = "sherpa-onnx-zipformer-small-en-2023-06-26"
    curl.exe -L --progress-bar "$Rel/asr-models/$en.tar.bz2" -o "$en.tar.bz2"
    tar.exe -xf "$en.tar.bz2"
    Copy-Item "$en\encoder-epoch-99-avg-1.int8.onnx" (Join-Path $Root "asr-en\encoder.onnx")
    Copy-Item "$en\decoder-epoch-99-avg-1.onnx"      (Join-Path $Root "asr-en\decoder.onnx")
    Copy-Item "$en\joiner-epoch-99-avg-1.int8.onnx"  (Join-Path $Root "asr-en\joiner.onnx")
    Copy-Item "$en\tokens.txt"                        (Join-Path $Root "asr-en\tokens.txt")

    # Piper TTS models use tokens.txt + espeak-ng-data\ (NO lexicon.txt).
    # Auto-detect the inner .onnx (skip int8) and rename to vits.onnx for a stable path.
    function Install-Piper {
        param([string]$Pkg, [string]$DestName)
        $dest = Join-Path $Root $DestName
        curl.exe -L --progress-bar "$Rel/tts-models/$Pkg.tar.bz2" -o "$Pkg.tar.bz2"
        tar.exe -xf "$Pkg.tar.bz2"
        $onnx = Get-ChildItem "$Pkg\*.onnx" | Where-Object { $_.Name -notlike '*.int8.onnx' } | Select-Object -First 1
        Copy-Item $onnx.FullName        (Join-Path $dest "vits.onnx")
        Copy-Item "$Pkg\tokens.txt"     (Join-Path $dest "tokens.txt")
        Copy-Item "$Pkg\espeak-ng-data" (Join-Path $dest "espeak-ng-data") -Recurse -Force
    }

    Write-Host ">> English TTS (Piper LibriTTS-R medium)"
    Install-Piper "vits-piper-en_US-libritts_r-medium" "tts-en"

    Write-Host ">> Vietnamese TTS (Piper VAIS-1000 medium)"
    Install-Piper "vits-piper-vi_VN-vais1000-medium" "tts-vi"
}
finally {
    Pop-Location
    Remove-Item $Work -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "Done. Layout:"
Write-Host "  $Assets\vad\silero_vad.onnx"
Write-Host "  $Assets\asr-vi\{encoder,decoder,joiner}.onnx tokens.txt"
Write-Host "  $Assets\asr-en\{encoder,decoder,joiner}.onnx tokens.txt"
Write-Host "  $Assets\tts-en\vits.onnx tokens.txt espeak-ng-data\"
Write-Host "  $Assets\tts-vi\vits.onnx tokens.txt espeak-ng-data\"
Write-Host ""
Write-Host "NOTE: TTS configs must set dataDir=espeak-ng-data (Piper), NOT lexicon."
