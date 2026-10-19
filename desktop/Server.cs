using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Logging;

namespace CopyCat;

public class Server(Store store, Action<string, string> onText, Action<string, string, string> onFile, Action<string> onPaired)
{
    public const int HttpPort = 47820;
    public const int DiscoveryPort = 47821;
    const long MaxUpload = 512L * 1024 * 1024;
    const int MaxPairFailures = 5;
    static readonly TimeSpan PairLockout = TimeSpan.FromMinutes(1);

    static readonly Lazy<string> WebPage = new(() =>
    {
        using var stream = typeof(Server).Assembly.GetManifestResourceStream("index.html")!;
        return new StreamReader(stream).ReadToEnd();
    });

    WebApplication? app;
    UdpClient? udp;
    int pairFailures;
    DateTime pairLockedUntil;

    public async Task StartAsync()
    {
        var builder = WebApplication.CreateSlimBuilder();
        builder.Logging.ClearProviders();
        builder.WebHost.UseUrls($"http://0.0.0.0:{HttpPort}");
        builder.WebHost.ConfigureKestrel(k => k.Limits.MaxRequestBodySize = MaxUpload);
        app = builder.Build();

        app.MapGet("/", () => Results.Content(WebPage.Value, "text/html; charset=utf-8"));
        app.MapGet("/ping", () => Results.Json(new { app = "copycat", name = Environment.MachineName }));
        app.MapPost("/pair", Pair);
        app.MapPost("/text", ReceiveText);
        app.MapPost("/file", ReceiveFile);

        await app.StartAsync();
        _ = Task.Run(RunDiscovery);
    }

    public async Task StopAsync()
    {
        udp?.Dispose();
        if (app != null) await app.StopAsync();
    }

    async Task<IResult> Pair(HttpRequest req)
    {
        if (DateTime.UtcNow < pairLockedUntil) return Results.StatusCode(429);
        var body = await JsonSerializer.DeserializeAsync<JsonElement>(req.Body);
        var pin = body.TryGetProperty("pin", out var p) ? p.GetString() ?? "" : "";
        var token = store.Pair(pin.Trim());
        if (token == null)
        {
            if (++pairFailures >= MaxPairFailures)
            {
                pairFailures = 0;
                pairLockedUntil = DateTime.UtcNow + PairLockout;
            }
            return Results.StatusCode(403);
        }
        pairFailures = 0;
        onPaired(body.TryGetProperty("device", out var d) ? d.GetString() ?? "phone" : "phone");
        return Results.Json(new { token, name = Environment.MachineName });
    }

    async Task<IResult> ReceiveText(HttpRequest req)
    {
        if (!store.IsValidToken(req.Headers["X-CopyCat-Token"])) return Results.StatusCode(401);
        using var reader = new StreamReader(req.Body, Encoding.UTF8);
        var text = await reader.ReadToEndAsync();
        if (text.Length == 0) return Results.BadRequest();
        onText(text, Device(req));
        return Results.Ok();
    }

    async Task<IResult> ReceiveFile(HttpRequest req)
    {
        if (!store.IsValidToken(req.Headers["X-CopyCat-Token"])) return Results.StatusCode(401);
        var name = SafeFileName(req.Query["name"].ToString());
        Directory.CreateDirectory(Store.ReceivedDir);
        var path = UniquePath(Path.Combine(Store.ReceivedDir, name));
        await using (var fs = File.Create(path)) await req.Body.CopyToAsync(fs);
        onFile(path, req.ContentType ?? "application/octet-stream", Device(req));
        return Results.Ok();
    }

    async Task RunDiscovery()
    {
        udp = new UdpClient(new IPEndPoint(IPAddress.Any, DiscoveryPort)) { EnableBroadcast = true };
        var reply = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(
            new { app = "copycat", name = Environment.MachineName, port = HttpPort }));
        while (true)
        {
            UdpReceiveResult msg;
            try { msg = await udp.ReceiveAsync(); }
            catch (ObjectDisposedException) { return; }
            catch (SocketException) { continue; }
            if (Encoding.UTF8.GetString(msg.Buffer) == "COPYCAT_DISCOVER")
                await udp.SendAsync(reply, msg.RemoteEndPoint);
        }
    }

    static string Device(HttpRequest req)
    {
        var d = req.Headers["X-CopyCat-Device"].ToString();
        return string.IsNullOrWhiteSpace(d) ? "phone" : d;
    }

    static string SafeFileName(string name)
    {
        name = Path.GetFileName(name);
        foreach (var c in Path.GetInvalidFileNameChars()) name = name.Replace(c, '_');
        return string.IsNullOrWhiteSpace(name) ? $"shared-{DateTime.Now:yyyyMMdd-HHmmss}" : name;
    }

    static string UniquePath(string path)
    {
        if (!File.Exists(path)) return path;
        var dir = Path.GetDirectoryName(path)!;
        var stem = Path.GetFileNameWithoutExtension(path);
        var ext = Path.GetExtension(path);
        for (var i = 2; ; i++)
        {
            var candidate = Path.Combine(dir, $"{stem} ({i}){ext}");
            if (!File.Exists(candidate)) return candidate;
        }
    }
}
