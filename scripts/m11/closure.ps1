param(
    [string]$DbName = "rs_m11_closure",
    [int]$Port = 18080,
    [string]$ReportDate = "2026-11-23"
)

if ($DbName -ne "rs_m11_closure") {
    Write-Error "FATAL: closure.ps1 must run against rs_m11_closure, got '$DbName'"
    exit 1
}

$ErrorActionPreference = "Continue"
Add-Type -AssemblyName System.Net.Http
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$OutDir = Join-Path $PSScriptRoot "out"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$OutFile = Join-Path $OutDir "closure-$ReportDate.json"

$PgBin = "D:\DevTools\pgsql\bin"
$PgData = "D:\软件工程职业实践\.m04-audit-syntax-pg"
$PgLog = Join-Path $RepoRoot ".workbuddy\pg_closure.log"
$AppLog = Join-Path $RepoRoot ".workbuddy\app_closure.log"
$AppErrLog = Join-Path $RepoRoot ".workbuddy\app_closure.err.log"
$JavaExe = "D:\DevTools\zulu21.54.109-ca-jdk21.0.13.0.3-win_x64\bin\java.exe"
$JarPath = Join-Path $RepoRoot "backend\target\backend-0.1.0-SNAPSHOT.jar"
$BaseUrl = "http://127.0.0.1:$Port"

# 从仓库根 .env 补齐环境变量（只补未设置的），否则"有 key 却判成不可用"
$EnvFile = Join-Path $RepoRoot ".env"
$EnvLoaded = @()
if (Test-Path $EnvFile) {
    Get-Content $EnvFile | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
            $k = $Matches[1]; $v = $Matches[2].Trim('"').Trim("'")
            if (-not [Environment]::GetEnvironmentVariable($k)) {
                [Environment]::SetEnvironmentVariable($k, $v, "Process")
                $EnvLoaded += $k
            }
        }
    }
}

$AiEnabled = ($env:AI_ENABLED -eq 'true') -and [bool]$env:AI_API_KEY
$Steps = New-Object System.Collections.Generic.List[object]
$RunSw = [System.Diagnostics.Stopwatch]::StartNew()

function Add-StepResult {
    param(
        [string]$Step,
        [string]$Request,
        [string]$Expected,
        [string]$Actual,
        [string]$Sql = "",
        [string]$SqlResult = "",
        [string]$Status = "pass",
        [long]$Ms = 0
    )
    $passVal = ($Status -eq "pass")
    $Steps.Add([ordered]@{
        step      = $Step
        request   = $Request
        expected  = $Expected
        actual    = $Actual
        sql       = $Sql
        sqlResult = $SqlResult
        status    = $Status
        pass      = $passVal
        ms        = $Ms
    })
}

function Invoke-Psql {
    param([string]$Query)
    $env:PGPASSWORD = "postgres"
    $env:PGCLIENTENCODING = "UTF8"
    $res = & "$PgBin\psql.exe" -h 127.0.0.1 -p 5432 -U postgres -d $DbName -t -A -c $Query 2>&1
    return ($res -join "`n").Trim()
}

function New-ApiSession {
    return @{
        Cookies = New-Object Microsoft.PowerShell.Commands.WebRequestSession
        CsrfToken = $null
        CsrfHeader = "X-XSRF-TOKEN"
    }
}

function Refresh-Csrf {
    param($Sess)
    $r = Invoke-RestMethod -Uri "$BaseUrl/api/auth/csrf" -Method Get -WebSession $Sess.Cookies
    $Sess.CsrfToken = $r.token
    if ($r.headerName) { $Sess.CsrfHeader = $r.headerName }
}

function Invoke-Api {
    param(
        $Sess,
        [string]$Method,
        [string]$Path,
        $Body = $null,
        [string]$IdempotencyKey = ""
    )
    $headers = @{}
    if ($Method -in @("POST","PUT","DELETE","PATCH")) {
        if (-not $Sess.CsrfToken) { Refresh-Csrf $Sess }
        $headers[$Sess.CsrfHeader] = $Sess.CsrfToken
        if ($Method -eq "POST") {
            $headers["Idempotency-Key"] = if ($IdempotencyKey) { $IdempotencyKey } else { [guid]::NewGuid().ToString() }
        }
    }
    $params = @{
        Uri = "$BaseUrl$Path"
        Method = $Method
        WebSession = $Sess.Cookies
        Headers = $headers
        ContentType = "application/json; charset=utf-8"
    }
    if ($null -ne $Body) {
        $json = if ($Body -is [string]) { $Body } else { ($Body | ConvertTo-Json -Depth 15 -Compress) }
        $params["Body"] = [System.Text.Encoding]::UTF8.GetBytes($json)
    }
    try {
        $resp = Invoke-WebRequest @params -UseBasicParsing
        $parsed = $null
        if ($resp.Content) {
            try { $parsed = $resp.Content | ConvertFrom-Json } catch { $parsed = $resp.Content }
        }
        return @{ Status = [int]$resp.StatusCode; Body = $parsed; Raw = $resp.Content }
    } catch {
        $code = 0
        $raw = ""
        if ($_.Exception.Response) {
            $code = [int]$_.Exception.Response.StatusCode
            try {
                $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
                $raw = $sr.ReadToEnd()
            } catch {}
        }
        return @{ Status = $code; Body = $null; Raw = $raw; Error = $_.Exception.Message }
    }
}

