using System.ComponentModel;
using System.Drawing;
using System.Runtime.InteropServices;

namespace PortalRemote.Input;

/// <summary>
/// Thin P/Invoke wrapper over the Win32 <c>SendInput</c> API. Everything here maps
/// 1:1 onto user32 calls; higher-level gestures live in <see cref="InputActions"/>.
/// </summary>
public static partial class WinInput
{
    private const int InputMouse = 0;
    private const int InputKeyboard = 1;

    private const uint MouseEventMove = 0x0001;
    private const uint MouseEventLeftDown = 0x0002;
    private const uint MouseEventLeftUp = 0x0004;
    private const uint MouseEventRightDown = 0x0008;
    private const uint MouseEventRightUp = 0x0010;
    private const uint MouseEventMiddleDown = 0x0020;
    private const uint MouseEventMiddleUp = 0x0040;
    private const uint MouseEventWheel = 0x0800;
    private const uint MouseEventHWheel = 0x1000;
    private const uint MouseEventAbsolute = 0x8000;
    private const uint MouseEventVirtualDesk = 0x4000;

    private const uint KeyEventExtended = 0x0001;
    private const uint KeyEventKeyUp = 0x0002;
    private const uint KeyEventUnicode = 0x0004;

    public const int WheelDelta = 120;

    private const int SmCxScreen = 0;
    private const int SmCyScreen = 1;
    private const int SmXVirtualScreen = 76;
    private const int SmYVirtualScreen = 77;
    private const int SmCxVirtualScreen = 78;
    private const int SmCyVirtualScreen = 79;

    /// <summary>Keys that must carry the extended-key flag or Windows misroutes them.</summary>
    private static readonly HashSet<ushort> ExtendedKeys =
    [
        0x21, 0x22, 0x23, 0x24,             // PgUp PgDn End Home
        0x25, 0x26, 0x27, 0x28,             // arrows
        0x2D, 0x2E,                         // Insert Delete
        0x5B, 0x5C, 0x5D,                   // LWin RWin Apps
        0x6F,                               // Numpad divide
        0x90,                               // NumLock
        0xA3, 0xA5,                         // RControl RMenu
        0xAD, 0xAE, 0xAF,                   // volume mute/down/up
        0xB0, 0xB1, 0xB2, 0xB3              // media next/prev/stop/play-pause
    ];

