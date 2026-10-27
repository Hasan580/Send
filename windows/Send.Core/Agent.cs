using System.Collections.Concurrent;
using System.Net;
using System.Security.Authentication;
using System.Text.Json.Nodes;

namespace Send.Core;

/// <summary>
/// The whole PC side. It looks for a phone running Send (last known address first, then a scan of the
/// local network), pairs once (the phone asks its owner to tap Allow), and from then on reconnects by
/// itself whenever both apps are open. Transfers run over extra connections it opens on demand.
/// </summary>
public sealed class Agent : IDisposable
{
    private sealed class SessionCtx
    {
        public PhoneRec Rec { get; }
        public Conn Conn { get; }
        private readonly SemaphoreSlim _w = new(1, 1);

        public SessionCtx(PhoneRec rec, Conn conn)
        {
            Rec = rec;
            Conn = conn;
        }

        public async Task SendAsync(JsonObject o, CancellationToken ct = default)
        {
            await _w.WaitAsync(ct).ConfigureAwait(false);
            try { await Wire.WriteFrameAsync(Conn.Ssl, o, ct).ConfigureAwait(false); }
            finally { _w.Release(); }
        }
    }

    public Config Cfg { get; }
    public Status Status { get; private set; } = Status.Searching;
    public string? PhoneName { get; private set; }
    public string? PhoneIp { get; private set; }
    public string? PairCode { get; private set; }
    public string? Hint { get; private set; }
    public IReadOnlyList<Found> Nearby { get; private set; } = Array.Empty<Found>();
    public bool IsConnected => _current != null;

    public event Action? Changed;
    public event Action<Xfer>? XferAdded;
    public event Action<Xfer>? XferFinished;
    public event Action<string>? TextReceived;

    private readonly CancellationTokenSource _cts = new();
    private readonly List<Xfer> _xfers = new();
    private readonly Dictionary<string, DateTime> _declined = new();
    private volatile SessionCtx? _current;
    private TaskCompletionSource _wake = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private Task? _loop, _ticker;
    private string? _sticky;
    private DateTime _stickyUntil;

    public Agent(Config cfg) => Cfg = cfg;

    /// <summary>A message that should survive the next few status refreshes (e.g. "pairing was declined").</summary>
    private void Sticky(string msg, int seconds = 25)
    {
        _sticky = msg;
        _stickyUntil = DateTime.UtcNow.AddSeconds(seconds);
        Hint = msg;
    }

    private string? StickyHint() => _sticky != null && DateTime.UtcNow < _stickyUntil ? _sticky : null;

    public IReadOnlyList<Xfer> Xfers
    {
        get { lock (_xfers) return _xfers.ToArray(); }
    }

    public void Start()
    {
        _loop ??= Task.Run(() => LoopAsync(_cts.Token));
        _ticker ??= Task.Run(() => TickerAsync(_cts.Token));
    }

    public void Dispose()
    {
        _cts.Cancel();
        try { _current?.Conn.Dispose(); } catch { }
    }

    /// <summary>Look right now (used by the Retry button).</summary>
    public void Retry()
    {
        lock (_declined) _declined.Clear();
        _wake.TrySetResult();
    }

    private void Set(Status s, string? name = null, string? ip = null, string? hint = null, string? code = null)
    {
        Status = s;
        PhoneName = name;
        PhoneIp = ip;
        Hint = hint;
        PairCode = code;
        Changed?.Invoke();
    }

    private async Task Nap(int ms, CancellationToken ct)
    {
        var wake = _wake;
        await Task.WhenAny(Task.Delay(ms, ct), wake.Task).ConfigureAwait(false);
        if (wake.Task.IsCompleted) _wake = new(TaskCreationOptions.RunContinuationsAsynchronously);
        ct.ThrowIfCancellationRequested();
    }

    // ------------------------------------------------------------------ finding, pairing, connecting

