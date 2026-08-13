using System.Runtime.InteropServices;

namespace PortalRemote.Audio;

/// <summary>
/// The PC's own master output volume: where it's at, and a way to set it exactly rather
/// than only nudge it. <see cref="Input.WinInput"/>'s VK_VOLUME_UP/DOWN/MUTE keys already
/// cover "up a bit"/"down a bit"/"mute" — this is for the slider that shows where the
/// level actually is and can jump straight to a value.
///
/// Same shape as <see cref="LoopbackCapture"/>: raw COM interop against the same default
/// render endpoint rather than a NuGet audio library — <c>IAudioEndpointVolume</c> is one
/// interface and a handful of methods, not a reason to add a dependency. Opened fresh per
/// call rather than held, since this is asked for on a button press, not a stream, and the
/// default endpoint can change (headphones plugged in) between one call and the next.
///
/// No change-notification callback (<c>IAudioEndpointVolumeCallback</c>). Every path that
/// can change the level here — the slider, the media keys — already reads the result back
/// and reports it; catching a volume nudged from somewhere this app didn't cause (the
/// hardware mixer, another app) is a different feature.
/// ponytail: a volume changed elsewhere won't echo to the phone until this next reads it —
/// wire the callback if that turns out to matter.
/// </summary>
public sealed class SystemVolume
{
    public static readonly SystemVolume Instance = new();

    private static readonly Guid MMDeviceEnumeratorClsid = new("BCDE0395-E52F-467C-8E3D-C4579291692E");

    private const int Render = 0;
    private const int Console = 0;
    private const int ClsCtxAll = 23;

    /// <summary>Raised whenever <see cref="Snapshot"/> would answer differently — wired
    /// in Program.cs, exactly like <c>PowerTimer.Changed</c>, so every paired phone's
    /// slider stays where the last one left it rather than only the one that moved it.</summary>
    public event Action<object>? Changed;

    private SystemVolume() { }

    /// <summary>Current level (0..1) and mute state, read fresh from the device. Null
    /// fields when this machine has no audio output device to ask — same shape as
    /// <c>ServerHello.mac</c>: absent rather than a fake zero.</summary>
    public object Snapshot()
    {
        try
        {
            var (level, muted) = Read();
            return new { t = "volume", level = (float?)level, muted = (bool?)muted };
        }
        catch (InvalidOperationException)
        {
            return new { t = "volume", level = (float?)null, muted = (bool?)null };
        }
    }

    /// <summary>Sets the level to exactly <paramref name="level"/> (0..1, clamped — a
    /// stray float from a bad drag is cheaper to clamp than to reject) and reports the
    /// result to every paired phone.</summary>
    public void Set(float level)
    {
        try
        {
            Write(Math.Clamp(level, 0f, 1f));
        }
        catch (InvalidOperationException)
        {
            // No device to set; Snapshot below reports the same "nothing to show" it
            // would have anyway.
        }
        Changed?.Invoke(Snapshot());
    }

    /// <summary>Re-reads and reports — for the media keys' vol_up/vol_down/mute, which
    /// change the level through a synthetic keypress rather than through this class.</summary>
    public void Refresh() => Changed?.Invoke(Snapshot());

    private (float Level, bool Muted) Read()
    {
        var volume = Open();
        try
        {
            Check(volume.GetMasterVolumeLevelScalar(out var level), "read the system volume");
            Check(volume.GetMute(out var muted), "read the system mute state");
            return (level, muted != 0);
        }
        finally
        {
            Marshal.ReleaseComObject(volume);
        }
    }

    private void Write(float level)
    {
        var volume = Open();
        try
        {
            Check(volume.SetMasterVolumeLevelScalar(level, 0), "set the system volume");
        }
        finally
        {
            Marshal.ReleaseComObject(volume);
        }
    }

    private static IAudioEndpointVolume Open()
    {
        var type = Type.GetTypeFromCLSID(MMDeviceEnumeratorClsid)
            ?? throw new InvalidOperationException("Windows Core Audio is not available on this machine.");
        var enumerator = (IMMDeviceEnumerator)Activator.CreateInstance(type)!;
        try
        {
            Check(enumerator.GetDefaultAudioEndpoint(Render, Console, out var device),
                "find an audio output device");
            try
            {
                Check(device.Activate(typeof(IAudioEndpointVolume).GUID, ClsCtxAll, 0, out var volumeObject),
                    "open the volume control");
                return (IAudioEndpointVolume)volumeObject;
            }
            finally
            {
                Marshal.ReleaseComObject(device);
            }
        }
        finally
        {
            Marshal.ReleaseComObject(enumerator);
        }
    }

    private static void Check(int hr, string what)
    {
        if (hr < 0)
            throw new InvalidOperationException(
                $"Could not {what} (0x{hr:X8}).", Marshal.GetExceptionForHR(hr));
    }

    // Same two interfaces LoopbackCapture.cs declares, for the same purpose (the default
    // render endpoint) — kept local rather than shared, since each file's use of them is
    // small enough that a shared header would be more indirection than the duplication.

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

    // Only the methods actually called are declared, but they must be declared in vtable
    // order and none before them may be skipped — hence the unused leading entries, same
    // rule LoopbackCapture's interfaces follow.
    [ComImport, Guid("5CDF2C82-841E-4546-9722-0CF74078229A"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioEndpointVolume
    {
        [PreserveSig] int RegisterControlChangeNotify(nint notify);
        [PreserveSig] int UnregisterControlChangeNotify(nint notify);
        [PreserveSig] int GetChannelCount(out int channelCount);
        [PreserveSig] int SetMasterVolumeLevel(float levelDb, nint eventContext);
        [PreserveSig] int SetMasterVolumeLevelScalar(float level, nint eventContext);
        [PreserveSig] int GetMasterVolumeLevel(out float levelDb);
        [PreserveSig] int GetMasterVolumeLevelScalar(out float level);
        [PreserveSig] int SetChannelVolumeLevel(int channel, float levelDb, nint eventContext);
        [PreserveSig] int SetChannelVolumeLevelScalar(int channel, float level, nint eventContext);
        [PreserveSig] int GetChannelVolumeLevel(int channel, out float levelDb);
        [PreserveSig] int GetChannelVolumeLevelScalar(int channel, out float level);
        [PreserveSig] int SetMute(int mute, nint eventContext);
        [PreserveSig] int GetMute(out int mute);
    }
}
