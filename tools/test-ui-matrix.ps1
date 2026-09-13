param(
    [string]$JdkHome='C:\Users\vitalii\.jdks\ms-21.0.11',
    [string]$OutputDirectory,
    [string]$ImageRoot='build/install/wreck-riff',
    [string[]]$Cases=@()
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$image=if([IO.Path]::IsPathRooted($ImageRoot)){$ImageRoot}else{Join-Path $root $ImageRoot}
$output=if($OutputDirectory){[IO.Path]::GetFullPath($OutputDirectory)}else{Join-Path $root ('build/menu-review-matrix-'+[Guid]::NewGuid().ToString('N'))}
if(Test-Path -LiteralPath $output){throw 'Use a new output directory to preserve prior evidence.'}
. (Join-Path $root 'tools/release-evidence.ps1')
[IO.Directory]::CreateDirectory($output) | Out-Null
$java=Join-Path $JdkHome 'bin/java.exe'
$originalLocalAppData=$env:LOCALAPPDATA
$results=[Collections.Generic.List[object]]::new()
$matrix=@('640x480@1.5','3840x2160@1.5')
foreach($resolution in @('640x480','1280x720','1920x1080','2560x1440','3840x1080','3840x2160')) {
    foreach($scale in @('0.8','1','1.5')) { $key="$resolution@$scale"; if($matrix -notcontains $key){$matrix+=$key} }
}
if($Cases.Count -gt 0) {
    if(@($Cases | Where-Object {$matrix -cnotcontains $_}).Count -gt 0 -or @($Cases | Select-Object -Unique).Count -ne $Cases.Count) {
        throw 'Cases must contain distinct supported resolution@scale pairs from the standard matrix.'
    }
    $matrix=$Cases
}
$jar=Join-Path $image 'lib/wreck-riff-0.4.0.jar'
$jarHash=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
try {
    foreach($key in $matrix) {
        if(Test-Path -LiteralPath (Join-Path $output 'STOP')){throw 'Matrix stopped between cases for a source correction.'}
        $parts=$key.Split('@');$resolution=$parts[0];$scale=$parts[1]
        $runRoot=Join-Path $output ($key.Replace('@','-scale'))
        [IO.Directory]::CreateDirectory($runRoot) | Out-Null
        $env:LOCALAPPDATA=Join-Path $runRoot 'user-data'
        $arguments=@('-Xmx1024m','-cp',(Join-Path $image 'lib/*'),'game.wreckriff.Main','--dev','--ui-review',"--resolution=$resolution","--ui-scale=$scale")
        if($resolution -in @('640x480','1280x720')){$arguments+='--windowed'}
        $log=Join-Path $runRoot 'process.log'
        Write-Output "START $key"
        # Windows PowerShell 5.1 wraps ordinary Java stderr logs in NativeCommandError.
        # Check the native exit code and capture evidence, rather than treating a log line as a crash.
        $previousErrorAction=$ErrorActionPreference
        try {
            $ErrorActionPreference='Continue'
            & $java @arguments *> $log
            $code=$LASTEXITCODE
        } finally {$ErrorActionPreference=$previousErrorAction}
        $reports=@(Get-ChildItem -LiteralPath $env:LOCALAPPDATA -Recurse -File -Filter 'ui-review-manifest.json')
        if($code -ne 0 -or $reports.Count -ne 1){throw "Process/capture failure $key; exit=$code; manifests=$($reports.Count); log=$log"}
        $manifest=Get-Content -LiteralPath $reports[0].FullName -Raw | ConvertFrom-Json
        $diagnostic=Get-Content -LiteralPath (Join-Path $reports[0].DirectoryName 'diagnostic-result.json') -Raw | ConvertFrom-Json
        $captured=@($manifest.cases | Where-Object status -eq 'CAPTURED')
        $failed=@($manifest.cases | Where-Object status -eq 'FAIL')
        $request=Get-ReleaseUiReviewRequest $resolution ([double]::Parse($scale,[Globalization.CultureInfo]::InvariantCulture)) ($resolution -in @('640x480','1280x720'))
        $proof=Get-ReleaseUiReviewEvidence $reports[0].FullName $diagnostic $request
        [IO.File]::WriteAllText((Join-Path $runRoot 'capture-evidence.json'),(ConvertTo-Json -InputObject $proof -Depth 12),[Text.UTF8Encoding]::new($false))
        $entry=[ordered]@{request=$key;status=$manifest.status;cases=$manifest.cases.Count;captured=$captured.Count;manifest=$reports[0].FullName;mainJarSha256=$jarHash;sourceSha256=$diagnostic.sourceSha256;visualReview='PENDING';ownerAcceptance='NOT_GRANTED'}
        $results.Add($entry)
        [IO.File]::WriteAllText((Join-Path $output 'matrix.json'),(ConvertTo-Json -InputObject $results.ToArray() -Depth 8),[Text.UTF8Encoding]::new($false))
        if($manifest.status -ne 'CAPTURES_COMPLETE_HUMAN_REVIEW_PENDING' -or $failed.Count -gt 0){throw "Scenario failure $key : $($failed | ConvertTo-Json -Depth 6 -Compress)"}
        Write-Output "DONE $key $($captured.Count) PNG"
    }
} finally {$env:LOCALAPPDATA=$originalLocalAppData}
