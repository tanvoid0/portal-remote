; Portal Remote — Windows installer.
;
; Installs per user, not machine-wide, and that is a requirement rather than a
; shortcut. The app updates itself by overwriting its own .exe in place (see
; Update/UpdateCheck.cs); a process running as the signed-in user cannot do that to a
; file in Program Files, so a machine-wide install would ship with its own updater
; permanently broken. Per-user also means no UAC prompt to install a LAN remote, and an
; uninstall that leaves nothing behind for other accounts on the PC.
;
; PrivilegesRequired=lowest is what makes {autopf} resolve to %LOCALAPPDATA%\Programs.
;
; Built by .github/workflows/release.yml:
;   iscc /DAppVersion=0.6.0 /DExeSource=<path>\PortalRemote.exe PortalRemote.iss

#ifndef AppVersion
  #define AppVersion "0.1.0-dev"
#endif
; Default points at publish.ps1's output, so a local `iscc PortalRemote.iss` after a
; local publish just works.
#ifndef ExeSource
  #define ExeSource "..\publish\PortalRemote.exe"
#endif
#define AppName "Portal Remote"
#define AppUrl "https://github.com/Aipairs-Ltd/portal-remote"

[Setup]
; Never change this: it is the identity Windows matches an upgrade against, and a new
; one would install a second copy beside the old rather than over it.
AppId={{7A870589-8B8C-4577-B080-E73414CA9C86}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppName}
AppPublisherURL={#AppUrl}
AppSupportURL={#AppUrl}/issues
AppUpdatesURL={#AppUrl}/releases
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
; One program group with one shortcut in it is not a choice worth a wizard page.
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=.
OutputBaseFilename=PortalRemote-Setup-{#AppVersion}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
SetupIconFile=..\PortalRemote.Server\app.ico
UninstallDisplayIcon={app}\PortalRemote.exe
UninstallDisplayName={#AppName}
; The tray app is almost certainly running during an upgrade, and Windows will not let
; its .exe be replaced while it is. Restart Manager closes it here, which is what makes
; upgrading one click rather than "exit from the tray first, then install".
CloseApplications=yes
; ...but do not bring it back automatically: the [Run] entry below already starts the
; new build, and Restart Manager would otherwise start a second copy of it.
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
; Checked by default, and the whole reason this installer exists. Portal Remote is a
; thing a phone dials into: a PC that only runs it when somebody remembers to
; double-click it is a PC the phone cannot reach, and "it was gone again after I
; rebooted" is the symptom.
Name: "startup"; Description: "Start {#AppName} when I sign in"; GroupDescription: "Startup:"

[Files]
Source: "{#ExeSource}"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\PortalRemote.exe"
Name: "{group}\Uninstall {#AppName}"; Filename: "{uninstallexe}"

[Registry]
; The same value name the in-app checkbox writes (Config/StartupRegistration.cs), so
; the two agree instead of leaving two registrations behind. uninsdeletevalue takes it
; away with the app; deletevalue on install clears it when the task is unticked, which
; is what makes re-running the installer able to turn autostart back off.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; \
    ValueType: string; ValueName: "PortalRemote"; ValueData: """{app}\PortalRemote.exe"""; \
    Flags: uninsdeletevalue; Tasks: startup
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; \
    ValueType: none; ValueName: "PortalRemote"; \
    Flags: deletevalue; Tasks: not startup

[Run]
Filename: "{app}\PortalRemote.exe"; Description: "Start {#AppName} now"; \
    Flags: nowait postinstall skipifsilent

; Deliberately not removed on uninstall: %APPDATA%\portal-remote holds the config and
; the pairing token, and keeping it means reinstalling does not make every paired phone
; scan a new QR code. It is a few hundred bytes, and "Rotate pairing token" in the app
; is the button for actually wanting them locked out.
