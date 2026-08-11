using PortalRemote.Auth;
using PortalRemote.Config;

namespace PortalRemote.Files;

public sealed record FileEntry(string Name, bool IsDir, long Size, DateTimeOffset Modified);

/// <summary>
/// `/files/*` — browse, download and upload within the configured share root.
/// Every path comes from the client, so every handler resolves it through
/// <see cref="FilePaths.ResolveSafe"/> before touching the filesystem.
/// </summary>
public static class FilesEndpoints
{
    public static void MapFilesEndpoints(this WebApplication app, ServerConfig config)
    {
        var group = app.MapGroup("/files").AddEndpointFilter(new TokenAuth.RequireTokenFilter(config));

        group.MapGet("/list", (string? path) =>
        {
            string dir;
            try { dir = FilePaths.ResolveSafe(config.ResolvedShareRoot(), path); }
            catch (PathTraversalException ex) { return Results.BadRequest(new { error = ex.Message }); }

            if (!Directory.Exists(dir))
                return Results.NotFound(new { error = "directory not found" });

            var entries = new List<FileEntry>();
            foreach (var d in Directory.EnumerateDirectories(dir))
            {
                var info = new DirectoryInfo(d);
                entries.Add(new FileEntry(info.Name, true, 0, info.LastWriteTimeUtc));
            }
            foreach (var f in Directory.EnumerateFiles(dir))
            {
                var info = new FileInfo(f);
                entries.Add(new FileEntry(info.Name, false, info.Length, info.LastWriteTimeUtc));
            }

            var ordered = entries
                .OrderByDescending(e => e.IsDir)
                .ThenBy(e => e.Name, StringComparer.OrdinalIgnoreCase);
            return Results.Ok(ordered);
        });

        group.MapGet("/download", (string path) =>
        {
            string full;
            try { full = FilePaths.ResolveSafe(config.ResolvedShareRoot(), path); }
            catch (PathTraversalException ex) { return Results.BadRequest(new { error = ex.Message }); }

            if (!File.Exists(full))
                return Results.NotFound(new { error = "file not found" });

            // Range processing lets the client resume/seek large transfers for free.
            return Results.File(full, "application/octet-stream", Path.GetFileName(full), enableRangeProcessing: true);
        });

        group.MapPost("/upload", async (HttpRequest request, string? path) =>
        {
            if (!request.HasFormContentType)
                return Results.BadRequest(new { error = "expected multipart/form-data" });

            string dir;
            try { dir = FilePaths.ResolveSafe(config.ResolvedShareRoot(), path); }
            catch (PathTraversalException ex) { return Results.BadRequest(new { error = ex.Message }); }

            Directory.CreateDirectory(dir);

            var form = await request.ReadFormAsync();
            if (form.Files.Count == 0)
                return Results.BadRequest(new { error = "no files in upload" });

            var saved = new List<string>();
            foreach (var file in form.Files)
            {
                var safeName = FilePaths.SafeFileName(file.FileName);
                if (string.IsNullOrWhiteSpace(safeName)) continue;

                var destPath = Path.Combine(dir, safeName);
                await using var stream = File.Create(destPath);
                await file.CopyToAsync(stream);
                saved.Add(safeName);
            }
            return Results.Ok(new { saved });
        });
    }
}
