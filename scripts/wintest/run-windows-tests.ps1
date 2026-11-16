#requires -Version 5.1
<#
  insilicoPCR - Windows test harness.

  Runs a portable Windows build through the situations a Windows user meets: install folders and
  data folders with spaces, parentheses, accents, '&', '%', '!', non-Latin characters, commas,
  long paths, a subst drive, relative paths, a trailing backslash, a downloaded copy (Mark of the
  Web), the batch launcher from cmd and from PowerShell, a rerun, two runs into one folder, a file
  held open by another program, and a run killed from Task Manager (TerminateProcess).

  The data is the repository's five-isolate example (docs\examples\five-isolates). Everything happens
  under this script's folder: work\ (scratch) and results\ (one folder per case, plus summary.tsv and
  environment.txt). Nothing outside it is written, except a subst drive letter that is removed at the
  end. Exits with 1 when a case fails, 0 otherwise (KNOWN, INFO and SKIP do not fail it).

  CI runs it on the Windows ZIP of every build (ci-portable-release.yml, job windows-harness).

  usage (from this folder, in Windows PowerShell or PowerShell 7):
    powershell -NoProfile -ExecutionPolicy Bypass -File .\run-windows-tests.ps1 -Zip <release ZIP or downloaded artifact .zip>
#>
param(
    [Parameter(Mandatory = $true)][string]$Zip,
    [switch]$SkipBig,
    # The five-isolate example: genomes\, reads\, primers.fasta and the report.tsv the genomes give.
    [string]$Examples = (Join-Path $PSScriptRoot '..\..\docs\examples\five-isolates')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2
Add-Type -AssemblyName System.IO.Compression.FileSystem

$Here = $PSScriptRoot
$Work = Join-Path $Here 'work'
$Results = Join-Path $Here 'results'
$Examples = (Resolve-Path -LiteralPath $Examples).ProviderPath
$Summary = Join-Path $Results 'summary.tsv'

if (Test-Path $Work) { cmd /c "rmdir /s /q `"$Work`"" | Out-Null }
if (Test-Path $Results) { cmd /c "rmdir /s /q `"$Results`"" | Out-Null }
New-Item -ItemType Directory -Force $Work, $Results | Out-Null
"case`tstatus`texit`tseconds`tnote" | Set-Content -Encoding UTF8 $Summary

function Log([string]$Message) {
    Write-Host $Message
    Add-Content -Encoding UTF8 (Join-Path $Results 'harness.log') $Message
}

function Record([string]$Case, [string]$Status, $Exit, $Seconds, [string]$Note) {
    $line = "$Case`t$Status`t$Exit`t$Seconds`t" + ($Note -replace "[`r`n`t]+", ' | ')
    Add-Content -Encoding UTF8 $Summary $line
    $color = switch ($Status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } default { 'Yellow' } }
    Write-Host ("[{0}] {1}  {2}" -f $Status, $Case, $Note) -ForegroundColor $color
}

# --- Command-line quoting, as the Microsoft C runtime and the Java launcher parse it -------------
function ConvertTo-ArgString([string[]]$Arguments) {
    $parts = foreach ($s in $Arguments) {
        if ($s -eq '') { '""'; continue }
        if ($s -notmatch '[\s"]') { $s; continue }
        $sb = New-Object System.Text.StringBuilder
        [void]$sb.Append('"')
        $bs = 0
        foreach ($c in $s.ToCharArray()) {
            if ($c -eq '\') { $bs++ }
            elseif ($c -eq '"') { [void]$sb.Append('\' * (2 * $bs + 1)); [void]$sb.Append('"'); $bs = 0 }
            else { [void]$sb.Append('\' * $bs); $bs = 0; [void]$sb.Append($c) }
        }
        [void]$sb.Append('\' * (2 * $bs))
        [void]$sb.Append('"')
        $sb.ToString()
    }
    return ($parts -join ' ')
}

# A command line as a user types it in cmd.exe (cmd /s /c strips the outer quotes only).
function CmdLine([string]$Exe, [string[]]$Arguments) {
    $quoted = foreach ($a in @($Exe) + $Arguments) { if ($a -match '[\s&()%!^,;=]') { '"' + $a + '"' } else { $a } }
    return ($quoted -join ' ')
}

# A path Windows PowerShell can open past 260 characters.
function Long([string]$Path) {
    if ($Path.Length -ge 240 -and $Path -match '^[A-Za-z]:\\') { return '\\?\' + $Path }
    return $Path
}

function Get-Tree([string]$Dir) {
    if ($Dir -eq '') { return '' }
    if (-not [IO.Directory]::Exists((Long $Dir))) { return '(missing)' }
    $root = (Resolve-Path -LiteralPath $Dir).ProviderPath.TrimEnd('\')
    Get-ChildItem -LiteralPath $Dir -Recurse -Force -ErrorAction SilentlyContinue |
        ForEach-Object { '{0,12}  {1}' -f $(if ($_.PSIsContainer) { '<dir>' } else { $_.Length }), $_.FullName.Substring($root.Length) }
}

$script:Started = @{}

# Starts $Exe with $ArgString (already quoted) in $Cwd, output to the case's folder.
function Start-Case([string]$Case, [string]$Exe, [string]$ArgString, [string]$Cwd = $Work) {
    $dir = Join-Path $Results $Case
    New-Item -ItemType Directory -Force $dir | Out-Null
    Set-Content -Encoding UTF8 (Join-Path $dir 'command.txt') "cwd: $Cwd`r`n$Exe $ArgString"
    $p = Start-Process -FilePath $Exe -ArgumentList $ArgString -WorkingDirectory $Cwd -NoNewWindow -PassThru `
        -RedirectStandardOutput (Join-Path $dir 'stdout.txt') -RedirectStandardError (Join-Path $dir 'stderr.txt')
    $null = $p.Handle # keeps ExitCode readable (Windows PowerShell)
    $script:Started[$Case] = Get-Date
    return $p
}

function Wait-Case([string]$Case, $Process, [int]$TimeoutSec = 900) {
    $dir = Join-Path $Results $Case
    if (-not $Process.WaitForExit($TimeoutSec * 1000)) {
        & taskkill.exe /T /F /PID $Process.Id | Out-Null
        $Process.WaitForExit()
        $code = 'timeout'
    } else {
        $Process.WaitForExit() # flushes the redirected output
        $code = $Process.ExitCode
    }
    $seconds = [math]::Round(((Get-Date) - $script:Started[$Case]).TotalSeconds, 1)
    $out = Get-Content -Raw -ErrorAction SilentlyContinue (Join-Path $dir 'stdout.txt')
    $err = Get-Content -Raw -ErrorAction SilentlyContinue (Join-Path $dir 'stderr.txt')
    return [pscustomobject]@{ Exit = $code; Seconds = $seconds; Out = "$out"; Err = "$err"; All = "$out`n$err"; Dir = $dir }
}

function Run-Case([string]$Case, [string]$Exe, [string]$ArgString, [string]$Cwd = $Work, [int]$TimeoutSec = 900) {
    try {
        $p = Start-Case $Case $Exe $ArgString $Cwd
        return Wait-Case $Case $p $TimeoutSec
    } catch {
        $dir = Join-Path $Results $Case
        New-Item -ItemType Directory -Force $dir | Out-Null
        Set-Content -Encoding UTF8 (Join-Path $dir 'harness-error.txt') ($_ | Out-String)
        return [pscustomobject]@{ Exit = 'not started'; Seconds = 0; Out = ''; Err = ($_ | Out-String); All = ($_ | Out-String); Dir = $dir }
    }
}

# The rows of a report, without line ends.
function Read-Rows([string]$Tsv) {
    if (-not [IO.File]::Exists((Long $Tsv))) { return $null }
    return @([IO.File]::ReadAllLines((Long $Tsv)) | ForEach-Object { $_.TrimEnd("`r") })
}

# "Sample Gene Size SizeCheck" per row, sorted: what a FASTQ run must find.
function Get-Products([string]$Tsv) {
    $rows = Read-Rows $Tsv
    if ($null -eq $rows -or $rows.Count -lt 1) { return '' }
    $h = $rows[0].Split("`t")
    $ix = @{}; for ($i = 0; $i -lt $h.Count; $i++) { $ix[$h[$i]] = $i }
    $items = foreach ($r in $rows[1..($rows.Count - 1)]) {
        if ($r -eq '') { continue }
        $c = $r.Split("`t")
        "{0} {1} {2} {3}" -f $c[$ix['Sample']], $c[$ix['Gene']], $c[$ix['AmpliconSize']], $c[$ix['SizeCheck']]
    }
    return (@($items) | Sort-Object) -join '; '
}

$ExpectedGenomeRows = Read-Rows (Join-Path $Examples 'report.tsv')
$ExpectedReadProducts = 'isolate_01 stxQ 118 target; isolate_01 toxA 312 target'

# Checks a finished run: exit code, report, rows; records the case.
function Check-Run($Case, $R, [string]$Output, [string]$Kind, [int]$ExpectExit = 0, [string]$ExpectText = $null) {
    Set-Content -Encoding UTF8 (Join-Path $R.Dir 'tree.txt') (Get-Tree $Output)
    if ($R.Exit -ne $ExpectExit) {
        $tail = ($R.All -split "`r?`n" | Where-Object { $_ -match '\S' } | Select-Object -Last 4) -join ' / '
        Record $Case 'FAIL' $R.Exit $R.Seconds "expected exit $ExpectExit; last lines: $tail"
        return $false
    }
    if ($ExpectText -and $R.All -notlike "*$ExpectText*") {
        Record $Case 'FAIL' $R.Exit $R.Seconds "output lacks: $ExpectText"
        return $false
    }
    if ($Kind -eq 'none') { Record $Case 'PASS' $R.Exit $R.Seconds ''; return $true }
    $report = Join-Path $Output 'consolidated_report\report.tsv'
    if ($Kind -like 'genomes*') {
        $rows = Read-Rows $report
        if ($null -eq $rows -or $rows.Count -eq 0) { Record $Case 'FAIL' $R.Exit $R.Seconds "no rows in $report"; return $false }
        if ((Compare-Object $ExpectedGenomeRows $rows -SyncWindow 0) ) {
            Record $Case 'FAIL' $R.Exit $R.Seconds 'report.tsv differs from five-isolates\report.tsv (diff in report-diff.txt)'
            Compare-Object $ExpectedGenomeRows $rows -SyncWindow 0 | Out-String | Set-Content -Encoding UTF8 (Join-Path $R.Dir 'report-diff.txt')
            return $false
        }
    } elseif ($Kind -eq 'reads') {
        $p = Get-Products $report
        if ($p -ne $ExpectedReadProducts) { Record $Case 'FAIL' $R.Exit $R.Seconds "products: '$p'"; return $false }
    }
    if (-not [IO.File]::Exists((Long (Join-Path $Output 'consolidated_report\report.html')))) {
        Record $Case 'FAIL' $R.Exit $R.Seconds 'no report.html'; return $false
    }
    $left = @(Get-ChildItem -LiteralPath $Output -Recurse -Force -Filter 'blast_query_*' -ErrorAction SilentlyContinue)
    if ($left.Count) { Record $Case 'FAIL' $R.Exit $R.Seconds "$($left.Count) blast_query_* left behind"; return $false }
    Record $Case 'PASS' $R.Exit $R.Seconds ''
    return $true
}

function Copy-Tree([string]$From, [string]$To) {
    & robocopy.exe $From $To /E /NFL /NDL /NJH /NJS /NP /R:1 /W:1 | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "robocopy $From -> $To failed ($LASTEXITCODE)" }
}

# --- Environment -----------------------------------------------------------------------------------
$envFile = Join-Path $Results 'environment.txt'
$os = Get-CimInstance Win32_OperatingSystem
$lp = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem' -ErrorAction SilentlyContinue).LongPathsEnabled
$acp = Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Nls\CodePage' -ErrorAction SilentlyContinue
if (-not $acp) { $acp = [pscustomobject]@{ ACP = '?'; OEMCP = '?' } }
@(
    "Date: $(Get-Date -Format o)"
    "OS: $($os.Caption) $($os.Version) build $($os.BuildNumber) $($os.OSArchitecture)"
    "PowerShell: $($PSVersionTable.PSVersion) $($PSVersionTable.PSEdition)"
    "Culture: $((Get-Culture).Name)  UI: $((Get-UICulture).Name)"
    "ANSI code page: $($acp.ACP)  OEM: $($acp.OEMCP)  console: $([Console]::OutputEncoding.CodePage)"
    "LongPathsEnabled: $lp"
    "CPU: $((Get-CimInstance Win32_Processor | Select-Object -First 1).Name)  logical: $([Environment]::ProcessorCount)"
    "RAM GB: $([math]::Round($os.TotalVisibleMemorySize / 1MB, 1))"
    "User profile: $env:USERPROFILE"
    "Script folder: $Here"
    "Zip: $Zip"
) | Set-Content -Encoding UTF8 $envFile
Get-Content $envFile | ForEach-Object { Log $_ }

# --- The build ---------------------------------------------------------------------------------------
$Zip = (Resolve-Path -LiteralPath $Zip).ProviderPath
$inner = $Zip
$z = [IO.Compression.ZipFile]::OpenRead($Zip)
try {
    $nested = @($z.Entries | Where-Object { $_.FullName -like '*windows-x64.zip' })
    $entryNames = @($z.Entries | ForEach-Object { $_.FullName })
} finally { $z.Dispose() }
if ($nested.Count) {
    # The GitHub Actions artifact: a ZIP holding the release ZIP and its checksum.
    $unpack = Join-Path $Work 'artifact'
    [IO.Compression.ZipFile]::ExtractToDirectory($Zip, $unpack)
    $inner = (Get-ChildItem $unpack -Recurse -Filter '*windows-x64.zip' | Select-Object -First 1).FullName
    $sha = Get-ChildItem $unpack -Recurse -Filter '*.sha256' | Select-Object -First 1
    if ($sha) {
        $want = ((Get-Content $sha.FullName -Raw).Trim() -split '\s+')[0]
        $have = (Get-FileHash $inner -Algorithm SHA256).Hash.ToLower()
        if ($want -eq $have) { Record 'zip-checksum' 'PASS' '' 0 '' } else { Record 'zip-checksum' 'FAIL' '' 0 "sha256 $have, file says $want" }
    }
    $z = [IO.Compression.ZipFile]::OpenRead($inner)
    try { $entryNames = @($z.Entries | ForEach-Object { $_.FullName }) } finally { $z.Dispose() }
}
$backslashed = @($entryNames | Where-Object { $_ -match '\\' })
$longest = ($entryNames | Sort-Object Length -Descending | Select-Object -First 1)
Record 'zip-entries' $(if ($backslashed.Count) { 'FAIL' } else { 'PASS' }) '' 0 `
    "$($entryNames.Count) entries, $($backslashed.Count) with '\' in their name; longest ($($longest.Length)): $longest"

$Plain = Join-Path $Work 'inst\plain'
[IO.Compression.ZipFile]::ExtractToDirectory($inner, $Plain)
$jar = Get-ChildItem $Plain -Recurse -Filter insilicoPCR.jar | Select-Object -First 1
$Plain = $jar.DirectoryName
Log "Installed at $Plain"

function Install-At([string]$Parent) {
    $target = Join-Path $Parent 'insilicoPCR'
    Copy-Tree $Plain $target
    return $target
}
function Java-Of([string]$App) { Join-Path $App 'runtime\windows\jdk\bin\java.exe' }
function Bat-Of([string]$App) { Join-Path $App 'run-insilicoPCR.bat' }

# --- Data -------------------------------------------------------------------------------------------
function New-Data([string]$Parent) {
    New-Item -ItemType Directory -Force $Parent | Out-Null
    Copy-Tree (Join-Path $Examples 'genomes') (Join-Path $Parent 'genomes')
    Copy-Tree (Join-Path $Examples 'reads') (Join-Path $Parent 'reads')
    Copy-Item (Join-Path $Examples 'primers.fasta') (Join-Path $Parent 'primers.fasta')
    return $Parent
}
$DataPlain = New-Data (Join-Path $Work 'data\plain')

# --- 1. The launchers, from install folders with awkward names ---------------------------------------
$installs = [ordered]@{
    'plain'   = $Plain
    'space'   = Install-At (Join-Path $Work 'inst\My Apps (x86)')
    'accent'  = Install-At (Join-Path $Work 'inst\Données Rémi')
    'symbols' = Install-At (Join-Path $Work 'inst\R&D 100% !bang')
    'unicode' = Install-At (Join-Path $Work 'inst\数据 Δ')
}

foreach ($name in $installs.Keys) {
    $app = $installs[$name]
    if ($name -eq 'unicode') {
        # A limit of the JDK's java.exe, documented in Troubleshooting: it cannot find itself there.
        $r = Run-Case "launch-$name-cmd-version" 'cmd.exe' ('/d /s /c "' + (CmdLine (Bat-Of $app) @('--version')) + '"') $Work 60
        Record "launch-$name-cmd-version" $(if ($r.All -like '*java.dll*') { 'KNOWN' } else { 'INFO' }) $r.Exit $r.Seconds `
            (($r.All -split "`r?`n" | Where-Object { $_ -match '\S' } | Select-Object -First 2) -join ' / ')
        continue
    }
    # As typed in cmd.exe.
    $r = Run-Case "launch-$name-cmd-version" 'cmd.exe' ('/d /s /c "' + (CmdLine (Bat-Of $app) @('--version')) + '"') $Work 60
    Check-Run "launch-$name-cmd-version" $r '' 'none' 0 'insilicoPCR ' | Out-Null

    # As typed in PowerShell: & 'C:\...\run-insilicoPCR.bat' -i ... (a child PowerShell, the command encoded).
    $out = Join-Path $Work "out\launch-$name-ps"
    $psCommand = "& '{0}' -i '{1}' -p '{2}' -o '{3}' -t 2; exit `$LASTEXITCODE" -f `
        ((Bat-Of $app) -replace "'", "''"), ("$DataPlain\genomes" -replace "'", "''"), ("$DataPlain\primers.fasta" -replace "'", "''"), ($out -replace "'", "''")
    $enc = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($psCommand))
    $r = Run-Case "launch-$name-ps-genomes" 'powershell.exe' "-NoProfile -ExecutionPolicy Bypass -EncodedCommand $enc" $Work
    Check-Run "launch-$name-ps-genomes" $r $out 'genomes' | Out-Null

    # As typed in cmd.exe, a full run into an output folder named like the install.
    $out = Join-Path (Split-Path $app) 'results here'
    $line = CmdLine (Bat-Of $app) @('-i', "$DataPlain\genomes", '-p', "$DataPlain\primers.fasta", '-o', $out, '-t', '2')
    $r = Run-Case "launch-$name-cmd-genomes" 'cmd.exe' ('/d /s /c "' + $line + '"') $Work
    Check-Run "launch-$name-cmd-genomes" $r $out 'genomes' | Out-Null

    # FASTQ mode: BBTools started from this install (java -cp <bbtools.jar under it>).
    $out = Join-Path (Split-Path $app) 'reads results'
    $r = Run-Case "install-$name-reads" (Java-Of $app) (ConvertTo-ArgString @('-jar', (Join-Path $app 'insilicoPCR.jar'), '-i', "$DataPlain\reads", '-p', "$DataPlain\primers.fasta", '-o', $out, '-t', '2'))
    Check-Run "install-$name-reads" $r $out 'reads' | Out-Null
}

$Java = Java-Of $Plain
$Jar = Join-Path $Plain 'insilicoPCR.jar'
function App-Args([string[]]$Rest) { ConvertTo-ArgString (@('-jar', $Jar) + $Rest) }

# --- 2. Data and output folders with awkward names ------------------------------------------------------
$dataNames = [ordered]@{
    'space-accent' = 'data\Données de Rémi (copie)'
    'symbols'      = 'data\R&D 100% !bang'
    'unicode'      = 'data\数据 Δ'
    'comma'        = 'data\a,b'
    'apostrophe'   = "data\l'équipe"
}
foreach ($name in $dataNames.Keys) {
    $d = New-Data (Join-Path $Work $dataNames[$name])
    $out = Join-Path $d 'out genomes'
    $r = Run-Case "data-$name-genomes" $Java (App-Args @('-i', "$d\genomes", '-p', "$d\primers.fasta", '-o', $out, '-t', '2'))
    if ($name -eq 'unicode') {
        Check-Run "data-$name-genomes" $r $out 'none' 2 'reaches the Java runtime as ?' | Out-Null
    } else {
        Check-Run "data-$name-genomes" $r $out 'genomes' | Out-Null
    }
    $out = Join-Path $d 'out reads'
    $r = Run-Case "data-$name-reads" $Java (App-Args @('-i', "$d\reads", '-p', "$d\primers.fasta", '-o', $out, '-t', '2'))
    if ($name -eq 'unicode') {
        # Java receives such letters as '?': a limit of the Java launcher; the run must say so.
        Check-Run "data-$name-reads" $r $out 'none' 2 'reaches the Java runtime as ?' | Out-Null
    } else {
        Check-Run "data-$name-reads" $r $out 'reads' | Out-Null
    }
}

# Accented input folder, plain output folder: blastn -query and BBTools in= with accents.
$d = New-Data (Join-Path $Work 'data\Entrée données')
foreach ($kind in 'genomes', 'reads') {
    $out = Join-Path $Work "out\accented-input-$kind"
    $r = Run-Case "data-accented-input-$kind" $Java (App-Args @('-i', "$d\$kind", '-p', "$d\primers.fasta", '-o', $out, '-t', '2'))
    Check-Run "data-accented-input-$kind" $r $out $kind | Out-Null
}

# A genome FASTA saved with a byte order mark.
$d = Join-Path $Work 'data\bom-genome'
New-Item -ItemType Directory -Force $d | Out-Null
[IO.File]::WriteAllText("$d\isolate_01.fasta", [IO.File]::ReadAllText("$Examples\genomes\isolate_01.fasta"), (New-Object System.Text.UTF8Encoding($true)))
$out = Join-Path $d 'out'
$r = Run-Case 'bom-genome' $Java (App-Args @('-i', $d, '-p', "$DataPlain\primers.fasta", '-o', $out, '-t', '2'))
Check-Run 'bom-genome' $r $out 'none' | Out-Null
$p = Get-Products (Join-Path $out 'consolidated_report\report.tsv')
if ($p -eq 'isolate_01 stxQ 118 target; isolate_01 toxA 312 target') { Record 'bom-genome-products' 'PASS' '' 0 '' } else { Record 'bom-genome-products' 'FAIL' '' 0 "products: '$p'" }

# Sample names Windows cannot give a results folder: the sample is left out, the others are run.
$d = Join-Path $Work 'data\odd-names'
New-Item -ItemType Directory -Force $d | Out-Null
Copy-Item "$Examples\genomes\isolate_01.fasta" "$d\isolate_01.fasta"
Copy-Item "$Examples\genomes\isolate_02.fasta" "$d\trailing space .fasta"
# A file cannot be named con.fasta on Windows, but nul_R1.fastq.gz can, and its sample is "nul".
Copy-Item "$Examples\reads\isolate_01_R1.fastq.gz" "$d\nul_R1.fastq.gz"
Copy-Item "$Examples\reads\isolate_01_R2.fastq.gz" "$d\nul_R2.fastq.gz"
$out = Join-Path $d 'out'
$r = Run-Case 'sample-names-windows-cannot-use' $Java (App-Args @('-i', $d, '-p', "$DataPlain\primers.fasta", '-o', $out, '-t', '2'))
Check-Run 'sample-names-windows-cannot-use' $r $out 'none' 0 'the sample name ends with a space' | Out-Null
$p = Get-Products (Join-Path $out 'consolidated_report\report.tsv')
$ok = $p -eq 'isolate_01 stxQ 118 target; isolate_01 toxA 312 target' -and $r.All -like '*keeps for a device*'
Record 'sample-names-windows-cannot-use-products' $(if ($ok) { 'PASS' } else { 'FAIL' }) '' 0 "products: '$p'"

# Every input file faulty: no report, exit code 1, for a workflow manager to see.
$d = Join-Path $Work 'data\all-faulty'
New-Item -ItemType Directory -Force $d | Out-Null
Set-Content -Encoding ASCII "$d\headers.fasta" ">c1`n>c2`n"
$r = Run-Case 'all-inputs-faulty' $Java (App-Args @('-i', $d, '-p', "$DataPlain\primers.fasta", '-o', "$d\out", '-t', '2'))
Check-Run 'all-inputs-faulty' $r "$d\out" 'none' 1 'every input file is faulty' | Out-Null

# Plain data, awkward output folders.
foreach ($o in @('out\Données (résultats)', 'out\数据 Δ result', 'out\R&D 100% !bang')) {
    $out = Join-Path $Work $o
    $case = 'output-' + (($o -replace '[^A-Za-z0-9]+', '-').Trim('-'))
    $unicode = $o -match '[^\x00-\xFF]'
    $r = Run-Case "$case-genomes" $Java (App-Args @('-i', "$DataPlain\genomes", '-p', "$DataPlain\primers.fasta", '-o', $out, '-t', '2'))
    if ($unicode) { Check-Run "$case-genomes" $r $out 'none' 2 'reaches the Java runtime as ?' | Out-Null }
    else { Check-Run "$case-genomes" $r $out 'genomes' | Out-Null }
    $r = Run-Case "$case-reads" $Java (App-Args @('-i', "$DataPlain\reads", '-p', "$DataPlain\primers.fasta", '-o', "$out reads", '-t', '2'))
    if ($unicode) { Check-Run "$case-reads" $r "$out reads" 'none' 2 'reaches the Java runtime as ?' | Out-Null }
    else { Check-Run "$case-reads" $r "$out reads" 'reads' | Out-Null }
}

# --- 3. Files as Windows tools write them --------------------------------------------------------------
$d = Join-Path $Work 'data\windows-files'
New-Item -ItemType Directory -Force "$d\genomes" | Out-Null
$utf8Bom = New-Object System.Text.UTF8Encoding($true)
# Primer file saved by Notepad: UTF-8 with a BOM, CRLF line ends.
[IO.File]::WriteAllText("$d\primers.fasta", ([IO.File]::ReadAllText("$Examples\primers.fasta") -replace "`r?`n", "`r`n"), $utf8Bom)
# Genomes with CRLF, upper-case and other extensions.
$i = 0
foreach ($g in Get-ChildItem "$Examples\genomes" -Filter *.fasta) {
    $text = [IO.File]::ReadAllText($g.FullName) -replace "`r?`n", "`r`n"
    $ext = @('.FASTA', '.fa', '.fna', '.fasta', '.Fsa')[$i++]
    [IO.File]::WriteAllText((Join-Path "$d\genomes" ($g.BaseName + $ext)), $text, (New-Object System.Text.UTF8Encoding($false)))
}
# Files Explorer leaves in a folder.
Set-Content -Encoding Unicode "$d\genomes\desktop.ini" "[.ShellClassInfo]`r`nIconResource=C:\Windows\System32\imageres.dll,3"
Set-Content "$d\genomes\Thumbs.db" 'x'
$out = Join-Path $d 'out'
$r = Run-Case 'windows-files-crlf-bom' $Java (App-Args @('-i', "$d\genomes", '-p', "$d\primers.fasta", '-o', $out, '-t', '2'))
Check-Run 'windows-files-crlf-bom' $r $out 'genomes-any' | Out-Null
$p = Get-Products (Join-Path $out 'consolidated_report\report.tsv')
$want = 'isolate_01 stxQ 118 target; isolate_01 toxA 312 target; isolate_02 stxQ 118 target; isolate_02 toxA 312 target; isolate_03 toxA 312 target; isolate_04 stxQ 118 target; isolate_04 toxA 583 unexpected'
if ($p -eq $want) { Record 'windows-files-products' 'PASS' '' 0 '' } else { Record 'windows-files-products' 'FAIL' '' 0 "products: '$p'" }

# Reads with CRLF line ends (a FASTQ file converted by a Windows tool), uncompressed.
$d2 = Join-Path $Work 'data\crlf-reads'
New-Item -ItemType Directory -Force $d2 | Out-Null
foreach ($m in 'R1', 'R2') {
    $src = "$Examples\reads\isolate_01_$m.fastq.gz"
    $in = New-Object IO.Compression.GZipStream([IO.File]::OpenRead($src), [IO.Compression.CompressionMode]::Decompress)
    $sr = New-Object IO.StreamReader($in)
    $text = $sr.ReadToEnd(); $sr.Dispose()
    [IO.File]::WriteAllText("$d2\isolate_01_$m.fastq", ($text -replace "`r?`n", "`r`n"), (New-Object System.Text.UTF8Encoding($false)))
}
$out = Join-Path $d2 'out'
$r = Run-Case 'windows-files-crlf-reads' $Java (App-Args @('-i', $d2, '-p', "$d\primers.fasta", '-o', $out, '-t', '2'))
Check-Run 'windows-files-crlf-reads' $r $out 'reads' | Out-Null

# --- 4. Ways of naming paths ----------------------------------------------------------------------------
# Relative paths, from the data folder.
$r = Run-Case 'path-relative' $Java (App-Args @('-i', 'genomes', '-p', 'primers.fasta', '-o', 'out-relative', '-t', '2')) $DataPlain
Check-Run 'path-relative' $r (Join-Path $DataPlain 'out-relative') 'genomes' | Out-Null
$r = Run-Case 'path-relative-reads' $Java (App-Args @('-i', 'reads', '-p', 'primers.fasta', '-o', 'out-relative-reads', '-t', '2')) $DataPlain
Check-Run 'path-relative-reads' $r (Join-Path $DataPlain 'out-relative-reads') 'reads' | Out-Null

# Forward slashes.
$fs = $DataPlain -replace '\\', '/'
$r = Run-Case 'path-forward-slashes' $Java (App-Args @('-i', "$fs/reads", '-p', "$fs/primers.fasta", '-o', "$fs/out-slashes", '-t', '2'))
Check-Run 'path-forward-slashes' $r (Join-Path $DataPlain 'out-slashes') 'reads' | Out-Null

# A trailing backslash inside quotes, as cmd users type a folder: "C:\data dir\" eats the quote.
$spaced = New-Data (Join-Path $Work 'data\with space')
$raw = '-jar "' + $Jar + '" -i "' + $spaced + '\genomes\" -p "' + $spaced + '\primers.fasta" -o "' + $spaced + '\out\" -t 2'
$r = Run-Case 'path-trailing-backslash-quoted' $Java $raw
Check-Run 'path-trailing-backslash-quoted' $r '' 'none' 2 'leave out the last backslash' | Out-Null
# A shell pattern, which cmd does not expand.
$r = Run-Case 'path-wildcard' $Java (App-Args @('-i', "$DataPlain\genomes\*.fasta", '-p', "$DataPlain\primers.fasta", '-o', "$Work\out\wildcard", '-t', '2'))
Record 'path-wildcard' 'INFO' $r.Exit $r.Seconds (($r.All -split "`r?`n" | Where-Object { $_ -match '\S' } | Select-Object -First 2) -join ' / ')

# A subst drive: input on another drive letter than the output.
$letter = @('X', 'Y', 'W', 'V', 'U', 'Q') | Where-Object { -not (Test-Path "$($_):\") } | Select-Object -First 1
if ($letter) {
    & subst.exe "$($letter):" $DataPlain
    try {
        $r = Run-Case 'path-subst-drive-genomes' $Java (App-Args @('-i', "$($letter):\genomes", '-p', "$($letter):\primers.fasta", '-o', "$Work\out\subst genomes", '-t', '2'))
        Check-Run 'path-subst-drive-genomes' $r "$Work\out\subst genomes" 'genomes' | Out-Null
        $r = Run-Case 'path-subst-drive-reads' $Java (App-Args @('-i', "$($letter):\reads", '-p', "$($letter):\primers.fasta", '-o', "$Work\out\subst reads", '-t', '2'))
        Check-Run 'path-subst-drive-reads' $r "$Work\out\subst reads" 'reads' | Out-Null
        $r = Run-Case 'path-subst-drive-output' $Java (App-Args @('-i', "$DataPlain\reads", '-p', "$DataPlain\primers.fasta", '-o', "$($letter):\out on subst", '-t', '2'))
        Check-Run 'path-subst-drive-output' $r "$($letter):\out on subst" 'reads' | Out-Null
        # Output at the root of a drive.
        $r = Run-Case 'path-drive-root-output' $Java (App-Args @('-i', "$DataPlain\genomes", '-p', "$DataPlain\primers.fasta", '-o', "$($letter):\", '-t', '2'))
        Check-Run 'path-drive-root-output' $r "$($letter):\" 'genomes' | Out-Null
    } finally {
        & subst.exe "$($letter):" /D
    }
} else { Record 'path-subst-drive' 'SKIP' '' 0 'no free drive letter' }

# A UNC path to this computer's own drive (works where the administrative share is reachable).
$unc = '\\localhost\' + $DataPlain.Substring(0, 1) + '$' + $DataPlain.Substring(2)
if (Test-Path -LiteralPath "$unc\primers.fasta") {
    $r = Run-Case 'path-unc-genomes' $Java (App-Args @('-i', "$unc\genomes", '-p', "$unc\primers.fasta", '-o', "$unc\out unc genomes", '-t', '2'))
    Check-Run 'path-unc-genomes' $r "$unc\out unc genomes" 'genomes' | Out-Null
    $r = Run-Case 'path-unc-reads' $Java (App-Args @('-i', "$unc\reads", '-p', "$unc\primers.fasta", '-o', "$unc\out unc reads", '-t', '2'))
    Check-Run 'path-unc-reads' $r "$unc\out unc reads" 'reads' | Out-Null
} else { Record 'path-unc' 'SKIP' '' 0 "$unc not reachable (administrative share off)" }

# Long paths: an output folder, then an input folder, past 260 characters.
$deep = $Work
while ($deep.Length -lt 230) { $deep = Join-Path $deep 'a-rather-long-folder-name' }
[IO.Directory]::CreateDirectory("\\?\$deep") | Out-Null
$longOut = Join-Path $deep 'results-of-the-long-path-run-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx'
# Past what Windows programs can use: refused before any program runs, saying why.
$r = Run-Case 'path-long-output-genomes' $Java (App-Args @('-i', "$DataPlain\genomes", '-p', "$DataPlain\primers.fasta", '-o', $longOut, '-t', '2'))
Check-Run 'path-long-output-genomes' $r $longOut 'none' 1 'too long for Windows' | Out-Null
$r = Run-Case 'path-long-output-reads' $Java (App-Args @('-i', "$DataPlain\reads", '-p', "$DataPlain\primers.fasta", '-o', "$longOut-reads", '-t', '2'))
Check-Run 'path-long-output-reads' $r "$longOut-reads" 'none' 1 'too long for Windows' | Out-Null
# Just under the limit for a FASTA run: works.
$fits = $Work
while ($fits.Length -lt 160) { $fits = Join-Path $fits 'a-rather-long-folder-name' }
$r = Run-Case 'path-near-limit-output-genomes' $Java (App-Args @('-i', "$DataPlain\genomes", '-p', "$DataPlain\primers.fasta", '-o', "$fits\out", '-t', '2'))
Check-Run 'path-near-limit-output-genomes' $r "$fits\out" 'genomes' | Out-Null
$longIn = Join-Path $deep 'input-folder-with-a-long-name-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx'
[IO.Directory]::CreateDirectory("\\?\$longIn\genomes") | Out-Null
[IO.Directory]::CreateDirectory("\\?\$longIn\reads") | Out-Null
foreach ($f in Get-ChildItem "$DataPlain\genomes") { [IO.File]::Copy($f.FullName, "\\?\$longIn\genomes\$($f.Name)") }
foreach ($f in Get-ChildItem "$DataPlain\reads") { [IO.File]::Copy($f.FullName, "\\?\$longIn\reads\$($f.Name)") }
$r = Run-Case 'path-long-input-genomes' $Java (App-Args @('-i', "$longIn\genomes", '-p', "$DataPlain\primers.fasta", '-o', "$Work\out\long input genomes", '-t', '2'))
Check-Run 'path-long-input-genomes' $r "$Work\out\long input genomes" 'genomes' | Out-Null
$r = Run-Case 'path-long-input-reads' $Java (App-Args @('-i', "$longIn\reads", '-p', "$DataPlain\primers.fasta", '-o', "$Work\out\long input reads", '-t', '2'))
Check-Run 'path-long-input-reads' $r "$Work\out\long input reads" 'reads' | Out-Null

# --- 5. A copy downloaded with a browser (Mark of the Web on every file) -----------------------------------
$motw = Install-At (Join-Path $Work 'inst\downloaded')
Get-ChildItem $motw -Recurse -File | ForEach-Object {
    Set-Content -LiteralPath $_.FullName -Stream Zone.Identifier -Value "[ZoneTransfer]`r`nZoneId=3`r`nHostUrl=https://github.com/"
}
$out = Join-Path $Work 'out\motw genomes'
$r = Run-Case 'motw-cmd-genomes' 'cmd.exe' ('/d /s /c "' + (CmdLine (Bat-Of $motw) @('-i', "$DataPlain\genomes", '-p', "$DataPlain\primers.fasta", '-o', $out, '-t', '2')) + '"')
Check-Run 'motw-cmd-genomes' $r $out 'genomes' | Out-Null
$out = Join-Path $Work 'out\motw reads'
$r = Run-Case 'motw-reads' (Java-Of $motw) (ConvertTo-ArgString @('-jar', (Join-Path $motw 'insilicoPCR.jar'), '-i', "$DataPlain\reads", '-p', "$DataPlain\primers.fasta", '-o', $out, '-t', '2'))
Check-Run 'motw-reads' $r $out 'reads' | Out-Null

# --- 6. Runs after runs ------------------------------------------------------------------------------------
$out = Join-Path $Work 'out\rerun'
$r = Run-Case 'rerun-1' $Java (App-Args @('-i', "$DataPlain\genomes", '-p', "$DataPlain\primers.fasta", '-o', $out, '-t', '2'))
Check-Run 'rerun-1' $r $out 'genomes' | Out-Null
$r = Run-Case 'rerun-2' $Java (App-Args @('-i', "$DataPlain\genomes", '-p', "$DataPlain\primers.fasta", '-o', $out, '-t', '2'))
Set-Content -Encoding UTF8 (Join-Path $r.Dir 'tree.txt') (Get-Tree $out)
$second = @(Get-ChildItem "$out\consolidated_report" -Filter 'report*.tsv' | ForEach-Object Name) -join ', '
$logs = @(Get-ChildItem $out -Filter 'QAlog*.txt' | ForEach-Object Name) -join ', '
Record 'rerun-2' $(if ($r.Exit -eq 0 -and $second -like '*report(1).tsv*' -and $logs -like '*QAlog(1).txt*') { 'PASS' } else { 'FAIL' }) $r.Exit $r.Seconds "reports: $second; logs: $logs"

# A file of the last run held open with no sharing, as Excel holds a TSV it shows.
$held = Get-ChildItem "$out\detailed_report" -Recurse -File | Select-Object -First 1
$heldReport = Join-Path $out 'consolidated_report\report.tsv'
$h1 = [IO.File]::Open($held.FullName, 'Open', 'ReadWrite', 'None')
$h2 = [IO.File]::Open($heldReport, 'Open', 'Read', 'Read')
try {
    $r = Run-Case 'rerun-file-held-open' $Java (App-Args @('-i', "$DataPlain\genomes", '-p', "$DataPlain\primers.fasta", '-o', $out, '-t', '2'))
} finally { $h1.Dispose(); $h2.Dispose() }
Check-Run 'rerun-file-held-open' $r $out 'none' 1 'open in another program' | Out-Null

# The primer database of the last run held by another program (antivirus scanning, a blastn left over).
$db = Get-ChildItem $out -Filter 'primer_tmp.fasta.n*' | Select-Object -First 1
if ($db) {
    $h1 = [IO.File]::Open($db.FullName, 'Open', 'Read', 'Read')
    try {
        $r = Run-Case 'rerun-database-held-open' $Java (App-Args @('-i', "$DataPlain\genomes", '-p', "$DataPlain\primers.fasta", '-o', $out, '-t', '2'))
    } finally { $h1.Dispose() }
    Check-Run 'rerun-database-held-open' $r $out 'none' 1 'open in another program' | Out-Null
}

# Output is an existing file.
$file = Join-Path $Work 'out\not-a-folder.txt'
Set-Content $file 'x'
$r = Run-Case 'output-is-a-file' $Java (App-Args @('-i', "$DataPlain\genomes", '-p', "$DataPlain\primers.fasta", '-o', $file, '-t', '2'))
$trace = $r.All -match '\tat '
Record 'output-is-a-file' $(if ($r.Exit -ne 0 -and -not $trace) { 'PASS' } else { 'FAIL' }) $r.Exit $r.Seconds (($r.All -split "`r?`n" | Where-Object { $_ -match '\S' } | Select-Object -Last 2) -join ' / ')

# Gel image (-g) from the command line.
$out = Join-Path $Work 'out\gel'
$r = Run-Case 'gel-png' $Java (App-Args @('-i', "$DataPlain\genomes", '-p', "$DataPlain\primers.fasta", '-o', $out, '-t', '2', '-g'))
Check-Run 'gel-png' $r $out 'genomes' | Out-Null
$png = @(Get-ChildItem $out -Recurse -Filter *.png)
Record 'gel-png-file' $(if ($png.Count) { 'PASS' } else { 'FAIL' }) '' 0 (($png | ForEach-Object Name) -join ', ')

# --- 7. Big inputs: two runs into one folder, and a run killed from Task Manager ------------------------------
if (-not $SkipBig) {
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Text;
public static class FakeData {
    static char[] B = { 'A', 'C', 'G', 'T' };
    static string Rc(string s) {
        var a = new char[s.Length];
        for (int i = 0; i < s.Length; i++) {
            char c = s[s.Length - 1 - i];
            a[i] = c == 'A' ? 'T' : c == 'C' ? 'G' : c == 'G' ? 'C' : 'A';
        }
        return new string(a);
    }
    public static void Genome(string path, int length, int seed) {
        var r = new Random(seed);
        var g = new char[length];
        for (int i = 0; i < length; i++) g[i] = B[r.Next(4)];
        string f = "TTTCCTCATGCAATTCAAAACC", rr = Rc("ATGTCCGTAATGTAGGCGAAA");
        int at = length / 2;
        f.CopyTo(0, g, at, f.Length);
        rr.CopyTo(0, g, at + 312 - rr.Length, rr.Length);
        using (var w = new StreamWriter(path, false, new UTF8Encoding(false), 1 << 20)) {
            w.Write(">big_contig1\n");
            for (int i = 0; i < length; i += 80) { w.Write(g, i, Math.Min(80, length - i)); w.Write('\n'); }
        }
    }
    public static void Reads(string genome, string r1, string r2, int pairs, int seed) {
        var sb = new StringBuilder();
        foreach (var line in File.ReadLines(genome)) if (!line.StartsWith(">")) sb.Append(line.Trim());
        string g = sb.ToString();
        var r = new Random(seed);
        string q = new string('I', 150);
        using (var w1 = new StreamWriter(r1, false, new UTF8Encoding(false), 1 << 20))
        using (var w2 = new StreamWriter(r2, false, new UTF8Encoding(false), 1 << 20)) {
            for (int n = 1; n <= pairs; n++) {
                int s = r.Next(0, g.Length - 350);
                string frag = g.Substring(s, 350);
                if (r.Next(2) == 0) frag = Rc(frag);
                w1.Write("@big." + n + "/1\n" + frag.Substring(0, 150) + "\n+\n" + q + "\n");
                w2.Write("@big." + n + "/2\n" + Rc(frag.Substring(200)) + "\n+\n" + q + "\n");
            }
        }
    }
}
'@
    $big = Join-Path $Work 'data\big'
    New-Item -ItemType Directory -Force "$big\genomes", "$big\reads" | Out-Null
    Log 'Writing a 30 Mb genome and 600,000 read pairs...'
    [FakeData]::Genome("$big\genomes\big.fasta", 30000000, 7)
    [FakeData]::Reads("$big\genomes\big.fasta", "$big\reads\big_R1.fastq", "$big\reads\big_R2.fastq", 600000, 8)

    function Get-Descendants([int]$ProcessId) {
        $all = @(Get-CimInstance Win32_Process)
        $found = @()
        $queue = @($ProcessId)
        while ($queue.Count) {
            $parent = $queue[0]; $queue = @($queue | Select-Object -Skip 1)
            foreach ($c in $all | Where-Object { $_.ParentProcessId -eq $parent -and $_.ProcessId -ne $parent }) {
                $found += $c; $queue += [int]$c.ProcessId
            }
        }
        return $found
    }

    # Two runs into one output folder: the second must stop, naming the first.
    $out = Join-Path $Work 'out\shared folder'
    $first = Start-Case 'lock-first-run' $Java (App-Args @('-i', "$big\reads", '-p', "$DataPlain\primers.fasta", '-o', $out, '-t', '2', '--input-check', 'quick'))
    for ($i = 0; $i -lt 600 -and -not (Get-Descendants $first.Id | Where-Object Name -in 'java.exe', 'blastn.exe', 'makeblastdb.exe'); $i++) { Start-Sleep -Milliseconds 100 }
    $r = Run-Case 'lock-second-run' $Java (App-Args @('-i', "$DataPlain\genomes", '-p', "$DataPlain\primers.fasta", '-o', $out, '-t', '2'))
    Check-Run 'lock-second-run' $r $out 'none' 1 'Another run is using the output folder' | Out-Null
    $r = Wait-Case 'lock-first-run' $first
    Record 'lock-first-run' $(if ($r.Exit -eq 0) { 'PASS' } else { 'FAIL' }) $r.Exit $r.Seconds "finished after the second was refused; products: $(Get-Products (Join-Path $out 'consolidated_report\report.tsv'))"

    # Killed as Task Manager's End task does (TerminateProcess): its programs must not run on.
    foreach ($k in @(
            @{ Case = 'kill-during-bbtools'; Input = "$big\reads"; Wait = 'java.exe' },
            @{ Case = 'kill-during-blastn'; Input = "$big\genomes"; Wait = 'blastn.exe' })) {
        $out = Join-Path $Work "out\$($k.Case)"
        $p = Start-Case $k.Case $Java (App-Args @('-i', $k.Input, '-p', "$DataPlain\primers.fasta", '-o', $out, '-t', '2', '--input-check', 'quick'))
        $children = @()
        for ($i = 0; $i -lt 1200 -and -not ($children | Where-Object Name -eq $k.Wait); $i++) {
            Start-Sleep -Milliseconds 50
            $children = @(Get-Descendants $p.Id)
        }
        Start-Sleep -Milliseconds 700
        $children = @(Get-Descendants $p.Id)
        $endedFirst = $p.HasExited
        Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 4
        $alive = @($children | Where-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue })
        $names = ($children | ForEach-Object { "$($_.Name)($($_.ProcessId))" }) -join ', '
        $aliveNames = ($alive | ForEach-Object { "$($_.Name)($($_.ProcessId))" }) -join ', '
        $dir = Join-Path $Results $k.Case
        $children | Select-Object ProcessId, Name, CommandLine | Format-List | Out-String -Width 4000 | Set-Content -Encoding UTF8 (Join-Path $dir 'children.txt')
        # Only the program waited for proves anything: a case that never saw it, or whose run had
        # ended before the kill, tested nothing, and fails rather than passing unseen.
        if ($endedFirst) { Record $k.Case 'FAIL' $p.ExitCode 0 "the run ended before it could be killed (children seen: $names)" }
        elseif (-not ($children | Where-Object Name -eq $k.Wait)) { Record $k.Case 'FAIL' '' 0 "no $($k.Wait) child seen within 60 s (children seen: $names)" }
        elseif ($alive) { Record $k.Case 'FAIL' 'killed' 0 "still running 4 s after insilicoPCR was killed: $aliveNames (children were: $names)" }
        else { Record $k.Case 'PASS' 'killed' 0 "children stopped with it: $names" }
        foreach ($a in $alive) { Stop-Process -Id $a.ProcessId -Force -ErrorAction SilentlyContinue }
        $tmp = @(Get-ChildItem -LiteralPath $out -Recurse -Force -Filter 'blast_query_*' -ErrorAction SilentlyContinue)
        if ($tmp) { Record "$($k.Case)-leftovers" 'INFO' '' 0 "$($tmp.Count) blast_query_* left (cleaned by the next run into the folder)" }
    }

    # The big genome, gzipped, run to the end (decompressed query, larger BLAST output).
    $gz = "$big\genomes-gz"
    New-Item -ItemType Directory -Force $gz | Out-Null
    $src = [IO.File]::OpenRead("$big\genomes\big.fasta")
    $dst = New-Object IO.Compression.GZipStream([IO.File]::Create("$gz\big.fasta.gz"), [IO.Compression.CompressionLevel]::Fastest)
    $src.CopyTo($dst); $dst.Dispose(); $src.Dispose()
    $out = Join-Path $Work 'out\big gz'
    $r = Run-Case 'big-genome-gz' $Java (App-Args @('-i', $gz, '-p', "$DataPlain\primers.fasta", '-o', $out, '-t', '4'))
    Check-Run 'big-genome-gz' $r $out 'none' | Out-Null
    Record 'big-genome-gz-products' 'INFO' '' 0 (Get-Products (Join-Path $out 'consolidated_report\report.tsv'))
}

# --- Done --------------------------------------------------------------------------------------------------
$rows = Import-Csv -Delimiter "`t" $Summary
$counts = $rows | Group-Object status | ForEach-Object { "$($_.Name): $($_.Count)" }
Log ("Done. " + ($counts -join ', ') + ". Results in $Results")
# Said explicitly: the exit code of the last program run here (robocopy, subst) would be taken instead.
$failed = @($rows | Where-Object status -eq 'FAIL').Count
if ($failed) { exit 1 }
exit 0
