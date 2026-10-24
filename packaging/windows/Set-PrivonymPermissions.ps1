[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory = $true)][string[]]$Directory,
    [string]$ServiceIdentity = 'S-1-5-18'
)
$ErrorActionPreference = 'Stop'
$operatorSid = [Security.Principal.WindowsIdentity]::GetCurrent().User
$serviceSid = if ($ServiceIdentity -match '^S-1-') {
    [Security.Principal.SecurityIdentifier]::new($ServiceIdentity)
} else {
    ([Security.Principal.NTAccount]::new($ServiceIdentity)).Translate([Security.Principal.SecurityIdentifier])
}
$administrators = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
$system = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
$items = @()
foreach ($entry in $Directory) {
    if (-not [IO.Path]::IsPathRooted($entry)) { throw 'An absolute application directory is required.' }
    $root = [IO.Path]::GetFullPath($entry).TrimEnd('\')
    if ($root -eq [IO.Path]::GetPathRoot($root).TrimEnd('\') -or
            $root -eq $env:SystemRoot -or $root -eq $env:ProgramFiles -or $root -eq $env:USERPROFILE) {
        throw 'Refusing to change permissions on a system or profile root.'
    }
    $item = Get-Item -LiteralPath $root -Force
    if (-not $item.PSIsContainer) { throw 'Expected an application directory.' }
    $items += $item
    $items += Get-ChildItem -LiteralPath $root -Force -Recurse
}
if ($items | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) {
    throw 'Reparse points are not supported; no permissions were changed.'
}
foreach ($item in $items | Sort-Object FullName -Unique) {
    $acl = if ($item.PSIsContainer) {
        [Security.AccessControl.DirectorySecurity]::new()
    } else { [Security.AccessControl.FileSecurity]::new() }
    $acl.SetAccessRuleProtection($true, $false)
    $inheritance = if ($item.PSIsContainer) {
        [Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit'
    } else { [Security.AccessControl.InheritanceFlags]::None }
    foreach ($sid in @($operatorSid, $administrators, $system, $serviceSid) | Sort-Object Value -Unique) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($sid,
                [Security.AccessControl.FileSystemRights]::FullControl, $inheritance,
                [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow)
        $acl.AddAccessRule($rule)
    }
    if ($PSCmdlet.ShouldProcess($item.FullName, 'Restrict access to operator, administrators and service')) {
        Set-Acl -LiteralPath $item.FullName -AclObject $acl
    }
}
