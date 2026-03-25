$dest = "/sdcard/Android/data/com.example.scottishhillnav/files/dem"
$src  = "C:\Android\scottishhillnav\tools\hgt"
$tiles = @("N56W004","N56W005","N56W006","N56W007","N57W004","N57W005","N57W006","N57W007")
foreach ($t in $tiles) {
    $srcFile  = "$src\$t.hgt"
    $destFile = "$dest/$t.hgt"
    adb push $srcFile $destFile
    Write-Host "Pushed $t.hgt"
}
