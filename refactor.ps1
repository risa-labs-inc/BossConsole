$filePath = "d:\projects\BossConsole\composeApp\src\desktopTest\kotlin\ai\rever\boss\fluck\FluckTabInfoTest.kt"
$content = Get-Content -Path $filePath -Raw

# Replace val tabInfo with var tabInfo (and other variables)
$content = $content -replace '\bval (tabInfo|original|copied|tab1|tab2|tab3)\b', 'var $1'

# Replace .navigateToPage(...) with = ...updateNavigation(...)
$content = [regex]::Replace($content, '(\b(?:tabInfo|original|copied|tab1|tab2|tab3)\b)\.navigateToPage\(([^)]+)\)', '$1 = $1.updateNavigation($2)')

# Replace .navigateBack() with = ...goBack()
$content = [regex]::Replace($content, '(\b(?:tabInfo|original|copied|tab1|tab2|tab3)\b)\.navigateBack\(\)', '$1 = $1.goBack()')

# Replace .navigateForward() with = ...goForward()
$content = [regex]::Replace($content, '(\b(?:tabInfo|original|copied|tab1|tab2|tab3)\b)\.navigateForward\(\)', '$1 = $1.goForward()')

Set-Content -Path $filePath -Value $content -Encoding UTF8
Write-Host "Refactored FluckTabInfoTest.kt"
