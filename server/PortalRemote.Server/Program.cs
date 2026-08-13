using System.Net.Sockets;
using System.Windows.Forms;
using PortalRemote.Ai;
using PortalRemote.Auth;
using PortalRemote.Cast;
using PortalRemote.Config;
using PortalRemote.Control;
using PortalRemote.Dlna;
using PortalRemote.Files;
using PortalRemote.Input;
using PortalRemote.Media;
using PortalRemote.Mirror;
using PortalRemote.Pairing;
using PortalRemote.Share;
using PortalRemote.Tray;

namespace PortalRemote;

internal static class Program
{
    /// <summary>
    /// STA is required for the WinForms tray icon and clipboard. Kestrel runs on
    /// background threads while the message loop owns the main thread.
    /// </summary>
    [STAThread]
    private static int Main(string[] args)
    {
        // Before anything else: this is the elevated copy of ourselves, launched
        // only to write a firewall rule. No tray, no server, no message loop.
        if (args.Contains(Reachability.InstallFirewallArg, StringComparer.OrdinalIgnoreCase))
            return Reachability.InstallFirewallRule();

        ApplicationConfiguration.Initialize();

        var config = ServerConfig.Load();
        var connectionState = new ConnectionState();
        var share = new ShareHub(config);
        using var approval = new PairApproval(config);

        // Every state change on the PC's media session goes straight out to whoever
        // is connected. Nothing is buffered for phones that aren't: what's playing is
        // only interesting live, and a phone gets the current state on connect anyway.
        using var nowPlaying = new NowPlaying();
        nowPlaying.Changed += payload =>
        {
            if (share.HasClients) _ = share.BroadcastAsync(payload);
        };

        // A cast receiver reports its own position at 1 Hz; forwarding that is what
        // turns the phone's blind transport buttons into a scrub bar. Same
        // fire-and-forget shape as above — a phone that isn't listening isn't owed
        // the playhead it missed.
        CastHub.Instance.Changed += payload =>
        {
            if (share.HasClients) _ = share.BroadcastAsync(payload);
        };

        // mpv reports through the same hub, so the line above covers it too.
        MpvPlayer.Instance.ConfiguredPath = config.MpvPath;

        // A LAN scan takes seconds, so the phone is answered from the cache and told
        // again when the Rokus and TVs turn up — otherwise the picker is a list that
        // silently grew after the user stopped looking at it.
        CastRouter.TargetsChanged += payload =>
        {
            if (share.HasClients) _ = share.BroadcastAsync(payload);
        };

        // The assistant's backend is a separate app the user starts independently, so
        // "not running" is the normal case and the phone is told rather than left to
        // find out by failing a request — docs/phase7-assistant.md §4.
        using var ai = new AiHealth(config.AgentPlatform);
        ai.Changed += payload =>
        {
            if (share.HasClients) _ = share.BroadcastAsync(payload);
        };

        // The acting half — step 7c. Registers what this PC can do the first time
        // somebody asks for something, not at startup: the backend is usually not up
        // yet, and a guaranteed-failed request every launch is not a registration.
        using var aiActions = new AiActions(config.AgentPlatform);

        // Built before the app so the endpoints can be mapped against it; it doesn't
        // touch the network until Start().
        using var dlna = new DlnaRenderer(config);

        // We answer our own M-SEARCH, so without this the PC discovers itself and offers
        // "cast to this PC" a second time by the long way round — and a transport command
        // sent to it recurses straight back into this router. Set unconditionally: the
        // renderer can be switched on in config between runs, and a stale id here would
        // filter nothing.
        CastRouter.OwnRendererUuid = dlna.Uuid;

        var app = BuildApp(config, args, connectionState, approval, share, nowPlaying, ai, aiActions, dlna);

        try
        {
            // Start synchronously so a port conflict surfaces before the tray appears.
            app.StartAsync().GetAwaiter().GetResult();
        }
        catch (IOException ex) when (ex.InnerException is SocketException)
        {
            var message = $"Could not listen on port {config.Port}.\n\n"
                        + "Another program is probably already using it. Change \"Port\" in:\n"
                        + ServerConfig.DefaultConfigPath;
            Console.Error.WriteLine(message);
            MessageBox.Show(message, ServerInfo.Name, MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }

        // Checked once and reused: it shells out to netsh, and the answer can't
        // change between here and the balloon a few lines below.
        var warning = Reachability.StartupWarning();
        PrintStartupBanner(config, warning);

        // Started after Kestrel: if the HTTP port was taken we've already bailed
        // out above, and there's no point advertising a server that isn't up.
        using var discovery = new DiscoveryResponder(config);
        discovery.Start();

        // Same ordering rule as discovery: nothing should advertise a renderer whose
        // HTTP half isn't listening. Said out loud because it is off by default and,
        // unlike everything else here, unauthenticated — see ServerConfig.
        if (config.EnableDlnaRenderer)
        {
            dlna.Start();
            Console.WriteLine("  DLNA renderer on: any app on this network can cast to this PC.");
        }

        // Not awaited: attaching to the media session is a nice-to-have, and the tray
        // should appear whether or not this machine has one.
        _ = nowPlaying.StartAsync();

        using var tray = new TrayIcon(config, connectionState, approval, share, onExit: Application.ExitThread);

        // Nothing has ever paired with this PC, so the QR code is the only useful
        // next step — show it rather than leaving a new user hunting the tray.
        // A phone that cannot reach this PC has no way to say why, so the PC says it
        // instead — at the moment the user is most likely to be about to try.
        if (config.IsFirstRun) tray.ShowWindow();
        else if (warning is not null) tray.Notify(ServerInfo.Name, warning);
        else tray.Notify(ServerInfo.Name, $"Listening on {PairingService.HttpBase(config)}");

        Application.Run();

        // A player window we launched and can no longer be reached to control is an
        // orphan; closing it is the last thing this process owes the desktop.
        MpvPlayer.Instance.Quit();
        app.StopAsync(TimeSpan.FromSeconds(3)).GetAwaiter().GetResult();
        return 0;
    }

    private static WebApplication BuildApp(
        ServerConfig config, string[] args, ConnectionState connectionState, PairApproval approval, ShareHub share,
        NowPlaying nowPlaying, AiHealth ai, AiActions aiActions, DlnaRenderer dlna)
    {
        var builder = WebApplication.CreateBuilder(args);

        // Bind all interfaces: the phone connects over the LAN, not loopback.
        builder.WebHost.UseUrls($"http://0.0.0.0:{config.Port}");
        // File transfers can be large; the default ~28.6MB Kestrel cap would
        // truncate them. LAN-only + token auth already bounds who can hit this.
        builder.WebHost.ConfigureKestrel(o => o.Limits.MaxRequestBodySize = null);
        builder.Logging.AddSimpleConsole(o => o.SingleLine = true);
        builder.Services.AddSingleton(config);

        var app = builder.Build();
        app.UseWebSockets();

        app.MapGet("/health", () => Results.Ok(new { ok = true, version = ServerInfo.Version }));

        app.MapGet("/pair/info", () =>
        {
            var (width, height) = WinInput.ScreenSize();
            return Results.Ok(new
            {
                name = Environment.MachineName,
                version = ServerInfo.Version,
                screen = new { width, height },
                shareRoot = config.ResolvedShareRoot()
            });
        }).AddEndpointFilter(new TokenAuth.RequireTokenFilter(config));

        // Unauthenticated by design — it's how a phone that has never seen this PC
        // *gets* the token. The gate is the Allow dialog PairApproval puts on this
        // screen, not a header. Left open until the user answers, so the phone can
        // show "waiting for approval" rather than poll.
        app.MapPost("/pair/request", async (HttpContext http, PairRequestBody? body) =>
        {
            var remoteIp = http.Connection.RemoteIpAddress?.ToString() ?? "unknown address";
            var token = await approval.RequestTokenAsync(body?.Device, remoteIp);
            return token is null
                ? Results.StatusCode(StatusCodes.Status403Forbidden)
                : Results.Ok(new { token, name = Environment.MachineName, port = config.RunningPort });
        });

        app.MapControlEndpoint(config, connectionState, share, nowPlaying, ai, aiActions);
        app.MapFilesEndpoints(config);
        app.MapScreenEndpoints(config);
        app.MapShareEndpoints(config, share);
        app.MapCastEndpoints(config);
        app.MapMediaEndpoints(config, nowPlaying);
        app.MapDlnaEndpoints(config, dlna);
        app.MapAiEndpoints(config, ai);
        app.MapAiModelEndpoints(config, ai);

        return app;
    }

    private static void PrintStartupBanner(ServerConfig config, string? warning)
    {
        var url = PairingService.HttpBase(config);
        Console.WriteLine();
        Console.WriteLine($"  {ServerInfo.Name} {ServerInfo.Version}");
        Console.WriteLine($"  Listening on {url}");
        Console.WriteLine($"  Config       {ServerConfig.DefaultConfigPath}");
        Console.WriteLine($"  Shared files {config.ResolvedShareRoot()}");
        // Open this on any other screen — a TV, a laptop, a console — and it becomes
        // a cast target. Also in the app window, since the console is only up when
        // the server was started from one.
        Console.WriteLine($"  Cast to a screen: {PairingService.ReceiverUrl(config)}");
        Console.WriteLine();
        Console.WriteLine("  Scan this with the Portal Remote app to pair:");
        Console.WriteLine();
        Console.WriteLine(PairingService.QrAscii(PairingService.PairUrl(config)));
        Console.WriteLine();
        if (warning is not null)
        {
            Console.WriteLine($"  {warning}");
            Console.WriteLine();
        }
    }
}
