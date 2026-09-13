# Shared, read-only identity and acceptance checks. Compatible with Windows PowerShell 5.1.
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem

function Get-ReleaseSha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Get-ReleaseStreamHash([IO.Stream]$Stream) {
    $hash=[Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($hash.ComputeHash($Stream)).Replace('-','').ToLowerInvariant() }
    finally { $hash.Dispose() }
}
function Write-ReleaseJson([string]$Path,$Value) {
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($Path))) | Out-Null
    [IO.File]::WriteAllText($Path,($Value | ConvertTo-Json -Depth 30),[Text.UTF8Encoding]::new($false))
}
function Read-ReleaseJson([string]$Path) { return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json }
function Invoke-ReleaseNativeCommand([string]$Executable,[string[]]$Arguments,[string]$LogPath) {
    # Windows PowerShell 5.1 wraps redirected native stderr in NativeCommandError,
    # even for successful INFO output. Let the native process finish, then check
    # its exit code; do not relax error handling for the surrounding build checks.
    $savedPreference=$ErrorActionPreference;$global:LASTEXITCODE=$null;$exitCode=$null
    try {
        $ErrorActionPreference='Continue'
        & $Executable @Arguments *> $LogPath
        $exitCode=$global:LASTEXITCODE
    } finally {$ErrorActionPreference=$savedPreference}
    if($null -eq $exitCode){throw "Native command did not provide an exit code: $Executable; see $LogPath"}
    if($exitCode -ne 0) {
        $failure=[InvalidOperationException]::new("Native command failed ($exitCode): $Executable; see $LogPath")
        $failure.Data['exitCode']=[int]$exitCode
        throw $failure
    }
    return [int]$exitCode
}
function New-ReleaseZip([string]$Image,[string]$ZipPath) {
    # .NET Framework's CreateFromDirectory uses backslashes on Windows. ZIP paths
    # are always slash-separated, including when this is run by PowerShell 5.1.
    $root=(Resolve-Path -LiteralPath $Image).ProviderPath.TrimEnd('\','/')
    if((Split-Path -Leaf $root) -ne 'WreckRiff'){throw 'Release image must be named WreckRiff.'}
    $stream=[IO.File]::Open($ZipPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {
        $archive=[IO.Compression.ZipArchive]::new($stream,[IO.Compression.ZipArchiveMode]::Create,$true)
        try {
            foreach($file in Get-ChildItem -LiteralPath $root -File -Recurse | Sort-Object FullName) {
                $name='WreckRiff/'+$file.FullName.Substring($root.Length+1).Replace('\','/')
                $entry=$archive.CreateEntry($name,[IO.Compression.CompressionLevel]::Optimal)
                $zipInput=[IO.File]::OpenRead($file.FullName);$zipOutput=$entry.Open()
                try {$zipInput.CopyTo($zipOutput)}finally{$zipOutput.Dispose();$zipInput.Dispose()}
            }
        } finally {$archive.Dispose()}
    } finally {$stream.Dispose()}
}
function Get-ReleaseArtifact([string]$Path) {
    return [ordered]@{path=[IO.Path]::GetFullPath($Path);sha256=(Get-ReleaseSha256 $Path)}
}
function Assert-ReleaseArtifact($Artifact) {
    if($Artifact.sha256 -notmatch '^[a-f0-9]{64}$' -or (Get-ReleaseSha256 $Artifact.path) -ne $Artifact.sha256) {
        throw "Missing or changed evidence: $($Artifact.path)"
    }
}
function Assert-ReleaseProcessLogs([string[]]$Paths) {
    # A decoder/native thread may fail without changing the launcher exit code or
    # render-thread diagnostic. Stream the logs; ordinary WARNING lines are allowed.
    $fatal='Exception in thread|AssertionError|A fatal error has been detected by the Java Runtime Environment|EXCEPTION_ACCESS_VIOLATION|SIGSEGV|FATAL ERROR in native method'
    foreach($path in $Paths) {
        $reader=[IO.File]::OpenText($path)
        try {
            while($null -ne ($line=$reader.ReadLine())) {
                if($line -match $fatal){throw "Uncaught or native process failure in $(Split-Path -Leaf $path)."}
            }
        } finally {$reader.Dispose()}
    }
}
function Get-ReleaseInputHash([string]$Root,[switch]$RuntimeOnly) {
    $files=[Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
    $directories=if($RuntimeOnly){@('src/main','src/tools')}else{@('src','tools','gradle','docs')}
    foreach($directory in $directories) {
        foreach($file in Get-ChildItem -LiteralPath (Join-Path $Root $directory) -File -Recurse) {
            $relative=$file.FullName.Substring($Root.TrimEnd('\','/').Length+1).Replace('\','/')
            if($relative -match '(^|/)__pycache__/|\.pyc$'){continue}
            $files[$relative]=$file.FullName
        }
    }
    $names=@('build.gradle','settings.gradle','gradle.lockfile')
    if(!$RuntimeOnly){$names+=@('gradlew','gradlew.bat','gradle.properties','README.md')}
    foreach($name in $names){$path=Join-Path $Root $name;if(Test-Path -LiteralPath $path){$files[$name]=$path}}
    $ordered=[string[]]@($files.Keys);[Array]::Sort($ordered,[StringComparer]::Ordinal)
    $hash=[Security.Cryptography.SHA256]::Create()
    try {
        foreach($name in $ordered) {
            $prefix=[Text.Encoding]::UTF8.GetBytes($name+[char]0)
            $null=$hash.TransformBlock($prefix,0,$prefix.Length,$prefix,0)
            $stream=[IO.File]::OpenRead($files[$name])
            try {
                $buffer=New-Object byte[] 65536
                while(($read=$stream.Read($buffer,0,$buffer.Length)) -gt 0){$null=$hash.TransformBlock($buffer,0,$read,$buffer,0)}
            } finally {$stream.Dispose()}
        }
        $null=$hash.TransformFinalBlock((New-Object byte[] 0),0,0)
        return [BitConverter]::ToString($hash.Hash).Replace('-','').ToLowerInvariant()
    } finally {$hash.Dispose()}
}
function Get-ReleaseJarInfo([IO.Stream]$Stream) {
    $copy=[IO.MemoryStream]::new()
    try {
        $Stream.CopyTo($copy);$copy.Position=0
        $jarHash=Get-ReleaseStreamHash $copy;$copy.Position=0
        $jar=[IO.Compression.ZipArchive]::new($copy,[IO.Compression.ZipArchiveMode]::Read,$true)
        try {
            $entry=$jar.GetEntry('build-info.properties');if(!$entry){throw 'Application JAR has no build metadata.'}
            $reader=[IO.StreamReader]::new($entry.Open())
            try {$metadata=ConvertFrom-StringData $reader.ReadToEnd()}finally{$reader.Dispose()}
            if($metadata.sourceSha256 -notmatch '^[a-f0-9]{64}$'){throw 'Application JAR has invalid source identity.'}
            return [pscustomobject]@{version=$metadata.version;sourceSha256=$metadata.sourceSha256;mainJarSha256=$jarHash}
        } finally {$jar.Dispose()}
    } finally {$copy.Dispose()}
}
function Get-ReleasePackageIdentity([string]$ZipPath) {
    $absolute=(Resolve-Path -LiteralPath $ZipPath).ProviderPath
    $zipHash=Get-ReleaseSha256 $absolute
    $zip=[IO.Compression.ZipFile]::OpenRead($absolute)
    try {
        $main=@($zip.Entries | Where-Object {$_.FullName -match '^WreckRiff/app/wreck-riff-[0-9.]+\.jar$'})
        if($main.Count -ne 1){throw 'ZIP must contain exactly one versioned application JAR under WreckRiff/app.'}
        $names=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        foreach($entry in $zip.Entries) {
            if(!$entry.FullName.StartsWith('WreckRiff/') -or $entry.FullName -match '(^|[/\\])\.\.?([/\\]|$)|:|\\|[ .](/|$)' -or !$names.Add($entry.FullName)) {throw 'Unsafe, duplicate or unexpected ZIP entry.'}
        }
        $stream=$main[0].Open();try{$info=Get-ReleaseJarInfo $stream}finally{$stream.Dispose()}
        $entry=$zip.GetEntry('WreckRiff/reports/package-verification.json');if(!$entry){throw 'ZIP lacks package verification.'}
        $reader=[IO.StreamReader]::new($entry.Open())
        try{$package=$reader.ReadToEnd()|ConvertFrom-Json}finally{$reader.Dispose()}
        if($package.schemaVersion -lt 2 -or $package.status -ne 'PACKAGE_STRUCTURE_VERIFIED' -or $package.sourceSha256 -ne $info.sourceSha256 -or $package.mainJarSha256 -ne $info.mainJarSha256 -or $package.version -ne $info.version -or $package.verificationInputsSha256 -notmatch '^[a-f0-9]{64}$') {
            throw 'Package metadata does not identify the application JAR (repackage with current tooling).'
        }
        Assert-ReleaseDocumentation $zip $package
        if((Get-ReleaseSha256 $absolute) -ne $zipHash){throw 'ZIP changed while its identity was read.'}
        return [pscustomobject][ordered]@{version=$info.version;sourceSha256=$info.sourceSha256;mainJarSha256=$info.mainJarSha256;
            verificationInputsSha256=$package.verificationInputsSha256;zipSha256=$zipHash;zipPath=$absolute;packagedAtUtc=$package.packagedAtUtc}
    } finally {$zip.Dispose()}
}
function Assert-ReleaseIdentity($Evidence,$Identity) {
    if($Evidence.version -ne $Identity.version){throw 'Release version mismatch.'}
    foreach($field in @('sourceSha256','mainJarSha256','verificationInputsSha256','zipSha256')) {
        if($Evidence.$field -notmatch '^[a-f0-9]{64}$' -or $Evidence.$field -ne $Identity.$field){throw "Release identity mismatch: $field"}
    }
}
function Expand-ReleasePackage([string]$ZipPath,[string]$Destination) {
    $identity=Get-ReleasePackageIdentity $ZipPath
    if(Test-Path -LiteralPath $Destination){throw 'Extraction destination must be new; previous evidence is preserved.'}
    [IO.Compression.ZipFile]::ExtractToDirectory($identity.zipPath,$Destination)
    if((Get-ReleaseSha256 $identity.zipPath) -ne $identity.zipSha256){throw 'ZIP changed during extraction.'}
    return [pscustomobject]@{identity=$identity;image=(Join-Path $Destination 'WreckRiff')}
}
function Assert-ReleaseImage([string]$ZipPath,[string]$Image) {
    $zip=[IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        $entries=@($zip.Entries | Where-Object {$_.Name.Length -gt 0})
        if(@(Get-ChildItem -LiteralPath $Image -Recurse -File).Count -ne $entries.Count){throw 'Extracted image file count changed.'}
        foreach($entry in $entries) {
            $path=Join-Path $Image $entry.FullName.Substring('WreckRiff/'.Length)
            $stream=$entry.Open();try{$expected=Get-ReleaseStreamHash $stream}finally{$stream.Dispose()}
            if((Get-ReleaseSha256 $path) -ne $expected){throw "Extracted image changed: $($entry.FullName)"}
        }
    } finally {$zip.Dispose()}
}
function Read-ReleaseZipText($Zip,[string]$Name) {
    $entry=$Zip.GetEntry($Name)
    if(!$entry -or $entry.Length -lt 1 -or $entry.Length -gt 1MB){throw "Missing or oversized package text: $Name"}
    $reader=[IO.StreamReader]::new($entry.Open())
    try{return $reader.ReadToEnd()}finally{$reader.Dispose()}
}
function Assert-ReleaseZipFile($Zip,[string]$Name,[string]$Sha256,[long]$Bytes) {
    $entry=$Zip.GetEntry($Name)
    if(!$entry -or $Bytes -lt 1 -or $Bytes -gt 256MB -or $entry.Length -ne $Bytes -or $Sha256 -notmatch '^[a-f0-9]{64}$'){throw "Missing or invalid packaged source/notice: $Name"}
    $stream=$entry.Open();try{$actual=Get-ReleaseStreamHash $stream}finally{$stream.Dispose()}
    if($actual -ne $Sha256){throw "Packaged source/notice checksum mismatch: $Name"}
}
function Assert-ReleaseMaterialPath([string]$Path) {
    if([string]::IsNullOrWhiteSpace($Path) -or $Path.StartsWith('/') -or $Path -match '(^|/)\.\.?(/|$)|\\|:'){throw 'Unsafe source material path.'}
}
function ConvertTo-ReleasePlainText([string]$Text) {
    # Local repository links are labels in the portable text; the package guide
    # provides the actual portable filenames. Do not ship links into absent docs.
    $text=[regex]::Replace($Text,'!?\[([^\]]+)\]\([^)]+\)','$1')
    $text=[regex]::Replace($text,'(?m)^#{1,6}\s+','')
    return $text.Replace('`','').Trim()
}
function New-ReleaseDocumentation([string]$ProjectRoot,[string]$Image,[string]$Version) {
    $sourceNames=@('README.md','docs/RELEASE_NOTES.md','docs/ACCEPTANCE.md','docs/PERFORMANCE_BASELINE_2026-09-13.md','docs/THIRD_PARTY_NOTICES.md')
    $sources=@();$texts=@{}
    foreach($name in $sourceNames) {
        $path=Join-Path $ProjectRoot $name
        $texts[$name]=[IO.File]::ReadAllText($path,[Text.Encoding]::UTF8)
        $sources+=@{path=$name;bytes=(Get-Item -LiteralPath $path).Length;sha256=(Get-ReleaseSha256 $path)}
    }
    $readmeSections=[regex]::Split($texts['README.md'],'(?m)(?=^## )')
    $controls=@($readmeSections | Where-Object {$_ -match '(?m)^\|'}) | Select-Object -First 1
    if(!$controls -or !$controls.Contains('Controls') -or !$controls.Contains('Grinder') -or !$controls.Contains('Spark')) {
        throw 'The current README does not provide the expected three-chassis control guide.'
    }
    $acceptanceSections=[regex]::Split($texts['docs/ACCEPTANCE.md'],'(?m)(?=^## )')
    $limits=@($acceptanceSections | Where-Object {$_ -match '^## ' -and $_ -match 'benchmark' -and $_ -match 'PENDING'})
    if($limits.Count -ne 1){throw 'Expected one current limitations section in ACCEPTANCE; review the portable guide extractor.'}
    $baseline=$texts['docs/PERFORMANCE_BASELINE_2026-09-13.md']
    $gpu=[regex]::Match($baseline,'(?m)^GPU[^\r\n]+').Value
    $window=[regex]::Match($baseline,'(?m)^[^\r\n]*undrawableSeconds[^\r\n]*').Value
    $priorSource=[regex]::Match($baseline,'[a-f0-9]{64}').Value
    if(!$gpu -or !$window -or !$priorSource){throw 'The historical hardware observation is incomplete.'}
    $header="Wreck Riff $Version - Windows x64 release candidate`r`n`r`n"
    $guide="Portable guide: README.txt (installation/data), CONTROLS.txt, RELEASE_NOTES.txt,`r`nKNOWN_LIMITATIONS.txt, TESTED_HARDWARE.txt, VERIFICATION_STATUS.txt.`r`nResources: reports/asset-register.csv. Notices: licenses/THIRD_PARTY_NOTICES.md.`r`n`r`n"
    $contents=[ordered]@{}
    $contents['CONTROLS.txt']=$header+(ConvertTo-ReleasePlainText $controls)
    $contents['RELEASE_NOTES.txt']=$header+$guide+(ConvertTo-ReleasePlainText $texts['docs/RELEASE_NOTES.md'])
    $contents['KNOWN_LIMITATIONS.txt']=$header+@"
Snapshot of the documented pre-package limitations. Developer log paths mentioned
below identify historical investigations; those logs are not inside this game ZIP.
Packaging runs no graphical, performance, controller or owner-acceptance scenario.
Later evidence for this exact ZIP must be delivered separately; see VERIFICATION_STATUS.txt.

"@+(ConvertTo-ReleasePlainText $limits[0])
    $contents['TESTED_HARDWARE.txt']=$header+@"
HISTORICAL_DEVELOPMENT_OBSERVATION_ONLY
The following configuration was observed before packaging, for source:
$priorSource
$gpu
$window

This was a short 1280x720 diagnostic profile, not the final ZIP benchmark.
CPU, RAM and exact Windows build are not established by this source document.
Minimum system requirements have not been established. One observed GPU does not
prove other configurations. Physical controller and a separate clean Windows
installation require actual hardware observations for the exact candidate.
This document is a factual hardware record, not an owner approval.
"@
    $contents['VERIFICATION_STATUS.txt']=$header+@"
RELEASE_CANDIDATE - PACKAGE_CANDIDATE_SNAPSHOT
This immutable package records structure/material integrity and its source/JAR identity.
It was assembled before final EXE scenarios; it cannot contain their future results.
Graphical launch / normal profiles / six benchmarks / 1800-second soak:
NOT_RUN_BY_PACKAGING. Physical controller and another Windows installation:
NOT_VERIFIED_BY_PACKAGING. Owner feel: OWNER_REVIEW_NOT_RECORDED_BY_PACKAGING.
Documentation completeness is a technical check, separate from owner feel.

The supplied reports/package-verification.json identifies the version, source and
application JAR and binds every portable guide file and its source-document SHA-256.
reports/verification.json, reports/asset-register.csv and reports/source-distribution.json
record asset/source-material checks; they do not grant distribution or owner approval.

Final acceptance must accompany this candidate separately as the release-gate report
and verification archive bound to this exact ZIP SHA-256. Their timestamps must follow
packaging. No such external result is manufactured or implied by this guide.
The game ZIP remains immutable after measurement; a changed ZIP needs new evidence.
"@
    foreach($name in $contents.Keys) {
        [IO.File]::WriteAllText((Join-Path $Image $name),$contents[$name]+"`r`n",[Text.UTF8Encoding]::new($false))
    }
    $files=@()
    foreach($name in @('README.txt')+@($contents.Keys)+@('reports/asset-register.csv','licenses/THIRD_PARTY_NOTICES.md')) {
        $path=Join-Path $Image $name
        $files+=@{path=$name;bytes=(Get-Item -LiteralPath $path).Length;sha256=(Get-ReleaseSha256 $path)}
    }
    return [ordered]@{schemaVersion=1;version=$Version;scope='PACKAGE_CANDIDATE_SNAPSHOT';finalEvidence='EXTERNAL_AFTER_PACKAGING';sources=$sources;files=$files}
}
function Assert-ReleaseDocumentation($Zip,$Package) {
    $docs=$Package.documentation
    if($docs.schemaVersion -ne 1 -or $docs.version -ne $Package.version -or $docs.scope -ne 'PACKAGE_CANDIDATE_SNAPSHOT' -or $docs.finalEvidence -ne 'EXTERNAL_AFTER_PACKAGING') {
        throw 'Package documentation must identify this candidate without claiming future acceptance.'
    }
    $expected=@('README.txt','CONTROLS.txt','RELEASE_NOTES.txt','KNOWN_LIMITATIONS.txt','TESTED_HARDWARE.txt','VERIFICATION_STATUS.txt','reports/asset-register.csv','licenses/THIRD_PARTY_NOTICES.md')
    $names=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach($file in $docs.files) {
        if($expected -cnotcontains $file.path -or !$names.Add($file.path)){throw 'Unexpected or duplicate release document.'}
        Assert-ReleaseZipFile $Zip ('WreckRiff/'+$file.path) $file.sha256 $file.bytes
        if($file.path.EndsWith('.txt')) {
            $text=Read-ReleaseZipText $Zip ('WreckRiff/'+$file.path)
            if(!$text.Contains('Wreck Riff '+$Package.version) -or $text -match '!?\[[^\]]+\]\([^)]+\)'){throw 'Release guide has stale version or non-portable Markdown links.'}
        }
    }
    if($names.Count -ne $expected.Count){throw 'Incomplete portable release documentation.'}
    $sourceNames=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach($source in $docs.sources) {
        Assert-ReleaseMaterialPath $source.path
        if(!$sourceNames.Add($source.path) -or $source.bytes -lt 1 -or $source.sha256 -notmatch '^[a-f0-9]{64}$'){throw 'Invalid release documentation provenance.'}
    }
    foreach($name in @('README.md','docs/RELEASE_NOTES.md','docs/ACCEPTANCE.md','docs/PERFORMANCE_BASELINE_2026-09-13.md','docs/THIRD_PARTY_NOTICES.md')) {
        if(!$sourceNames.Contains($name)){throw "Missing release documentation source: $name"}
    }
    $status=Read-ReleaseZipText $Zip 'WreckRiff/VERIFICATION_STATUS.txt'
    foreach($required in @('RELEASE_CANDIDATE','PACKAGE_CANDIDATE_SNAPSHOT','NOT_RUN_BY_PACKAGING','NOT_VERIFIED_BY_PACKAGING','OWNER_REVIEW_NOT_RECORDED_BY_PACKAGING')) {
        if(!$status.Contains($required)){throw 'Portable status omitted a pending acceptance boundary.'}
    }
}
function Get-ReleaseRuntimeBinding([string]$JdkRoot,[string]$Image,$SourceReport) {
    $releaseSha=Get-ReleaseSha256 (Join-Path $JdkRoot 'release')
    if($releaseSha -ne $SourceReport.selectedJdkReleaseSha256){throw 'Selected JDK release differs from verified source materials.'}
    $jmodPath=Join-Path $JdkRoot 'jmods/java.base.jmod';$jmodFile=Get-Item -LiteralPath $jmodPath
    if($jmodFile.Length -lt 4 -or $jmodFile.Length -gt 128MB){throw 'Selected java.base.jmod is absent or oversized.'}
    $jmodSha=Get-ReleaseSha256 $jmodPath
    $inputStream=[IO.File]::OpenRead($jmodPath);$zipStream=[IO.MemoryStream]::new()
    try {
        # JMOD 1.0 is a ZIP preceded by these four bytes. ZIP offsets are relative
        # to the ZIP payload, so passing the whole JMOD to ZipArchive is incorrect.
        $header=[byte[]]::new(4)
        if($inputStream.Read($header,0,4) -ne 4 -or [BitConverter]::ToString($header) -ne '4A-4D-01-00'){throw 'Unsupported JMOD header.'}
        $inputStream.CopyTo($zipStream);$zipStream.Position=0
        $archive=[IO.Compression.ZipArchive]::new($zipStream,[IO.Compression.ZipArchiveMode]::Read,$true)
        try {
            $binaries=@();$mapping=[ordered]@{'bin/java.exe'='runtime/bin/java.exe';'lib/server/jvm.dll'='runtime/bin/server/jvm.dll'}
            foreach($entryName in $mapping.Keys) {
                $entries=@($archive.Entries | Where-Object {$_.FullName -ceq $entryName})
                if($entries.Count -ne 1 -or $entries[0].Length -lt 1 -or $entries[0].Length -gt 64MB){throw "Missing or invalid JMOD native entry: $entryName"}
                $entry=$entries[0];$stream=$entry.Open();try{$sha=Get-ReleaseStreamHash $stream}finally{$stream.Dispose()}
                $file=Get-Item -LiteralPath (Join-Path $Image $mapping[$entryName])
                if($file.Length -ne $entry.Length -or (Get-ReleaseSha256 $file.FullName) -ne $sha){throw "Bundled runtime differs from selected JDK JMOD entry: $entryName"}
                $binaries+=[ordered]@{path=$mapping[$entryName];entry=$entryName;bytes=$entry.Length;sha256=$sha}
            }
        }finally{$archive.Dispose()}
    }finally{$inputStream.Dispose();$zipStream.Dispose()}
    if((Get-ReleaseSha256 $jmodPath) -ne $jmodSha){throw 'Selected JMOD changed during packaging.'}
    return [ordered]@{schemaVersion=1;status='BUNDLED_RUNTIME_MATCHES_SELECTED_JMOD';selectedJdkReleaseSha256=$releaseSha;
        jmod=[ordered]@{path='jmods/java.base.jmod';bytes=$jmodFile.Length;sha256=$jmodSha};binaries=$binaries}
}
function Assert-ReleaseSourceDistribution([string]$ZipPath) {
    $zip=[IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        $package=Read-ReleaseZipText $zip 'WreckRiff/reports/package-verification.json' | ConvertFrom-Json
        $binding=$package.sourceDistribution
        if($binding.path -ne 'reports/source-distribution.json'){throw 'Package has no corresponding-source report binding.'}
        $entry=$zip.GetEntry('WreckRiff/'+$binding.path)
        if(!$entry){throw 'Package lacks corresponding-source verification.'}
        Assert-ReleaseZipFile $zip ('WreckRiff/'+$binding.path) $binding.sha256 $entry.Length
        $report=Read-ReleaseZipText $zip ('WreckRiff/'+$binding.path) | ConvertFrom-Json
        if($report.schemaVersion -ne 1 -or $report.status -ne 'SOURCE_AND_NOTICE_MATERIALS_VERIFIED' -or $report.distributionApproval -ne 'NOT_GRANTED' -or $report.inputIndexSha256 -ne $binding.inputIndexSha256 -or $report.inputIndexSha256 -notmatch '^[a-f0-9]{64}$'){throw 'Unverified source materials or unsupported distribution claim.'}
        if($report.index.path -ne 'source-index.json'){throw 'Unexpected packaged source index.'}
        Assert-ReleaseZipFile $zip 'WreckRiff/licenses/source-index.json' $report.index.sha256 $report.index.bytes
        $index=Read-ReleaseZipText $zip 'WreckRiff/licenses/source-index.json' | ConvertFrom-Json
        if($index.schemaVersion -ne 1 -or $index.sources.Count -ne 2 -or $report.sources.Count -ne 2){throw 'Missing source components or repo-only parts index in package.'}
        $sources=@{}
        foreach($source in $report.sources) {
            if($sources.ContainsKey($source.id) -or $source.id -notin @('openal-soft','microsoft-openjdk') -or $source.commit -notmatch '^[a-f0-9]{40}$'){throw 'Invalid source component identity.'}
            $sources[$source.id]=$source
            $matches=@($index.sources | Where-Object {$_.id -eq $source.id})
            if($matches.Count -ne 1){throw 'Packaged source index component mismatch.'};$indexed=$matches[0]
            if($indexed.PSObject.Properties['parts']){throw 'Repo archive parts must not replace complete package sources.'}
            foreach($field in @('version','commit','url')){if($source.$field -ne $indexed.$field){throw "Source identity changed: $field"}}
            if($source.archive.path -ne $indexed.archive -or $source.archive.bytes -ne $indexed.bytes -or $source.archive.sha256 -ne $indexed.sha256){throw 'Source archive identity differs from the package index.'}
            Assert-ReleaseMaterialPath $source.archive.path
            if(!$source.archive.path.StartsWith('sources/') -or !$source.archive.path.EndsWith('.tar.gz')){throw 'Package must contain full source tar.gz archives.'}
            Assert-ReleaseZipFile $zip ('WreckRiff/licenses/'+$source.archive.path) $source.archive.sha256 $source.archive.bytes
        }
        $required=@('README.md','openal/COPYING','openal/BSD-3Clause','openal/LICENSE-pffft','openal/fmt-LICENSE','openal/REPLACEMENT.md',
            'runtime/LICENSE','runtime/ADDITIONAL_LICENSE_INFO','runtime/ASSEMBLY_EXCEPTION','runtime/microsoft-jdk-release.txt','runtime/README.md')
        $documents=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach($document in $report.documents) {
            Assert-ReleaseMaterialPath $document.path
            if(!$documents.Add($document.path)){throw 'Duplicate source notice.'}
            $matches=@($index.documents | Where-Object {$_.path -eq $document.path})
            if($matches.Count -ne 1 -or $matches[0].sha256 -ne $document.sha256){throw 'Source notice differs from the package index.'}
            Assert-ReleaseZipFile $zip ('WreckRiff/licenses/'+$document.path) $document.sha256 $document.bytes
        }
        foreach($requiredName in $required){if(!$documents.Contains($requiredName)){throw "Missing source notice $requiredName"}}
        if($documents.Count -ne $index.documents.Count){throw 'Package source report omitted indexed notices.'}
        $native=$report.nativeLibrary
        foreach($field in @('sourceId','jar','jarSha256','entry','sha256','gitEntry')){if($native.$field -ne $index.nativeLibrary.$field){throw 'Native source binding changed.'}}
        if($native.sourceId -ne 'openal-soft' -or $native.entry -ne 'windows/x64/org/lwjgl/openal/OpenAL.dll' -or $native.gitEntry -ne 'META-INF/'+$native.entry+'.git' -or $native.jar -notmatch '^lwjgl-openal-[0-9.]+-natives-windows\.jar$'){throw 'Unsupported OpenAL native binding.'}
        $jarEntry=$zip.GetEntry('WreckRiff/app/'+$native.jar)
        if(!$jarEntry -or $jarEntry.Length -gt 64MB){throw 'OpenAL native JAR is absent or oversized.'}
        Assert-ReleaseZipFile $zip ('WreckRiff/app/'+$native.jar) $native.jarSha256 $jarEntry.Length
        $copy=[IO.MemoryStream]::new();$stream=$jarEntry.Open()
        try {$stream.CopyTo($copy);$copy.Position=0;$jar=[IO.Compression.ZipArchive]::new($copy,[IO.Compression.ZipArchiveMode]::Read,$true)
            try {
                $dll=$jar.GetEntry($native.entry);if(!$dll -or $dll.Length -gt 16MB){throw 'OpenAL DLL is absent or oversized.'}
                Assert-ReleaseZipFile $jar $native.entry $native.sha256 $dll.Length
                $commit=(Read-ReleaseZipText $jar $native.gitEntry).Trim()
                if($commit -ne $sources['openal-soft'].commit){throw 'OpenAL source commit does not match the native JAR.'}
                $reader=[IO.StreamReader]::new($dll.Open(),[Text.Encoding]::GetEncoding(28591))
                try {$binary=$reader.ReadToEnd()}finally{$reader.Dispose()}
                if(!$binary.Contains('1.1 ALSOFT '+$sources['openal-soft'].version+[char]0)){throw 'OpenAL source version does not match the DLL.'}
            }finally{$jar.Dispose()}
        }finally{$stream.Dispose();$copy.Dispose()}
        $runtime=$report.runtime
        foreach($field in @('sourceId','releaseFile','implementor','implementorVersion','runtimeVersion','javaVersion','source')){if($runtime.$field -ne $index.runtime.$field){throw 'Runtime source binding changed.'}}
        if($runtime.sourceId -ne 'microsoft-openjdk' -or $runtime.releaseFile -ne 'runtime/microsoft-jdk-release.txt' -or $runtime.implementor -ne 'Microsoft' -or !$runtime.javaVersion.StartsWith('21.') -or $runtime.runtimeVersion -ne $sources['microsoft-openjdk'].version -or $runtime.source -notmatch '^[a-f0-9]{12,40}$' -or !$sources['microsoft-openjdk'].commit.StartsWith($runtime.source)){throw 'Runtime source version or commit mismatch.'}
        $expected=@{IMPLEMENTOR=$runtime.implementor;IMPLEMENTOR_VERSION=$runtime.implementorVersion;JAVA_RUNTIME_VERSION=$runtime.runtimeVersion;JAVA_VERSION=$runtime.javaVersion;SOURCE=('.:git:'+$runtime.source)}
        $originalReleasePath='WreckRiff/licenses/'+$runtime.releaseFile
        $originalRelease=ConvertFrom-StringData (Read-ReleaseZipText $zip $originalReleasePath)
        foreach($field in $expected.Keys) {
            if(!$originalRelease.ContainsKey($field) -or [string]::IsNullOrEmpty($originalRelease[$field]) -or $originalRelease[$field].Trim('"') -ne $expected[$field]){throw "Original JDK/source metadata missing or mismatched: $field"}
        }
        # Stock jlink writes only JAVA_VERSION and MODULES. Its native files are
        # bound below to the selected JDK's actual JMOD entries, not its signed bin/ copies.
        $release=ConvertFrom-StringData (Read-ReleaseZipText $zip 'WreckRiff/runtime/release')
        if(!$release.ContainsKey('JAVA_VERSION') -or $release['JAVA_VERSION'].Trim('"') -ne $runtime.javaVersion){throw 'Bundled jlink Java version is missing or differs from its source.'}
        if(!$release.ContainsKey('MODULES') -or [string]::IsNullOrEmpty($release['MODULES'])){throw 'Bundled jlink module list is missing.'}
        foreach($field in $expected.Keys) {
            if($release.ContainsKey($field) -and $release[$field].Trim('"') -ne $expected[$field]){throw "Bundled jlink metadata contradicts its source: $field"}
        }
        foreach($module in @('java.base','java.desktop','java.logging','java.management','jdk.unsupported','jdk.crypto.ec','jdk.jfr')) {
            if($runtime.jlinkModules -notcontains $module -or $index.runtime.jlinkModules -notcontains $module -or $release['MODULES'].Trim('"').Split(' ') -notcontains $module){throw "Bundled runtime omitted $module"}
        }
        if(!$package.PSObject.Properties['runtimeBinding']){throw 'Package lacks selected-JMOD runtime identity.'}
        $runtimeBinding=$package.runtimeBinding
        $originalReleaseEntry=$zip.GetEntry($originalReleasePath)
        Assert-ReleaseZipFile $zip $originalReleasePath $report.selectedJdkReleaseSha256 $originalReleaseEntry.Length
        if($runtimeBinding.schemaVersion -ne 1 -or $runtimeBinding.status -ne 'BUNDLED_RUNTIME_MATCHES_SELECTED_JMOD' -or $runtimeBinding.selectedJdkReleaseSha256 -ne $report.selectedJdkReleaseSha256 -or $runtimeBinding.jmod.path -ne 'jmods/java.base.jmod' -or $runtimeBinding.jmod.sha256 -notmatch '^[a-f0-9]{64}$'){throw 'Runtime JMOD/source binding is invalid.'}
        Assert-ReleaseNumber $runtimeBinding.jmod.bytes 4 128MB 'selected JMOD size'
        if($runtimeBinding.binaries.Count -ne 2){throw 'Runtime binding must cover both the launcher and JVM.'}
        $nativeRuntimePaths=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $nativeRuntimeMapping=@{'runtime/bin/java.exe'='bin/java.exe';'runtime/bin/server/jvm.dll'='lib/server/jvm.dll'}
        foreach($binary in $runtimeBinding.binaries) {
            if(!$nativeRuntimeMapping.ContainsKey($binary.path) -or !$nativeRuntimePaths.Add($binary.path) -or $binary.entry -cne $nativeRuntimeMapping[$binary.path]){throw 'Unexpected or duplicate runtime JMOD entry binding.'}
            Assert-ReleaseNumber $binary.bytes 1 64MB 'runtime native size'
            Assert-ReleaseZipFile $zip ('WreckRiff/'+$binary.path) $binary.sha256 $binary.bytes
        }
        $noticeNames=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach($notice in $binding.assetNotices) {
            Assert-ReleaseMaterialPath $notice.path
            if(!$notice.path.StartsWith('licenses/assets/') -or !$noticeNames.Add($notice.path)){throw 'Invalid external asset notice identity.'}
            Assert-ReleaseZipFile $zip ('WreckRiff/'+$notice.path) $notice.sha256 $notice.bytes
        }
        foreach($name in @('CC-BY-4.0.txt','CC0-1.0.txt','Roboto-OFL.txt')){if(!$noticeNames.Contains('licenses/assets/'+$name)){throw "Missing external asset license $name"}}
        return [pscustomobject]@{reportSha256=$binding.sha256;inputIndexSha256=$report.inputIndexSha256;status=$report.status}
    }finally{$zip.Dispose()}
}
function Assert-ReleaseTestXml([string]$Path,[DateTime]$StartedUtc) {
    if((Get-Item -LiteralPath $Path).LastWriteTimeUtc -lt $StartedUtc.ToUniversalTime()){throw "Stale test XML: $Path"}
    [xml]$xml=Get-Content -LiteralPath $Path -Raw
    $suite=$xml.testsuite
    if([int]$suite.tests -le 0 -or [int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0){throw "Unsuccessful or empty test suite: $Path"}
    return [int]$suite.tests
}
function Assert-ReleaseNumber($Value,[double]$Minimum,[double]$Maximum,[string]$Name) {
    if($null -eq $Value -or $Value -is [string] -or $Value -is [bool] -or [double]::IsNaN([double]$Value) -or [double]::IsInfinity([double]$Value) -or [double]$Value -lt $Minimum -or [double]$Value -gt $Maximum){throw "Invalid $Name"}
}
function Assert-ReleaseBenchmark($Report,$Diagnostic,$Memory,[string]$Arena) {
    if($Report.status -ne 'PASS' -or $Diagnostic.status -ne 'BENCHMARK_MEASURED' -or $Diagnostic.mode -ne 'benchmark' -or $Diagnostic.arenaId -ne $Arena){throw "Benchmark did not pass for $Arena"}
    if($Diagnostic.releaseEligible -ne $true -or $Diagnostic.width -ne 1920 -or $Diagnostic.height -ne 1080 -or $Diagnostic.msaaSamples -ne 4 -or $Diagnostic.vsync -ne $false -or $Diagnostic.audioEnabled -ne $true -or $Diagnostic.windowVisible -ne $true -or $Diagnostic.autoIconify -isnot [bool] -or $Diagnostic.autoIconify -ne $true -or $Diagnostic.detailedProfiling -ne $false -or $Diagnostic.invalidBenchmarkWindowObserved -ne $false){throw 'Invalid final benchmark environment.'}
    Assert-ReleaseNumber $Diagnostic.requestedSeconds 600 3600 'requested benchmark seconds'
    Assert-ReleaseNumber $Diagnostic.warmupActiveSeconds 30 3600 'active warmup'
    Assert-ReleaseNumber $Diagnostic.measuredActiveSeconds 600 7200 'measured active seconds'
    $frames=$Diagnostic.activeCombatFrames
    Assert-ReleaseNumber $frames.sampleSeconds 600 7200 'sample seconds'
    if([Math]::Abs($frames.sampleSeconds-$Diagnostic.measuredActiveSeconds) -gt .001){throw 'Active duration mismatch.'}
    Assert-ReleaseNumber $frames.frames 1 ([double]::MaxValue) 'frame count'
    Assert-ReleaseNumber $frames.p95FrameMs 0 16.7 'p95'
    Assert-ReleaseNumber $frames.p99FrameMs 0 25 'p99'
    Assert-ReleaseNumber $frames.maxFrameMs 0 100 'maximum frame'
    Assert-ReleaseNumber $frames.framesOver100ms 0 0 'frames above 100 ms'
    Assert-ReleaseNumber $Diagnostic.phaseMetrics.droppedSimulationSeconds 0 0 'dropped simulation time'
    Assert-ReleaseNumber $Diagnostic.undrawableSeconds 0 0 'undrawable window seconds'
    Assert-ReleaseMemory $Report $Diagnostic $Memory 600
    $coverage=$Diagnostic.measuredCoverage
    Assert-ReleaseNumber $coverage.arenaCombatSeconds .001 7200 'ordinary combat coverage'
    Assert-ReleaseNumber $coverage.maximumEffects 1 100000 'effects coverage'
    if($Arena -ne 'dead-air-yard') {
        Assert-ReleaseNumber $coverage.bossCombatSeconds .001 7200 'boss coverage'
        Assert-ReleaseNumber $coverage.maximumLaunchingVehicles 1 100 'launch coverage'
    }
}
function Assert-ReleaseMemory($Report,$Diagnostic,$Memory,[double]$MinimumSeconds) {
    if($Report.gamePid -ne $Diagnostic.pid -or $Memory.pid -ne $Diagnostic.pid -or $Memory.processExited -ne $true -or $Memory.samples.Count -lt $MinimumSeconds -or $Report.processStartTimeUtc -ne $Memory.processStartTimeUtc){throw 'Missing or unrelated external process memory samples.'}
    $previous=-1.0;$peak=0.0
    foreach($sample in $Memory.samples) {
        Assert-ReleaseNumber $sample.seconds 0 7200 'memory sample time'
        if($sample.seconds -le $previous -or ($previous -ge 0 -and $sample.seconds-$previous -gt 5)){throw 'Memory sampling has a gap or unordered samples.'}
        $previous=[double]$sample.seconds
        Assert-ReleaseNumber $sample.workingSetBytes 1 1.5GB 'working set'
        $peak=[Math]::Max($peak,[double]$sample.workingSetBytes)
    }
    if($Memory.samples[0].seconds -gt 5 -or $previous -lt $MinimumSeconds -or $Memory.peakWorkingSetBytes -ne $peak){throw 'External memory coverage or peak mismatch.'}
}
function Assert-ReleaseDiagnostic($Diagnostic,$Identity,[string]$Image,[string]$Mode) {
    if($Diagnostic.schemaVersion -lt 2 -or $Diagnostic.sourceSha256 -ne $Identity.sourceSha256 -or $Diagnostic.version -ne $Identity.version -or $Diagnostic.mode -ne $Mode -or $Diagnostic.pid -le 0){throw 'Diagnostic build/process/mode identity mismatch.'}
    $runtime=[IO.Path]::GetFullPath((Join-Path $Image 'runtime'))
    if(![IO.Path]::GetFullPath($Diagnostic.javaHome).Equals($runtime,[StringComparison]::OrdinalIgnoreCase)){throw 'Game did not use this ZIP bundled JVM.'}
    if($Diagnostic.windowVisible -ne $true -or $Diagnostic.audioEnabled -ne $true -or $Diagnostic.errors.Count -ne 0){throw 'Real visible window/audio or error-free completion was not demonstrated.'}
    if($Mode -eq 'normal') {
        Assert-ReleaseNormal $Diagnostic
    } else {
        if($Diagnostic.autoIconify -isnot [bool] -or $Diagnostic.autoIconify -ne $true){throw 'Automated verification must allow normal window iconification.'}
        Assert-ReleaseNumber $Diagnostic.undrawableSeconds 0 0 'undrawable seconds'
    }
}
function Get-ReleaseUiReviewRequest([string]$Resolution,[double]$UiScale,[bool]$Windowed) {
    $canonical=switch($Resolution){'720p'{'1280x720'} '1080p'{'1920x1080'} default{$Resolution}}
    if($canonical -notin @('640x480','1280x720','1920x1080','2560x1440','3440x1440','3840x1080','3840x2160')){throw 'Unsupported UI review resolution.'}
    Assert-ReleaseNumber $UiScale .8 1.5 'UI review scale'
    $size=$canonical.Split('x');$fullscreen=(!$Windowed -and [int]$size[1] -ge 1080)
    return [pscustomobject][ordered]@{width=[int]$size[0];height=[int]$size[1];uiScale=$UiScale;fullscreen=$fullscreen;windowMode=$(if($fullscreen){'fullscreen'}else{'windowed'})}
}
function Get-ReleaseUiReviewCaseIds {
    # Fixed current UiReview.catalogue, not a count-only acceptance of arbitrary images.
    $arenas=@('construction_17','neon_zero','euphoria_park','ash_necropolis','doomsday_arena')
    @('fresh-main','fresh-maps')
    foreach($arena in $arenas){"fresh-maps-$arena"}
    @('fresh-statistics','fresh-statistics-bottom','vehicles-rivet','vehicles-grinder','vehicles-spark',
      'settings-video','settings-tab-1','settings-tab-2','settings-tab-3','controls-keyboard','controls-keyboard-bottom',
      'controls-gamepad','controls-gamepad-bottom','video-keep-confirm','video-kept','video-revert-confirm','video-reverted',
      'video-timeout-rollback','confirm-first','confirm-danger-focused','confirm-reopened','credits','credits-bottom',
      'unlocked-main','unlocked-new-confirm','unlocked-maps')
    foreach($arena in $arenas){"unlocked-maps-$arena"}
    @('unlocked-statistics','unlocked-statistics-bottom','loading','error','error-return','results-defeat','results-draw','results-victory')
    foreach($profile in @('rivet','grinder','spark')){foreach($variant in 0..5){"hud-$profile-$variant"}}
    @('pause','pause-settings','pause-return','hardware-controller')
}
function Assert-ReleaseUiPng([string]$Path,[int]$Width,[int]$Height) {
    $file=Get-Item -LiteralPath $Path
    if($file.Length -lt 33 -or $file.Length -gt 128MB -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Invalid UI capture size or linked file.'}
    $stream=[IO.File]::OpenRead($file.FullName)
    try {$header=New-Object byte[] 24;if($stream.Read($header,0,24) -ne 24){throw 'Truncated PNG header.'}}finally{$stream.Dispose()}
    if([BitConverter]::ToString($header,0,8) -ne '89-50-4E-47-0D-0A-1A-0A' -or [Text.Encoding]::ASCII.GetString($header,12,4) -ne 'IHDR'){throw 'UI capture is not a PNG with IHDR.'}
    $widthBytes=$header[16..19];$heightBytes=$header[20..23];[Array]::Reverse($widthBytes);[Array]::Reverse($heightBytes)
    if([BitConverter]::ToUInt32($widthBytes,0) -ne $Width -or [BitConverter]::ToUInt32($heightBytes,0) -ne $Height){throw 'UI PNG dimensions differ from the requested framebuffer.'}
}
function Get-ReleaseUiReviewEvidence([string]$ManifestPath,$Diagnostic,$Request) {
    if((Get-Item -LiteralPath $ManifestPath).Length -gt 2MB){throw 'UI manifest exceeds its bounded size.'}
    $manifest=Read-ReleaseJson $ManifestPath;$directory=[IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($ManifestPath))
    if($manifest.cases.Count -ne 68){throw 'UI manifest has an unexpected case count.'}
    $captures=@(foreach($case in $manifest.cases) {
        if($case.status -eq 'CAPTURED') {
            if($case.captureFile -cnotmatch '^captures/[A-Za-z0-9_.-]+\.png$'){throw 'Unsafe UI capture path.'}
            $path=Join-Path $directory $case.captureFile
            Assert-ReleaseUiPng $path $Request.width $Request.height
            $artifact=Get-ReleaseArtifact $path
            [pscustomobject][ordered]@{caseId=$case.caseId;path=$artifact.path;sha256=$artifact.sha256}
        }
    })
    $evidence=[pscustomobject][ordered]@{schemaVersion=1;status='CAPTURE_COLLECTION_COMPLETE';scenarioStatus='PASS';
        releaseEligible=$false;ownerAcceptance='NOT_GRANTED';hardwareController='PENDING';visualReview='PENDING';
        requestedFramebuffer=$Request;manifest=(Get-ReleaseArtifact $ManifestPath);captures=$captures}
    Assert-ReleaseUiReview $evidence $Diagnostic $Request
    return $evidence
}
function Assert-ReleaseUiReview($Evidence,$Diagnostic,$Request) {
    if($Evidence.schemaVersion -ne 1 -or $Evidence.status -ne 'CAPTURE_COLLECTION_COMPLETE' -or $Evidence.scenarioStatus -ne 'PASS' -or
       $Evidence.releaseEligible -isnot [bool] -or $Evidence.releaseEligible -ne $false -or $Evidence.ownerAcceptance -ne 'NOT_GRANTED' -or
       $Evidence.hardwareController -ne 'PENDING' -or $Evidence.visualReview -ne 'PENDING'){throw 'UI collection cannot grant release, visual or hardware acceptance.'}
    if($Diagnostic.mode -ne 'ui-review' -or $Diagnostic.status -ne 'CAPTURES_COMPLETE_HUMAN_REVIEW_PENDING' -or
       $Diagnostic.uiReviewCaptureStatus -ne 'CAPTURES_COMPLETE_HUMAN_REVIEW_PENDING' -or $Diagnostic.uiReviewManifest -ne 'ui-review-manifest.json' -or
       $Diagnostic.releaseEligible -isnot [bool] -or $Diagnostic.releaseEligible -ne $false -or $Diagnostic.hardwareController -ne 'PENDING_MANUAL' -or $Diagnostic.feelApproval -ne 'PENDING_MANUAL'){throw 'UI diagnostic collection was not completed without acceptance.'}
    if($Diagnostic.width -ne $Request.width -or $Diagnostic.height -ne $Request.height){throw 'UI diagnostic framebuffer differs from the request.'}
    foreach($field in @('width','height','uiScale','fullscreen','windowMode')) {
        if($Evidence.requestedFramebuffer.$field -ne $Request.$field){throw "UI evidence request mismatch: $field"}
    }
    Assert-ReleaseArtifact $Evidence.manifest
    $file=Get-Item -LiteralPath $Evidence.manifest.path
    if($file.Name -ne 'ui-review-manifest.json' -or $file.Length -gt 2MB -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Invalid UI manifest file.'}
    $manifest=Read-ReleaseJson $file.FullName
    if($manifest.schemaVersion -ne 1 -or $manifest.mode -ne 'ui-review' -or $manifest.status -ne 'CAPTURES_COMPLETE_HUMAN_REVIEW_PENDING' -or
       $manifest.ownerAcceptance -ne 'NOT_GRANTED' -or $manifest.releaseEligible -isnot [bool] -or $manifest.releaseEligible -ne $false -or $manifest.plannedCases -ne 68 -or
       $manifest.cases.Count -ne 68 -or $manifest.remainingCaseIds.Count -ne 0 -or $Evidence.captures.Count -ne 67){throw 'Incomplete UI manifest or invalid acceptance state.'}
    if($manifest.requestedFramebuffer.width -ne $Request.width -or $manifest.requestedFramebuffer.height -ne $Request.height){throw 'UI manifest framebuffer differs from the request.'}
    Assert-ReleaseNumber $manifest.requestedFramebuffer.uiScale ($Request.uiScale-.000001) ($Request.uiScale+.000001) 'manifest UI scale'
    $required=@(Get-ReleaseUiReviewCaseIds);$caseIds=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $captureIds=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $capturePaths=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach($capture in $Evidence.captures) {
        if(!$captureIds.Add($capture.caseId) -or !$capturePaths.Add([IO.Path]::GetFullPath($capture.path))){throw 'Duplicate UI capture binding.'}
    }
    $directory=$file.DirectoryName;$captureDirectory=Join-Path $directory 'captures'
    if((Get-Item -LiteralPath $captureDirectory).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Linked UI captures directory.'}
    foreach($case in $manifest.cases) {
        if($required -cnotcontains $case.caseId -or !$caseIds.Add($case.caseId)){throw 'Missing, unexpected or duplicate UI case.'}
        $observed=$case.observed
        if($observed.visible -isnot [bool] -or $observed.visible -ne $true -or $observed.width -ne $Request.width -or $observed.height -ne $Request.height -or
           $observed.state.fullscreen -isnot [bool] -or $observed.state.fullscreen -ne $Request.fullscreen){throw "Incorrect UI framebuffer, visibility or window mode: $($case.caseId)"}
        Assert-ReleaseNumber $observed.scale ($Request.uiScale-.000001) ($Request.uiScale+.000001) 'observed UI scale'
        Assert-ReleaseNumber $observed.state.storedWins 0 0 'UI stored wins'
        Assert-ReleaseNumber $observed.state.storedMatches 0 0 'UI stored matches'
        if($case.caseId -eq 'hardware-controller') {
            if($case.status -ne 'PENDING' -or [string]::IsNullOrWhiteSpace($case.reason) -or $case.captureFile -ne '' -or $case.settledDrawFrames -ne 0){throw 'UI fixture cannot claim a physical controller pass.'}
            continue
        }
        if($case.status -ne 'CAPTURED' -or $case.captureFile -cnotmatch '^captures/[A-Za-z0-9_.-]+\.png$'){throw 'UI case lacks a settled PNG capture.'}
        Assert-ReleaseNumber $case.settledDrawFrames 2 ([double]::MaxValue) 'settled UI frames'
        $path=[IO.Path]::GetFullPath((Join-Path $directory $case.captureFile))
        $matching=@($Evidence.captures | Where-Object {$_.caseId -ceq $case.caseId})
        if($matching.Count -ne 1 -or ![IO.Path]::GetFullPath($matching[0].path).Equals($path,[StringComparison]::OrdinalIgnoreCase)){throw 'UI capture path/case binding mismatch.'}
        Assert-ReleaseArtifact $matching[0]
        Assert-ReleaseUiPng $path $Request.width $Request.height
    }
    if(@(Get-ChildItem -LiteralPath $captureDirectory -File -Filter '*.png').Count -ne 67){throw 'UI capture directory does not contain exactly 67 PNG files.'}
}

function Assert-ReleaseNormal($Diagnostic) {
    if($Diagnostic.status -ne 'CLOSED' -or $Diagnostic.dev -ne $false -or $Diagnostic.shutdownComplete -ne $true -or $Diagnostic.progressFlushed -ne $true){throw 'Normal executable did not shut down and flush progress successfully.'}
    Assert-ReleaseNumber $Diagnostic.renderedFrames 1 ([double]::MaxValue) 'normal rendered frames'
    if([DateTime]$Diagnostic.closedAtUtc -lt [DateTime]$Diagnostic.startedAtUtc){throw 'Normal launch chronology is invalid.'}
}
function Assert-ReleaseObserved($Observations,[string[]]$Fields) {
    foreach($field in $Fields){if($Observations.$field -isnot [bool] -or !$Observations.$field){throw "Unconfirmed observation: $field"}}
}
function Assert-ReleaseObservedList($Actual,[string[]]$Required,[string]$Name) {
    if($Actual -isnot [array]){throw "Missing observation list: $Name"}
    foreach($item in $Required){if($Actual -notcontains $item){throw "Missing $Name observation: $item"}}
}
function Assert-ReleaseReview([string]$Name,$Review,$Identity) {
    if($Review.status -ne 'ACCEPTED' -or $Review.reviewerType -notin @('agent','human') -or [string]::IsNullOrWhiteSpace($Review.reviewedBy) -or [string]::IsNullOrWhiteSpace($Review.summary)){throw 'No explicit documented factual review.'}
    $at=([DateTimeOffset]$Review.reviewedAtUtc).UtcDateTime
    if($at -lt ([DateTimeOffset]$Identity.packagedAtUtc).UtcDateTime -or $at -gt [DateTime]::UtcNow.AddMinutes(5)){throw 'Review date does not match this candidate.'}
    if($Review.artifacts.Count -eq 0){throw 'No supporting evidence artifacts.'}
    foreach($artifact in $Review.artifacts){Assert-ReleaseArtifact $artifact}
    $facts=$Review.observations
    switch($Name) {
        'normalNewProfile' {
            if($facts.mode -ne 'NormalNew'){throw 'Review names another normal scenario.'}
            Assert-ReleaseObserved $facts @('newProfile','campaignStarted','saveAndExit')
        }
        'normalMigratedProfile' {
            if($facts.mode -ne 'NormalMigrated'){throw 'Review names another normal scenario.'}
            Assert-ReleaseObserved $facts @('oldSchemaObserved','settingsRetained','statisticsRetained','saveAndExit')
        }
        'normalCampaignContinue' {
            if($facts.mode -ne 'NormalContinue'){throw 'Review names another normal scenario.'}
            Assert-ReleaseObserved $facts @('checkpointResumed','playerResourcesVerified','campaignProgressVerified','saveAndExit')
        }
        'ownerFeel' {
            if($Review.reviewerType -ne 'human'){throw 'Owner feel requires the actual human owner review.'}
            Assert-ReleaseObserved $facts @('handling','ui','visuals','sound')
        }
        'physicalController' {
            if([string]::IsNullOrWhiteSpace($facts.deviceModel) -or [string]::IsNullOrWhiteSpace($facts.connection)){throw 'Controller hardware/connection is not identified.'}
            Assert-ReleaseObserved $facts @('physicalDeviceObserved')
            Assert-ReleaseObservedList $facts.checks @('menus','focus','remapping','combat','disconnect','reconnect') 'controller'
        }
        'otherWindows' {
            if([string]::IsNullOrWhiteSpace($facts.machineDescription) -or [string]::IsNullOrWhiteSpace($facts.osVersion)){throw 'Other Windows machine/OS is not identified.'}
            Assert-ReleaseObserved $facts @('separateInstallation','cleanInstallation','bundledJavaVerified','audioVerified','normalExit')
        }
        'graphicalUxMatrix' {
            Assert-ReleaseObservedList $facts.resolutions @('640x480','1280x720','1920x1080','2560x1440','3840x2160') 'resolution'
            $wide=@($facts.resolutions | Where-Object {$_ -match '^(\d+)x(\d+)$' -and [double]$Matches[1]/[Math]::Max(1,[double]$Matches[2]) -gt 2})
            if($wide.Count -eq 0){throw 'Actual ultrawide dimensions are absent.'}
            Assert-ReleaseObservedList $facts.windowModes @('windowed','fullscreen') 'window mode'
            Assert-ReleaseObservedList $facts.checks @('resize','ui-scale','long-strings','notifications','empty-ammo','all-abilities','focus','mouse','keyboard') 'graphical UX'
            Assert-ReleaseNumber $facts.openBlockingDefects 0 0 'open blocking UX defects'
        }
        'arenaRoutes' {
            foreach($arena in @('dead-air-yard','construction_17','neon_zero','euphoria_park','ash_necropolis','doomsday_arena')) {
                $rows=@($facts.arenas | Where-Object {$_.id -eq $arena})
                if($rows.Count -ne 1){throw "Missing/duplicate route observations for $arena"}
                $required=@('lower','upper-combat','pickup','descent')
                if($arena -ne 'dead-air-yard'){$required+=@('launch','boss-lower','boss-upper')}
                Assert-ReleaseObservedList $rows[0].checks $required "route $arena"
            }
        }
        'distributionLicenses' {
            Assert-ReleaseObserved $facts @('materialsVerified','sourceReplacementInstructionsReviewed','assetNoticesPresent')
            if($facts.distributionApproval -ne 'NOT_GRANTED'){throw 'Material review must not manufacture a distribution approval.'}
        }
        'releaseDocumentation' {
            Assert-ReleaseObservedList $facts.documents @('installation','controls','release-notes','known-limitations','hardware','resource-register','licenses','acceptance-report') 'release documentation'
        }
        default {throw 'Unknown release review category.'}
    }
}
function Assert-ReleaseSoak($Report,$Diagnostic,$Memory) {
    if($Report.status -ne 'PASS' -or $Diagnostic.status -ne 'SOAK_MEASURED' -or $Diagnostic.mode -ne 'soak'){throw 'Soak was not completed.'}
    Assert-ReleaseNumber $Diagnostic.requestedSeconds 1800 3600 'requested soak duration'
    Assert-ReleaseNumber $Diagnostic.soakCoverage.measuredSeconds 1800 7200 'measured soak duration'
    Assert-ReleaseNumber $Diagnostic.soakCoverage.mapChanges 10 1000 'map changes'
    Assert-ReleaseNumber $Diagnostic.soakCoverage.retries 20 1000 'retry count'
    foreach($arena in @('dead-air-yard','construction_17','neon_zero','euphoria_park','ash_necropolis','doomsday_arena')) {
        if($Diagnostic.soakCoverage.arenaIds -notcontains $arena){throw "Soak omitted $arena"}
    }
    if($Diagnostic.soakCoverage.resourcesWarmed -ne $true -or $Diagnostic.soakCoverage.coverageComplete -ne $true -or $Diagnostic.resourceChecks.status -ne 'PASS' -or $Diagnostic.resourceChecks.errors.Count -ne 0){throw 'Soak resource checks did not pass.'}
    Assert-ReleaseMemory $Report $Diagnostic $Memory 1800
    $unloads=@($Diagnostic.resourceSnapshots | Where-Object {$_.stage -eq 'UNLOAD'})
    if($unloads.Count -lt 10){throw 'Soak lacks repeated unload resource observations.'}
    $requiredModes=@('dead-air-yard/LEGACY')+@(@('construction_17','neon_zero','euphoria_park','ash_necropolis','doomsday_arena') | ForEach-Object {"$_/ARENA";"$_/BOSS_DUEL"})
    $modes=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $baselines=@{};$baseline=$null;$handleBaseline=$null;$comparisons=0;$previousSeconds=-1.0
    $timedSamples=@($Memory.samples | ForEach-Object {[pscustomobject]@{sample=$_;utc=([DateTimeOffset]$_.observedAtUtc).UtcDateTime}})
    foreach($snapshot in $Diagnostic.resourceSnapshots) {
        Assert-ReleaseNumber $snapshot.elapsedSeconds 0 7200 'resource observation time'
        if($snapshot.elapsedSeconds -lt $previousSeconds){throw 'Resource observations are not chronological.'}
        $previousSeconds=[double]$snapshot.elapsedSeconds
        foreach($field in @('bodies','listeners','tickListeners','projectiles','trackers','directBufferBytes','textures','voices','saveQueueDepth','revision','persistedRevision')) {
            Assert-ReleaseNumber $snapshot.$field 0 ([double]::MaxValue) "resource $field"
        }
        if($snapshot.saveQueueDepth -gt 1 -or $snapshot.voices -gt 32){throw 'Resource queue or audio bound exceeded.'}
        $mode=$snapshot.arenaId+'/'+$snapshot.mode
        if($requiredModes -notcontains $mode){throw 'Resource observation names an unexpected arena/mode.'}
        if($snapshot.stage -eq 'LOAD') {
            $null=$modes.Add($mode)
            $key=@($snapshot.arenaId,$snapshot.mode,$snapshot.phase,$snapshot.profileId,$snapshot.topology) | ConvertTo-Json -Compress
            if($baselines.ContainsKey($key)) {
                $load=$baselines[$key]
                if($snapshot.bodies -ne $load.bodies -or $snapshot.listeners -ne $load.listeners -or $snapshot.tickListeners -ne $load.tickListeners){throw 'Native load baseline changed.'}
            } else {$baselines[$key]=$snapshot}
            if($snapshot.projectiles -ne 0){throw 'Projectiles survived match load.'}
            continue
        }
        if($snapshot.stage -ne 'UNLOAD'){throw 'Unknown resource observation stage.'}
        if($snapshot.bodies -ne 0 -or $snapshot.listeners -ne 0 -or $snapshot.tickListeners -ne 0 -or $snapshot.projectiles -ne 0 -or $snapshot.voices -ne 0){throw 'Match-owned resources survived unload.'}
        if($snapshot.saveQueueDepth -ne 0 -or $snapshot.saveWorkerScheduled -ne $false -or $snapshot.revision -ne $snapshot.persistedRevision){throw 'Progress did not settle after unload.'}
        if($snapshot.collectionRequested -ne $true){throw 'Unload retention observation omitted collection and menu settling.'}
        $at=[DateTimeOffset]::FromUnixTimeMilliseconds([long]$snapshot.observedAtEpochMillis).UtcDateTime
        $nearest=$timedSamples | Sort-Object { [Math]::Abs(($_.utc-$at).TotalSeconds) } | Select-Object -First 1
        if($null -eq $nearest -or [Math]::Abs(($nearest.utc-$at).TotalSeconds) -gt 2){throw 'Unload has no contemporaneous Windows handle sample.'}
        Assert-ReleaseNumber $nearest.sample.handles 1 1000000 'process handles'
        # Match ResourceRetention: every arena and its duel are initialized before a
        # fixed retained-resource baseline is established. Time alone is insufficient.
        if($modes.Count -eq $requiredModes.Count) {
            if($null -eq $baseline){$baseline=$snapshot;$handleBaseline=[double]$nearest.sample.handles}
            else {
                $comparisons++
                if($snapshot.trackers -gt $baseline.trackers+64 -or $snapshot.directBufferBytes -gt $baseline.directBufferBytes+4MB -or $snapshot.textures -gt $baseline.textures+8){throw 'Native/GPU resources exceeded the fixed warmed unload baseline.'}
                if($nearest.sample.handles -gt $handleBaseline+16){throw 'Windows handles increased by more than 16 after warmup unload.'}
            }
        }
    }
    if($null -eq $baseline -or $comparisons -lt 3 -or $Diagnostic.resourceChecks.minimumUnloadComparisons -ne 3 -or $Diagnostic.resourceChecks.unloadComparisons -ne $comparisons){throw 'Insufficient or inconsistent fixed warmed unload comparisons.'}
    foreach($mode in $requiredModes){if($Diagnostic.soakCoverage.loadedModes -notcontains $mode){throw 'Soak omitted an arena or duel resource mode.'}}
}

# All final executable tests use a newly extracted ZIP and an isolated LOCALAPPDATA.
# Only the process started here (or its verified bundled-JVM child) is monitored/stopped.
function Invoke-ReleaseProcess([string]$Image,[string]$RunRoot,[string[]]$Arguments,[string]$Mode,[int]$TimeoutSeconds,$Identity) {
    $stdout=Join-Path $RunRoot 'stdout.log';$stderr=Join-Path $RunRoot 'stderr.log'
    $started=[DateTime]::UtcNow;$game=$null;$launcher=$null;$reportPath=$null;$exitCode=$null
    $samples=[Collections.Generic.List[object]]::new();$sampleStarted=$null;$processStarted=$null;$completed=$false
    $oldJava=$env:JAVA_HOME;$oldPath=$env:PATH;$oldLocal=$env:LOCALAPPDATA
    $env:JAVA_HOME=Join-Path $RunRoot 'Java is not installed'
    $env:PATH="$env:SystemRoot\System32;$env:SystemRoot";$env:LOCALAPPDATA=Join-Path $RunRoot 'user-data'
    try {
        $parameters=@{FilePath=(Join-Path $Image 'WreckRiff.exe');WorkingDirectory=$Image;WindowStyle='Hidden';PassThru=$true;RedirectStandardOutput=$stdout;RedirectStandardError=$stderr}
        if($Arguments.Count -gt 0){$parameters.ArgumentList=$Arguments}
        $launcher=Start-Process @parameters;$null=$launcher.Handle
        while(([DateTime]::UtcNow-$started).TotalSeconds -lt 45) {
            $line=Get-Content -LiteralPath $stdout -Encoding UTF8 | Where-Object {$_.StartsWith('DIAGNOSTIC_REPORT: ')} | Select-Object -First 1
            if($line) {
                $candidate=[IO.Path]::GetFullPath($line.Substring('DIAGNOSTIC_REPORT: '.Length).Trim())
                $allowed=[IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'WreckRiff'))+[IO.Path]::DirectorySeparatorChar
                if(!$candidate.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)){throw 'Reported evidence escaped this launch user-data directory.'}
                if((Get-Item -LiteralPath $candidate).LastWriteTimeUtc -lt $started){throw 'Reported evidence predates this launch.'}
                try {$initial=Read-ReleaseJson $candidate} catch {Start-Sleep -Milliseconds 100;continue}
                if($initial.sourceSha256 -ne $Identity.sourceSha256 -or $initial.mode -ne $Mode){throw 'Startup diagnostic identity mismatch.'}
                $game=Get-Process -Id $initial.pid -ErrorAction Stop;$null=$game.Handle
                $processStarted=$game.StartTime.ToUniversalTime()
                if($processStarted -lt $started.AddSeconds(-1)){throw 'Reported process predates the launcher.'}
                $gamePath=[IO.Path]::GetFullPath($game.Path)
                if(!$gamePath.StartsWith([IO.Path]::GetFullPath($Image)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Reported process is outside this extracted ZIP.'}
                if($game.Id -ne $launcher.Id) {
                    $ancestor=$game.Id;$related=$false
                    for($depth=0;$depth -lt 4;$depth++) {
                        $owner=Get-CimInstance Win32_Process -Filter "ProcessId=$ancestor" -OperationTimeoutSec 5
                        if(!$owner){break};$ancestor=[int]$owner.ParentProcessId
                        if($ancestor -eq $launcher.Id){$related=$true;break}
                    }
                    if(!$related){throw 'Reported JVM is unrelated to this launcher.'}
                }
                $reportPath=$candidate;break
            }
            if($launcher.HasExited -and $launcher.ExitCode -ne 0){throw 'Launcher failed before producing startup evidence.'}
            Start-Sleep -Milliseconds 250
        }
        if(!$reportPath){throw 'No startup diagnostic report within 45 seconds.'}
        $sampleStarted=[DateTime]::UtcNow
        while(!$game.HasExited) {
            if(([DateTime]::UtcNow-$started).TotalSeconds -gt $TimeoutSeconds){throw 'Executable verification timed out.'}
            $game.Refresh();$now=[DateTime]::UtcNow
            if($game.HasExited){break}
            $samples.Add([pscustomobject][ordered]@{seconds=[Math]::Round(($now-$sampleStarted).TotalSeconds,3);observedAtUtc=$now.ToString('o');workingSetBytes=$game.WorkingSet64;privateBytes=$game.PrivateMemorySize64;handles=$game.HandleCount;threads=$game.Threads.Count})
            Start-Sleep -Seconds 1
        }
        if(!$launcher.WaitForExit(15000)){throw 'Launcher did not finish after the JVM stopped.'}
        $exitCode=$game.ExitCode
        if($exitCode -ne 0 -or $launcher.ExitCode -ne 0){throw 'Packaged executable exited unsuccessfully.'}
        Assert-ReleaseProcessLogs @($stdout,$stderr)
        $diagnostic=Read-ReleaseJson $reportPath
        Assert-ReleaseDiagnostic $diagnostic $Identity $Image $Mode
        Copy-Item -LiteralPath $reportPath -Destination (Join-Path $RunRoot 'diagnostic-result.json')
        $completed=$true
    } finally {
        if(!$completed) {
            if($null -ne $game -and !$game.HasExited -and $null -ne $reportPath){$game.Kill();$null=$game.WaitForExit(10000)}
            if($null -ne $launcher -and !$launcher.HasExited){$launcher.Kill();$null=$launcher.WaitForExit(10000)}
        }
        $peak=if($samples.Count -gt 0){($samples | Measure-Object workingSetBytes -Maximum).Maximum}else{0}
        $memory=[ordered]@{schemaVersion=2;pid=$(if($game){$game.Id}else{0});processStartTimeUtc=$(if($processStarted){$processStarted.ToString('o')}else{$null});
            processExited=($null -ne $game -and $game.HasExited);peakWorkingSetBytes=$peak;measurement='Windows process working set and handles';samples=$samples.ToArray()}
        Write-ReleaseJson (Join-Path $RunRoot 'memory.json') $memory
        $env:JAVA_HOME=$oldJava;$env:PATH=$oldPath;$env:LOCALAPPDATA=$oldLocal
    }
    return [pscustomobject]@{diagnostic=$diagnostic;reportPath=$reportPath;memory=[pscustomobject]$memory;gamePid=$game.Id;launcherPid=$launcher.Id;processStartTimeUtc=$processStarted.ToString('o');exitCode=$exitCode;startedAtUtc=$started.ToString('o');completedAtUtc=[DateTime]::UtcNow.ToString('o')}
}
