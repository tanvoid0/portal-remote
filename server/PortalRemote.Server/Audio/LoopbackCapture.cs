using System.Buffers.Binary;
using System.Runtime.InteropServices;

namespace PortalRemote.Audio;

/// <summary>
/// The PC's own output, captured as it plays. WASAPI <em>loopback</em>: a capture
/// client opened against the default render endpoint, which hands back exactly the mix
/// Windows just sent to the speakers.
///
/// Loopback is the one direction here that needs no driver. Making the phone appear in
/// Windows' own output list would mean a signed WDM audio driver — a different kind of
/// project, sketched in docs/phase8-audio.md §2. The consequence is visible to the user
/// and worth knowing up front: this <em>copies</em> the default output rather than
/// replacing it, so the PC's own speakers keep playing unless they are muted.
///
/// Raw COM interop rather than a NuGet audio library, for the same reason
/// <see cref="Mirror.ScreenCapture"/> is 40 lines of GDI: four interfaces and two
/// structs is less to own than a dependency, and everything past the constructor is
/// arithmetic.
/// </summary>
internal sealed class LoopbackCapture : IDisposable
{
    private static readonly Guid MMDeviceEnumeratorClsid = new("BCDE0395-E52F-467C-8E3D-C4579291692E");

    private const int Render = 0;            // EDataFlow.eRender
    private const int Console = 0;           // ERole.eConsole
    private const int SharedMode = 0;        // AUDCLNT_SHAREMODE_SHARED
    private const int Loopback = 0x00020000; // AUDCLNT_STREAMFLAGS_LOOPBACK
    private const int ClsCtxAll = 23;

    /// <summary>The packet's bytes are undefined and mean silence — see <see cref="Read"/>.</summary>
    private const int BufferSilent = 0x2;

    /// <summary>100-ns units: 200ms of endpoint buffer, ample for a 10ms poll.</summary>
    private const long BufferDuration = 2_000_000;

    private const int FormatPcm = 1;
    private const int FormatFloat = 3;
    private const int FormatExtensible = 0xFFFE;

    /// <summary>WAVEFORMATEX is 18 bytes; WAVEFORMATEXTENSIBLE's SubFormat GUID follows
    /// the 2-byte samples union and the 4-byte channel mask.</summary>
    private const int SubFormatOffset = 24;

    private readonly IMMDeviceEnumerator enumerator;
    private readonly IMMDevice device;
    private readonly IAudioClient client;
    private readonly IAudioCaptureClient capture;
    private readonly int sourceChannels;
    private readonly int sourceBits;
    private readonly bool sourceFloat;

    private byte[] buffer;
    private bool started;

    /// <summary>The device's own mix rate. Nothing here resamples — the phone is told
    /// what came off the card and configures its output to match, which is one whole
    /// class of code (and of artefacts) neither side has to have.</summary>
    public int SampleRate { get; }

    /// <summary>Channels as delivered by <see cref="Read"/>: never more than two.</summary>
    public int Channels { get; }

    /// <summary>Bytes per second of the stream this produces.</summary>
    public int ByteRate => SampleRate * Channels * 2;

