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
$WorkflowFile = 'release.yml'
$ProjectDir   = Split-Path -Parent $PSScriptRoot
$ApkDir       = Join-Path $ProjectDir 'APK'
$ErrorFile    = Join-Path $ProjectDir 'build-error.txt'
$StateFile    = Join-Path $PSScriptRoot 'state.json'
$LogFile      = Join-Path $PSScriptRoot 'wizard.log'
$KeyDir       = Join-Path $env:USERPROFILE '.kitaab-signing'
$KeyFile      = Join-Path $KeyDir 'kitaab-release.jks'

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

  [void](Run-Git @('add', '-A'))
  $st = Run-Git @('status', '--porcelain')
  if ($st.Out.Trim().Length -gt 0) {
    $msg = 'Update from the setup wizard ' + (Get-Date -Format 'yyyy-MM-dd HH:mm')
    $c = Run-Git @('commit', '-m', $msg)
    if ($c.Code -ne 0) { throw ('Commit failed: ' + $c.Out) }
    Ok 'Saved your changes.'
  } else {
    Info 'No file changes since the last upload.'
  }
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
  $all = @($r.Lines)
  $keep = New-Object System.Collections.Generic.List[string]
  $pat = '(?i)(^|\s)(e:\s|error:|error\b|FAILED|FAILURE|What went wrong|Execution failed|Unresolved reference|Cannot access|Type mismatch|Could not resolve|Could not find|AAPT|lint|Exception|\.kt:\d+|\.xml:\d+|Caused by|BUILD FAILED|::error)'
  foreach ($l in $all) {
    $t = ($l -replace '^\S+\s+\S+\s+\d{4}-\d{2}-\d{2}T[\d:.]+Z\s?', '')
    if ($t -match $pat -and $t -notmatch '(?i)^\s*warning:|w:\s') { $keep.Add($t.TrimEnd()) }
  }
  $head = @('Kitaab build error report', ('Repository: ' + $full), ('Run: ' + $id), ('Link: https://github.com/' + $full + '/actions/runs/' + $id), ('Time: ' + (Get-Date -Format 's')), '')
  $body = $keep | Select-Object -Unique | Select-Object -First 80
  if (-not $body -or @($body).Count -eq 0) { $body = $all | Select-Object -Last 60 }
  Set-Content -Path $ErrorFile -Value ($head + $body) -Encoding ASCII
  Bad ('The build failed. A short error report was saved here:')
  Info $ErrorFile
  Info 'Send me the text of build-error.txt and I will fix it.'
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
  Remove-Item -Recurse -Force $tmp
  if (Test-Path $ErrorFile) { Remove-Item -Force $ErrorFile }
  Say ''
  Ok 'DONE. Your signed APK is here:'
  Say ('        ' + $last) 'White'
  Say ''
  Info 'Phones that already have Kitaab 1.1.0 or newer update by themselves (Home banner / notification).'
  Info 'The first time, install this APK on the phone by hand. Android keeps your data when the signing key is the same.'
  try { Start-Process explorer.exe $ApkDir } catch { }
  return $last
}

# ------------------------------------------------------------------ flows
function Finish-Run([string]$full, [string]$id) {
  $conclusion = Watch-Run $full $id
  if ($conclusion -eq 'success') { [void](Get-Apk $full $id) } else { Save-BuildError $full $id }
}

function Invoke-Setup([bool]$rebuild) {
  Ensure-Tools
  Ensure-GitHubLogin
  $full = Ensure-Repository
  Ensure-Signing $full
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
    Say '   5) Exit' 'White'
    $c = Ask 'Choose 1-5'
    try {
      if ($c -eq '1') { Invoke-Setup $false }
      elseif ($c -eq '2') { Invoke-Rebuild }
      elseif ($c -eq '3') { Invoke-Follow }
      elseif ($c -eq '4') { Invoke-Status }
      elseif ($c -eq '5') { return }
    } catch {
      Bad $_.Exception.Message
      Info ('Details are in ' + $LogFile)
    }
  }
}

# ------------------------------------------------------------------ entry
Load-State
Write-Log ('=== start, mode ' + $Mode)
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
