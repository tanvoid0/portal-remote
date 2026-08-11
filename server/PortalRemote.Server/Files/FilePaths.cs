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
    /// Strips any directory component from an uploaded filename — defense in depth
    /// against a crafted multipart filename like "../../evil.exe".
    /// </summary>
    public static string SafeFileName(string fileName) => Path.GetFileName(fileName);
}
