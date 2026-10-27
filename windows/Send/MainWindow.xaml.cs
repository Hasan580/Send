using System;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using System.Windows.Documents;
using System.Windows.Threading;
using Microsoft.Win32;
using Send.Core;

namespace Send;

public sealed record TextItem(bool Out, string Text)
{
    public string Who => Out ? "You sent" : "From your phone";
}

public partial class MainWindow : Window
{
    private readonly Agent _agent;
    private readonly ObservableCollection<Xfer> _items = new();
    private readonly ObservableCollection<TextItem> _texts = new();
    private int _refreshQueued;
    private string _titleKey = "";
    private string? _glowKey;
    private string? _toast;
    private bool _pulsing;

    public MainWindow(Agent agent)
    {
        InitializeComponent();
        _agent = agent;
        Items.ItemsSource = _items;
        Texts.ItemsSource = _texts;

        agent.Changed += QueueRefresh;
        agent.XferAdded += x => Dispatcher.BeginInvoke(() => { _items.Insert(0, x); UpdateLists(); });
        agent.XferFinished += x => Dispatcher.BeginInvoke(() => OnFinished(x));
        agent.TextReceived += t => Dispatcher.BeginInvoke(() =>
        {
            _texts.Insert(0, new TextItem(false, t));
            UpdateLists();
            if (!IsActive) ((App)Application.Current).Balloon("Text from your phone", t.Length > 120 ? t[..120] + "..." : t);
        });
        PreviewKeyDown += (_, e) => { if (e.Key == Key.Escape && PairOverlay.Visibility != Visibility.Visible) CloseOverlays(); };
        Refresh();
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        if (!App.Quitting)
        {
            e.Cancel = true;
            ((App)Application.Current).HideToTray();
        }
        base.OnClosing(e);
    }

    private Brush Res(string key) => (Brush)FindResource(key);

    // ------------------------------------------------------------------ state -> screen

    private void QueueRefresh()
    {
        if (Interlocked.Exchange(ref _refreshQueued, 1) == 0)
            Dispatcher.BeginInvoke(DispatcherPriority.Background, () => { Interlocked.Exchange(ref _refreshQueued, 0); Refresh(); });
    }

    private void SetTitle(params (string text, bool italic)[] parts)
    {
        var key = string.Concat(parts.Select(p => (p.italic ? "*" : "") + p.text));
        if (key == _titleKey) return;
        _titleKey = key;
        HeroTitle.Inlines.Clear();
        foreach (var (t, it) in parts) HeroTitle.Inlines.Add(new Run(t) { FontStyle = it ? FontStyles.Italic : FontStyles.Normal });
    }

    private void SetGlow(Color c)
    {
        var key = c.ToString();
        if (key == _glowKey) return;
        _glowKey = key;
        var clear = Color.FromArgb(0, c.R, c.G, c.B);
        GlowA.BeginAnimation(GradientStop.ColorProperty, new ColorAnimation(c, TimeSpan.FromMilliseconds(800)));
        GlowB.BeginAnimation(GradientStop.ColorProperty, new ColorAnimation(clear, TimeSpan.FromMilliseconds(800)));
    }

