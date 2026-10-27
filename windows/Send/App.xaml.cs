using System;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using Microsoft.Win32;
using Send.Core;

namespace Send;

public partial class App : Application
{
    public static Agent Agent { get; private set; } = null!;
    public static bool Quitting { get; private set; }

    private Mutex? _single;
    private EventWaitHandle? _show;
    private MainWindow? _main;
    private System.Windows.Forms.NotifyIcon? _tray;
    private bool _toldAboutTray;

    protected override void OnStartup(StartupEventArgs e)
    {
        // One Send at a time: a second launch just brings the first to the front.
        _single = new Mutex(true, "Send.SingleInstance", out bool first);
        _show = new EventWaitHandle(false, EventResetMode.AutoReset, "Send.Show");
        if (!first)
        {
            _show.Set();
            Shutdown();
            return;
        }

        base.OnStartup(e);
        ShutdownMode = ShutdownMode.OnExplicitShutdown;
        DispatcherUnhandledException += (_, ex) =>
        {
            try { File.AppendAllText(Path.Combine(Config.Dir, "crash.log"), $"{DateTime.Now:s} {ex.Exception}\n\n"); } catch { }
            ex.Handled = true;
        };

        Agent = new Agent(Config.Load());
        Agent.Start();
        _main = new MainWindow(Agent);
        CreateTray();

        var wait = new Thread(() =>
        {
            while (_show.WaitOne()) Dispatcher.BeginInvoke(ShowMain);
        }) { IsBackground = true };
        wait.Start();

        if (!e.Args.Contains("--tray")) ShowMain();
    }

    public void ShowMain()
    {
        if (_main == null) return;
        if (!_main.IsVisible) _main.Show();
        if (_main.WindowState == WindowState.Minimized) _main.WindowState = WindowState.Normal;
        _main.Activate();
    }

    /// <summary>Closing the window keeps Send running in the tray, so the phone can still find this PC.</summary>
    public void HideToTray()
    {
        _main?.Hide();
        if (_toldAboutTray) return;
        _toldAboutTray = true;
        Balloon("Send is still running", "It stays in the tray so your phone can connect. Right-click the icon to quit.");
    }

    public void Balloon(string title, string text)
    {
        if (_tray == null) return;
        _tray.BalloonTipTitle = title;
        _tray.BalloonTipText = text;
        _tray.ShowBalloonTip(4000);
    }

    private void CreateTray()
    {
        var res = GetResourceStream(new Uri("pack://application:,,,/Assets/send.ico"));
        _tray = new System.Windows.Forms.NotifyIcon
        {
            Icon = new System.Drawing.Icon(res.Stream),
            Text = "Send",
            Visible = true,
            ContextMenuStrip = new System.Windows.Forms.ContextMenuStrip(),
        };
        _tray.ContextMenuStrip.Items.Add("Open Send", null, (_, _) => Dispatcher.BeginInvoke(ShowMain));
        _tray.ContextMenuStrip.Items.Add("Quit", null, (_, _) => Dispatcher.BeginInvoke(Quit));
        _tray.DoubleClick += (_, _) => Dispatcher.BeginInvoke(ShowMain);
    }

    public void Quit()
    {
        Quitting = true;
        if (_tray != null) { _tray.Visible = false; _tray.Dispose(); }
        Agent.Dispose();
        _main?.Close();
        Shutdown();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        try { _single?.ReleaseMutex(); } catch { }
        base.OnExit(e);
    }

    // ---- start with Windows (off unless the user turns it on in Settings)

    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

    public static bool AutoStart
    {
        get
        {
            using var k = Registry.CurrentUser.OpenSubKey(RunKey);
            return k?.GetValue("Send") != null;
        }
        set
        {
            using var k = Registry.CurrentUser.OpenSubKey(RunKey, true);
            if (k == null) return;
            if (value) k.SetValue("Send", $"\"{Environment.ProcessPath}\" --tray");
            else k.DeleteValue("Send", false);
        }
    }
}
