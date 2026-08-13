# Security notes

Anyone holding the pairing token has the practical equivalent of physical keyboard/mouse
access to the PC — the same trust model as RDP or TeamViewer. Rotate the token from the
app window if it's ever suspected leaked.

- **LAN-only by design.** No relay, no cloud. Don't port-forward this to the internet —
  there's no TLS and a single shared bearer token.
- **`POST /pair/request` is unauthenticated on purpose** — it's how a phone that has
  never seen the PC gets a token in the first place. The gate is the dialog it puts on
  the PC's screen, which is the same bar the QR code sets: you have to be at the machine.
  It defaults to No, names the requesting phone and its IP, and only one can be open at a
  time, so nothing on the LAN can bury the screen in prompts. The discovery reply carries
  no token — only a machine name, port and version, all of which a port scan would reveal
  anyway.
- Every `/files/*` and `/control` request requires the pairing token, sent as an
  `Authorization: Bearer` header from the app. A `?token=` query-string fallback exists
  server-side for requests that can't set headers (image tags, streaming endpoints) but
  the app itself never uses it for `/control` precisely because query strings land in the
  server's plaintext request logs — this was checked and fixed during development (see
  git history).
- Path traversal is blocked on every file endpoint (list/download/upload) by resolving
  against the share root and rejecting anything that escapes it — `server/PortalRemote.Tests/
  FilePathsTests.cs` covers `../..`, drive-rooted and UNC paths, drive-relative `C:name`,
  and a sibling folder whose name merely starts with the root's.
- **An uploaded filename is reduced to something that can only name one ordinary file**
  (`FilePaths.SafeFileName`). Dropping the directory component is not enough on Windows:
  `Path.GetFileName` deliberately keeps an NTFS stream suffix, so `notes.txt:hidden` would
  have written an alternate data stream that nothing afterwards lists; a quote would have
  reached `explorer.exe`'s argument line via the tray's reveal-in-folder; and `NUL` or
  `COM1` name a device in every directory, which `File.Create` opens instead of a file.
  All three are handled in the one function every caller routes through.
- File uploads have no size cap (`Kestrel MaxRequestBodySize` is unlimited) —
  intentional, so large files transfer, but it means anyone holding the pairing token can
  fill the disk. Acceptable for a personal LAN tool; would want a cap before this became
  multi-user or internet-facing.
- **Quick share writes to the clipboard on arrival** on both devices, and a paired phone
  can drop any file into `<share root>/Inbox/`. So the tray balloon *reveals* a shared
  file in Explorer (`explorer /select,`) rather than opening it, and only `http`/`https`
  links are ever handed to `ShellExecute` — one click of a notification must not be able
  to run something the phone sent. `/share/*` is behind the same token as everything
  else, with the same path-traversal guard on the upload filename; shared text is capped
  at 256KB per message.
- **The mirror streams whatever is on screen** to anyone holding the token, including
  whatever happens to be open — password managers, private chats. It's behind the same
  single token as everything else, so treat the token as granting "sees and controls my
  desktop", not "can move my mouse". GDI capture fails outright against the lock screen
  and the UAC secure desktop; the stream handler treats that as a skipped frame and holds
  the response open rather than dropping the client, so the mirror should resume on
  unlock (coded for, not yet exercised live).
- **A file cast from the phone to a TV carries its own secret, not the pairing token.**
  The phone serves picked files at `http://phone:port/f/<id>?token=…`, and that URL is
  handed to whatever plays it — which, since the Roku and DLNA senders landed, can be a
  television that logs it and shows it in its own status. So the phone's media server
  mints its **own** per-process secret rather than reusing the pairing token: the URL
  grants "read the files this phone offered", never "see and control the PC's desktop".
  The id in the path is 96 unguessable bits on top of that, and both die when the app's
  process does.
- **Casting to a third-party device sends it the URL, naked.** A Roku or a DLNA renderer
  fetches what you give it with no `Referer` and no `Cookie` — that is a property of
  their protocols, not a choice here. It means a link behind a login will fail there
  rather than leak the session, but it also means the URL itself (and anything in its
  query string) is visible to that device and to anything on the LAN that can read its
  state. Only the PC's own player is ever handed headers.
- **The DLNA renderer is unauthenticated, and off by default.** Turning on
  `EnableDlnaRenderer` in `config.json` lets Web Video Caster, BubbleUPnP and gallery apps
  cast to this PC — and they cannot present the pairing token, which is the whole point of
  speaking their protocol. So while it is on, **anyone on the same network can put a video
  on this screen.** They cannot do anything else: the URL goes through the same validation
  as the phone's, so only `http`/`https` ever reaches a player, and there is no other
  action on that surface. The startup banner says when it is on.
- **The assistant sends what you ask it off this machine if its provider is a hosted
  one.** Every other feature here is LAN-only; this one is not, and how far a message
  travels is decided by whichever provider `agent-platformd` is configured with — a local
  model keeps it on the machine, a hosted one does not. That applies to both halves: chat
  messages, and the goals behind "do this on the PC". The phone never talks to
  agent-platform directly and never holds its token — the PC does, over loopback — but
  that is about credentials, not about where the text ends up.
- **Nothing the assistant proposes runs on its own.** `/decide` only ever returns a plan;
  the PC validates every action against the six it registered, drops anything else, and
  runs only the ones ticked on the phone — with a second confirmation for shutdown and
  restart, and a refusal to run the same plan twice.
- **Both self-updaters will only download from GitHub.** The tray's update replaces the
  running `.exe` with what it fetched, and the assistant's one-click setup unzips and
  starts a second program — in both cases the URL comes out of a release JSON, and neither
  binary is code-signed, so there is nothing downstream to catch a swap. The release list
  is read over TLS from `api.github.com`, and `UpdateCheck.IsTrustedAssetUrl` then requires
  the asset URL itself to be HTTPS on `github.com` or `*.githubusercontent.com`; an asset
  pointing anywhere else is treated as no asset at all. Checked again immediately before
  the swap, since that is the method that overwrites the exe.
- **The phone re-checks a link's scheme before opening it**, rather than trusting the
  `kind: "link"` the PC put on the share. It is the mirror of the `ShellExecute` rule
  above: handing the system a `intent://` or a custom scheme is a launch into another app,
  not a page, so a share only reaches `ACTION_VIEW` through `CastUrl.normalize`, which
  allows `http`/`https` and nothing else.
- **The Android app is excluded from backup and device transfer** (`allowBackup="false"`
  plus `data_extraction_rules.xml`, since API 31+ reads the latter). The pairing token
  lives in the app's DataStore, and holding it is what the first line of this file says it
  is — Auto Backup would put it in Google Drive and restore it onto a different phone.
  Re-pairing is a QR scan, so nothing here is worth carrying across a device swap.
- **Request logging is turned down in code, not only in `appsettings.json`.** The shipped
  build is a single `.exe` and that file is not published beside it, so the setting there
  applies to development only. What it suppresses is Hosting's per-request line, which
  prints the full URL — and the streaming endpoints carry `?token=` because an `<img>` tag
  cannot set a header.
