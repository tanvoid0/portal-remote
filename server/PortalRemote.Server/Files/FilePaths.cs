namespace PortalRemote.Files;

/// <summary>Thrown when a client-supplied path would resolve outside the share root.</summary>
public sealed class PathTraversalException(string message) : Exception(message);

/// <summary>Resolves client-supplied relative paths against the share root, safely.</summary>
public static class FilePaths
{
    /// <summary>
    /// Resolves <paramref name="relativePath"/> against <paramref name="shareRoot"/>,
    /// rejecting anything that would escape it via "..", a drive-rooted path, or a
    /// UNC path. The check is done on the fully-normalized result, not the raw
    /// string, so "a/../../b" style tricks collapse correctly before comparison.
    /// </summary>
    public static string ResolveSafe(string shareRoot, string? relativePath)
    {
        var root = Path.GetFullPath(shareRoot);
        if (string.IsNullOrEmpty(relativePath)) return root;

        if (Path.IsPathRooted(relativePath) || relativePath.Contains(':'))
            throw new PathTraversalException($"absolute paths are not allowed: {relativePath}");

        var candidate = Path.GetFullPath(Path.Combine(root, relativePath.Replace('\\', '/')));

        var rootWithSep = root.EndsWith(Path.DirectorySeparatorChar) ? root : root + Path.DirectorySeparatorChar;
        if (!candidate.Equals(root, StringComparison.OrdinalIgnoreCase) &&
            !candidate.StartsWith(rootWithSep, StringComparison.OrdinalIgnoreCase))
        {
            throw new PathTraversalException($"path escapes the shared folder: {relativePath}");
        }
        return candidate;
    }

    /// <summary>
    /// Reduces a client-supplied filename to something that can only ever name one
    /// ordinary file inside the folder it is combined with — defense in depth against a
    /// crafted multipart filename like "../../evil.exe".
    ///
    /// <see cref="Path.GetFileName(string)"/> alone is not that.  It strips the directory
    /// component and stops: it deliberately keeps an NTFS stream suffix ("notes.txt:hidden",
    /// which <c>File.Create</c> writes as an alternate data stream nothing then lists), it
    /// leaves quotes in place (the tray's "reveal in folder" puts this name on
    /// <c>explorer.exe</c>'s argument line), and it has no opinion on the names Windows
    /// reserves for devices.  Every caller here is handling a name chosen by the other end
    /// of the wire, so the rule lives in one place rather than at each of them.
    ///
    /// Returns an empty string when nothing usable is left; callers either skip the file
    /// or substitute their own name.
    /// </summary>
    public static string SafeFileName(string fileName)
    {
        var name = Path.GetFileName(fileName);
        foreach (var invalid in Path.GetInvalidFileNameChars())
            name = name.Replace(invalid, '_');

        // Windows trims these on the way to the filesystem, so without doing it here the
        // name that gets created is not the name that was checked.
        name = name.TrimEnd('.', ' ');

        // "." and ".." are directories, not names.
        if (name is "" or "." or "..") return string.Empty;

        // CON, NUL, COM1… are devices in every directory, so File.Create on one opens the
        // device rather than a file. Prefixed rather than rejected: the name is still what
        // the user sent, and a share that vanishes is worse than one called _NUL.
        return ReservedDeviceNames.Contains(Path.GetFileNameWithoutExtension(name))
            ? "_" + name
            : name;
    }

    private static readonly HashSet<string> ReservedDeviceNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    };
}
