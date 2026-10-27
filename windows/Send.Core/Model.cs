using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace Send.Core;

/// <summary>In = phone to PC, Out = PC to phone.</summary>
public enum Dir { In, Out }

public enum XState { Queued, Offered, Active, Done, Failed, Cancelled }

public sealed class XItem
{
    public int I { get; init; }
    public string Name { get; init; } = "";
    public string? Rel { get; init; }
    /// <summary>Local source file (outgoing only).</summary>
    public string? Path { get; init; }
    public long Size { get; init; }
    public bool Done { get; set; }
    public long Got;
}

/// <summary>One batch of files moving in one direction. Bound to the UI, so everything visible is a property.</summary>
public sealed class Xfer : INotifyPropertyChanged
{
    public string Tid { get; }
    public Dir Dir { get; }
    public string PeerName { get; }
    public IReadOnlyList<XItem> Items { get; }
    public long Total { get; }
    public DateTime Created { get; } = DateTime.Now;

    internal CancellationTokenSource Cts { get; } = new();
    internal TaskCompletionSource<bool> Answer { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
    internal long LastProgress = Environment.TickCount64;

    private long _bytes;
    private long _sampleAt, _sampleBytes;
    private XState _state = XState.Queued;
    private string? _error;
    private double _speed;

    public Xfer(string tid, Dir dir, string peerName, IReadOnlyList<XItem> items)
    {
        Tid = tid;
        Dir = dir;
        PeerName = peerName;
        Items = items;
        Total = items.Sum(i => i.Size);
    }

    public long Bytes => Interlocked.Read(ref _bytes);
    public double Speed => _speed;
    /// <summary>For received transfers: the first saved file, or the top folder; "Show" opens it.</summary>
    public string? OpenPath { get; internal set; }
    public DateTime Started { get; internal set; }
    public DateTime Ended { get; internal set; }
    public string? Error => _error;
    public XState State => _state;
    public int Count => Items.Count;

    public string Title => Items.Count == 1 ? Items[0].Name : $"{Items[0].Name} + {Items.Count - 1} more";
    public double Progress => _state == XState.Done ? 1 : Total > 0 ? Math.Min(1.0, (double)Bytes / Total) : 0;
    public bool IsActive => _state is XState.Active or XState.Offered or XState.Queued;
    public string SizeText => Total == 0 ? $"{Count} empty file{(Count == 1 ? "" : "s")}" : Util.Size(Total);
    public string SpeedText => _state == XState.Active && _speed > 0 ? $"{Util.Size((long)_speed)}/s" : "";

    public bool IsOut => Dir == Dir.Out;
    public bool CanShow => Dir == Dir.In && _state == XState.Done && OpenPath != null;

    /// <summary>The grey line under the title.</summary>
    public string Meta => _state switch
    {
        XState.Queued or XState.Offered => $"Waiting for {PeerName} to accept",
        XState.Active => $"{(int)(Progress * 100)}% of {Util.Size(Total)}" + (_speed > 0 ? $" · {Util.Size((long)_speed)}/s" : ""),
        XState.Done => $"{(Dir == Dir.Out ? "Sent" : "Received")} · {SizeText}" + (Ended > Started && Started != default ? $" · {(Ended - Started).TotalSeconds:0.0}s" : ""),
        XState.Failed => _error ?? "Failed",
        _ => "Cancelled",
    };

    public string StatusText => _state switch
    {
        XState.Queued or XState.Offered => "Waiting",
        XState.Active => $"{(int)(Progress * 100)}%",
        XState.Done => "Done",
        XState.Failed => _error ?? "Failed",
        _ => "Cancelled",
    };

    /// <summary>Average speed of a finished transfer.</summary>
    public double AvgSpeed
    {
        get
        {
            var s = (Ended - Started).TotalSeconds;
            return s > 0.05 ? Total / s : 0;
        }
    }

    internal void Add(long n)
    {
        Interlocked.Add(ref _bytes, n);
        LastProgress = Environment.TickCount64;
    }

    internal void SetState(XState s, string? err = null)
    {
        _state = s;
        _error = err;
        if (s is XState.Done or XState.Failed or XState.Cancelled)
        {
            Ended = DateTime.Now;
            _speed = 0;
            if (s == XState.Done) Interlocked.Exchange(ref _bytes, Total);
        }
        Raise(nameof(State)); Raise(nameof(StatusText)); Raise(nameof(Error)); Raise(nameof(IsActive));
        Raise(nameof(Progress)); Raise(nameof(Bytes)); Raise(nameof(SpeedText)); Raise(nameof(Meta)); Raise(nameof(CanShow));
    }

    /// <summary>Called ~5x/second by the agent: smooths the speed and tells the UI.</summary>
    internal void Tick(long nowMs)
    {
        long b = Bytes;
        if (_sampleAt == 0) { _sampleAt = nowMs; _sampleBytes = b; return; }
        long dt = nowMs - _sampleAt;
        if (dt < 400) return;
        double inst = (b - _sampleBytes) * 1000.0 / dt;
        _speed = _speed == 0 ? inst : _speed * 0.6 + inst * 0.4;
        _sampleAt = nowMs;
        _sampleBytes = b;
        Raise(nameof(Bytes)); Raise(nameof(Progress)); Raise(nameof(SpeedText)); Raise(nameof(StatusText)); Raise(nameof(Meta));
    }

    public event PropertyChangedEventHandler? PropertyChanged;
    private void Raise([CallerMemberName] string? n = null) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(n));
}

public enum Status { Searching, Pairing, Connecting, Connected }
