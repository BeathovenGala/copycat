using System.Collections.Specialized;
using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace CopyCat;

public class TrayApp : ApplicationContext
{
    const int MenuHistoryItems = 15;
    const int LabelLength = 60;
    static readonly HashSet<string> ImageExtensions = [".png", ".jpg", ".jpeg", ".bmp", ".gif", ".webp"];

    readonly Store store = new();
    readonly NotifyIcon tray;
    readonly SynchronizationContext ui;
    readonly Server server;
    readonly System.Windows.Forms.Timer updateTimer = new() { Interval = (int)TimeSpan.FromHours(12).TotalMilliseconds };
    bool isUpdating;

    public TrayApp()
    {
        ui = SynchronizationContext.Current ?? new WindowsFormsSynchronizationContext();
        using var iconStream = typeof(TrayApp).Assembly.GetManifestResourceStream("copycat.ico");
        tray = new NotifyIcon
        {
            Icon = iconStream != null ? new Icon(iconStream) : SystemIcons.Application,
            Text = "CopyCat",
            Visible = true,
        };
        tray.DoubleClick += (_, _) => OpenFolder();
        store.Changed += () => ui.Post(_ => BuildMenu(), null);

        if (!File.Exists(Path.Combine(Store.Dir, ".launched")))
        {
            Startup.Set(true);
            File.WriteAllText(Path.Combine(Store.Dir, ".launched"), "");
        }

        server = new Server(store,
            (text, from) => ui.Post(_ => OnText(text, from), null),
            (path, mime, from) => ui.Post(_ => OnFile(path, mime, from), null),
            device => ui.Post(_ => Notify("Phone connected", $"{device} can now send to this laptop"), null));
        BuildMenu();
        _ = StartServer();

        updateTimer.Tick += async (_, _) => await CheckForUpdates(manual: false);
        updateTimer.Start();
        _ = Task.Delay(TimeSpan.FromSeconds(30)).ContinueWith(_ => ui.Post(async _ => await CheckForUpdates(manual: false), null));
    }

