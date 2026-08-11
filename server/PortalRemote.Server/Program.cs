using System.Net.Sockets;
using System.Windows.Forms;
using PortalRemote.Auth;
using PortalRemote.Config;
using PortalRemote.Control;
using PortalRemote.Files;
using PortalRemote.Input;
using PortalRemote.Mirror;
using PortalRemote.Pairing;
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
        ApplicationConfiguration.Initialize();

        var config = ServerConfig.Load();
        var connectionState = new ConnectionState();
        var app = BuildApp(config, args, connectionState);

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

        PrintStartupBanner(config);

        using var tray = new TrayIcon(config, connectionState, onExit: Application.ExitThread);
        tray.Notify(ServerInfo.Name, $"Listening on {PairingService.HttpBase(config)}");

        Application.Run();

        app.StopAsync(TimeSpan.FromSeconds(3)).GetAwaiter().GetResult();
        return 0;
    }

    private static WebApplication BuildApp(ServerConfig config, string[] args, ConnectionState connectionState)
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

        app.MapControlEndpoint(config, connectionState);
        app.MapFilesEndpoints(config);
        app.MapScreenEndpoints(config);

        return app;
    }

    private static void PrintStartupBanner(ServerConfig config)
    {
        var url = PairingService.HttpBase(config);
        Console.WriteLine();
        Console.WriteLine($"  {ServerInfo.Name} {ServerInfo.Version}");
        Console.WriteLine($"  Listening on {url}");
        Console.WriteLine($"  Config       {ServerConfig.DefaultConfigPath}");
        Console.WriteLine($"  Shared files {config.ResolvedShareRoot()}");
        Console.WriteLine();
        Console.WriteLine("  Scan this with the Portal Remote app to pair:");
        Console.WriteLine();
        Console.WriteLine(PairingService.QrAscii(PairingService.PairUrl(config)));
        Console.WriteLine();
        Console.WriteLine("  If the phone cannot connect, allow this app through Windows");
        Console.WriteLine("  Firewall on Private networks.");
        Console.WriteLine();
    }
}
