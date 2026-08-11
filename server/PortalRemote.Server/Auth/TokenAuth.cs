using PortalRemote.Config;

namespace PortalRemote.Auth;

/// <summary>Shared-token check used by both the HTTP routes and the control socket.</summary>
public static class TokenAuth
{
    /// <summary>
    /// Pull the token from <c>?token=</c> or an <c>Authorization: Bearer</c> header.
    /// The query-string form exists because streaming and &lt;img&gt; requests on the
    /// client cannot attach custom headers.
    /// </summary>
    public static string? Extract(HttpContext context)
    {
        if (context.Request.Query.TryGetValue("token", out var q) && !string.IsNullOrEmpty(q))
            return q.ToString();

        var header = context.Request.Headers.Authorization.ToString();
        return header.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase)
            ? header["Bearer ".Length..].Trim()
            : null;
    }

    public static bool IsAuthorized(HttpContext context, ServerConfig config) =>
        config.CheckToken(Extract(context));

    /// <summary>
    /// Endpoint filter that rejects unauthenticated requests with a 401.
    /// Apply with <c>.AddEndpointFilter(new RequireTokenFilter())</c>.
    /// </summary>
    public sealed class RequireTokenFilter(ServerConfig config) : IEndpointFilter
    {
        public async ValueTask<object?> InvokeAsync(
            EndpointFilterInvocationContext context, EndpointFilterDelegate next)
        {
            if (!IsAuthorized(context.HttpContext, config))
                return Results.Problem("invalid or missing token", statusCode: StatusCodes.Status401Unauthorized);
            return await next(context);
        }
    }
}