    public LoopbackCapture()
    {
        var type = Type.GetTypeFromCLSID(MMDeviceEnumeratorClsid)
            ?? throw new InvalidOperationException("Windows Core Audio is not available on this machine.");
        enumerator = (IMMDeviceEnumerator)Activator.CreateInstance(type)!;

        Check(enumerator.GetDefaultAudioEndpoint(Render, Console, out device),
            "find an audio output device");

        Check(device.Activate(typeof(IAudioClient).GUID, ClsCtxAll, 0, out var clientObject),
            "open the audio output device");
        client = (IAudioClient)clientObject;

        Check(client.GetMixFormat(out var format), "read the device's mix format");
        try
        {
            var wave = Marshal.PtrToStructure<WaveFormatEx>(format);
            (sourceBits, sourceFloat) = Describe(wave, format);
            sourceChannels = wave.Channels;
            SampleRate = (int)wave.SamplesPerSec;
            // 5.1 and 7.1 exist and a phone cannot play them. The first two channels of
            // every WAVE layout are front left and right, so taking those is a downmix
            // that loses the centre and surrounds rather than one that folds them in —
            // ponytail: dialogue in the centre channel will sound quiet on a 5.1 desktop.
            // Proper fold-down is a matrix per layout; do it if anyone actually notices.
            Channels = Math.Min(sourceChannels, 2);

            Check(client.Initialize(SharedMode, Loopback, BufferDuration, 0, format, 0),
                "start loopback capture");
        }
        finally
        {
            // GetMixFormat allocates with CoTaskMemAlloc and the caller owns it. Freed
            // after Initialize, which is the last thing that reads it.
            Marshal.FreeCoTaskMem(format);
        }

        Check(client.GetService(typeof(IAudioCaptureClient).GUID, out var captureObject),
            "open the capture client");
        capture = (IAudioCaptureClient)captureObject;

        Check(client.GetBufferSize(out var bufferFrames), "size the capture buffer");
        buffer = new byte[bufferFrames * Channels * 2];

        Check(client.Start(), "start the audio device");
        started = true;
    }

    /// <summary>
    /// One packet of captured audio as little-endian 16-bit PCM, or an empty segment
    /// when the device has produced nothing since the last call — which is what silence
    /// looks like here: WASAPI stops delivering packets rather than delivering zeros.
    ///
    /// The returned segment points at a buffer this instance reuses, so it is only valid
    /// until the next call.
    /// </summary>
    public ArraySegment<byte> Read()
    {
        Check(capture.GetNextPacketSize(out var pending), "poll the capture buffer");
        if (pending == 0) return ArraySegment<byte>.Empty;

        Check(capture.GetBuffer(out var data, out var frames, out var flags, out _, out _),
            "read captured audio");
        try
        {
            if (frames == 0) return ArraySegment<byte>.Empty;

            var bytes = frames * Channels * 2;
            // A packet cannot exceed the endpoint buffer, so this should never fire —
            // but a wrong guess here is a buffer overrun, and growing is one line.
            if (bytes > buffer.Length) buffer = new byte[bytes];

            if ((flags & BufferSilent) != 0)
            {
                // The flag means the packet's contents are undefined, not that they are
                // zero. Copying it would stream whatever was last in that memory.
                Array.Clear(buffer, 0, bytes);
            }
            else
            {
                unsafe
                {
                    var source = new ReadOnlySpan<byte>((void*)data, frames * sourceChannels * (sourceBits / 8));
                    ToPcm16(source, frames, sourceChannels, sourceBits, sourceFloat, Channels, buffer);
                }
            }

            return new ArraySegment<byte>(buffer, 0, bytes);
        }
        finally
        {
            // Must be the frame count GetBuffer returned, not the count consumed.
            capture.ReleaseBuffer(frames);
        }
    }

    /// <summary>
    /// Convert one packet to little-endian 16-bit PCM, keeping the first
    /// <paramref name="outChannels"/> channels of each frame. Returns bytes written.
    ///
    /// Static and free of the COM plumbing above so the arithmetic — which is where a
    /// mistake becomes a burst of noise in someone's ear — can be tested without a
    /// sound card. See `AudioFormatTests`.
    /// </summary>
    internal static int ToPcm16(
        ReadOnlySpan<byte> source, int frames, int sourceChannels, int sourceBits, bool sourceFloat,
        int outChannels, Span<byte> destination)
    {
        var sampleBytes = sourceBits / 8;
        var frameBytes = sourceChannels * sampleBytes;
        var written = 0;

        for (var frame = 0; frame < frames; frame++)
        {
            for (var channel = 0; channel < outChannels; channel++)
            {
                var at = frame * frameBytes + channel * sampleBytes;
                var sample = sourceFloat
                    // Clamped, not wrapped: a mix can exceed ±1.0 (Windows mixes in float
                    // precisely so it can), and letting that wrap turns a loud passage
                    // into a burst of noise rather than clipping.
                    ? (short)(Math.Clamp(BinaryPrimitives.ReadSingleLittleEndian(source[at..]), -1f, 1f)
                        * short.MaxValue)
                    : BinaryPrimitives.ReadInt16LittleEndian(source[at..]);

                BinaryPrimitives.WriteInt16LittleEndian(destination[written..], sample);
                written += 2;
            }
        }

        return written;
    }

