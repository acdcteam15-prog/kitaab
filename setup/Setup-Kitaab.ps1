<#
  Kitaab - Windows setup and rebuild wizard.
  Works with Windows PowerShell 5.1. This file is plain ASCII on purpose.
  Started by START-HERE.bat (menu) or Rebuild-APK.bat (one-click rebuild).

  What it does: puts the project on GitHub, lets GitHub build and sign the APK, and downloads the APK.
  Nothing is built on this computer.
#>
param(
  [ValidateSet('menu', 'setup', 'rebuild', 'follow', 'status')]
  [string]$Mode = 'menu'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch { }

# ------------------------------------------------------------------ constants
$AppName      = 'Kitaab'
$WizardVersion = '2026-11-03.3'
$WorkflowFile = 'release.yml'
$ProjectDir   = Split-Path -Parent $PSScriptRoot
$ApkDir       = Join-Path $ProjectDir 'APK'
$ErrorFile    = Join-Path $ProjectDir 'build-error.txt'
$StateFile    = Join-Path $PSScriptRoot 'state.json'
$LogFile      = Join-Path $PSScriptRoot 'wizard.log'
$KeyDir       = Join-Path $env:USERPROFILE '.kitaab-signing'
$KeyFile      = Join-Path $KeyDir 'kitaab-release.jks'
$ToolsBase    = $env:LOCALAPPDATA
if (-not $ToolsBase) { $ToolsBase = $env:USERPROFILE }
$ToolsDir     = Join-Path $ToolsBase 'KitaabSetup\tools'   # outside the project, never uploaded
$FirebaseExe   = Join-Path $ToolsDir 'firebase.exe'
$PackageName  = 'com.pdfreader.app'
$GoogleJson   = Join-Path $ProjectDir 'app\google-services.json'
# Taken from the google-services.json that came with the project (used when that file is not on this PC)
$DefaultFirebaseProject = 'pdfreaderapp-e823a'
$DefaultFirebaseAppId   = '1:856189749075:android:b33660992f00cbabe983a6'
$Script:FbAccount = ''

# ------------------------------------------------------------------ output helpers
function Write-Log([string]$m) {
  try { Add-Content -Path $LogFile -Value ('[{0}] {1}' -f (Get-Date -Format 's'), $m) } catch { }
}
function Say([string]$m, [string]$c = 'Gray') { Write-Host $m -ForegroundColor $c; Write-Log $m }
function Ok([string]$m)   { Say ('  [OK]  ' + $m) 'Green' }
function Warn([string]$m) { Say ('  [!]   ' + $m) 'Yellow' }
function Bad([string]$m)  { Say ('  [X]   ' + $m) 'Red' }
function Info([string]$m) { Say ('        ' + $m) 'Gray' }
function Head([string]$m) { Say ''; Say ('=== ' + $m + ' ===') 'Cyan' }
function Ask([string]$p) { $v = Read-Host $p; if ($null -eq $v) { return '' }; return $v.Trim() }
function AskYes([string]$p) { $a = (Ask ($p + ' (y/n)')).ToLower(); return ($a -eq 'y' -or $a -eq 'yes') }

# ------------------------------------------------------------------ native commands (PS 5.1 safe)
# In Windows PowerShell 5.1 a native program that writes to stderr turns into an error record. This
# wrapper runs it with errors relaxed and returns the exit code and the text.
function Invoke-Native {
  param([string]$Exe, [string[]]$ArgList = @(), [string]$Cwd = '', [switch]$Stream, [string]$InputText = '')
  $old = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  $lines = New-Object System.Collections.Generic.List[string]
  $code = -1
  $pushed = $false
  try {
    if ($Cwd) { Push-Location $Cwd; $pushed = $true }
    $shown = @()
    $hide = $false
    foreach ($a in $ArgList) {
      if ($hide) { $shown += '***'; $hide = $false; continue }
      $shown += $a
      if ($a -eq '--body' -or $a -eq '-storepass' -or $a -eq '-keypass') { $hide = $true }
    }
    Write-Log ('RUN ' + $Exe + ' ' + ($shown -join ' '))
    $global:LASTEXITCODE = 0
    $handler = {
      param($item)
      if ($item -is [System.Management.Automation.ErrorRecord]) { $t = [string]$item.Exception.Message } else { $t = [string]$item }
      $t = $t -replace '\x1b\[[0-9;]*[A-Za-z]', ''
      $t = $t -replace 'System\.Management\.Automation\.RemoteException', ''
      if ($t.Trim().Length -gt 0) { $lines.Add($t); if ($Stream) { Write-Host $t } }
    }
    if ($InputText) {
      $InputText | & $Exe @ArgList 2>&1 | ForEach-Object { & $handler $_ }
    } else {
      & $Exe @ArgList 2>&1 | ForEach-Object { & $handler $_ }
    }
    $code = $global:LASTEXITCODE
    if ($null -eq $code) { $code = 0 }
  } catch {
    $lines.Add([string]$_.Exception.Message)
    $code = 9009
  } finally {
    if ($pushed) { Pop-Location }
    $ErrorActionPreference = $old
  }
  Write-Log ('EXIT ' + $code)
  return [pscustomobject]@{ Code = [int]$code; Out = ($lines -join "`n"); Lines = $lines.ToArray() }
}

function Have([string]$n) { return [bool](Get-Command $n -ErrorAction SilentlyContinue) }

function Update-SessionPath {
  $m = [Environment]::GetEnvironmentVariable('Path', 'Machine')
  $u = [Environment]::GetEnvironmentVariable('Path', 'User')
  $extra = @()
  if ($env:ProgramFiles) { $extra += (Join-Path $env:ProgramFiles 'Git\cmd'); $extra += (Join-Path $env:ProgramFiles 'GitHub CLI') }
  $env:Path = (($extra + @($m, $u)) -join ';')
}

function Install-Winget([string]$Id, [string]$Label) {
  if (-not (Have 'winget')) {
    throw ('winget is missing. Open the Microsoft Store, install "App Installer", then run this again. (Needed to install ' + $Label + ')')
  }
  Info ('Installing ' + $Label + ' (a few minutes, please wait)...')
  $r = Invoke-Native 'winget' @('install', '--id', $Id, '-e', '--silent', '--accept-package-agreements', '--accept-source-agreements') -Stream
  Update-SessionPath
  return $r.Code
}

# ------------------------------------------------------------------ saved answers
function Load-State {
  $Script:State = @{}
  if (Test-Path $StateFile) {
    try {
      $o = Get-Content -Raw -Path $StateFile | ConvertFrom-Json
      foreach ($p in $o.PSObject.Properties) { $Script:State[$p.Name] = $p.Value }
    } catch { $Script:State = @{} }
  }
}
function Save-State { try { ($Script:State | ConvertTo-Json) | Set-Content -Path $StateFile -Encoding ASCII } catch { } }
function Get-St([string]$k) { if ($Script:State.ContainsKey($k)) { return $Script:State[$k] }; return $null }
function Set-St([string]$k, $v) { $Script:State[$k] = $v; Save-State }

# ------------------------------------------------------------------ tools: Git and GitHub CLI
function Ensure-Tools {
  Head 'Tools (Git and GitHub CLI)'
  Update-SessionPath
  if (-not (Have 'git')) { [void](Install-Winget 'Git.Git' 'Git'); Update-SessionPath }
  if (-not (Have 'git')) { throw 'Git was installed but is not visible yet. Close this window and run START-HERE.bat again.' }
  Ok 'Git is ready.'
  if (-not (Have 'gh')) { [void](Install-Winget 'GitHub.cli' 'GitHub CLI'); Update-SessionPath }
  if (-not (Have 'gh')) { throw 'GitHub CLI was installed but is not visible yet. Close this window and run START-HERE.bat again.' }
  Ok 'GitHub CLI is ready.'
}

function Ensure-GitHubLogin {
  Head 'GitHub sign-in'
  $r = Invoke-Native 'gh' @('auth', 'status')
  if ($r.Code -eq 0) {
    Ok 'Already signed in to GitHub.'
    if ($r.Out -notmatch 'workflow') {
      Info 'GitHub needs one more permission ("workflow") to upload the build recipe. A browser window opens once.'
      $old = $ErrorActionPreference
      $ErrorActionPreference = 'Continue'
      try { & gh auth refresh --hostname github.com --scopes workflow } finally { $ErrorActionPreference = $old }
    }
    return
  }
  Info 'A browser window opens. Sign in to GitHub there and type the code that this window shows.'
  Info 'If you have no GitHub account yet: create a free one at github.com first, then come back.'
  $old = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try { & gh auth login --web --git-protocol https --hostname github.com --scopes 'repo,workflow,read:org' } finally { $ErrorActionPreference = $old }
  $r = Invoke-Native 'gh' @('auth', 'status')
  if ($r.Code -ne 0) { throw 'GitHub sign-in did not finish. Run the wizard again and complete the browser sign-in.' }
  [void](Invoke-Native 'gh' @('auth', 'setup-git'))
  Ok 'Signed in to GitHub.'
}

function Get-GhLogin {
  $r = Invoke-Native 'gh' @('api', 'user', '--jq', '.login')
  if ($r.Code -ne 0) { throw ('Could not read your GitHub name: ' + $r.Out) }
  return $r.Out.Trim()
}

# ------------------------------------------------------------------ repository (create or attach)
function Test-RepoExists([string]$full) {
  $r = Invoke-Native 'gh' @('repo', 'view', $full, '--json', 'name')
  return ($r.Code -eq 0)
}

function Get-GitDirOk { return (Test-Path (Join-Path $ProjectDir '.git')) }

function Ensure-Repository {
  Head 'GitHub repository'
  $saved = Get-St 'repo'
  if ($saved -and (Test-RepoExists $saved)) { Ok ('Repository: ' + $saved); return $saved }

  $login = Get-GhLogin
  Info ('Signed in as ' + $login + '.')
  Say ''
  Say '  1) Create a NEW repository (first time)' 'White'
  Say '  2) Use a repository that already exists (for example a new unzip of an older project)' 'White'
  $choice = Ask 'Choose 1 or 2'
  if ($choice -eq '2') {
    $name = Ask ('Repository name or owner/name (example: ' + $login + '/kitaab)')
    if ($name -notmatch '/') { $name = $login + '/' + $name }
    if (-not (Test-RepoExists $name)) { throw ('The repository ' + $name + ' was not found. Check the name and that you are signed in to the right GitHub account.') }
    $full = $name
  } else {
    $name = Ask 'Name for the new repository (press Enter for kitaab)'
    if (-not $name) { $name = 'kitaab' }
    $name = ($name -replace '[^A-Za-z0-9._-]', '-')
    $full = $login + '/' + $name
    Say ''
    Say '  A private repository keeps your code hidden. Updates then need a GitHub token inside the app (Updates screen).' 'Gray'
    Say '  A public repository lets every phone update without a token; the APK file becomes public.' 'Gray'
    $pub = AskYes 'Make it PUBLIC (easiest for in-app updates)?'
    $vis = 'private'; if ($pub) { $vis = 'public' }
    $r = Invoke-Native 'gh' @('repo', 'create', $full, ('--' + $vis), '--description', 'Kitaab Android app') -Stream
    if ($r.Code -ne 0) { throw ('Could not create the repository: ' + $r.Out) }
  }
  Set-St 'repo' $full
  Ok ('Repository: ' + $full)
  return $full
}

# NOTE: not named Git - PowerShell names are case-insensitive and that would call itself forever.
function Run-Git([string[]]$a) { return (Invoke-Native 'git.exe' $a -Cwd $ProjectDir) }

function Ensure-GitIdentity {
  $n = (Run-Git @('config', 'user.name')).Out.Trim()
  $e = (Run-Git @('config', 'user.email')).Out.Trim()
  if (-not $n -or -not $e) {
    $login = Get-GhLogin
    if (-not $n) { [void](Run-Git @('config', 'user.name', $login)) }
    if (-not $e) { [void](Run-Git @('config', 'user.email', ($login + '@users.noreply.github.com'))) }
  }
}

# Lines that must be in .gitignore (secrets, downloads and wizard output never go to GitHub).
function Ensure-GitIgnore {
  $f = Join-Path $ProjectDir '.gitignore'
  $need = @('*.jks', '*.keystore', 'keystore.properties', 'app/google-services.json', 'APK/', 'build-error.txt', 'gradle.log', 'setup/state.json', 'setup/wizard.log', 'setup/tools/', '/Booky/')
  $have = @()
  if (Test-Path $f) { $have = @(Get-Content $f | ForEach-Object { $_.Trim() }) }
  $add = @($need | Where-Object { $have -notcontains $_ })
  if ($add.Count -gt 0) {
    $text = ''
    if (Test-Path $f) { $text = [IO.File]::ReadAllText($f) }
    if ($text.Length -gt 0 -and -not $text.EndsWith("`n")) { $text += "`r`n" }
    $text += (($add -join "`r`n") + "`r`n")
    [IO.File]::WriteAllText($f, $text)
    Info ('Added to .gitignore: ' + ($add -join ', '))
  }
}

# GitHub refuses files over 100 MB. Take anything over 50 MB out of the upload (and out of future ones).
function Unstage-LargeFiles {
  $r = Run-Git @('diff', '--cached', '--name-only', '--diff-filter=AM')
  foreach ($name in @($r.Lines)) {
    $n = $name.Trim()
    if (-not $n) { continue }
    $full = Join-Path $ProjectDir ($n -replace '/', '\')
    if ((Test-Path -LiteralPath $full) -and ((Get-Item -LiteralPath $full -Force).Length -gt 50MB)) {
      [void](Run-Git @('rm', '--cached', '-q', '--', $n))
      $gi = Join-Path $ProjectDir '.gitignore'
      Add-Content -Path $gi -Value $n
      Warn ('Left out of the upload because it is too big for GitHub: ' + $n)
    }
  }
}

# Last line of defence: if our own (not yet pushed) commit contains a file over 50 MB, take it out of the commit.
function Remove-LargeTracked([string]$branch) {
  $head = (Run-Git @('rev-parse', 'HEAD')).Out.Trim()
  $remote = (Run-Git @('rev-parse', ('origin/' + $branch))).Out.Trim()
  if (-not $head -or $head -eq $remote) { return }   # nothing of ours to fix
  $r = Run-Git @('ls-tree', '-r', '-l', 'HEAD')
  $big = @()
  foreach ($l in @($r.Lines)) {
    if ($l -match '^\d+\s+blob\s+[0-9a-f]+\s+(\d+)\t(.+)$') {
      if ([int64]$Matches[1] -gt 50MB) { $big += $Matches[2].Trim() }
    }
  }
  if ($big.Count -eq 0) { return }
  $gi = Join-Path $ProjectDir '.gitignore'
  foreach ($n in $big) {
    [void](Run-Git @('rm', '--cached', '-q', '--', $n))
    Add-Content -Path $gi -Value $n
    Warn ('Removed from the upload (too big for GitHub): ' + $n)
  }
  [void](Run-Git @('add', '.gitignore'))
  $c = Run-Git @('commit', '--amend', '--no-edit')
  if ($c.Code -ne 0) { throw ('Could not remove the big file from the commit: ' + $c.Out) }
}

# Push the project. A fresh unzip has no history: attach it to the repository's existing history
# (fetch, then move the branch pointer without touching the files) so the push is a normal fast-forward.
function Push-Project([string]$full) {
  Head 'Uploading the project to GitHub'
  $url = 'https://github.com/' + $full + '.git'
  $need = @('gradlew', 'gradlew.bat', 'gradle\wrapper\gradle-wrapper.jar', 'gradle\wrapper\gradle-wrapper.properties', 'settings.gradle.kts', 'app\build.gradle.kts', 'version.properties')
  $missing = @($need | Where-Object { -not (Test-Path (Join-Path $ProjectDir $_)) })
  if ($missing.Count -gt 0) {
    throw ('These project files are missing next to START-HERE.bat: ' + ($missing -join ', ') + '. START-HERE.bat must sit in the same folder as the app folder and gradlew. Paste the new files over your WHOLE project folder and run again.')
  }
  if (-not (Get-GitDirOk)) {
    [void](Run-Git @('init'))
    Ok 'Created a local git folder.'
  }
  Ensure-GitIdentity
  $remotes = (Run-Git @('remote')).Out
  if ($remotes -match '(?m)^origin\s*$') { [void](Run-Git @('remote', 'set-url', 'origin', $url)) } else { [void](Run-Git @('remote', 'add', 'origin', $url)) }

  # Which branch does the repository use?
  $branch = 'main'
  $ls = Run-Git @('ls-remote', '--symref', 'origin', 'HEAD')
  if ($ls.Code -eq 0 -and $ls.Out -match 'ref:\s+refs/heads/(\S+)\s+HEAD') { $branch = $Matches[1] }
  $remoteHasHistory = ($ls.Code -eq 0 -and $ls.Out -match 'refs/heads/')
  if (-not $remoteHasHistory) {
    $heads = Run-Git @('ls-remote', '--heads', 'origin')
    $remoteHasHistory = ($heads.Code -eq 0 -and $heads.Out.Trim().Length -gt 0)
    if ($remoteHasHistory -and $heads.Out -match 'refs/heads/(\S+)') { $branch = $Matches[1] }
  }

  if ($remoteHasHistory) {
    $f = Run-Git @('fetch', 'origin', $branch)
    if ($f.Code -ne 0) { throw ('Could not read the repository history: ' + $f.Out) }
    # Point our branch at the remote history WITHOUT changing the files in this folder.
    [void](Run-Git @('checkout', '-B', $branch))
    $r = Run-Git @('reset', '--mixed', ('origin/' + $branch))
    if ($r.Code -ne 0) { throw ('Could not attach to the existing history: ' + $r.Out) }
    Ok ('Attached this folder to the existing history of branch ' + $branch + ' (nothing is overwritten).')
  } else {
    [void](Run-Git @('checkout', '-B', $branch))
  }

  Ensure-GitIgnore
  # The key file must not stay in the repository (it lives in a GitHub secret now)
  [void](Run-Git @('rm', '--cached', '--ignore-unmatch', '-q', 'app/google-services.json'))
  [void](Run-Git @('add', '-A'))
  Unstage-LargeFiles
  $st = Run-Git @('status', '--porcelain')
  if ($st.Out.Trim().Length -gt 0) {
    $msg = 'Update from the setup wizard ' + (Get-Date -Format 'yyyy-MM-dd HH:mm')
    $c = Run-Git @('commit', '-m', $msg)
    if ($c.Code -ne 0) { throw ('Commit failed: ' + $c.Out) }
    Ok 'Saved your changes.'
  } else {
    Info 'No file changes since the last upload.'
  }
  Remove-LargeTracked $branch
  # gradlew must be executable on the Linux build machine; Windows cannot set that bit, so set it in git.
  [void](Run-Git @('update-index', '--chmod=+x', 'gradlew'))
  $st2 = Run-Git @('status', '--porcelain')
  if ($st2.Out.Trim().Length -gt 0) { [void](Run-Git @('commit', '-m', 'Make gradlew executable')) }

  $p = Run-Git @('push', '-u', 'origin', $branch)
  if ($p.Code -ne 0) {
    throw ('Upload failed. ' + $p.Out + "`nIf it says the remote has changes, run the wizard again (it fetches them first).")
  }
  $chk = Invoke-Native 'gh' @('api', ('repos/' + $full + '/contents/.github/workflows/release.yml'), '--silent')
  if ($chk.Code -ne 0) { throw ('The upload did not reach GitHub (release.yml is not there). ' + $chk.Out) }
  Ok 'Project uploaded and checked on GitHub.'
  Set-St 'branch' $branch
  return $branch
}

# ------------------------------------------------------------------ signing key
function Get-Keytool {
  $c = Get-Command keytool -ErrorAction SilentlyContinue
  if ($c) { return $c.Source }
  foreach ($root in @($env:JAVA_HOME, (Join-Path $env:ProgramFiles 'Eclipse Adoptium'), (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'), (Join-Path $env:ProgramFiles 'Java'))) {
    if (-not $root -or -not (Test-Path $root)) { continue }
    $k = Get-ChildItem -Path $root -Recurse -Filter 'keytool.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($k) { return $k.FullName }
  }
  return $null
}

# Java is installed ONLY when a new key has to be created (keytool needs it).
function Ensure-Keytool {
  $k = Get-Keytool
  if ($k) { return $k }
  Info 'Creating a key needs Java (keytool). Installing a small Java now...'
  [void](Install-Winget 'EclipseAdoptium.Temurin.21.JDK' 'Java (Temurin 21)')
  $k = Get-Keytool
  if (-not $k) { throw 'Java was installed but keytool was not found. Close this window and run the wizard again.' }
  return $k
}

function Test-KeystoreAlias([string]$jks, [string]$storePw, [string]$alias) {
  $kt = Get-Keytool
  if (-not $kt) { return $true }
  $r = Invoke-Native $kt @('-list', '-keystore', $jks, '-storepass', $storePw, '-alias', $alias)
  return ($r.Code -eq 0)
}

# Google sign-in (Drive) only works for a signing key whose SHA-1 is registered in Google Cloud / Firebase.
function Show-Sha1([string]$jks, [string]$storePw, [string]$alias) {
  $kt = Get-Keytool
  if (-not $kt) { return }
  $r = Invoke-Native $kt @('-list', '-v', '-keystore', $jks, '-storepass', $storePw, '-alias', $alias)
  foreach ($l in $r.Lines) {
    if ($l -match 'SHA1:\s*([0-9A-Fa-f:]+)') {
      Say ''
      Warn 'Google sign-in: add this SHA-1 fingerprint to your Android app in the Firebase / Google Cloud console:'
      Say ('        ' + $Matches[1]) 'White'
      return
    }
  }
}

function Set-Secret([string]$full, [string]$name, [string]$value) {
  $r = Invoke-Native 'gh' @('secret', 'set', $name, '--repo', $full, '--body', $value)
  if ($r.Code -ne 0) { throw ('Could not save the secret ' + $name + ': ' + $r.Out) }
}

function Save-SigningSecrets([string]$full, [string]$jks, [string]$storePw, [string]$alias, [string]$keyPw) {
  $b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($jks))
  Set-Secret $full 'KEYSTORE_BASE64' $b64
  Set-Secret $full 'KEYSTORE_PASSWORD' $storePw
  Set-Secret $full 'KEY_ALIAS' $alias
  Set-Secret $full 'KEY_PASSWORD' $keyPw
  Set-St 'signing_done_for' $full
  Ok 'Signing key saved safely in GitHub (as secrets).'
}

function Ensure-Signing([string]$full) {
  Head 'Signing key'
  if ((Get-St 'signing_done_for') -eq $full) {
    $r = Invoke-Native 'gh' @('secret', 'list', '--repo', $full)
    if ($r.Code -eq 0 -and $r.Out -match 'KEYSTORE_BASE64' -and $r.Out -match 'KEY_PASSWORD') { Ok 'Signing key is already in GitHub.'; return }
  }
  Say 'Android only updates an app in place when the new APK is signed with the SAME key as the app on your phone.' 'White'
  Say ''
  Say '  1) Use the Android Studio DEBUG key  (best if the app on your phone was installed from Android Studio)' 'White'
  Say '  2) Use my own .jks key file' 'White'
  Say '  3) Create a NEW key  (the app on your phone must then be uninstalled once; its data is lost)' 'White'
  $c = Ask 'Choose 1, 2 or 3'

  if ($c -eq '1') {
    $jks = Join-Path $env:USERPROFILE '.android\debug.keystore'
    if (-not (Test-Path $jks)) {
      throw ('The Android Studio debug key was not found at ' + $jks + '. It exists on the PC where Android Studio built the app. Choose option 2 or 3, or run this wizard on that PC.')
    }
    Info ('Found: ' + $jks)
    Save-SigningSecrets $full $jks 'android' 'androiddebugkey' 'android'
    return
  }
  if ($c -eq '2') {
    $jks = (Ask 'Full path of your .jks file (you can drag the file into this window)').Trim('"')
    if (-not (Test-Path $jks)) { throw 'That file was not found.' }
    $sp = Ask 'Store password'
    $al = Ask 'Key alias'
    $kp = Ask 'Key password (press Enter if it is the same as the store password)'
    if (-not $kp) { $kp = $sp }
    if (-not (Test-KeystoreAlias $jks $sp $al)) { throw 'The password or alias does not match this key file. Nothing was saved. Try again.' }
    Save-SigningSecrets $full $jks $sp $al $kp
    Show-Sha1 $jks $sp $al
    return
  }
  if ($c -eq '3') {
    if (Test-Path $KeyFile) {
      Warn ('A key already exists at ' + $KeyFile)
      if (-not (AskYes 'Use that existing key instead of making another?')) { throw 'Stopped. Move or delete that file first if you really want a new key.' }
      $sp = Get-St 'new_key_password'
      if (-not $sp) { $sp = Ask 'Password of that key' }
      Save-SigningSecrets $full $KeyFile $sp 'kitaab' $sp
      return
    }
    $kt = Ensure-Keytool
    New-Item -ItemType Directory -Force -Path $KeyDir | Out-Null
    $chars = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
    $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
    $bytes = New-Object byte[] 20
    $rng.GetBytes($bytes)
    $pw = -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
    $r = Invoke-Native $kt @('-genkeypair', '-v', '-keystore', $KeyFile, '-alias', 'kitaab', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-storepass', $pw, '-keypass', $pw, '-dname', 'CN=Kitaab, O=Kitaab, C=AE')
    if ($r.Code -ne 0) { throw ('Could not create the key: ' + $r.Out) }
    Set-St 'new_key_password' $pw
    Say ''
    Ok ('New key created: ' + $KeyFile)
    Warn 'KEEP A COPY of that file and of state.json (it holds the password). Lose the key and phones can no longer update.'
    Save-SigningSecrets $full $KeyFile $pw 'kitaab' $pw
    Show-Sha1 $KeyFile $pw 'kitaab'
    return
  }
  throw 'Please choose 1, 2 or 3.'
}

# ------------------------------------------------------------------ Firebase / Google key file
# google-services.json holds the API key. It is kept OUT of the repository: the wizard stores it in GitHub as a
# secret and the build rebuilds the file from it. The wizard also fetches it from Firebase by itself (Firebase CLI).

function Ensure-FirebaseCli {
  if (Test-Path $FirebaseExe) { return }
  Info 'Downloading the Firebase CLI (one time, about 100 MB)...'
  New-Item -ItemType Directory -Force -Path $ToolsDir | Out-Null
  $url = 'https://github.com/firebase/firebase-tools/releases/latest/download/firebase-tools-win.exe'
  try { Invoke-WebRequest -Uri $url -OutFile $FirebaseExe -UseBasicParsing } catch {
    if (Test-Path $FirebaseExe) { Remove-Item -Force $FirebaseExe }
    throw ('Could not download the Firebase CLI: ' + $_.Exception.Message)
  }
  if (-not (Test-Path $FirebaseExe) -or (Get-Item $FirebaseExe).Length -lt 1000000) {
    if (Test-Path $FirebaseExe) { Remove-Item -Force $FirebaseExe }
    throw 'The Firebase CLI download is incomplete. Check the internet connection and run this again.'
  }
  Ok 'Firebase CLI is ready.'
}

function Get-FirebaseAccounts {
  $r = Invoke-Native $FirebaseExe @('login:list')
  $list = @()
  foreach ($l in $r.Lines) {
    if ($l -match 'Logged in as\s+(\S+@\S+)') { $list += $Matches[1] }
    elseif ($l -match '^\s*-\s+(\S+@\S+)') { $list += $Matches[1] }
  }
  return @($list | Select-Object -Unique)
}

function Add-FirebaseAccount([bool]$first) {
  Info 'A browser window opens. Sign in with the Google account that owns your Firebase project.'
  $old = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    if ($first) { & $FirebaseExe login } else { & $FirebaseExe login:add }
  } finally { $ErrorActionPreference = $old }
}

# Asks which Google account to use for Firebase. The choice is remembered; $forceAsk asks again.
function Select-FirebaseAccount([bool]$forceAsk) {
  $accounts = @(Get-FirebaseAccounts)
  if ($accounts.Count -eq 0) {
    Add-FirebaseAccount $true
    $accounts = @(Get-FirebaseAccounts)
    if ($accounts.Count -eq 0) { throw 'Firebase sign-in did not finish.' }
  }
  $saved = [string](Get-St 'firebase_account')
  if (-not $forceAsk -and $saved -and ($accounts -contains $saved)) { $Script:FbAccount = $saved; return }
  while ($true) {
    Say ''
    Say 'Which Google account owns your Firebase project?' 'White'
    for ($i = 0; $i -lt $accounts.Count; $i++) { Say ('  ' + ($i + 1) + ') ' + $accounts[$i]) 'Gray' }
    $addNo = $accounts.Count + 1
    Say ('  ' + $addNo + ') Add another Google account (a browser opens)') 'Gray'
    $c = Ask 'Choose a number'
    if ($c -notmatch '^\d+$') { continue }
    $n = [int]$c
    if ($n -eq $addNo) { Add-FirebaseAccount $false; $accounts = @(Get-FirebaseAccounts); continue }
    if ($n -ge 1 -and $n -le $accounts.Count) {
      $Script:FbAccount = $accounts[$n - 1]
      Set-St 'firebase_account' $Script:FbAccount
      Ok ('Using ' + $Script:FbAccount)
      return
    }
  }
}

# Runs the Firebase CLI as the chosen account.
function Invoke-Firebase([string[]]$a) {
  $x = $a
  if ($Script:FbAccount) { $x = $a + @('--account', $Script:FbAccount) }
  return (Invoke-Native $FirebaseExe $x)
}

# The Firebase CLI prints spinner lines around its JSON: cut from the first { to the last } before parsing.
function Parse-FbJson([string]$text) {
  if (-not $text) { return $null }
  $a = $text.IndexOf('{')
  $b = $text.LastIndexOf('}')
  if ($a -lt 0 -or $b -le $a) { return $null }
  try { return ($text.Substring($a, $b - $a + 1) | ConvertFrom-Json) } catch { return $null }
}

# The old google-services.json tells us the project id and the app id.
function Read-GoogleJson([string]$file) {
  if (-not (Test-Path $file)) { return $null }
  try {
    $j = Get-Content -Raw -Path $file | ConvertFrom-Json
    $appId = ''
    foreach ($c in @($j.client)) {
      if ($c.client_info.android_client_info.package_name -eq $PackageName) { $appId = [string]$c.client_info.mobilesdk_app_id }
    }
    return @{ ProjectId = [string]$j.project_info.project_id; AppId = $appId }
  } catch { return $null }
}

function Get-FirebaseProjectId {
  $saved = Get-St 'firebase_project'
  if ($saved) { return [string]$saved }
  $old = Read-GoogleJson $GoogleJson
  if ($old -and $old.ProjectId) { return $old.ProjectId }
  return $DefaultFirebaseProject
}

# Makes sure the Android app exists in Firebase, optionally registers the signing SHA-1, and writes the fresh
# google-services.json. Returns $true when the file content changed.
function Sync-FirebaseConfig([string]$sha1, [bool]$forceAsk = $false) {
  Ensure-FirebaseCli
  Select-FirebaseAccount $forceAsk
  $project = Get-FirebaseProjectId
  Set-St 'firebase_project' $project
  Info ('Firebase project: ' + $project)

  $appId = ''
  $old = Read-GoogleJson $GoogleJson
  if ($old) { $appId = $old.AppId }
  if (-not $appId -and $project -eq $DefaultFirebaseProject) { $appId = $DefaultFirebaseAppId }
  $r = Invoke-Firebase @('apps:list', 'ANDROID', '--project', $project, '--json')
  if ($r.Code -ne 0) { throw ('Could not read the apps of Firebase project ' + $project + ' with this account: ' + $r.Out) }
  $apps = @()
  $parsed = Parse-FbJson $r.Out
  $listOk = ($null -ne $parsed -and $null -ne $parsed.result)
  if ($listOk) { $apps = @($parsed.result) }
  $found = $null
  if ($appId) { $found = $apps | Where-Object { $_.appId -eq $appId } | Select-Object -First 1 }
  if (-not $found -and $apps.Count -eq 1 -and -not $appId) { $found = $apps[0] }
  if ($found) {
    $appId = [string]$found.appId
  } elseif ($appId -and -not $listOk) {
    # The list could not be read, but we know the app id: use it (never create a second app by mistake)
    Info 'Using the known Firebase app id.'
  } else {
    Info ('Creating the Android app ' + $PackageName + ' in Firebase...')
    $c = Invoke-Firebase @('apps:create', 'ANDROID', 'Kitaab', '--package-name', $PackageName, '--project', $project, '--json')
    $cp = Parse-FbJson $c.Out
    if ($c.Code -ne 0 -or $null -eq $cp -or -not $cp.result.appId) { throw ('Could not create the Firebase app: ' + $c.Out) }
    $appId = [string]$cp.result.appId
  }
  Ok ('Firebase app: ' + $appId)

  if ($sha1) {
    $s = Invoke-Firebase @('apps:android:sha:create', $appId, $sha1, '--project', $project)
    if ($s.Code -eq 0) { Ok 'Signing SHA-1 registered in Firebase (Google sign-in key).' }
    elseif ($s.Out -match '(?i)already') { Ok 'Signing SHA-1 was already registered.' }
    else { Warn ('Could not register the SHA-1: ' + $s.Out) }
  }

  $tmp = Join-Path $env:TEMP 'kitaab-google-services.json'
  if (Test-Path $tmp) { Remove-Item -Force $tmp }
  $g = Invoke-Firebase @('apps:sdkconfig', 'ANDROID', $appId, '--project', $project, '--out', $tmp)
  if ($g.Code -ne 0 -or -not (Test-Path $tmp)) { throw ('Could not download google-services.json: ' + $g.Out) }
  $new = Get-Content -Raw -Path $tmp
  $cur = ''
  if (Test-Path $GoogleJson) { $cur = Get-Content -Raw -Path $GoogleJson }
  Remove-Item -Force $tmp
  if ($new.Trim() -eq $cur.Trim()) { return $false }
  Set-Content -Path $GoogleJson -Value $new -Encoding UTF8
  Ok 'Fresh google-services.json saved in the app folder.'
  return $true
}

function Ensure-FirebaseConfig([string]$full) {
  Head 'Firebase / Google configuration (google-services.json)'
  $ok = $false
  $ask = $false
  for ($try = 1; $try -le 4; $try++) {
    try { [void](Sync-FirebaseConfig '' $ask); $ok = $true; break }
    catch {
      Warn ('Automatic Firebase setup did not work: ' + $_.Exception.Message)
      if (-not (AskYes 'Try again with a different Google account or project?')) { break }
      $ask = $true
      $cur = [string](Get-St 'firebase_project')
      if (-not $cur) { $cur = $DefaultFirebaseProject }
      $p = Ask ('Firebase project ID (Enter = ' + $cur + ')')
      if ($p) { Set-St 'firebase_project' $p }
    }
  }
  if (-not $ok) {
    if (-not (Test-Path $GoogleJson)) { throw 'There is no app\google-services.json to fall back on. Run this again and choose the right account.' }
    Warn 'Using the google-services.json that is already in the app folder.'
  }
  Upload-GoogleJson $full
}

function Upload-GoogleJson([string]$full) {
  if (Test-Path $GoogleJson) {
    $b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($GoogleJson))
    Set-Secret $full 'GOOGLE_SERVICES_JSON_BASE64' $b64
    Ok 'google-services.json saved safely in GitHub (as a secret). It is not uploaded with the project.'
    return
  }
  $r = Invoke-Native 'gh' @('secret', 'list', '--repo', $full)
  if ($r.Code -eq 0 -and $r.Out -match 'GOOGLE_SERVICES_JSON_BASE64') { Ok 'google-services.json is already in GitHub.'; return }
  throw 'The file app\google-services.json is missing.'
}

# After the first signed build: register its SHA-1 in Firebase (this also creates the Google sign-in OAuth client),
# fetch the updated google-services.json and, when it changed, tell the caller that one more build is needed.
function Register-Fingerprint([string]$full) {
  $fp = Get-FingerprintText
  if (-not $fp) { return $false }
  if ((Get-St 'sha_registered') -eq $fp) { return $false }
  Head 'Google sign-in key'
  try {
    $changed = Sync-FirebaseConfig $fp
    Set-St 'sha_registered' $fp
    $project = [string](Get-St 'firebase_project')
    Say ''
    Warn 'One thing only you can do (Google has no automatic way): switch on the Google Drive API for your project:'
    Say ('        https://console.cloud.google.com/apis/library/drive.googleapis.com?project=' + $project) 'White'
    Warn 'and add your Google account under Test users:'
    Say ('        https://console.cloud.google.com/apis/credentials/consent?project=' + $project) 'White'
    if ($changed) { Upload-GoogleJson $full }
    return $changed
  } catch {
    Warn ('Could not register the SHA-1 automatically: ' + $_.Exception.Message)
    return $false
  }
}

# ------------------------------------------------------------------ release notes + version
function Get-VersionName {
  $f = Join-Path $ProjectDir 'version.properties'
  if (Test-Path $f) {
    foreach ($l in Get-Content $f) { if ($l -match '^\s*VERSION_NAME\s*=\s*(\S+)') { return $Matches[1] } }
  }
  return '?'
}

function Get-ReleaseNotes {
  Say ''
  Say 'What is new in this build? One short line is enough. It is shown in the app.' 'White'
  Say 'Add the word [required] to force every phone to update.' 'Gray'
  $n = Ask 'Release notes (Enter to skip)'
  if (-not $n) { $n = 'Kitaab ' + (Get-VersionName) }
  return $n
}

# ------------------------------------------------------------------ run, watch, download
function Start-ReleaseRun([string]$full, [string]$branch, [string]$notes) {
  Head 'Starting the build on GitHub'
  # gh prints the link of the new run; the number at the end of it is the run id (no clock comparison needed).
  $r = $null
  for ($try = 1; $try -le 6; $try++) {
    $r = Invoke-Native 'gh' @('workflow', 'run', $WorkflowFile, '--repo', $full, '--ref', $branch, '-f', ('notes=' + $notes))
    if ($r.Code -eq 0 -or $r.Out -notmatch '404') { break }
    Info 'GitHub is still reading the upload, trying again in 10 seconds...'
    Start-Sleep -Seconds 10
  }
  if ($r.Code -ne 0) {
    throw ('Could not start the build: ' + $r.Out + "`nIf it says the workflow was not found, wait a minute and run again (GitHub is still reading the upload).")
  }
  $id = $null
  if ($r.Out -match '/actions/runs/(\d+)') { $id = $Matches[1] }
  if (-not $id) {
    # Older gh versions print nothing: take the newest run of this workflow that was just created.
    Start-Sleep -Seconds 5
    $l = Invoke-Native 'gh' @('run', 'list', '--repo', $full, '--workflow', $WorkflowFile, '--limit', '1', '--json', 'databaseId', '--jq', '.[0].databaseId')
    if ($l.Code -eq 0 -and $l.Out.Trim() -match '^\d+$') { $id = $l.Out.Trim() }
  }
  if (-not $id) { throw 'The build was started but its number could not be read. Use menu option "Follow a build that is already running".' }
  Set-St 'last_run' $id
  Ok ('Build started: run ' + $id)
  return $id
}

function Get-RunState([string]$full, [string]$id) {
  $r = Invoke-Native 'gh' @('run', 'view', $id, '--repo', $full, '--json', 'status,conclusion')
  if ($r.Code -ne 0) { return @{ Status = 'unknown'; Conclusion = '' } }
  try {
    $o = $r.Out | ConvertFrom-Json
    $c = ''; if ($o.conclusion) { $c = [string]$o.conclusion }
    return @{ Status = [string]$o.status; Conclusion = $c }
  } catch { return @{ Status = 'unknown'; Conclusion = '' } }
}

function Watch-Run([string]$full, [string]$id) {
  Head ('Waiting for the build (run ' + $id + ')')
  Info 'The first build takes 10-15 minutes, later ones 4-8. You can leave this window open and do something else.'
  Info ('Live page: https://github.com/' + $full + '/actions/runs/' + $id)
  $start = Get-Date
  $lastText = ''
  $failures = 0
  while ($true) {
    $s = Get-RunState $full $id
    if ($s.Status -eq 'unknown') {
      $failures++
      if ($failures -gt 20) { throw 'Lost contact with GitHub. Check your internet, then use "Follow a build that is already running".' }
    } else { $failures = 0 }
    $mins = [int]((Get-Date) - $start).TotalMinutes
    $text = ('  status: ' + $s.Status + '  (' + $mins + ' min)')
    if ($text -ne $lastText) { Say $text 'Gray'; $lastText = $text }
    if ($s.Status -eq 'completed') { return $s.Conclusion }
    Start-Sleep -Seconds 15
  }
}

function Save-BuildError([string]$full, [string]$id) {
  $r = Invoke-Native 'gh' @('run', 'view', $id, '--repo', $full, '--log-failed')
  $clean = New-Object System.Collections.Generic.List[string]
  foreach ($l in @($r.Lines)) {
    $t = ($l -replace '^[^\t]*\t[^\t]*\t\d{4}-\S*Z ?', '').TrimEnd()
    if ($t -match '^\s+at ' -or $t -match '^\s*\.\.\. \d+ more') { continue }
    $clean.Add($t)
  }
  $keep = New-Object System.Collections.Generic.List[string]
  $ctx = 0
  $pat = '(?i)(^e:\s|error:|FAILED|Execution failed|Unresolved reference|Type mismatch|Could not|AAPT|\.kt:\d+|\.xml:\d+|Caused by|BUILD FAILED|::error|^\s*>\s)'
  foreach ($t in $clean) {
    if ($t -match 'What went wrong') { $ctx = 14 }
    if ($ctx -gt 0) { $keep.Add($t); $ctx--; continue }
    if ($t -match $pat) { $keep.Add($t) }
  }
  $head = @('Kitaab build error report', ('Repository: ' + $full), ('Run: ' + $id), ('Link: https://github.com/' + $full + '/actions/runs/' + $id), ('Time: ' + (Get-Date -Format 's')), '')
  $body = @($keep | Select-Object -Unique | Select-Object -First 90)
  $tail = @($clean | Select-Object -Last 25)
  $out = $head + $body + @('', '--- last lines of the log ---') + $tail
  Set-Content -Path $ErrorFile -Value $out -Encoding ASCII
  Bad ('The build failed. A short error report was saved here:')
  Info $ErrorFile
  Info 'Send me the text of build-error.txt and I will fix it.'
}

# The certificate SHA-1 that Google needs, read from the signed build (APK\signature.txt). Colon format, or $null.
function Get-FingerprintText {
  $f = Join-Path $ApkDir 'signature.txt'
  if (-not (Test-Path $f)) { return $null }
  foreach ($l in (Get-Content $f)) {
    if ($l -match 'SHA-1 digest:\s*([0-9A-Fa-f]{40})') {
      $hex = $Matches[1].ToUpper()
      $pairs = @()
      for ($i = 0; $i -lt 40; $i += 2) { $pairs += $hex.Substring($i, 2) }
      return ($pairs -join ':')
    }
  }
  return $null
}

function Show-Fingerprint {
  $fp = Get-FingerprintText
  if (-not $fp) { Warn 'No fingerprint yet: run a build first (menu 2).'; return }
  Say ''
  Warn 'Signing SHA-1 of your APK (Google sign-in key):'
  Say ('        ' + $fp) 'White'
}

function Get-Apk([string]$full, [string]$id) {
  Head 'Downloading the APK'
  New-Item -ItemType Directory -Force -Path $ApkDir | Out-Null
  $tmp = Join-Path $ApkDir '_download'
  if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
  New-Item -ItemType Directory -Force -Path $tmp | Out-Null
  $r = Invoke-Native 'gh' @('run', 'download', $id, '--repo', $full, '--name', 'Kitaab-release-apk', '--dir', $tmp) -Stream
  if ($r.Code -ne 0) { throw ('Could not download the APK: ' + $r.Out) }
  $apks = Get-ChildItem -Path $tmp -Filter '*.apk' -Recurse
  if (-not $apks) { throw 'The build finished but no APK was found in it.' }
  $last = $null
  foreach ($a in $apks) {
    $dest = Join-Path $ApkDir $a.Name
    Copy-Item -Force $a.FullName $dest
    $last = $dest
  }
  foreach ($h in (Get-ChildItem -Path $tmp -Filter '*.sha256' -Recurse)) { Copy-Item -Force $h.FullName (Join-Path $ApkDir $h.Name) }
  foreach ($h in (Get-ChildItem -Path $tmp -Filter 'signature.txt' -Recurse)) { Copy-Item -Force $h.FullName (Join-Path $ApkDir 'signature.txt') }
  Remove-Item -Recurse -Force $tmp
  if (Test-Path $ErrorFile) { Remove-Item -Force $ErrorFile }
  Say ''
  Ok 'DONE. Your signed APK is here:'
  Say ('        ' + $last) 'White'
  Say ''
  Show-Fingerprint
  Info 'Phones that already have Kitaab 1.1.0 or newer update by themselves (Home banner / notification).'
  Info 'The first time, install this APK on the phone by hand. Android keeps your data when the signing key is the same.'
  try { Start-Process explorer.exe $ApkDir } catch { }
  return $last
}

# ------------------------------------------------------------------ flows
function Finish-Run([string]$full, [string]$id, [bool]$allowRebuild = $true) {
  $conclusion = Watch-Run $full $id
  if ($conclusion -ne 'success') { Save-BuildError $full $id; return }
  [void](Get-Apk $full $id)
  if (-not $allowRebuild) { return }
  if (Register-Fingerprint $full) {
    Say ''
    Say 'The Firebase file changed (it now holds the Google sign-in key). Building once more automatically...' 'Cyan'
    $branch = Get-St 'branch'
    if (-not $branch) { $branch = 'main' }
    $id2 = Start-ReleaseRun $full $branch 'Google sign-in key added'
    Finish-Run $full $id2 $false
  }
}

function Invoke-Setup([bool]$rebuild) {
  Ensure-Tools
  Ensure-GitHubLogin
  $full = Ensure-Repository
  Ensure-Signing $full
  Ensure-FirebaseConfig $full
  $branch = Push-Project $full
  $notes = Get-ReleaseNotes
  $id = Start-ReleaseRun $full $branch $notes
  Finish-Run $full $id
}

function Invoke-Rebuild {
  Ensure-Tools
  Ensure-GitHubLogin
  $full = Get-St 'repo'
  if (-not $full) { Say 'This is the first run, so the full setup runs now.' 'Yellow'; Invoke-Setup $false; return }
  Ok ('Repository: ' + $full)
  Ensure-Signing $full
  Ensure-FirebaseConfig $full
  $branch = Push-Project $full
  $notes = Get-ReleaseNotes
  $id = Start-ReleaseRun $full $branch $notes
  Finish-Run $full $id
}

function Invoke-Follow {
  Ensure-Tools
  Ensure-GitHubLogin
  $full = Get-St 'repo'
  if (-not $full) { throw 'No repository is saved yet. Run the setup first.' }
  $r = Invoke-Native 'gh' @('run', 'list', '--repo', $full, '--workflow', $WorkflowFile, '--limit', '5', '--json', 'databaseId,status,conclusion,createdAt')
  if ($r.Code -ne 0) { throw ('Could not list the builds: ' + $r.Out) }
  $runs = @()
  try { $runs = @($r.Out | ConvertFrom-Json) } catch { $runs = @() }
  if ($runs.Count -eq 0) { throw 'No builds were found.' }
  Say 'Latest builds (newest first):' 'White'
  foreach ($x in $runs) { Say ('  ' + $x.databaseId + '   ' + $x.status + '   ' + $x.conclusion + '   ' + $x.createdAt) 'Gray' }
  $defId = [string]$runs[0].databaseId
  $id = Ask ('Run number to follow (Enter = ' + $defId + ')')
  if (-not $id) { $id = $defId }
  if ($id -notmatch '^\d+$') { throw 'A run number has only digits.' }
  Set-St 'last_run' $id
  Finish-Run $full $id
}

function Invoke-Status {
  Ensure-Tools
  $full = Get-St 'repo'
  if (-not $full) { Say 'Nothing is set up yet.' 'Yellow'; return }
  Ok ('Repository: ' + $full)
  $r = Invoke-Native 'gh' @('release', 'list', '--repo', $full, '--limit', '5')
  if ($r.Out.Trim()) { Say $r.Out 'Gray' } else { Info 'No release has been published yet.' }
}

function Show-Menu {
  while ($true) {
    Say ''
    Say '  ======================================' 'Cyan'
    Say '    Kitaab - build and update wizard' 'Cyan'
    Say '  ======================================' 'Cyan'
    Say '   1) Full setup and build  (first time)' 'White'
    Say '   2) Rebuild APK           (after you changed the app)' 'White'
    Say '   3) Follow a build that is already running' 'White'
    Say '   4) Show my published releases' 'White'
    Say '   5) Show the signing SHA-1 (for Google sign-in)' 'White'
    Say '   6) Exit' 'White'
    $c = Ask 'Choose 1-6'
    try {
      if ($c -eq '1') { Invoke-Setup $false }
      elseif ($c -eq '2') { Invoke-Rebuild }
      elseif ($c -eq '3') { Invoke-Follow }
      elseif ($c -eq '4') { Invoke-Status }
      elseif ($c -eq '5') { Show-Fingerprint }
      elseif ($c -eq '6') { return }
    } catch {
      Bad $_.Exception.Message
      Info ('Details are in ' + $LogFile)
    }
  }
}

# ------------------------------------------------------------------ entry
Load-State
Write-Log ('=== start, mode ' + $Mode)
Say ('Kitaab wizard ' + $WizardVersion + '   folder: ' + $ProjectDir) 'DarkGray'
try {
  if ($Mode -eq 'menu') { Show-Menu }
  elseif ($Mode -eq 'setup') { Invoke-Setup $false }
  elseif ($Mode -eq 'rebuild') { Invoke-Rebuild }
  elseif ($Mode -eq 'follow') { Invoke-Follow }
  elseif ($Mode -eq 'status') { Invoke-Status }
} catch {
  Bad $_.Exception.Message
  Info ('Details are in ' + $LogFile)
}