function Invoke-MultipartUpload {
    param(
        $Sess,
        [string]$Path,
        [byte[]]$Bytes,
        [string]$FileName = "sample.png",
        [string]$Mime = "image/png"
    )
    if (-not $Sess.CsrfToken) { Refresh-Csrf $Sess }
    $handler = New-Object System.Net.Http.HttpClientHandler
    $handler.CookieContainer = $Sess.Cookies.Cookies
    $client = New-Object System.Net.Http.HttpClient($handler)
    try {
        $content = New-Object System.Net.Http.MultipartFormDataContent
        $fileContent = New-Object System.Net.Http.ByteArrayContent -ArgumentList (,$Bytes)
        $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse($Mime)
        $content.Add($fileContent, "file", $FileName)
        $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, "$BaseUrl$Path")
        $req.Headers.Add($Sess.CsrfHeader, $Sess.CsrfToken)
        $req.Content = $content
        $resp = $client.SendAsync($req).GetAwaiter().GetResult()
        $raw = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $parsed = $null
        try { $parsed = $raw | ConvertFrom-Json } catch {}
        return @{ Status = [int]$resp.StatusCode; Body = $parsed; Raw = $raw }
    } finally {
        $client.Dispose()
    }
}

function Get-AnswerForQuestion {
    param([long]$QVerId, [string]$Type, [bool]$WantRight)
    $ansJson = Invoke-Psql "SELECT answer_json::text FROM rs_question_version WHERE id=$QVerId;"
    $ans = $ansJson | ConvertFrom-Json
    if ($Type -eq "CHOICE") {
        if ($WantRight) {
            return @{ selectedOption = $ans.correctOption }
        } else {
            $wrong = if ($ans.correctOption -eq "A") { "B" } else { "A" }
            return @{ selectedOption = $wrong }
        }
    } elseif ($Type -eq "FILL") {
        $vals = [System.Collections.Generic.List[string]]::new()
        foreach ($b in $ans.blanks) {
            if ($WantRight) { $vals.Add([string]$b.accepted[0]) } else { $vals.Add("__M11_WRONG__") }
        }
        return @{ blanks = $vals.ToArray() }
    } else {
        return @{ text = "M11 regression subjective answer"; code = "int f() { return 0; }" }
    }
}

$appProc = $null
& "$PgBin\pg_ctl.exe" -D $PgData -l $PgLog -w -t 300 start