    private async Task LoopAsync(CancellationToken ct)
    {
        int misses = 0;
        while (!ct.IsCancellationRequested)
        {
            try
            {
                Set(Status.Searching, hint: StickyHint() ?? (misses >= 3 ? "Open Send on your phone. Both devices need the same Wi-Fi or network, and VPN off on the phone." : null));

                // 1) a phone we already know, at the address we last saw it
                PhoneRec? rec = null;
                foreach (var p in Cfg.Phones.ToArray())
                {
                    if (!IPAddress.TryParse(p.Ip, out var ip)) continue;
                    var f = await Discovery.HelloAsync(ip, ct, 900).ConfigureAwait(false);
                    if (f != null && f.Id == p.Id) { rec = p; break; }
                }

                // 2) otherwise look around the network
                if (rec == null)
                {
                    var found = await Discovery.ScanAsync(Cfg.ManualIps, ct).ConfigureAwait(false);
                    Nearby = found;
                    foreach (var f in found)
                    {
                        var p = Cfg.Phones.FirstOrDefault(x => x.Id == f.Id);
                        if (p == null) continue;
                        p.Ip = f.Ip.ToString();
                        Cfg.Save();
                        rec = p;
                        break;
                    }

                    // 3) a phone we have never met: ask it to pair, but only if this PC has no phone yet
                    if (rec == null && Cfg.AutoPair && Cfg.Phones.Count == 0)
                    {
                        var target = found.FirstOrDefault(f => !RecentlyDeclined(f.Id));
                        if (target != null) rec = await PairAsync(target, ct).ConfigureAwait(false);
                    }
                }

                if (rec != null)
                {
                    misses = 0;
                    await RunSessionAsync(rec, ct).ConfigureAwait(false);
                    Nearby = Array.Empty<Found>();
                }
                else
                {
                    misses++;
                    await Nap(2500, ct).ConfigureAwait(false);
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { break; }
            catch (Exception e)
            {
                Hint = e.Message;
                try { await Nap(2500, ct).ConfigureAwait(false); } catch (OperationCanceledException) { break; }
            }
        }
    }

    private bool RecentlyDeclined(string id)
    {
        lock (_declined) return _declined.TryGetValue(id, out var t) && DateTime.UtcNow - t < TimeSpan.FromMinutes(3);
    }

    /// <summary>Manual pairing from the UI (ignores the decline cool-down).</summary>
    public async Task<bool> PairWithAsync(Found f)
    {
        var rec = await PairAsync(f, _cts.Token).ConfigureAwait(false);
        _wake.TrySetResult();
        return rec != null;
    }

    private async Task<PhoneRec?> PairAsync(Found f, CancellationToken ct)
    {
        try
        {
            using var c = await Link.ConnectAsync(f.Ip, null, ct).ConfigureAwait(false);
            var nonce = Util.RandomHex(8);
            var code = Util.PairCode(c.CertHex, nonce);
            Set(Status.Pairing, f.Name, f.Ip.ToString(), code: code);

            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(75_000);
            await Wire.WriteFrameAsync(c.Ssl, new JsonObject
            {
                ["op"] = "pair", ["id"] = Cfg.DeviceId, ["name"] = Cfg.DeviceName, ["nonce"] = nonce,
            }, cts.Token).ConfigureAwait(false);
            var r = await Wire.ReadFrameAsync(c.Ssl, cts.Token).ConfigureAwait(false);
            if (r != null && r.B("ok"))
            {
                var id = r.S("id").Length > 0 ? r.S("id") : f.Id;
                var rec = new PhoneRec
                {
                    Id = id,
                    Name = r.S("name").Length > 0 ? r.S("name") : f.Name,
                    Ip = f.Ip.ToString(),
                    Fp = c.CertHex,
                    Token = r.S("token"),
                };
                Cfg.Phones.RemoveAll(p => p.Id == id);
                Cfg.Phones.Add(rec);
                Cfg.Save();
                return rec;
            }
            lock (_declined) _declined[f.Id] = DateTime.UtcNow;
            Sticky(r?.S("err") == "busy" ? "The phone is already asking about another pairing." : "Pairing was declined on the phone. Press Retry to ask again.");
            return null;
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
        catch (Exception e)
        {
            lock (_declined) _declined[f.Id] = DateTime.UtcNow;
            Sticky("Pairing did not finish: " + e.Message);
            return null;
        }
        finally
        {
            PairCode = null;
        }
    }

    private async Task RunSessionAsync(PhoneRec rec, CancellationToken ct)
    {
        Set(Status.Connecting, rec.Name, rec.Ip);
        Conn conn;
        try
        {
            conn = await Link.ConnectAsync(IPAddress.Parse(rec.Ip), rec.Fp, ct).ConfigureAwait(false);
        }
        catch (AuthenticationException)
        {
            // The phone presents a different certificate than the one we pinned: app data was cleared or
            // someone is in the middle. Forget it; the next pairing shows a code to compare.
            Cfg.Phones.RemoveAll(p => p.Id == rec.Id);
            Cfg.Save();
            Sticky("The phone's identity changed, so it needs pairing again.");
            Set(Status.Searching, hint: StickyHint());
            return;
        }

        using (conn)
        {
            await Wire.WriteFrameAsync(conn.Ssl, new JsonObject
            {
                ["op"] = "session", ["id"] = Cfg.DeviceId, ["token"] = rec.Token,
            }, ct).ConfigureAwait(false);
            var resp = await Wire.ReadFrameAsync(conn.Ssl, ct).ConfigureAwait(false);
            if (resp == null || !resp.B("ok"))
            {
                if (resp?.S("err") == "auth")
                {
                    // The phone no longer knows us (app data cleared / "forget" pressed there).
                    Cfg.Phones.RemoveAll(p => p.Id == rec.Id);
                    Cfg.Save();
                }
                await Nap(1500, ct).ConfigureAwait(false);
                return;
            }

            var sess = new SessionCtx(rec, conn);
            _current = sess;
            Set(Status.Connected, rec.Name, rec.Ip);
            try
            {
                while (true)
                {
                    using var rc = CancellationTokenSource.CreateLinkedTokenSource(ct);
                    rc.CancelAfter(35_000); // the phone pings every 10 s
                    var m = await Wire.ReadFrameAsync(conn.Ssl, rc.Token).ConfigureAwait(false);
                    if (m == null) break;
                    await OnMessageAsync(sess, m, ct).ConfigureAwait(false);
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            catch { /* connection dropped: fall through and look for the phone again */ }
            finally
            {
                _current = null;
                foreach (var x in Xfers.Where(x => x.State == XState.Offered)) Finish(x, XState.Failed, "Phone disconnected");
                Set(Status.Searching);
            }
        }
    }

    private async Task OnMessageAsync(SessionCtx s, JsonObject m, CancellationToken ct)
    {
        switch (m.S("t"))
        {
            case "ping":
                await s.SendAsync(new JsonObject { ["t"] = "pong" }, ct).ConfigureAwait(false);
                break;
            case "offer":
                await OnOfferAsync(s, m, ct).ConfigureAwait(false);
                break;
            case "accept":
                Find(m.S("tid"))?.Answer.TrySetResult(true);
                break;
            case "reject":
                if (Find(m.S("tid")) is { } rx)
                {
                    rx.Answer.TrySetResult(false);
                    if (m.S("err") == "space") Finish(rx, XState.Failed, "Not enough space on the phone");
                }
                break;
            case "cancel":
                if (Find(m.S("tid")) is { } cx) { Finish(cx, XState.Cancelled); cx.Cts.Cancel(); }
                break;
            case "text":
                var txt = m.S("text");
                if (txt.Length > 0) TextReceived?.Invoke(txt);
                break;
        }
    }

    // ------------------------------------------------------------------ bookkeeping

    private Xfer? Find(string tid)
    {
        lock (_xfers) return _xfers.FirstOrDefault(x => x.Tid == tid);
    }

    private void Add(Xfer x)
    {
        lock (_xfers)
        {
            _xfers.Insert(0, x);
            while (_xfers.Count > 60) _xfers.RemoveAt(_xfers.Count - 1);
        }
        XferAdded?.Invoke(x);
        Changed?.Invoke();
    }

    private void Finish(Xfer x, XState s, string? err = null)
    {
        if (!x.IsActive) return;
        x.SetState(s, err);
        XferFinished?.Invoke(x);
        Changed?.Invoke();
    }

    public void Clear()
    {
        lock (_xfers) _xfers.RemoveAll(x => !x.IsActive);
        Changed?.Invoke();
    }

    public void Forget(PhoneRec p)
    {
        Cfg.Phones.RemoveAll(x => x.Id == p.Id);
        Cfg.Save();
        _current?.Conn.Dispose();
    }

    public void AddManualIp(string ip)
    {
        if (!IPAddress.TryParse(ip, out _) || Cfg.ManualIps.Contains(ip)) return;
        Cfg.ManualIps.Add(ip);
        Cfg.Save();
        Retry();
    }

    private async Task TickerAsync(CancellationToken ct)
    {
        using var t = new PeriodicTimer(TimeSpan.FromMilliseconds(200));
        try
        {
            while (await t.WaitForNextTickAsync(ct).ConfigureAwait(false))
            {
                long now = Environment.TickCount64;
                foreach (var x in Xfers)
                {
                    if (x.State != XState.Active) continue;
                    x.Tick(now);
                    if (now - x.LastProgress > 45_000)
                    {
                        Finish(x, XState.Failed, "Connection lost");
                        x.Cts.Cancel();
                    }
                }
            }
        }
        catch (OperationCanceledException) { }
    }

    public void Cancel(Xfer x)
    {
        if (!x.IsActive) return;
        var s = _current;
        if (s != null) _ = s.SendAsync(new JsonObject { ["t"] = "cancel", ["tid"] = x.Tid });
        Finish(x, XState.Cancelled);
        x.Cts.Cancel();
        x.Answer.TrySetResult(false);
    }

    public async Task SendTextAsync(string text)
    {
        var s = _current ?? throw new InvalidOperationException("Phone not connected");
        await s.SendAsync(new JsonObject { ["t"] = "text", ["text"] = text.Length > 20000 ? text[..20000] : text }).ConfigureAwait(false);
    }

    // ------------------------------------------------------------------ PC to phone

    /// <summary>Sends files and folders to the connected phone. Returns at once; watch the Xfer.</summary>
    public Xfer Send(IEnumerable<string> paths)
    {
        var s = _current ?? throw new InvalidOperationException("The phone is not connected yet");
        var items = new List<XItem>();
        foreach (var (path, name, rel, size) in Enumerate(paths))
            items.Add(new XItem { I = items.Count, Name = name, Rel = rel, Path = path, Size = size });
        if (items.Count == 0) throw new InvalidOperationException("Nothing to send");

        var x = new Xfer(Util.RandomHex(8), Dir.Out, s.Rec.Name, items);
        Add(x);
        _ = Task.Run(() => PushAsync(s, x));
        return x;
    }

    private static IEnumerable<(string path, string name, string? rel, long size)> Enumerate(IEnumerable<string> paths)
    {
        var opt = new EnumerationOptions { RecurseSubdirectories = true, IgnoreInaccessible = true, AttributesToSkip = FileAttributes.ReparsePoint };
        foreach (var p in paths)
        {
            if (File.Exists(p))
            {
                yield return (p, Path.GetFileName(p), null, new FileInfo(p).Length);
            }
            else if (Directory.Exists(p))
            {
                var root = Path.GetFullPath(p).TrimEnd('\\', '/');
                var top = Path.GetFileName(root);
                if (top.Length == 0) top = "Folder";
                foreach (var f in Directory.EnumerateFiles(root, "*", opt))
                {
                    var dir = Path.GetDirectoryName(Path.GetRelativePath(root, f)) ?? "";
                    var rel = dir.Length == 0 ? top : top + "/" + dir.Replace('\\', '/');
                    yield return (f, Path.GetFileName(f), rel, new FileInfo(f).Length);
                }
            }
        }
    }

    private async Task PushAsync(SessionCtx s, Xfer x)
    {
        var ct = x.Cts.Token;
        try
        {
            var arr = new JsonArray();
            foreach (var it in x.Items)
                arr.Add(new JsonObject { ["i"] = it.I, ["name"] = it.Name, ["size"] = it.Size, ["rel"] = it.Rel });
            x.SetState(XState.Offered);
            await s.SendAsync(new JsonObject { ["t"] = "offer", ["tid"] = x.Tid, ["items"] = arr }, ct).ConfigureAwait(false);

            var answered = await Task.WhenAny(x.Answer.Task, Task.Delay(30_000, ct)).ConfigureAwait(false);
            if (answered != x.Answer.Task) { Finish(x, XState.Failed, "The phone did not answer"); return; }
            if (!x.Answer.Task.Result) { Finish(x, XState.Failed, x.State == XState.Cancelled ? null : "Declined by the phone"); return; }

            x.Started = DateTime.Now;
            x.SetState(XState.Active);
            var queue = new ConcurrentQueue<XItem>(x.Items);
            int workers = Math.Min(3, x.Items.Count);
            await Task.WhenAll(Enumerable.Range(0, workers).Select(_ => Task.Run(() => PushWorkerAsync(s.Rec, x, queue, ct)))).ConfigureAwait(false);
            Finish(x, XState.Done);
        }
        catch (OperationCanceledException) { Finish(x, XState.Cancelled); }
        catch (Exception e) { Finish(x, XState.Failed, Short(e)); x.Cts.Cancel(); }
    }

    private async Task PushWorkerAsync(PhoneRec rec, Xfer x, ConcurrentQueue<XItem> q, CancellationToken ct)
    {
        Conn? c = null;
        var buf = new byte[Proto.Buf];
        try
        {
            while (q.TryDequeue(out var it))
            {
                for (int attempt = 0; ; attempt++)
                {
                    try
                    {
                        c ??= await Link.ConnectAsync(IPAddress.Parse(rec.Ip), rec.Fp, ct).ConfigureAwait(false);
                        await PutOneAsync(c, rec, x, it, buf, ct).ConfigureAwait(false);
                        break;
                    }
                    catch (OperationCanceledException) { throw; }
                    catch (Exception) when (attempt < 2 && !ct.IsCancellationRequested)
                    {
                        c?.Dispose();
                        c = null;
                        x.Add(-Interlocked.Exchange(ref it.Got, 0)); // the file starts over
                        await Task.Delay(400, ct).ConfigureAwait(false);
                    }
                }
            }
        }
        finally { c?.Dispose(); }
    }

    private async Task PutOneAsync(Conn c, PhoneRec rec, Xfer x, XItem it, byte[] buf, CancellationToken ct)
    {
        await Wire.WriteFrameAsync(c.Ssl, new JsonObject
        {
            ["op"] = "put", ["id"] = Cfg.DeviceId, ["token"] = rec.Token, ["tid"] = x.Tid, ["i"] = it.I,
        }, ct).ConfigureAwait(false);
        var r = await Wire.ReadFrameAsync(c.Ssl, ct).ConfigureAwait(false) ?? throw new EndOfStreamException();
        if (!r.B("ok"))
        {
            if (r.S("err") == "done") { it.Done = true; return; }
            throw new IOException("Phone refused: " + r.S("err"));
        }

        using var fs = new FileStream(it.Path!, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 1,
            FileOptions.Asynchronous | FileOptions.SequentialScan);
        if (fs.Length != it.Size) throw new IOException($"{it.Name} changed while sending");
        long left = it.Size;
        while (left > 0)
        {
            int n = await fs.ReadAsync(buf.AsMemory(0, (int)Math.Min(buf.Length, left)), ct).ConfigureAwait(false);
            if (n == 0) throw new IOException($"{it.Name} shrank while sending");
            await c.Ssl.WriteAsync(buf.AsMemory(0, n), ct).ConfigureAwait(false);
            x.Add(n);
            Interlocked.Add(ref it.Got, n);
            left -= n;
        }
        await c.Ssl.FlushAsync(ct).ConfigureAwait(false);
        var fin = await Wire.ReadFrameAsync(c.Ssl, ct).ConfigureAwait(false);
        if (fin == null || !fin.B("ok")) throw new IOException("Phone could not save " + it.Name);
        it.Done = true;
    }

    // ------------------------------------------------------------------ phone to PC

    private async Task OnOfferAsync(SessionCtx s, JsonObject m, CancellationToken ct)
    {
        var tid = m.S("tid");
        if (tid.Length == 0 || Find(tid) != null || m["items"] is not JsonArray arr) return;
        var items = new List<XItem>();
        foreach (var n in arr)
        {
            if (n is not JsonObject o) continue;
            items.Add(new XItem
            {
                I = (int)o.L("i", items.Count),
                Name = Util.CleanName(o.S("name")),
                Rel = Util.CleanRel(o.S("rel")),
                Size = Math.Max(0, o.L("size")),
            });
        }
        if (items.Count == 0 || items.Count > 20000)
        {
            await s.SendAsync(new JsonObject { ["t"] = "reject", ["tid"] = tid, ["err"] = "items" }, ct).ConfigureAwait(false);
            return;
        }

        long total = items.Sum(i => i.Size);
        try
        {
            Directory.CreateDirectory(Cfg.SaveDir);
            var free = new DriveInfo(Path.GetPathRoot(Path.GetFullPath(Cfg.SaveDir))!).AvailableFreeSpace;
            if (total + (64L << 20) > free)
            {
                await s.SendAsync(new JsonObject { ["t"] = "reject", ["tid"] = tid, ["err"] = "space" }, ct).ConfigureAwait(false);
                return;
            }
        }
        catch { /* unknown drive layout: try anyway */ }

        var x = new Xfer(tid, Dir.In, s.Rec.Name, items);
        x.Started = DateTime.Now;
        x.SetState(XState.Active);
        Add(x);
        await s.SendAsync(new JsonObject { ["t"] = "accept", ["tid"] = tid }, ct).ConfigureAwait(false);
        _ = Task.Run(() => PullAsync(s, x));
    }

    private async Task PullAsync(SessionCtx s, Xfer x)
    {
        var ct = x.Cts.Token;
        string? failure = null;
        try
        {
            var queue = new ConcurrentQueue<XItem>(x.Items);
            int workers = Math.Min(2, x.Items.Count);
            await Task.WhenAll(Enumerable.Range(0, workers).Select(_ => Task.Run(() => PullWorkerAsync(s.Rec, x, queue, ct)))).ConfigureAwait(false);
            Finish(x, XState.Done);
        }
        catch (OperationCanceledException) { Finish(x, XState.Cancelled); }
        catch (Exception e)
        {
            failure = Short(e);
            Finish(x, XState.Failed, failure);
            x.Cts.Cancel();
        }
        // tell the phone how it went (it keeps its side "active" until we do)
        if (x.State != XState.Cancelled)
            try { await s.SendAsync(new JsonObject { ["t"] = "done", ["tid"] = x.Tid, ["ok"] = failure == null, ["err"] = failure }).ConfigureAwait(false); }
            catch { }
    }

    private async Task PullWorkerAsync(PhoneRec rec, Xfer x, ConcurrentQueue<XItem> q, CancellationToken ct)
    {
        Conn? c = null;
        var buf = new byte[Proto.Buf];
        try
        {
            while (q.TryDequeue(out var it))
            {
                for (int attempt = 0; ; attempt++)
                {
                    try
                    {
                        c ??= await Link.ConnectAsync(IPAddress.Parse(rec.Ip), rec.Fp, ct).ConfigureAwait(false);
                        await GetOneAsync(c, rec, x, it, buf, ct).ConfigureAwait(false);
                        break;
                    }
                    catch (OperationCanceledException) { throw; }
                    catch (Exception) when (attempt < 2 && !ct.IsCancellationRequested)
                    {
                        c?.Dispose();
                        c = null;
                        x.Add(-Interlocked.Exchange(ref it.Got, 0));
                        await Task.Delay(400, ct).ConfigureAwait(false);
                    }
                }
            }
        }
        finally { c?.Dispose(); }
    }

    private async Task GetOneAsync(Conn c, PhoneRec rec, Xfer x, XItem it, byte[] buf, CancellationToken ct)
    {
        await Wire.WriteFrameAsync(c.Ssl, new JsonObject
        {
            ["op"] = "get", ["id"] = Cfg.DeviceId, ["token"] = rec.Token, ["tid"] = x.Tid, ["i"] = it.I,
        }, ct).ConfigureAwait(false);
        var r = await Wire.ReadFrameAsync(c.Ssl, ct).ConfigureAwait(false) ?? throw new EndOfStreamException();
        if (!r.B("ok")) throw new IOException("Phone refused: " + r.S("err"));
        long size = r.L("size", it.Size);

        var dir = it.Rel == null ? Cfg.SaveDir : Path.Combine(Cfg.SaveDir, it.Rel);
        Directory.CreateDirectory(dir);
        var part = Path.Combine(dir, $".{it.Name}.{x.Tid[..6]}.{it.I}.part");
        try
        {
            using (var fs = new FileStream(part, FileMode.Create, FileAccess.Write, FileShare.None, 1, FileOptions.Asynchronous | FileOptions.SequentialScan))
            {
                long left = size;
                while (left > 0)
                {
                    int n = await c.Ssl.ReadAsync(buf.AsMemory(0, (int)Math.Min(buf.Length, left)), ct).ConfigureAwait(false);
                    if (n == 0) throw new EndOfStreamException("phone closed mid-file");
                    await fs.WriteAsync(buf.AsMemory(0, n), ct).ConfigureAwait(false);
                    x.Add(n);
                    Interlocked.Add(ref it.Got, n);
                    left -= n;
                }
            }
            string dest;
            lock (_xfers)
            {
                dest = Util.UniquePath(dir, it.Name);
                File.Move(part, dest);
            }
            it.Done = true;
            x.OpenPath ??= it.Rel == null ? dest : Path.Combine(Cfg.SaveDir, it.Rel.Split(Path.DirectorySeparatorChar)[0]);
        }
        catch
        {
            try { File.Delete(part); } catch { }
            throw;
        }
    }

    private static string Short(Exception e) => e switch
    {
        EndOfStreamException => "Connection lost",
        IOException io => io.Message,
        _ => e.Message,
    };
}