    private void Pulse(Ellipse dot, bool on)
    {
        if (on) dot.BeginAnimation(OpacityProperty, new DoubleAnimation(1, 0.25, TimeSpan.FromMilliseconds(900)) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever });
        else { dot.BeginAnimation(OpacityProperty, null); dot.Opacity = 1; }
    }

    private void Refresh()
    {
        var st = _agent.Status;
        bool conn = st == Status.Connected;
        var phone = _agent.PhoneName;
        var paired = _agent.Cfg.Phones.FirstOrDefault();
        bool busy = _agent.Xfers.Any(x => x.State == XState.Active);

        // status pill
        var (label, dot, pulse) = st switch
        {
            Status.Connected => ("Connected", Res("Green"), false),
            Status.Pairing => ("Pairing", Res("Yellow"), true),
            Status.Connecting => ("Connecting", Res("Blue"), true),
            _ => ("Searching", Res("Blue"), true),
        };
        StatusText.Text = label;
        StatusDot.Fill = dot;
        if (pulse != _pulsing) { _pulsing = pulse; Pulse(StatusDot, pulse); }

        SetGlow(busy ? Color.FromArgb(0x38, 0xFF, 0x59, 0x00) : conn ? Color.FromArgb(0x2E, 0x22, 0xFF, 0x99) : Color.FromArgb(0x57, 0x00, 0x75, 0xFF));

        // hero
        string sub;
        switch (st)
        {
            case Status.Connected:
                SetTitle(("Send to ", false), (phone ?? "your phone", true), (".", false));
                sub = $"Connected · {_agent.PhoneIp}. Drop files anywhere in this window.";
                break;
            case Status.Pairing:
                SetTitle(("Check your ", false), ("phone", true), (".", false));
                sub = "Tap Allow on the phone if the code matches.";
                break;
            case Status.Connecting:
                SetTitle(("Connecting to ", false), (phone ?? "your phone", true), (".", false));
                sub = "One moment.";
                break;
            default:
                if (paired != null) SetTitle(("Looking for ", false), (paired.Name, true), (".", false));
                else SetTitle(("Looking for your ", false), ("phone", true), (".", false));
                sub = _agent.Hint ?? "Open Send on your phone. It finds this PC and connects by itself when both are on the same network.";
                break;
        }
        HeroSub.Text = _toast ?? sub;

        // drop zone
        BtnFiles.IsEnabled = BtnFolder.IsEnabled = BtnText.IsEnabled = conn;
        DropCard.Opacity = conn ? 1 : 0.5;
        DropTitle.Text = conn ? "Drop files or folders here" : "Waiting for your phone";
        DropSub.Text = conn ? "They go straight to your phone over your own network." : "Open Send on the phone and this lights up.";
        BtnRetry.Visibility = conn ? Visibility.Collapsed : Visibility.Visible;

        // code window
        KvPc.Text = _agent.Cfg.DeviceName;
        KvPhone.Text = conn ? $"{phone} · {_agent.PhoneIp}" : "not connected";
        KvSave.Text = _agent.Cfg.SaveDir;

        // pairing sheet
        if (st == Status.Pairing && _agent.PairCode != null)
        {
            PairTitle.Inlines.Clear();
            PairTitle.Inlines.Add(new Run("Pair with "));
            PairTitle.Inlines.Add(new Run(phone ?? "your phone") { FontStyle = FontStyles.Italic });
            PairTitle.Inlines.Add(new Run("?"));
            PairCodeText.Text = string.Join("  ", _agent.PairCode.ToCharArray());
            Pulse(PairDot, true);
            SetOverlay(PairOverlay, true);
        }
        else SetOverlay(PairOverlay, false);
    }

    private void Toast(string msg)
    {
        _toast = msg;
        Refresh();
        var t = new DispatcherTimer { Interval = TimeSpan.FromSeconds(4) };
        t.Tick += (_, _) => { t.Stop(); _toast = null; Refresh(); };
        t.Start();
    }

    private void UpdateLists()
    {
        EmptyCard.Visibility = _items.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        BtnClear.Visibility = _items.Any(x => !x.IsActive) ? Visibility.Visible : Visibility.Collapsed;
        MsgHead.Visibility = Texts.Visibility = _texts.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void OnFinished(Xfer x)
    {
        UpdateLists();
        if (x.Dir == Dir.In && x.State == XState.Done && !IsActive)
            ((App)Application.Current).Balloon(x.Count > 1 ? $"Received {x.Count} files" : $"Received {x.Title}", $"From {x.PeerName} · saved to {_agent.Cfg.SaveDir}");
    }

    // ------------------------------------------------------------------ overlays

    private void SetOverlay(Grid g, bool show)
    {
        if (show)
        {
            if (g.Visibility == Visibility.Visible && g.Opacity >= 1) return;
            g.Visibility = Visibility.Visible;
            g.BeginAnimation(OpacityProperty, new DoubleAnimation(g.Opacity, 1, TimeSpan.FromMilliseconds(140)));
        }
        else if (g.Visibility == Visibility.Visible)
        {
            var a = new DoubleAnimation(g.Opacity, 0, TimeSpan.FromMilliseconds(110));
            a.Completed += (_, _) => { if (g.Opacity == 0) g.Visibility = Visibility.Collapsed; };
            g.BeginAnimation(OpacityProperty, a);
        }
    }

    private void CloseOverlays()
    {
        SetOverlay(TextOverlay, false);
        SetOverlay(SettingsOverlay, false);
    }

    private void OnCloseOverlays(object sender, RoutedEventArgs e) => CloseOverlays();
    private void OnScrimDown(object sender, MouseButtonEventArgs e) => CloseOverlays();
    private void OnCardDown(object sender, MouseButtonEventArgs e) => e.Handled = true;

    // ------------------------------------------------------------------ title bar

    private void OnMinimize(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;
    private void OnClose(object sender, RoutedEventArgs e) => Close();
    private void OnQuit(object sender, RoutedEventArgs e) => ((App)Application.Current).Quit();
    private void OnRetry(object sender, RoutedEventArgs e) => _agent.Retry();

    // ------------------------------------------------------------------ sending

    private void SendPaths(string[] paths)
    {
        try { _agent.Send(paths); }
        catch (Exception ex) { Toast(ex.Message); }
    }

    private void OnChooseFiles(object sender, RoutedEventArgs e)
    {
        var d = new OpenFileDialog { Multiselect = true, Title = "Send files to your phone" };
        if (d.ShowDialog(this) == true) SendPaths(d.FileNames);
    }

    private void OnChooseFolder(object sender, RoutedEventArgs e)
    {
        var d = new OpenFolderDialog { Multiselect = true, Title = "Send folders to your phone" };
        if (d.ShowDialog(this) == true) SendPaths(d.FolderNames);
    }

    private void SetDragging(bool on)
    {
        DropCard.BorderBrush = on ? Res("Ink") : Res("HairlineStrong");
        DropCard.Background = on ? Res("Elevated") : Res("Card");
    }

    private void OnDragOver(object sender, DragEventArgs e)
    {
        bool files = e.Data.GetDataPresent(DataFormats.FileDrop);
        bool ok = files && _agent.IsConnected;
        e.Effects = ok ? DragDropEffects.Copy : DragDropEffects.None;
        SetDragging(ok);
        e.Handled = true;
    }

    private void OnDragLeave(object sender, DragEventArgs e) => SetDragging(false);

    private void OnDrop(object sender, DragEventArgs e)
    {
        SetDragging(false);
        if (e.Data.GetData(DataFormats.FileDrop) is string[] paths && paths.Length > 0)
        {
            if (_agent.IsConnected) SendPaths(paths);
            else Toast("Your phone is not connected yet.");
        }
        e.Handled = true;
    }

    // ------------------------------------------------------------------ text

    private void OnSendText(object sender, RoutedEventArgs e)
    {
        TextBoxMsg.Text = "";
        SetOverlay(TextOverlay, true);
        TextBoxMsg.Focus();
    }

    private void OnPasteText(object sender, RoutedEventArgs e)
    {
        try { if (Clipboard.ContainsText()) TextBoxMsg.Text = Clipboard.GetText(); } catch { }
    }

    private async void OnTextSend(object sender, RoutedEventArgs e)
    {
        var text = TextBoxMsg.Text.Trim();
        if (text.Length == 0) return;
        try
        {
            await _agent.SendTextAsync(text);
            _texts.Insert(0, new TextItem(true, text));
            UpdateLists();
            CloseOverlays();
        }
        catch (Exception ex) { Toast(ex.Message); }
    }

    private void OnCopyText(object sender, RoutedEventArgs e)
    {
        if (sender is not Button b || b.DataContext is not TextItem t) return;
        try { Clipboard.SetText(t.Text); } catch { return; }
        b.Content = "Copied";
        var timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(1400) };
        timer.Tick += (_, _) => { timer.Stop(); b.Content = "Copy"; };
        timer.Start();
    }

    // ------------------------------------------------------------------ list actions

    private void OnShowFile(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement fe || fe.DataContext is not Xfer x || x.OpenPath == null) return;
        try
        {
            if (File.Exists(x.OpenPath)) Process.Start("explorer.exe", $"/select,\"{x.OpenPath}\"");
            else if (Directory.Exists(x.OpenPath)) Process.Start("explorer.exe", $"\"{x.OpenPath}\"");
        }
        catch { }
    }

    private void OnCancel(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement fe && fe.DataContext is Xfer x) _agent.Cancel(x);
    }

    private void OnClear(object sender, RoutedEventArgs e)
    {
        _agent.Clear();
        foreach (var x in _items.Where(x => !x.IsActive).ToList()) _items.Remove(x);
        UpdateLists();
    }

    private void OnOpenSaveFolder(object sender, RoutedEventArgs e)
    {
        try
        {
            Directory.CreateDirectory(_agent.Cfg.SaveDir);
            Process.Start("explorer.exe", $"\"{_agent.Cfg.SaveDir}\"");
        }
        catch { }
    }

    // ------------------------------------------------------------------ settings

    private void OnSettings(object sender, RoutedEventArgs e)
    {
        FillSettings();
        SetOverlay(SettingsOverlay, true);
    }

    private void FillSettings()
    {
        NameBox.Text = _agent.Cfg.DeviceName;
        SaveBox.Text = _agent.Cfg.SaveDir;
        AutoStartBtn.Content = App.AutoStart ? "Start with Windows: on" : "Start with Windows: off";
        VersionText.Text = $"Send {typeof(MainWindow).Assembly.GetName().Version?.ToString(3) ?? "1.0.0"} · protocol SEND/1 · TLS 1.3";

        PhonesPanel.Children.Clear();
        if (_agent.Cfg.Phones.Count == 0)
        {
            PhonesPanel.Children.Add(new TextBlock { Text = "None yet. Open Send on your phone and it pairs by itself.", Style = (Style)FindResource("BodySmText") });
            return;
        }
        foreach (var p in _agent.Cfg.Phones.ToArray())
        {
            var row = new Grid { Margin = new Thickness(0, 0, 0, 6) };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var name = new TextBlock { VerticalAlignment = VerticalAlignment.Center, Style = (Style)FindResource("BodySmText"), Foreground = Res("Ink") };
            name.Inlines.Add(new Run(p.Name));
            name.Inlines.Add(new Run("   " + p.Ip) { FontFamily = (FontFamily)FindResource("Mono"), FontSize = 12, Foreground = Res("Ash") });
            Grid.SetColumn(name, 0);
            var forget = new Button { Content = "Forget", Style = (Style)FindResource("SmallGhostBtn"), Foreground = Res("Red"), Tag = p };
            forget.Click += (_, _) => { _agent.Forget(p); FillSettings(); };
            Grid.SetColumn(forget, 1);
            row.Children.Add(name);
            row.Children.Add(forget);
            PhonesPanel.Children.Add(row);
        }
    }

    private void OnSaveName(object sender, RoutedEventArgs e)
    {
        var n = NameBox.Text.Trim();
        if (n.Length == 0) return;
        _agent.Cfg.DeviceName = n;
        _agent.Cfg.Save();
        Refresh();
    }

    private void OnChangeSave(object sender, RoutedEventArgs e)
    {
        var d = new OpenFolderDialog { Title = "Where should received files go?", InitialDirectory = _agent.Cfg.SaveDir };
        if (d.ShowDialog(this) != true) return;
        _agent.Cfg.SaveDir = d.FolderName;
        _agent.Cfg.Save();
        SaveBox.Text = d.FolderName;
        Refresh();
    }

    private void OnAddIp(object sender, RoutedEventArgs e)
    {
        var ip = IpBox.Text.Trim();
        if (ip.Length == 0) return;
        _agent.AddManualIp(ip);
        IpBox.Text = "";
    }

    private void OnToggleAutoStart(object sender, RoutedEventArgs e)
    {
        App.AutoStart = !App.AutoStart;
        AutoStartBtn.Content = App.AutoStart ? "Start with Windows: on" : "Start with Windows: off";
    }
}