try {
    $env:DB_URL = "jdbc:postgresql://127.0.0.1:5432/$DbName"
    $env:DB_USER = "postgres"
    $env:DB_PASSWORD = "postgres"
    $env:AI_ENABLED = if ($AiEnabled) { "true" } else { "false" }

    $appArgs = @(
        "-jar", $JarPath,
        "--server.port=$Port",
        "--app.ai.scheduler.enabled=$(if ($AiEnabled) { 'true' } else { 'false' })"
    )
    $appProc = Start-Process -FilePath $JavaExe -ArgumentList $appArgs -WorkingDirectory $RepoRoot -RedirectStandardOutput $AppLog -RedirectStandardError $AppErrLog -PassThru

    $healthy = $false
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Milliseconds 500
        try {
            $h = Invoke-RestMethod -Uri "$BaseUrl/api/health" -Method Get -TimeoutSec 2
            if ($h.status -eq "UP") { $healthy = $true; break }
        } catch {}
    }
    if (-not $healthy) {
        throw "Backend failed to start on port $Port"
    }

    $tag = (Get-Date -Format "HHmmss")
    $pwd = "M11_Pass!2026_Secure"
    $uA = "m11_a_$tag"; $uB = "m11_b_$tag"; $uC = "m11_c_$tag"; $uAdmin = "m11_adm_$tag"

    # Step 1: Register & login A, B, C, Admin
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $sessA = New-ApiSession; $sessB = New-ApiSession; $sessC = New-ApiSession; $sessAdm = New-ApiSession
    $regOk = $true
    foreach ($pair in @(@($sessA, $uA), @($sessB, $uB), @($sessC, $uC), @($sessAdm, $uAdmin))) {
        $r1 = Invoke-Api $pair[0] "POST" "/api/auth/register" @{ username = $pair[1]; password = $pwd }
        if ($r1.Status -ne 201) { $regOk = $false }
    }
    Invoke-Psql "UPDATE rs_user SET role='ADMIN' WHERE username='$uAdmin';" | Out-Null
    foreach ($pair in @(@($sessA, $uA), @($sessB, $uB), @($sessC, $uC), @($sessAdm, $uAdmin))) {
        Refresh-Csrf $pair[0]
        $r2 = Invoke-Api $pair[0] "POST" "/api/auth/login" @{ username = $pair[1]; password = $pwd }
        if ($r2.Status -ne 200) { $regOk = $false }
    }
    $sw.Stop()
    Add-StepResult -Step "1_register_login" "POST /api/auth/register + login (A,B,C,Admin)" "All 201/200" "regOk=$regOk" -Status $(if($regOk){"pass"}else{"fail"}) -Ms $sw.ElapsedMilliseconds

    # Step 2: Admin create chapter & KP + coverage shows zero-question KP
    $sw.Restart()
    $chNum = Get-Random -Minimum 20 -Maximum 99
    while ((Invoke-Psql "SELECT count(*) FROM rs_chapter WHERE code='CH$chNum';") -ne "0") {
        $chNum = Get-Random -Minimum 20 -Maximum 99
    }
    $chCode = "CH$chNum"
    $kpCode = "$chCode-KP01"
    $chRes = Invoke-Api $sessAdm "POST" "/api/admin/chapters" @{ code = $chCode; name = "M11验收章节-$tag"; description = "M11"; sortOrder = ($chNum * 10); enabled = $true }
    $chId = $chRes.Body.id
    $kpRes = Invoke-Api $sessAdm "POST" "/api/admin/knowledge-points" @{ chapterId = $chId; code = $kpCode; name = "M11验收知识点-$tag"; description = "M11"; sortOrder = ($chNum * 10); enabled = $true }
    $kpId = $kpRes.Body.id
    $cov1 = Invoke-Api $sessAdm "GET" "/api/admin/question-coverage"
    $kpCov1 = $cov1.Body.knowledgePoints | Where-Object { $_.knowledgePointId -eq $kpId }
    $inZeroList = ($null -ne ($cov1.Body.zeroQuestionKnowledgePoints | Where-Object { $_.knowledgePointId -eq $kpId }))
    $s2ok = ($chRes.Status -eq 201 -and $kpRes.Status -eq 201 -and $null -ne $kpCov1 -and $kpCov1.zeroQuestion -eq $true -and $inZeroList)
    $sw.Stop()
    Add-StepResult -Step "2_admin_chapter_kp_zero_coverage" "POST chapter/kp + GET coverage" "201/201 + zeroQuestion=true" "ch=$($chRes.Status),kp=$($kpRes.Status),zero=$($kpCov1.zeroQuestion),inZero=$inZeroList" -Status $(if($s2ok){"pass"}else{"fail"}) -Ms $sw.ElapsedMilliseconds

    # Step 3: Create 4 question types via Admin API + verify coverage separation
    $sw.Restart()
    $qPayloads = @(
        @{ knowledgePointId = $kpId; type = "CHOICE"; maxScore = 2; content = @{ stem = "M11测试单选"; options = @(@{key="A";text="1"},@{key="B";text="2"},@{key="C";text="3"}) }; answer = @{ correctOption = "A" }; explanation = "exp"; sourceRef = "M11-API-1" },
        @{ knowledgePointId = $kpId; type = "FILL"; maxScore = 2; content = @{ stem = "M11测试填空{{blank}}" }; answer = @{ blanks = @(@{ accepted = @("42") }) }; explanation = "exp"; sourceRef = "M11-API-2" },
        @{ knowledgePointId = $kpId; type = "SHORT_ANSWER"; maxScore = 8; content = @{ stem = "M11测试简答" }; answer = @{ referenceAnswer = "ans" }; rubric = @{ items = @(@{score=4;criterion="c1"},@{score=4;criterion="c2"}) }; explanation = "exp"; sourceRef = "M11-API-3" },
        @{ knowledgePointId = $kpId; type = "ALGORITHM"; maxScore = 7; content = @{ stem = "M11测试算法" }; answer = @{ referenceAnswer = "int f(){return 0;}" }; rubric = @{ items = @(@{score=7;criterion="c1"}) }; explanation = "exp"; sourceRef = "M11-API-4" }
    )
    $qOk = $true
    foreach ($qp in $qPayloads) {
        $qr = Invoke-Api $sessAdm "POST" "/api/admin/questions" $qp
        if ($qr.Status -ne 201) { $qOk = $false }
    }
    $cov2 = Invoke-Api $sessAdm "GET" "/api/admin/question-coverage"
    $kpCov2 = $cov2.Body.knowledgePoints | Where-Object { $_.knowledgePointId -eq $kpId }
    $manSum = ($kpCov2.counts | Measure-Object -Property manual -Sum).Sum
    $aiSum = ($kpCov2.counts | Measure-Object -Property aiGenerated -Sum).Sum
    $s3ok = ($qOk -and $manSum -eq 4 -and $aiSum -eq 0)
    $sw.Stop()
    Add-StepResult -Step "3_admin_create_4_types_coverage" "POST /api/admin/questions x4 + GET coverage" "4 created, manual=4, aiGenerated=0" "qOk=$qOk,manual=$manSum,ai=$aiSum" -Status $(if($s3ok){"pass"}else{"fail"}) -Ms $sw.ElapsedMilliseconds

    # Step 4: Student A creates 10-question ordinary practice + assert count == 10
    $sw.Restart()
    $pCreate = Invoke-Api $sessA "POST" "/api/practices" @{ knowledgeIds = @("1","4","10","13","15","21"); questionCount = 10 }
    $practice = $pCreate.Body
    if ($pCreate.Status -eq 202 -and $practice.id) {
        for ($w = 0; $w -lt 180; $w++) {
            Start-Sleep -Milliseconds 500
            $poll = Invoke-Api $sessA "GET" "/api/practices/$($practice.id)"
            $practice = $poll.Body
            if ($practice.status -ne "PREPARING") { break }
        }
    }
    $s4ok = ($practice.questionCount -eq 10 -and $practice.items.Count -eq 10)
    $sw.Stop()
    Add-StepResult -Step "4_create_10q_practice" "POST /api/practices (count=10)" "questionCount=10, items=10" "status=$($practice.status),qc=$($practice.questionCount),items=$($practice.items.Count)" -Status $(if($s4ok){"pass"}else{"fail"}) -Ms $sw.ElapsedMilliseconds

    # Step 5: Start practice, upload PNG, submit (1 obj wrong, 1 obj right, others answered)
    $sw.Restart()
    $pracId = $practice.id
    $stRes = Invoke-Api $sessA "POST" "/api/practices/$pracId/start"
    $pngBytes = [Convert]::FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
    $upItem = $practice.items[0].id
    $upRes = Invoke-MultipartUpload $sessA "/api/practices/$pracId/items/$upItem/assets" $pngBytes "ans.png" "image/png"
    $assetId = if ($upRes.Body) { $upRes.Body.id } else { $null }

    $answers = @()
    $madeWrong = $false
    $madeRight = $false
    $wrongTargetQId = $null
    foreach ($it in $practice.items) {
        $isObj = ($it.type -in @("CHOICE","FILL"))
        $wantRight = $true
        if ($isObj -and -not $madeWrong) {
            $wantRight = $false
            $madeWrong = $true
            $wrongTargetQId = $it.questionId
        } elseif ($isObj -and -not $madeRight) {
            $wantRight = $true
            $madeRight = $true
        }
        $ansBody = Get-AnswerForQuestion ([long]$it.questionVersionId) $it.type $wantRight
        $aIds = [System.Collections.Generic.List[string]]::new()
        if ($it.id -eq $upItem -and $assetId) { $aIds.Add([string]$assetId) }
        $answers += @{ itemId = [string]$it.id; answer = $ansBody; assetIds = $aIds.ToArray() }
    }
    $subRes = Invoke-Api $sessA "POST" "/api/practices/$pracId/submit" @{ answers = $answers }
    $s5ok = ($stRes.Status -eq 200 -and $upRes.Status -eq 201 -and $subRes.Status -eq 200 -and $madeWrong -and $madeRight)
    $sw.Stop()
    Add-StepResult -Step "5_submit_practice_with_png" "start + upload PNG + submit" "200/201/200" "start=$($stRes.Status),up=$($upRes.Status),sub=$($subRes.Status),wrongQ=$wrongTargetQId" -Status $(if($s5ok){"pass"}else{"fail"}) -Ms $sw.ElapsedMilliseconds

    # Wait for async mastery event processing (updating=false & rs_change_event DONE)
    for ($w = 0; $w -lt 180; $w++) {
        $pendingEv = [int](Invoke-Psql "SELECT count(*) FROM rs_change_event WHERE state <> 'DONE';")
        $mPoll = Invoke-Api $sessA "GET" "/api/review/mastery?size=1"
        if ($pendingEv -eq 0 -and $mPoll.Body -and $mPoll.Body.items.Count -gt 0 -and $mPoll.Body.items[0].updating -eq $false) { break }
        Start-Sleep -Milliseconds 500
    }

    # Step 6: Check rs_practice_item.result_json, rs_evidence, rs_wrong_question
    $sw.Restart()
    $uidA = Invoke-Psql "SELECT id FROM rs_user WHERE username='$uA';"
    $resCnt = [int](Invoke-Psql "SELECT count(*) FROM rs_practice_item WHERE practice_id=$pracId AND result_json IS NOT NULL;")
    $evCnt = [int](Invoke-Psql "SELECT count(*) FROM rs_evidence WHERE user_id=$uidA;")
    $wqCnt = [int](Invoke-Psql "SELECT count(*) FROM rs_wrong_question WHERE user_id=$uidA;")
    $s6ok = ($resCnt -eq 10 -and $evCnt -ge 1 -and $wqCnt -ge 1)
    $sw.Stop()
    Add-StepResult -Step "6_sql_evidence_wrong_question" "SQL verify practice_item/evidence/wrong_question" "res=10, ev>=1, wq>=1" "res=$resCnt,ev=$evCnt,wq=$wqCnt" -Sql "SELECT count(*) FROM rs_practice_item/rs_evidence/rs_wrong_question" -SqlResult "res=$resCnt,ev=$evCnt,wq=$wqCnt" -Status $(if($s6ok){"pass"}else{"fail"}) -Ms $sw.ElapsedMilliseconds

    # Step 7: GET /api/review/mastery
    $sw.Restart()
    $masRes = Invoke-Api $sessA "GET" "/api/review/mastery?size=100"
    $assessed = $masRes.Body.items | Where-Object { $_.n -gt 0 -and $null -ne $_.ruleScore -and $null -ne $_.finalScore }
    $clampOk = $true
    foreach ($m in $assessed) {
        $diff = [Math]::Abs([double]$m.finalScore - [double]$m.ruleScore)
        if ($diff -gt 8.0001) { $clampOk = $false }
    }
    $s7ok = ($masRes.Status -eq 200 -and $assessed.Count -ge 1 -and $clampOk)
    $sw.Stop()
    Add-StepResult -Step "7_mastery_clamp" "GET /api/review/mastery" "ruleScore/finalScore/n present & |final-rule|<=8" "status=$($masRes.Status),assessed=$($assessed.Count),clampOk=$clampOk" -Status $(if($s7ok){"pass"}else{"fail"}) -Ms $sw.ElapsedMilliseconds

    # Step 8: Wrong question redo -> MASTERED
    $sw.Restart()
    $wqList = Invoke-Api $sessA "GET" "/api/wrong-questions"
    $objWq = $wqList.Body.items | Where-Object { $_.questionType -in @("CHOICE","FILL") } | Select-Object -First 1
    $s8ok = $false
    $s8Actual = "no_wrong_question"
    if ($objWq) {
        $redoRes = Invoke-Api $sessA "POST" "/api/wrong-questions/$($objWq.id)/redo" @{}
        $redoP = $redoRes.Body
        if ($redoP -and $redoP.items -and $redoP.items.Count -gt 0) {
            Invoke-Api $sessA "POST" "/api/practices/$($redoP.id)/start" | Out-Null
            $redoAns = Get-AnswerForQuestion ([long]$redoP.items[0].questionVersionId) $redoP.items[0].type $true
            Invoke-Api $sessA "POST" "/api/practices/$($redoP.id)/submit" @{ answers = @(@{ itemId = $redoP.items[0].id; answer = $redoAns; assetIds = @() }) } | Out-Null
            # Wait for async mastery event processing (updating=false & rs_change_event DONE)
    for ($w = 0; $w -lt 180; $w++) {
        $pendingEv = [int](Invoke-Psql "SELECT count(*) FROM rs_change_event WHERE state <> 'DONE';")
        $mPoll = Invoke-Api $sessA "GET" "/api/review/mastery?size=1"
        if ($pendingEv -eq 0 -and $mPoll.Body -and $mPoll.Body.items.Count -gt 0 -and $mPoll.Body.items[0].updating -eq $false) { break }
        Start-Sleep -Milliseconds 500
    }
    $wqAfter = Invoke-Api $sessA "GET" "/api/wrong-questions/$($objWq.id)"
            $s8ok = ($wqAfter.Status -eq 200 -and $wqAfter.Body.status -eq "MASTERED" -and $null -ne $wqAfter.Body.masteredAt)
            $s8Actual = "status=$($wqAfter.Body.status),masteredAt=$($wqAfter.Body.masteredAt)"
        } else {
            $s8Actual = "redo=$($redoRes.Status),raw=$($redoRes.Raw)"
        }
    }
    $sw.Stop()
    Add-StepResult -Step "8_wrong_question_redo_mastered" "POST /api/wrong-questions/{id}/redo + submit correct" "status=MASTERED, masteredAt!=null" $s8Actual -Status $(if($s8ok){"pass"}else{"fail"}) -Ms $sw.ElapsedMilliseconds

    # Step 9: AI Variant
    if (-not $AiEnabled) {
        Add-StepResult -Step "9_ai_variant_cycle" "POST /api/wrong-questions/{id}/variant" "AI variant generated & wrong redo creates linked wrong_question" "AI_API_KEY unavailable; degraded to AI_ENABLED=false" -Status "skipped" -Ms 0
    } else {
        $sw.Restart()
        # Ensure A has a fresh PENDING objective wrong question for variant generation
        $p9Create = Invoke-Api $sessA "POST" "/api/practices" @{ knowledgeIds = @("4"); questionCount = 2 }
        $p9 = $p9Create.Body
        if ($p9 -and $p9.status -eq "PREPARING") {
            for ($w = 0; $w -lt 180; $w++) {
                Start-Sleep -Milliseconds 500
                $p9 = (Invoke-Api $sessA "GET" "/api/practices/$($p9.id)").Body
                if ($p9.status -ne "PREPARING") { break }
            }
        }
        Invoke-Api $sessA "POST" "/api/practices/$($p9.id)/start" | Out-Null
        $p9Ans = @()
        $p9TargetQId = $null
        foreach ($it in $p9.items) {
            $wantR = $true
            if ($it.type -in @("CHOICE","FILL") -and -not $p9TargetQId) { $wantR = $false; $p9TargetQId = $it.questionId }
            $p9Ans += @{ itemId = [string]$it.id; answer = (Get-AnswerForQuestion ([long]$it.questionVersionId) $it.type $wantR); assetIds = @() }
        }
        Invoke-Api $sessA "POST" "/api/practices/$($p9.id)/submit" @{ answers = $p9Ans } | Out-Null
        for ($w = 0; $w -lt 30; $w++) {
            $mPoll = Invoke-Api $sessA "GET" "/api/review/mastery?size=1"
            if ($mPoll.Body -and $mPoll.Body.items.Count -gt 0 -and $mPoll.Body.items[0].updating -eq $false) { break }
            Start-Sleep -Milliseconds 300
        }
        $wq9 = ((Invoke-Api $sessA "GET" "/api/wrong-questions").Body.items | Where-Object { $_.questionId -eq $p9TargetQId } | Select-Object -First 1)
        $varJob = $null
        for ($tryV = 0; $tryV -lt 2; $tryV++) {
            $targetWqForVar = if ($tryV -eq 0) { $wq9 } else {
                ((Invoke-Api $sessA "GET" "/api/wrong-questions").Body.items | Where-Object { $_.status -eq "PENDING" -and $_.id -ne $wq9.id } | Select-Object -First 1)
            }
            if (-not $targetWqForVar) { break }
            $wq9 = $targetWqForVar
            $varJobRes = Invoke-Api $sessA "POST" "/api/wrong-questions/$($wq9.id)/variant" @{}
            $varJobId = $varJobRes.Body.jobId
            if (-not $varJobId) { continue }
            for ($w = 0; $w -lt 180; $w++) {
                Start-Sleep -Milliseconds 500
                $varJob = (Invoke-Api $sessA "GET" "/api/review/ai/jobs/$varJobId").Body
                if ($varJob.state -in @("SUCCEEDED","FAILED","EXPIRED")) { break }
            }
            if ($varJob.state -eq "SUCCEEDED") { break }
        }
        $varQId = if ($varJob -and $varJob.output) { $varJob.output.questionId } else { $null }
        $varPracId = if ($varJob -and $varJob.output) { $varJob.output.practiceId } else { $null }
        $varDbInfo = if ($varQId) { Invoke-Psql "SELECT origin || ',' || COALESCE(origin_question_version_id::text,'') FROM rs_question WHERE id=$varQId;" } else { "" }
        $linkedWqId = ""
        if ($varPracId -and $varQId) {
            $vp = (Invoke-Api $sessA "GET" "/api/practices/$varPracId").Body
            Invoke-Api $sessA "POST" "/api/practices/$varPracId/start" | Out-Null
            $vAns = @()
            foreach ($vit in $vp.items) {
                $vAns += @{ itemId = [string]$vit.id; answer = (Get-AnswerForQuestion ([long]$vit.questionVersionId) $vit.type $false); assetIds = @() }
            }
            Invoke-Api $sessA "POST" "/api/practices/$varPracId/submit" @{ answers = $vAns } | Out-Null
            for ($w = 0; $w -lt 30; $w++) {
                $mPoll = Invoke-Api $sessA "GET" "/api/review/mastery?size=1"
                if ($mPoll.Body -and $mPoll.Body.items.Count -gt 0 -and $mPoll.Body.items[0].updating -eq $false) { break }
                Start-Sleep -Milliseconds 300
            }
            $linkedWqId = Invoke-Psql "SELECT COALESCE(origin_wrong_question_id::text,'') FROM rs_wrong_question WHERE user_id=$uidA AND question_id=$varQId;"
        }
        $s9ok = ($varJob.state -eq "SUCCEEDED" -and $varDbInfo -match "^AI_GENERATED,\d+" -and $linkedWqId -eq [string]$wq9.id)
        $sw.Stop()
        Add-StepResult -Step "9_ai_variant_cycle" "POST /api/wrong-questions/{id}/variant + wrong submit" "SUCCEEDED, origin=AI_GENERATED, origin_wrong_question_id=$($wq9.id)" "state=$($varJob.state),err=$($varJob.errorCode),varQ=$varQId,db=$varDbInfo,linkedWq=$linkedWqId" -Sql "SELECT origin,origin_question_version_id / origin_wrong_question_id" -SqlResult "varQ=$varQId,db=$varDbInfo,linkedWq=$linkedWqId" -Status $(if($s9ok){"pass"}else{"fail"}) -Ms $sw.ElapsedMilliseconds
    }

    # Step 10: Recommendations + create review task
    $sw.Restart()
    $recRes = Invoke-Api $sessA "GET" "/api/review/recommendations"
    $firstRec = $recRes.Body.items | Select-Object -First 1
    $hasReason = ($null -ne $firstRec -and $null -ne $firstRec.reason)
    $taskCreate = Invoke-Api $sessA "POST" "/api/review/tasks" @{ questionCount = 5 }
    $task = $taskCreate.Body
    if ($taskCreate.Status -eq 202 -and $task -and $task.id) {
        for ($w = 0; $w -lt 180; $w++) {
            Start-Sleep -Milliseconds 500
            $task = (Invoke-Api $sessA "GET" "/api/review/tasks/$($task.id)").Body
            if ($task.status -ne "PREPARING") { break }
        }
    }
    $s10ok = ($recRes.Status -eq 200 -and $hasReason -and $taskCreate.Status -in @(201,202) -and $task.status -eq "READY" -and $task.items.Count -ge 1)
    $sw.Stop()
    Add-StepResult -Step "10_recommendations_and_task_create" "GET /api/review/recommendations + POST /api/review/tasks" "200 with reason + task created" "rec=$($recRes.Status),hasReason=$hasReason,task=$($taskCreate.Status),items=$($task.items.Count),err=$($taskCreate.Raw)" -Status $(if($s10ok){"pass"}else{"fail"}) -Ms $sw.ElapsedMilliseconds

    # Step 11: Submit review task -> rs_mastery_snapshot row added
    $sw.Restart()
    $snapBefore = [int](Invoke-Psql "SELECT count(*) FROM rs_mastery_snapshot WHERE user_id=$uidA;")
    Invoke-Api $sessA "POST" "/api/review/tasks/$($task.id)/start" | Out-Null
    $tAnswers = @()
    foreach ($ti in $task.items) {
        $ta = Get-AnswerForQuestion ([long]$ti.questionVersionId) $ti.questionType $true
        $tAnswers += @{ itemId = $ti.id; answer = $ta; assetIds = @() }
    }
    $tSub = Invoke-Api $sessA "POST" "/api/review/tasks/$($task.id)/submit" @{ answers = $tAnswers }
    for ($w = 0; $w -lt 30; $w++) {
        $mPoll = Invoke-Api $sessA "GET" "/api/review/mastery?size=1"
        if ($mPoll.Body -and $mPoll.Body.items.Count -gt 0 -and $mPoll.Body.items[0].updating -eq $false) { break }
        Start-Sleep -Milliseconds 300
    }
    $snapAfter = [int](Invoke-Psql "SELECT count(*) FROM rs_mastery_snapshot WHERE user_id=$uidA;")
    $snapSample = Invoke-Psql "SELECT rule_score, ai_score, final_score, clamped FROM rs_mastery_snapshot WHERE user_id=$uidA ORDER BY id DESC LIMIT 1;"
    $s11ok = ($tSub.Status -eq 200 -and $snapAfter -gt $snapBefore)
    $sw.Stop()
    Add-StepResult -Step "11_submit_task_mastery_snapshot" "POST /api/review/tasks/{id}/submit" "200 + snapshot count increased" "sub=$($tSub.Status),before=$snapBefore,after=$snapAfter" -Sql "SELECT count(*) FROM rs_mastery_snapshot" -SqlResult "before=$snapBefore,after=$snapAfter,latest=$snapSample" -Status $(if($s11ok){"pass"}else{"fail"}) -Ms $sw.ElapsedMilliseconds

    # Step 12: AI Guidance
    if (-not $AiEnabled) {
        Add-StepResult -Step "12_ai_guidance_job" "POST /api/review/ai/jobs (GUIDANCE)" "SUCCEEDED with citations>=1" "AI_API_KEY unavailable; degraded to AI_ENABLED=false" -Status "skipped" -Ms 0
    } else {
        $sw.Restart()
        $gRes = Invoke-Api $sessA "POST" "/api/review/ai/jobs" @{ scenario = "GUIDANCE"; knowledgeId = "1" }
        $gJobId = if ($gRes.Body) { $gRes.Body.jobId } else { $null }
        $gJob = $null
        if ($gJobId) {
            for ($w = 0; $w -lt 180; $w++) {
                Start-Sleep -Milliseconds 500
                $gJob = (Invoke-Api $sessA "GET" "/api/review/ai/jobs/$gJobId").Body
                if ($gJob.state -in @("SUCCEEDED","FAILED","EXPIRED")) { break }
            }
        }
        $citCnt = if ($gJob -and $gJob.citations) { @($gJob.citations).Count } elseif ($gJob -and $gJob.output -and $gJob.output.citations) { @($gJob.output.citations).Count } else { 0 }
        $s12ok = ($gRes.Status -in @(200,202) -and $null -ne $gJob -and $gJob.state -eq "SUCCEEDED" -and $citCnt -ge 1)
        $sw.Stop()
        Add-StepResult -Step "12_ai_guidance_job" "POST /api/review/ai/jobs (GUIDANCE)" "SUCCEEDED with citations>=1" "http=$($gRes.Status),job=$gJobId,state=$($gJob.state),err=$($gJob.errorCode),citations=$citCnt,raw=$($gRes.Raw)" -Status $(if($s12ok){"pass"}else{"fail"}) -Ms $sw.ElapsedMilliseconds
    }

    # Step 13: Cross-account AI
    if (-not $AiEnabled) {
        Add-StepResult -Step "13_cross_account_ai_question" "B recommendations include A's AI question" "B draws & grades AI question" "AI_API_KEY unavailable; degraded to AI_ENABLED=false" -Status "skipped" -Ms 0
    } else {
        $sw.Restart()
        $aAiIds = @(Invoke-Psql "SELECT q.id FROM rs_question q JOIN rs_ai_job j ON j.id=q.gen_job_id WHERE q.origin='AI_GENERATED' AND q.status='ACTIVE' AND j.user_id=$uidA;") -split "`n" | Where-Object { $_ -match '^\d+$' }
        $aiRow = (Invoke-Psql "SELECT q.id || ',' || v.knowledge_point_id FROM rs_question q JOIN rs_question_version v ON v.id=q.current_version_id JOIN rs_ai_job j ON j.id=q.gen_job_id WHERE q.origin='AI_GENERATED' AND q.status='ACTIVE' AND j.user_id=$uidA ORDER BY q.id DESC LIMIT 1;").Split(",")
        $targetAiQId = $aiRow[0]; $targetAiKpId = $aiRow[1]
        $bPracRes = Invoke-Api $sessB "POST" "/api/practices" @{ knowledgeIds = @($targetAiKpId); questionCount = 20 }
        $bPrac = $bPracRes.Body
        if ($bPrac -and $bPrac.status -eq "PREPARING") {
            for ($w = 0; $w -lt 180; $w++) {
                Start-Sleep -Milliseconds 500
                $bPrac = (Invoke-Api $sessB "GET" "/api/practices/$($bPrac.id)").Body
                if ($bPrac.status -ne "PREPARING") { break }
            }
        }
        $aiHit = $bPrac.items | Where-Object { $_.questionId -in $aAiIds } | Select-Object -First 1
        $bGraded = $false
        if ($aiHit) {
            Invoke-Api $sessB "POST" "/api/practices/$($bPrac.id)/start" | Out-Null
            $bAns = @()
            foreach ($bit in $bPrac.items) {
                $bAns += @{ itemId = [string]$bit.id; answer = (Get-AnswerForQuestion ([long]$bit.questionVersionId) $bit.type $true); assetIds = @() }
            }
            $bSub = Invoke-Api $sessB "POST" "/api/practices/$($bPrac.id)/submit" @{ answers = $bAns }
            if ($bSub.Status -eq 200) {
                $bResItem = $bSub.Body.items | Where-Object { $_.itemId -eq $aiHit.id } | Select-Object -First 1
                if ($null -ne $bResItem -and $null -ne $bResItem.result.passed) { $bGraded = $true }
            }
        } elseif ($bPrac -and $bPrac.id) {
            Invoke-Api $sessB "POST" "/api/practices/$($bPrac.id)/cancel" | Out-Null
        }
        $s13ok = ($null -ne $aiHit -and $bGraded)
        $sw.Stop()
        Add-StepResult -Step "13_cross_account_ai_question" "B draws & grades A's AI question" "hit=true, graded=true" "targetAiQ=$targetAiQId,kp=$targetAiKpId,hitQ=$($aiHit.questionId),graded=$bGraded" -Status $(if($s13ok){"pass"}else{"fail"}) -Ms $sw.ElapsedMilliseconds
    }

    # Step 14: 3 accounts report SUSPECTED_ERROR on same question -> review case -> filter -> resolve -> back
    $sw.Restart()
    # Create a dedicated CHOICE question on our test KP so all 3 students can get it wrong cleanly
    $fbQ = Invoke-Api $sessAdm "POST" "/api/admin/questions" @{
        knowledgePointId = $kpId; type = "CHOICE"; maxScore = 2
        content = @{ stem = "M11复核测试题"; options = @(@{key="A";text="x"},@{key="B";text="y"},@{key="C";text="z"}) }
        answer = @{ correctOption = "A" }; explanation = "exp"; sourceRef = "M11-FB-1"
    }
    $fbQId = $fbQ.Body.id
    $fbQNo = $fbQ.Body.questionNo
    foreach ($sess in @($sessA, $sessB, $sessC)) {
        $pr = (Invoke-Api $sess "POST" "/api/practices" @{ knowledgeIds = @($kpId); questionCount = 10 }).Body
        if ($pr -and $pr.status -eq "PREPARING") {
            for ($w = 0; $w -lt 180; $w++) {
                Start-Sleep -Milliseconds 500
                $pr = (Invoke-Api $sess "GET" "/api/practices/$($pr.id)").Body
                if ($pr.status -ne "PREPARING") { break }
            }
        }
        Invoke-Api $sess "POST" "/api/practices/$($pr.id)/start" | Out-Null
        $pAns = @()
        $targetWqId = $null
        foreach ($it in $pr.items) {
            $wantRight = ($it.questionId -ne $fbQId)
            $pAns += @{ itemId = $it.id; answer = (Get-AnswerForQuestion ([long]$it.questionVersionId) $it.type $wantRight); assetIds = @() }
        }
        Invoke-Api $sess "POST" "/api/practices/$($pr.id)/submit" @{ answers = $pAns } | Out-Null
        $wqs = (Invoke-Api $sess "GET" "/api/wrong-questions").Body.items
        $targetWq = $wqs | Where-Object { $_.questionId -eq $fbQId } | Select-Object -First 1
        if ($targetWq) {
            Invoke-Api $sess "POST" "/api/wrong-questions/$($targetWq.id)/feedback" @{ category = "SUSPECTED_ERROR"; comment = "M11 test report" } | Out-Null
        }
    }
    $casesOpen = Invoke-Api $sessAdm "GET" "/api/admin/review-cases?status=OPEN"
    $theCase = $casesOpen.Body.items | Where-Object { $_.questionId -eq $fbQId } | Select-Object -First 1
    $recFiltered = (Invoke-Api $sessA "GET" "/api/review/recommendations").Body.items
    $inPoolBeforeResolve = ($null -ne ($recFiltered | Where-Object { $_.questionId -eq $fbQId }))
    $resolveRes = if ($theCase) { Invoke-Api $sessAdm "POST" "/api/admin/review-cases/$($theCase.id)/resolve" @{} } else { @{ Status = 0 } }
    $qStatusAfter = Invoke-Psql "SELECT status FROM rs_question WHERE id=$fbQId;"
    $s14ok = ($null -ne $theCase -and $theCase.triggerFeedbackCount -eq 3 -and -not $inPoolBeforeResolve -and $resolveRes.Status -eq 200 -and $qStatusAfter -eq "ACTIVE")
    $sw.Stop()
    Add-StepResult -Step "14_review_case_3_reports_filter_resolve" "3x feedback SUSPECTED_ERROR -> case -> resolve" "case triggerCount=3, filtered=true, resolved=ACTIVE" "case=$($theCase.id),cnt=$($theCase.triggerFeedbackCount),filtered=$(-not $inPoolBeforeResolve),resolve=$($resolveRes.Status),qStatus=$qStatusAfter" -Status $(if($s14ok){"pass"}else{"fail"}) -Ms $sw.ElapsedMilliseconds

    # Step 15: Final consistency check
    $sw.Restart()
    $danglingAct = [int](Invoke-Psql "SELECT count(*) FROM rs_learning_activity WHERE user_id IN (SELECT id FROM rs_user WHERE username IN ('$uA','$uB','$uC')) AND status IN ('PREPARING','IN_PROGRESS');")
    $danglingAi = [int](Invoke-Psql "SELECT count(*) FROM rs_ai_job WHERE state IN ('QUEUED','RUNNING');")
    $s15ok = ($danglingAct -eq 0 -and $danglingAi -eq 0)
    $sw.Stop()
    Add-StepResult -Step "15_final_state_consistency" "SQL check dangling activities & AI jobs" "0 PREPARING/IN_PROGRESS, 0 QUEUED/RUNNING" "act=$danglingAct,ai=$danglingAi" -Sql "SELECT count(*) FROM rs_learning_activity/rs_ai_job" -SqlResult "act=$danglingAct,ai=$danglingAi" -Status $(if($s15ok){"pass"}else{"fail"}) -Ms $sw.ElapsedMilliseconds

    # Step 16 (B3): Sample 5 newly imported questions (id >= 205) and verify drawn & graded
    $sw.Restart()
    $sampleRows = (Invoke-Psql "SELECT q.id || ',' || v.knowledge_point_id || ',' || v.type || ',' || v.id FROM rs_question q JOIN rs_question_version v ON v.id=q.current_version_id WHERE q.id >= 205 AND v.type IN ('CHOICE','FILL') ORDER BY q.id LIMIT 5;") -split "`n"
    $drawnOk = 0
    $gradedRight = $false
    $gradedWrong = $false
    foreach ($row in $sampleRows) {
        $parts = $row.Split(",")
        $sqId = $parts[0]; $skpId = $parts[1]; $sqType = $parts[2]; $sqVer = [long]$parts[3]
        $sp = (Invoke-Api $sessB "POST" "/api/practices" @{ knowledgeIds = @($skpId); questionCount = 10 }).Body
        if ($sp -and $sp.status -eq "PREPARING") {
            for ($w = 0; $w -lt 180; $w++) {
                Start-Sleep -Milliseconds 500
                $sp = (Invoke-Api $sessB "GET" "/api/practices/$($sp.id)").Body
                if ($sp.status -ne "PREPARING") { break }
            }
        }
        $hit = $sp.items | Where-Object { $_.questionId -eq $sqId } | Select-Object -First 1
        if ($hit) {
            $drawnOk++
            Invoke-Api $sessB "POST" "/api/practices/$($sp.id)/start" | Out-Null
            $wantR = (-not $gradedRight)
            $sAns = @()
            foreach ($sit in $sp.items) {
                $rFlag = if ($sit.questionId -eq $sqId) { $wantR } else { $true }
                $sAns += @{ itemId = $sit.id; answer = (Get-AnswerForQuestion ([long]$sit.questionVersionId) $sit.type $rFlag); assetIds = @() }
            }
            $sSub = Invoke-Api $sessB "POST" "/api/practices/$($sp.id)/submit" @{ answers = $sAns }
            if ($sSub.Status -eq 200) {
                $resItem = $sSub.Body.items | Where-Object { $_.itemId -eq $hit.id } | Select-Object -First 1
                if ($wantR -and $resItem.result.passed -eq $true) { $gradedRight = $true }
                elseif (-not $wantR -and $resItem.result.passed -eq $false) { $gradedWrong = $true }
            }
        } else {
            Invoke-Api $sessB "POST" "/api/practices/$($sp.id)/cancel" | Out-Null
        }
    }
    $s16ok = ($drawnOk -ge 3 -and $gradedRight -and $gradedWrong)
    $sw.Stop()
    Add-StepResult -Step "16_b3_sample_new_questions" "Sample 5 new questions (id>=205) drawn & graded right/wrong" "drawn>=3, right=true, wrong=true" "drawn=$drawnOk/5,right=$gradedRight,wrong=$gradedWrong" -Status $(if($s16ok){"pass"}else{"fail"}) -Ms $sw.ElapsedMilliseconds
}
finally {
    if ($appProc -and -not $appProc.HasExited) {
        Stop-Process -Id $appProc.Id -Force -ErrorAction SilentlyContinue
    }
    & "$PgBin\pg_ctl.exe" -D $PgData -m fast stop
}

$RunSw.Stop()
$passed = @($Steps | Where-Object { $_.status -eq "pass" }).Count
$failed = @($Steps | Where-Object { $_.status -eq "fail" }).Count
$skipped = @($Steps | Where-Object { $_.status -eq "skipped" }).Count

$report = [ordered]@{
    database   = $DbName
    aiEnabled  = $AiEnabled
    aiScheduler = $AiEnabled
    envLoadedFromDotEnv = $EnvLoaded.Count -gt 0
    total      = $Steps.Count
    passed     = $passed
    failed     = $failed
    skipped    = $skipped
    durationMs = $RunSw.ElapsedMilliseconds
    steps      = $Steps
}

$report | ConvertTo-Json -Depth 10 | Set-Content -Path $OutFile -Encoding UTF8
Write-Host "CLOSURE SUMMARY: total=$($Steps.Count) passed=$passed failed=$failed skipped=$skipped durationMs=$($RunSw.ElapsedMilliseconds) out=$OutFile"
if ($failed -gt 0) { exit 1 } else { exit 0 }
