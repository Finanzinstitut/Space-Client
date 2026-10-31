$ErrorActionPreference = 'Stop'
# UTF-8 both ways: track names in any script, and a temp path
# under a user name with an umlaut, arrive intact
try { [Console]::InputEncoding = [System.Text.Encoding]::UTF8 } catch { }
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
try {
  Add-Type -AssemblyName System.Runtime.WindowsRuntime
  $asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() |
    Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
      $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]

  function Await($operation, $resultType) {
    $method = $asTask.MakeGenericMethod($resultType)
    $task = $method.Invoke($null, @($operation))
    if (-not $task.Wait(4000)) { throw 'media call timed out' }
    $task.Result
  }

  $managerType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]
  $manager = Await ($managerType::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])

  # Only these two. A media key would reach whatever Windows
  # last decided was the session, which is as easily a browser.
  function Target {
    $wanted = $null
    foreach ($s in $manager.GetSessions()) {
      $id = $s.SourceAppUserModelId.ToLower()
      if (-not ($id -like '*spotify*' -or $id -like '*amazon*')) { continue }
      if ($s.GetPlaybackInfo().PlaybackStatus -eq 'Playing') { return $s }
      if ($wanted -eq $null) { $wanted = $s }
    }
    return $wanted
  }

  $null = [Windows.Storage.Streams.DataReader,Windows.Storage.Streams,ContentType=WindowsRuntime]

  # What every player shows, one line each, then 'end'
  function Read-Sessions {
    foreach ($s in $manager.GetSessions()) {
      $appId = $s.SourceAppUserModelId
      $state = $s.GetPlaybackInfo().PlaybackStatus
      $p = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
      $pos = -1; $dur = -1; $age = 0
      try {
        $t = $s.GetTimelineProperties()
        if ($t -ne $null) {
          $pos = $t.Position.TotalSeconds
          $dur = $t.EndTime.TotalSeconds
          $age = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - $t.LastUpdatedTime.ToUnixTimeMilliseconds()
        }
      } catch { }
      $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
      $artist = ($p.Artist -replace '[\r\n|]', ' ')
      $title = ($p.Title -replace '[\r\n|]', ' ')
      Write-Output ("$appId|$state|$artist|$title|" + $pos + '|' + $dur + '|' + $age + '|' + $now)
    }
    Write-Output 'end'
  }

  # The cover of the session Target picks, written to a file
  function Write-Thumb($file) {
    $s = Target
    if ($s -eq $null) { return 'THUMB|none' }
    $p = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
    if ($p.Thumbnail -eq $null) { return 'THUMB|none' }
    $stream = Await ($p.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
    $size = [uint32]$stream.Size
    if ($size -le 0) { return 'THUMB|none' }
    $reader = [Windows.Storage.Streams.DataReader]::new($stream.GetInputStreamAt(0))
    $null = Await ($reader.LoadAsync($size)) ([uint32])
    $bytes = New-Object byte[] $size
    $reader.ReadBytes($bytes)
    [System.IO.File]::WriteAllBytes($file, $bytes)
    return ('THUMB|ok|' + $size)
  }

  Write-Output 'ready'

  while ($true) {
    $line = [Console]::In.ReadLine()
    if ($line -eq $null) { break }
    try {
      $cmd = $line.Trim()
      # Questions first: they must answer even with no player open
      if ($cmd -eq 'read') { Read-Sessions; continue }
      if ($cmd.StartsWith('thumb|')) { Write-Output (Write-Thumb $cmd.Substring(6)); continue }
      $session = Target
      if ($session -eq $null) { Write-Output 'err|no spotify or amazon session'; continue }
      # One line out per line in, always. 'continue' inside a
      # switch leaves the switch and not the loop, so an unknown
      # command would have printed its complaint AND 'ok' - two
      # answers to one question, and every reply after that
      # belongs to the press before it.
      $known = $true
      switch ($line.Trim()) {
        'toggle' { $null = Await ($session.TryTogglePlayPauseAsync()) ([bool]) }
        'next'   { $null = Await ($session.TrySkipNextAsync()) ([bool]) }
        'prev'   { $null = Await ($session.TrySkipPreviousAsync()) ([bool]) }
        default  { $known = $false }
      }
      if ($known) { Write-Output 'ok' } else { Write-Output 'err|unknown command' }
    } catch {
      # A read that failed half way still owes its 'end' - the
      # err line is what the reader stops on
      Write-Output ('err|' + ($_.Exception.Message -replace '[\r\n]', ' '))
    }
  }
} catch {
  Write-Output ('err|' + $_.Exception.Message)
}