    async Task CheckForUpdates(bool manual)
    {
        if (isUpdating) return;
        Release? release;
        try { release = await Updater.CheckAsync(); }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or System.Text.Json.JsonException)
        {
            if (manual) Notify("Couldn't check for updates", "Check your internet connection.");
            return;
        }
        if (release == null)
        {
            if (manual) Notify("You're up to date", $"CopyCat v{Updater.Current.ToString(3)}");
            return;
        }
        isUpdating = true;
        try
        {
            Notify("Updating CopyCat", $"Installing v{release.Version}…");
            await Updater.DownloadAndStageAsync(release);
            await Quit();
        }
        catch (Exception ex) when (ex is HttpRequestException or IOException or TaskCanceledException)
        {
            isUpdating = false;
            Notify("Update failed", ex.Message);
        }
    }

    async Task StartServer()
    {
        try { await server.StartAsync(); }
        catch (Exception ex)
        {
            File.AppendAllText(Path.Combine(Store.Dir, "error.log"), $"{DateTime.Now:o} server start failed: {ex}{Environment.NewLine}");
            ui.Post(_ => tray.ShowBalloonTip(5000, "CopyCat couldn't start", ex.Message, ToolTipIcon.Error), null);
        }
    }

    void OnText(string text, string from)
    {
        TrySetClipboard(() => Clipboard.SetText(text));
        store.Add(new HistoryItem("text", text, null, DateTime.Now, from));
        Notify($"Text from {from}", Shorten(text));
    }

    void OnFile(string path, string mime, string from)
    {
        var isImage = mime.StartsWith("image/") || ImageExtensions.Contains(Path.GetExtension(path).ToLowerInvariant());
        CopyFileToClipboard(path, isImage);
        store.Add(new HistoryItem(isImage ? "image" : "file", Path.GetFileName(path), path, DateTime.Now, from));
        Notify(isImage ? $"Image from {from}" : $"File from {from}", $"{Path.GetFileName(path)} — copied, saved to Downloads\\CopyCat");
    }

    static void CopyFileToClipboard(string path, bool isImage)
    {
        var data = new DataObject();
        data.SetFileDropList(new StringCollection { path });
        if (isImage)
        {
            try
            {
                using var fs = File.OpenRead(path);
                using var img = Image.FromStream(fs);
                data.SetImage(new Bitmap(img));
            }
            catch (Exception ex) when (ex is ArgumentException or OutOfMemoryException)
            {
                // Formats GDI+ can't decode (e.g. webp/heic) still paste as a file.
            }
        }
        TrySetClipboard(() => Clipboard.SetDataObject(data, copy: true));
    }

    static void TrySetClipboard(Action set)
    {
        // Another app can hold the clipboard open briefly; retry instead of failing.
        for (var i = 0; i < 5; i++)
        {
            try { set(); return; }
            catch (System.Runtime.InteropServices.ExternalException) { Thread.Sleep(100); }
        }
    }

    void Notify(string title, string body)
    {
        if (store.Config.Notify) tray.ShowBalloonTip(3000, title, body, ToolTipIcon.None);
    }

    void BuildMenu()
    {
        var menu = new ContextMenuStrip();
        menu.Items.Add(new ToolStripMenuItem($"CopyCat v{Updater.Current.ToString(3)}") { Enabled = false });
        menu.Items.Add(new ToolStripMenuItem(store.Config.AutoAccept ? "Phones on this Wi-Fi connect automatically" : $"Pairing PIN: {store.Config.Pin}") { Enabled = false });
        menu.Items.Add(new ToolStripMenuItem($"Address: {string.Join(", ", LocalAddresses())}") { Enabled = false });
        menu.Items.Add(new ToolStripSeparator());

        if (store.History.Count == 0)
            menu.Items.Add(new ToolStripMenuItem("Nothing received yet") { Enabled = false });
        foreach (var item in store.History.Take(MenuHistoryItems))
        {
            var prefix = item.Kind switch { "image" => "🖼 ", "file" => "📄 ", _ => "" };
            var mi = new ToolStripMenuItem(prefix + Shorten(item.Text).Replace("&", "&&")) { ToolTipText = $"{item.From} · {item.At:g}" };
            mi.Click += (_, _) => Recopy(item);
            menu.Items.Add(mi);
        }
        menu.Items.Add(new ToolStripSeparator());

        menu.Items.Add("Connect iPhone or another phone…", null, (_, _) => ShowConnectWindow());
        menu.Items.Add("Open received folder", null, (_, _) => OpenFolder());
        var settings = new ToolStripMenuItem("Settings");
        settings.DropDownItems.Add(Toggle("Start with Windows", Startup.IsEnabled(), on => Startup.Set(on)));
        settings.DropDownItems.Add(Toggle("Connect new phones automatically", store.Config.AutoAccept, store.SetAutoAccept));
        settings.DropDownItems.Add(Toggle("Show notifications", store.Config.Notify, store.SetNotify));
        settings.DropDownItems.Add("Check for updates", null, async (_, _) => await CheckForUpdates(manual: true));
        settings.DropDownItems.Add("Allow through firewall…", null, (_, _) => AllowFirewall());
        settings.DropDownItems.Add("New pairing PIN", null, (_, _) => store.RegeneratePin());
        settings.DropDownItems.Add("Unpair all phones", null, (_, _) => store.ForgetDevices());
        settings.DropDownItems.Add("Clear history", null, (_, _) => store.ClearHistory());
        menu.Items.Add(settings);
        menu.Items.Add("Quit", null, async (_, _) => await Quit());

        var old = tray.ContextMenuStrip;
        tray.ContextMenuStrip = menu;
        old?.Dispose();
    }

    static ToolStripMenuItem Toggle(string label, bool isOn, Action<bool> set)
    {
        var item = new ToolStripMenuItem(label) { Checked = isOn, CheckOnClick = true };
        item.CheckedChanged += (_, _) => set(item.Checked);
        return item;
    }

    void Recopy(HistoryItem item)
    {
        if (item.Kind == "text") TrySetClipboard(() => Clipboard.SetText(item.Text));
        else if (item.FilePath != null && File.Exists(item.FilePath)) CopyFileToClipboard(item.FilePath, item.Kind == "image");
        else { Notify("File is gone", item.Text); return; }
        Notify("Copied", Shorten(item.Text));
    }

    void ShowConnectWindow()
    {
        var url = $"http://{LocalAddresses().First()}:{Server.HttpPort}/";
        using var qr = new QRCoder.QRCodeGenerator().CreateQrCode(url, QRCoder.QRCodeGenerator.ECCLevel.M);
        using var png = new MemoryStream(new QRCoder.PngByteQRCode(qr).GetGraphic(10));
        var form = new Form
        {
            Text = "Connect a phone to CopyCat",
            FormBorderStyle = FormBorderStyle.FixedDialog,
            MaximizeBox = false,
            MinimizeBox = false,
            StartPosition = FormStartPosition.CenterScreen,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            Padding = new Padding(16),
            Icon = tray.Icon,
        };
        var layout = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, WrapContents = false };
        layout.Controls.Add(new Label
        {
            Text = $"Scan with the phone's camera, then in Safari tap Share → Add to Home Screen.{Environment.NewLine}Android phones can use the CopyCat app instead.",
            AutoSize = true,
            MaximumSize = new Size(380, 0),
        });
        layout.Controls.Add(new PictureBox { Image = Image.FromStream(png), SizeMode = PictureBoxSizeMode.AutoSize });
        layout.Controls.Add(new TextBox { Text = url, ReadOnly = true, Width = 380 });
        form.Controls.Add(layout);
        form.Show();
        form.Activate();
    }

    static void OpenFolder()
    {
        Directory.CreateDirectory(Store.ReceivedDir);
        Process.Start(new ProcessStartInfo("explorer.exe", Store.ReceivedDir) { UseShellExecute = true });
    }

    void AllowFirewall()
    {
        var args = $"advfirewall firewall add rule name=\"CopyCat\" dir=in action=allow program=\"{Environment.ProcessPath}\" enable=yes profile=any";
        try
        {
            Process.Start(new ProcessStartInfo("netsh", args) { Verb = "runas", UseShellExecute = true, WindowStyle = ProcessWindowStyle.Hidden });
        }
        catch (System.ComponentModel.Win32Exception)
        {
            Notify("Firewall unchanged", "Admin permission was declined.");
        }
    }

    async Task Quit()
    {
        updateTimer.Stop();
        tray.Visible = false;
        await server.StopAsync();
        ExitThread();
    }

    static IEnumerable<string> LocalAddresses() =>
        NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
            // Real Wi-Fi/hotspot first: virtual adapters (WSL, Hyper-V, VPN) usually have no gateway.
            .OrderByDescending(n => n.GetIPProperties().GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(System.Net.IPAddress.Any)))
            .SelectMany(n => n.GetIPProperties().UnicastAddresses)
            .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork && !a.Address.ToString().StartsWith("169.254"))
            .Select(a => a.Address.ToString())
            .DefaultIfEmpty("offline");

    static string Shorten(string s)
    {
        s = s.ReplaceLineEndings(" ").Trim();
        return s.Length <= LabelLength ? s : s[..(LabelLength - 1)] + "…";
    }
}