    private static (int Bits, bool IsFloat) Describe(WaveFormatEx wave, nint format)
    {
        var tag = wave.FormatTag == FormatExtensible ? SubFormatTag(format) : wave.FormatTag;
        return (tag, wave.BitsPerSample) switch
        {
            // What a shared-mode endpoint actually mixes at, on every machine seen so far.
            (FormatFloat, 32) => (32, true),
            (FormatPcm, 16) => (16, false),
            _ => throw new NotSupportedException(
                $"This PC mixes audio as {wave.BitsPerSample}-bit format 0x{tag:X4}, which this build cannot convert."),
        };
    }

    /// <summary>KSDATAFORMAT_SUBTYPE_* carry the plain format tag in the GUID's first
    /// field, so the extensible case reduces to the simple one.</summary>
    private static int SubFormatTag(nint format)
    {
        Span<byte> bytes = stackalloc byte[16];
        Marshal.PtrToStructure<Guid>(format + SubFormatOffset).TryWriteBytes(bytes);
        return BinaryPrimitives.ReadInt32LittleEndian(bytes);
    }

    private static void Check(int hr, string what)
    {
        if (hr < 0)
            throw new InvalidOperationException(
                $"Could not {what} (0x{hr:X8}).", Marshal.GetExceptionForHR(hr));
    }

    public void Dispose()
    {
        if (started)
        {
            client.Stop();
            started = false;
        }

        Marshal.ReleaseComObject(capture);
        Marshal.ReleaseComObject(client);
        Marshal.ReleaseComObject(device);
        Marshal.ReleaseComObject(enumerator);
    }

    [StructLayout(LayoutKind.Sequential, Pack = 1)]
    private struct WaveFormatEx
    {
        public ushort FormatTag;
        public ushort Channels;
        public uint SamplesPerSec;
        public uint AvgBytesPerSec;
        public ushort BlockAlign;
        public ushort BitsPerSample;
        public ushort Size;
    }

    // Only the methods actually called are declared, but they must be declared in vtable
    // order and none before them may be skipped — hence the unused leading entries.

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out nint collection);
        [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice
    {
        [PreserveSig] int Activate(in Guid iid, int clsCtx, nint activationParams,
            [MarshalAs(UnmanagedType.IUnknown)] out object instance);
    }

    [ComImport, Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioClient
    {
        [PreserveSig] int Initialize(int shareMode, int streamFlags, long bufferDuration,
            long periodicity, nint format, nint sessionGuid);
        [PreserveSig] int GetBufferSize(out int frames);
        [PreserveSig] int GetStreamLatency(out long latency);
        [PreserveSig] int GetCurrentPadding(out int frames);
        [PreserveSig] int IsFormatSupported(int shareMode, nint format, out nint closest);
        [PreserveSig] int GetMixFormat(out nint format);
        [PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
        [PreserveSig] int Start();
        [PreserveSig] int Stop();
        [PreserveSig] int Reset();
        [PreserveSig] int SetEventHandle(nint handle);
        [PreserveSig] int GetService(in Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }

    [ComImport, Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioCaptureClient
    {
        [PreserveSig] int GetBuffer(out nint data, out int frames, out int flags,
            out long devicePosition, out long qpcPosition);
        [PreserveSig] int ReleaseBuffer(int frames);
        [PreserveSig] int GetNextPacketSize(out int frames);
    }
}