    [StructLayout(LayoutKind.Sequential)]
    private struct MouseInput
    {
        public int Dx;
        public int Dy;
        public uint MouseData;
        public uint Flags;
        public uint Time;
        public nuint ExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KeyboardInput
    {
        public ushort Vk;
        public ushort Scan;
        public uint Flags;
        public uint Time;
        public nuint ExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct HardwareInput
    {
        public uint Msg;
        public ushort ParamL;
        public ushort ParamH;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public MouseInput Mouse;
        [FieldOffset(0)] public KeyboardInput Keyboard;
        [FieldOffset(0)] public HardwareInput Hardware;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Input
    {
        public int Type;
        public InputUnion Union;
    }

    [LibraryImport("user32.dll", SetLastError = true)]
    private static partial uint SendInput(uint count, [In] Input[] inputs, int size);

    [LibraryImport("user32.dll")]
    private static partial int GetSystemMetrics(int index);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool GetCursorPos(out POINT point);

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT
    {
        public int X;
        public int Y;
    }

    public static (int Width, int Height) ScreenSize() =>
        (GetSystemMetrics(SmCxScreen), GetSystemMetrics(SmCyScreen));

    /// <summary>Bounding box spanning all monitors.</summary>
    public static (int X, int Y, int Width, int Height) VirtualScreen() =>
    (
        GetSystemMetrics(SmXVirtualScreen),
        GetSystemMetrics(SmYVirtualScreen),
        GetSystemMetrics(SmCxVirtualScreen),
        GetSystemMetrics(SmCyVirtualScreen)
    );

    /// <summary>One attached display. <see cref="Index"/> is what clients pass back
    /// as <c>mon</c>/<c>monitor</c> to pick this display.</summary>
    public sealed record DisplayInfo(int Index, string Name, bool Primary, Rectangle Bounds);

    /// <summary>Attached displays, in the order Windows reports them.</summary>
    public static IReadOnlyList<DisplayInfo> Displays() =>
        System.Windows.Forms.Screen.AllScreens
            .Select((s, i) => new DisplayInfo(i, s.DeviceName, s.Primary, s.Bounds))
            .ToList();

    /// <summary>
    /// Region a monitor index refers to: <c>null</c> means the primary display (the
    /// sane default — mirroring a multi-monitor desktop onto a phone produces an
    /// unreadable strip), a negative index means every monitor at once, and anything
    /// out of range falls back to the full virtual desktop rather than throwing.
    /// </summary>
    public static Rectangle BoundsFor(int? monitor)
    {
        var (vx, vy, vw, vh) = VirtualScreen();
        var all = new Rectangle(vx, vy, vw, vh);
        if (monitor < 0) return all;

        var displays = Displays();
        if (monitor is null)
            return displays.FirstOrDefault(d => d.Primary)?.Bounds ?? all;

        return monitor < displays.Count ? displays[monitor.Value].Bounds : all;
    }

    public static (int X, int Y) CursorPos() =>
        GetCursorPos(out var p) ? (p.X, p.Y) : (0, 0);

    /// <summary>Dispatch a batch of events atomically.</summary>
    private static void Send(params Input[] inputs)
    {
        if (inputs.Length == 0) return;
        var sent = SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<Input>());
        if (sent != inputs.Length)
            throw new Win32Exception(Marshal.GetLastWin32Error(), "SendInput was blocked or failed");
    }

    private static Input Mouse(int dx, int dy, uint data, uint flags) => new()
    {
        Type = InputMouse,
        Union = new InputUnion
        {
            Mouse = new MouseInput { Dx = dx, Dy = dy, MouseData = data, Flags = flags }
        }
    };

    private static Input Key(ushort vk, ushort scan, uint flags) => new()
    {
        Type = InputKeyboard,
        Union = new InputUnion
        {
            Keyboard = new KeyboardInput { Vk = vk, Scan = scan, Flags = flags }
        }
    };

    public static void MoveRelative(int dx, int dy)
    {
        if (dx != 0 || dy != 0) Send(Mouse(dx, dy, 0, MouseEventMove));
    }

    /// <summary>Move to an absolute virtual-desktop pixel coordinate.</summary>
    public static void MoveAbsolute(int x, int y)
    {
        var (vx, vy, vw, vh) = VirtualScreen();
        if (vw <= 1 || vh <= 1) return;

        // SendInput absolute coordinates are normalised across a 0..65535 range.
        var nx = Math.Clamp((int)Math.Round((x - vx) * 65535.0 / (vw - 1)), 0, 65535);
        var ny = Math.Clamp((int)Math.Round((y - vy) * 65535.0 / (vh - 1)), 0, 65535);
        Send(Mouse(nx, ny, 0, MouseEventMove | MouseEventAbsolute | MouseEventVirtualDesk));
    }

    /// <summary>
    /// Move to a point given as a 0..1 fraction of a monitor (see <see cref="BoundsFor"/>).
    /// The screen mirror sends taps this way: the phone knows where the finger landed
    /// within the mirrored image and which monitor it asked for, nothing else, so the
    /// desktop's pixel geometry stays here.
    /// </summary>
    public static void MoveAbsoluteNormalized(double fx, double fy, int? monitor = null)
    {
        var bounds = BoundsFor(monitor);
        MoveAbsolute(
            bounds.X + (int)Math.Round(Math.Clamp(fx, 0, 1) * (bounds.Width - 1)),
            bounds.Y + (int)Math.Round(Math.Clamp(fy, 0, 1) * (bounds.Height - 1)));
    }

    public static void MouseButton(string button, bool down)
    {
        var flag = (button, down) switch
        {
            ("left", true) => MouseEventLeftDown,
            ("left", false) => MouseEventLeftUp,
            ("right", true) => MouseEventRightDown,
            ("right", false) => MouseEventRightUp,
            ("middle", true) => MouseEventMiddleDown,
            ("middle", false) => MouseEventMiddleUp,
            _ => throw new ArgumentException($"unknown mouse button: {button}", nameof(button))
        };
        Send(Mouse(0, 0, 0, flag));
    }

    /// <summary>Wheel scroll. Units are wheel deltas; 120 == one notch.</summary>
    public static void Scroll(int dy = 0, int dx = 0)
    {
        var events = new List<Input>(2);
        if (dy != 0) events.Add(Mouse(0, 0, unchecked((uint)dy), MouseEventWheel));
        if (dx != 0) events.Add(Mouse(0, 0, unchecked((uint)dx), MouseEventHWheel));
        Send([.. events]);
    }

    public static void KeyEvent(ushort vk, bool down)
    {
        var flags = down ? 0u : KeyEventKeyUp;
        if (ExtendedKeys.Contains(vk)) flags |= KeyEventExtended;
        Send(Key(vk, 0, flags));
    }

    public static void Tap(ushort vk)
    {
        KeyEvent(vk, true);
        KeyEvent(vk, false);
    }

    /// <summary>
    /// Inject a string as unicode, independent of the active keyboard layout.
    /// Surrogate pairs are sent as-is, so emoji and astral characters work.
    /// </summary>
    public static void TypeText(string text)
    {
        if (string.IsNullOrEmpty(text)) return;

        var batch = new List<Input>(128);
        foreach (var unit in text)
        {
            batch.Add(Key(0, unit, KeyEventUnicode));
            batch.Add(Key(0, unit, KeyEventUnicode | KeyEventKeyUp));
            if (batch.Count < 128) continue;
            Send([.. batch]);
            batch.Clear();
        }
        Send([.. batch]);
    }

    // --- Touch injection -----------------------------------------------------------
    //
    // A separate API from everything above: SendInput's mouse events are what every
    // other message on this socket drives, but they only ever look like a mouse to
    // the app on the other end. InjectTouchInput is Windows' actual touch-digitizer
    // path (Win8+) — an app that only responds to WM_POINTER/WM_TOUCH, or that does
    // its own native pinch/rotate handling, only sees a real finger through this.

    public enum TouchPhase { Down, Move, Up }

    /// <summary>One finger's state for a single injected frame. [Id] is a slot
    /// 0..9 the caller owns for the finger's whole contact — Windows identifies a
    /// touch by this id across Down/Move/Up, not by anything Android hands us.</summary>
    public readonly record struct TouchContact(uint Id, double Nx, double Ny, TouchPhase Phase);

    /// <summary>Concurrent contacts requested from Windows. 10 matches every touch
    /// digitizer Windows ships drivers for; nothing in this app needs more fingers
    /// than that.</summary>
    private const uint MaxTouchContacts = 10;

    /// <summary>Shows the system's own touch ripple at each injected point — the
    /// same visual feedback a real finger gets, so an injected touch isn't silently
    /// indistinguishable from a stuck digitizer if something goes wrong.</summary>
    private const uint TouchFeedbackIndirect = 0x2;

    private const int PointerTypeTouch = 0x00000002;

    private const uint PointerFlagInRange = 0x00000002;
    private const uint PointerFlagInContact = 0x00000004;
    private const uint PointerFlagDown = 0x00010000;
    private const uint PointerFlagUpdate = 0x00020000;
    private const uint PointerFlagUp = 0x00040000;

    private const uint TouchMaskContactArea = 0x00000004;

    /// <summary>Half-width/height of the synthetic contact ellipse, in px. A real
    /// fingertip covers a handful of pixels, not a mathematical point, and a touch-
    /// aware app is entitled to read <c>rcContact</c> to size its hit target.</summary>
    private const int TouchContactRadius = 5;

    [StructLayout(LayoutKind.Sequential)]
    private struct PointerInfo
    {
        public int PointerType;
        public uint PointerId;
        public uint FrameId;
        public uint PointerFlags;
        public nint SourceDevice;
        public nint HwndTarget;
        public POINT PtPixelLocation;
        public POINT PtPixelLocationRaw;
        public POINT PtHimetricLocation;
        public POINT PtHimetricLocationRaw;
        public uint Time;
        public uint HistoryCount;
        public int InputData;
        public uint KeyStates;
        public ulong PerformanceCount;
        public int ButtonChangeType;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct RECT
    {
        public int Left, Top, Right, Bottom;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct PointerTouchInfo
    {
        public PointerInfo PointerInfo;
        public uint TouchFlags;
        public uint TouchMask;
        public RECT RcContact;
        public RECT RcContactRaw;
        public uint Orientation;
        public uint Pressure;
    }

    [LibraryImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool InitializeTouchInjection(uint maxContacts, uint dwMode);

    [LibraryImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool InjectTouchInput(uint count, [In] PointerTouchInfo[] contacts);

    private static bool _touchInjectionReady;
    private static readonly object TouchInitLock = new();

    /// <summary>Per-process, and only once: a second call is a no-op on Windows, but
    /// there is no reason to pay the call on every touch frame.</summary>
    private static void EnsureTouchInjectionInitialized()
    {
        if (_touchInjectionReady) return;
        lock (TouchInitLock)
        {
            if (_touchInjectionReady) return;
            if (!InitializeTouchInjection(MaxTouchContacts, TouchFeedbackIndirect))
                throw new Win32Exception(Marshal.GetLastWin32Error(), "InitializeTouchInjection failed");
            _touchInjectionReady = true;
        }
    }

    /// <summary>
    /// Inject one frame of real touch input — Windows' touch-digitizer path, not a
    /// mouse pretending to be one. [contacts] should carry every finger still down,
    /// not just the one that changed, the same way a real digitizer reports a frame.
    /// </summary>
    public static void Touch(int? monitor, IReadOnlyList<TouchContact> contacts)
    {
        if (contacts.Count == 0) return;
        EnsureTouchInjectionInitialized();

        var bounds = BoundsFor(monitor);
        var infos = new PointerTouchInfo[contacts.Count];
        for (var i = 0; i < contacts.Count; i++)
        {
            var c = contacts[i];
            var x = bounds.X + (int)Math.Round(Math.Clamp(c.Nx, 0, 1) * (bounds.Width - 1));
            var y = bounds.Y + (int)Math.Round(Math.Clamp(c.Ny, 0, 1) * (bounds.Height - 1));
            var flags = c.Phase switch
            {
                TouchPhase.Down => PointerFlagDown | PointerFlagInRange | PointerFlagInContact,
                TouchPhase.Move => PointerFlagUpdate | PointerFlagInRange | PointerFlagInContact,
                TouchPhase.Up => PointerFlagUp,
                _ => throw new ArgumentOutOfRangeException(nameof(contacts), c.Phase, "unknown touch phase")
            };

            infos[i] = new PointerTouchInfo
            {
                PointerInfo = new PointerInfo
                {
                    PointerType = PointerTypeTouch,
                    PointerId = c.Id,
                    PointerFlags = flags,
                    PtPixelLocation = new POINT { X = x, Y = y },
                },
                TouchFlags = 0,
                TouchMask = TouchMaskContactArea,
                RcContact = new RECT
                {
                    Left = x - TouchContactRadius, Top = y - TouchContactRadius,
                    Right = x + TouchContactRadius, Bottom = y + TouchContactRadius,
                },
            };
        }

        if (!InjectTouchInput((uint)infos.Length, infos))
            throw new Win32Exception(Marshal.GetLastWin32Error(), "InjectTouchInput failed");
    }
}
