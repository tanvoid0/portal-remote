using System.Buffers.Binary;
using PortalRemote.Audio;
using Xunit;

namespace PortalRemote.Tests;

/// <summary>
/// The sample conversion behind `/audio/stream`. Split out of the COM plumbing for the
/// same reason as the touch parser: opening a loopback capture needs a real sound card,
/// and this arithmetic is where a mistake stops being visible in review — a sign error
/// or a wrapped sample is a burst of noise in someone's ear, at whatever volume they
/// had set for music.
/// </summary>
public class AudioFormatTests
{
    private static byte[] Floats(params float[] samples)
    {
        var bytes = new byte[samples.Length * 4];
        for (var i = 0; i < samples.Length; i++)
            BinaryPrimitives.WriteSingleLittleEndian(bytes.AsSpan(i * 4), samples[i]);
        return bytes;
    }

    private static short[] Shorts(byte[] pcm, int count)
    {
        var samples = new short[count / 2];
        for (var i = 0; i < samples.Length; i++)
            samples[i] = BinaryPrimitives.ReadInt16LittleEndian(pcm.AsSpan(i * 2));
        return samples;
    }

    [Fact]
    public void ConvertsStereoFloatToPcm16()
    {
        var source = Floats(0f, 1f, -1f, 0.5f);
        var destination = new byte[8];

        var written = LoopbackCapture.ToPcm16(
            source, frames: 2, sourceChannels: 2, sourceBits: 32, sourceFloat: true,
            outChannels: 2, destination);

        Assert.Equal(8, written);
        Assert.Equal(new short[] { 0, 32767, -32767, 16383 }, Shorts(destination, written));
    }

    /// <summary>Windows mixes in float precisely so it can exceed ±1.0. Wrapping that
    /// would turn the loudest moment of a track into white noise.</summary>
    [Fact]
    public void ClipsInsteadOfWrapping()
    {
        var source = Floats(4.2f, -3.9f);
        var destination = new byte[4];

        LoopbackCapture.ToPcm16(
            source, frames: 2, sourceChannels: 1, sourceBits: 32, sourceFloat: true,
            outChannels: 1, destination);

        Assert.Equal(new short[] { 32767, -32767 }, Shorts(destination, 4));
    }

    /// <summary>A 5.1 desktop: the first two channels of every WAVE layout are front
    /// left and right, and the rest are dropped rather than mixed in.</summary>
    [Fact]
    public void TakesTheFirstTwoChannelsOfASurroundFrame()
    {
        // One 6-channel frame: FL, FR, then centre/LFE/rear, all distinct.
        var source = Floats(0.25f, -0.25f, 1f, 1f, 1f, 1f);
        var destination = new byte[4];

        var written = LoopbackCapture.ToPcm16(
            source, frames: 1, sourceChannels: 6, sourceBits: 32, sourceFloat: true,
            outChannels: 2, destination);

        Assert.Equal(4, written);
        Assert.Equal(new short[] { 8191, -8191 }, Shorts(destination, written));
    }

    /// <summary>The rare endpoint that already mixes at 16-bit: copied, not scaled.</summary>
    [Fact]
    public void PassesThroughPcm16()
    {
        var source = new byte[4];
        BinaryPrimitives.WriteInt16LittleEndian(source, 1234);
        BinaryPrimitives.WriteInt16LittleEndian(source.AsSpan(2), -4321);
        var destination = new byte[4];

        LoopbackCapture.ToPcm16(
            source, frames: 1, sourceChannels: 2, sourceBits: 16, sourceFloat: false,
            outChannels: 2, destination);

        Assert.Equal(new short[] { 1234, -4321 }, Shorts(destination, 4));
    }
}
