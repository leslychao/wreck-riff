# Shared, read-only identity and acceptance checks. Compatible with Windows PowerShell 5.1.
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem

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
function Read-ReleaseJson([string]$Path) { return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json }
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
    if(!$RuntimeOnly){$names+=@('gradlew','gradlew.bat','gradle.properties')}
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
        foreach($releasePath in @('WreckRiff/runtime/release','WreckRiff/licenses/'+$runtime.releaseFile)) {
            $release=ConvertFrom-StringData (Read-ReleaseZipText $zip $releasePath)
            foreach($field in $expected.Keys){if($release[$field].Trim('"') -ne $expected[$field]){throw "Bundled JDK/source metadata mismatch: $field"}}
            if($releasePath -eq 'WreckRiff/runtime/release') {
                foreach($module in @('java.base','java.desktop','java.logging','java.management','jdk.unsupported','jdk.crypto.ec','jdk.jfr')) {
                    if($runtime.jlinkModules -notcontains $module -or $index.runtime.jlinkModules -notcontains $module -or $release['MODULES'].Trim('"').Split(' ') -notcontains $module){throw "Bundled runtime omitted $module"}
                }
            }
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
    if($Diagnostic.releaseEligible -ne $true -or $Diagnostic.width -ne 1920 -or $Diagnostic.height -ne 1080 -or $Diagnostic.msaaSamples -ne 4 -or $Diagnostic.vsync -ne $false -or $Diagnostic.audioEnabled -ne $true -or $Diagnostic.windowVisible -ne $true -or $Diagnostic.autoIconify -ne $false -or $Diagnostic.detailedProfiling -ne $false -or $Diagnostic.invalidBenchmarkWindowObserved -ne $false){throw 'Invalid final benchmark environment.'}
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
        if($Diagnostic.autoIconify -ne $false){throw 'Automated verification must keep its window drawable.'}
        Assert-ReleaseNumber $Diagnostic.undrawableSeconds 0 0 'undrawable seconds'
    }
}
function Assert-ReleaseNormal($Diagnostic) {
    if($Diagnostic.status -ne 'CLOSED' -or $Diagnostic.dev -ne $false -or $Diagnostic.shutdownComplete -ne $true -or $Diagnostic.progressFlushed -ne $true){throw 'Normal executable did not shut down and flush progress successfully.'}
    Assert-ReleaseNumber $Diagnostic.renderedFrames 1 ([double]::MaxValue) 'normal rendered frames'
    if([DateTime]$Diagnostic.closedAtUtc -lt [DateTime]$Diagnostic.startedAtUtc){throw 'Normal launch chronology is invalid.'}
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
            $line=Get-Content -LiteralPath $stdout | Where-Object {$_.StartsWith('DIAGNOSTIC_REPORT: ')} | Select-Object -First 1
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
    return [pscustomobject]@{diagnostic=$diagnostic;memory=[pscustomobject]$memory;gamePid=$game.Id;launcherPid=$launcher.Id;processStartTimeUtc=$processStarted.ToString('o');exitCode=$exitCode;startedAtUtc=$started.ToString('o');completedAtUtc=[DateTime]::UtcNow.ToString('o')}
}
