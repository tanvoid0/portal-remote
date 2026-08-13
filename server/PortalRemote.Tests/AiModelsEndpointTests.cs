using System.Text.Json;
using PortalRemote.Ai;
using Xunit;

namespace PortalRemote.Tests;

/// <summary>
/// <see cref="AiModelsEndpoint.BuildCatalog"/> — the pure half of letting the phone switch
/// providers and models (<c>docs/phase7-assistant.md</c> §11.2): agent-platform's two
/// catalogue responses in, the picker's shape out, no HTTP involved.
/// </summary>
public class AiModelsEndpointTests
{
    private static JsonElement Parse(string json) => JsonDocument.Parse(json).RootElement;

    private const string Capabilities = """
        {"providers":{
          "ollama":{"chat":true,"configured":true},
          "gemini":{"chat":true,"configured":false},
          "aimlapi":{"chat":false,"configured":true}
        }}
        """;

    private const string Models = """
        {"data":[
          {"id":"llama3.1:8b","object":"model","owned_by":"ollama"},
          {"id":"gemini-2.0-flash","object":"model","owned_by":"gemini"},
          {"id":"some-embed-model","object":"model","owned_by":"aimlapi"}
        ]}
        """;

    [Fact]
    public void KeepsOnlyChatCapableProvidersAndCarriesWhetherEachIsConfigured()
    {
        var catalog = AiModelsEndpoint.BuildCatalog(Parse(Capabilities), Parse(Models), "ollama", "llama3.1:8b");

        Assert.Equal(
            new[] { ("gemini", false), ("ollama", true) },
            catalog.Providers.Select(p => (p.Id, p.Configured)));
    }

    [Fact]
    public void DropsModelsOwnedByANonChatProviderEvenIfItsConfigured()
    {
        // aimlapi is configured but chat:false — its embeddings-only model must not show
        // up in a chat model picker.
        var catalog = AiModelsEndpoint.BuildCatalog(Parse(Capabilities), Parse(Models), null, null);

        Assert.DoesNotContain(catalog.Models, m => m.Provider == "aimlapi");
    }

    [Fact]
    public void KeepsAnUnconfiguredProvidersModelsWithConfiguredFalseRatherThanHidingThem()
    {
        // Never hide, say why (docs/design-system.md §4.5) — the phone greys these out
        // with a reason instead of a list that silently shrinks.
        var catalog = AiModelsEndpoint.BuildCatalog(Parse(Capabilities), Parse(Models), null, null);

        var gemini = Assert.Single(catalog.Models, m => m.Provider == "gemini");
        Assert.Equal("gemini-2.0-flash", gemini.Id);
        Assert.False(gemini.Configured);
    }

    [Fact]
    public void CarriesTheCurrentSelectionThrough() =>
        Assert.Equal(
            new CurrentSelection("ollama", "llama3.1:8b"),
            AiModelsEndpoint.BuildCatalog(Parse(Capabilities), Parse(Models), "ollama", "llama3.1:8b").Current);

    [Fact]
    public void TreatsMissingCapabilitiesOrModelsAsEmptyRatherThanThrowing()
    {
        var catalog = AiModelsEndpoint.BuildCatalog(Parse("{}"), Parse("{}"), null, null);

        Assert.Empty(catalog.Providers);
        Assert.Empty(catalog.Models);
    }
}
