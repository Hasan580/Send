using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;

namespace Send.Core;

public sealed class Conn : IDisposable
{
    public TcpClient Tcp { get; }
    public SslStream Ssl { get; }
    /// <summary>SHA-256 (hex) of the certificate the phone presented.</summary>
    public string CertHex { get; }

    public Conn(TcpClient tcp, SslStream ssl, string certHex)
    {
        Tcp = tcp;
        Ssl = ssl;
        CertHex = certHex;
    }

    public void Dispose()
    {
        try { Ssl.Dispose(); } catch { }
        try { Tcp.Dispose(); } catch { }
    }
}

public static class Link
{
    /// <summary>
    /// Opens a TLS connection to a phone. With a pin the phone's certificate must match it exactly
    /// (otherwise AuthenticationException); without one any certificate is accepted, which is only
    /// used for the hello probe and for the very first pairing, where the on-screen code checks it.
    /// </summary>
    public static async Task<Conn> ConnectAsync(IPAddress ip, string? pin, CancellationToken ct, int timeoutMs = 4000)
    {
        var tcp = new TcpClient(AddressFamily.InterNetwork)
        {
            NoDelay = true,
            ReceiveBufferSize = 1 << 20,
            SendBufferSize = 1 << 20,
        };
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(timeoutMs);
        try
        {
            await tcp.ConnectAsync(ip, Proto.Port, cts.Token).ConfigureAwait(false);
            string seen = "";
            var ssl = new SslStream(tcp.GetStream(), false, (_, cert, _, _) =>
            {
                if (cert == null) return false;
                seen = Util.Hex(SHA256.HashData(cert.GetRawCertData()));
                return pin == null || string.Equals(pin, seen, StringComparison.OrdinalIgnoreCase);
            });
            await ssl.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
            {
                TargetHost = "send",
                EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
                CertificateRevocationCheckMode = System.Security.Cryptography.X509Certificates.X509RevocationMode.NoCheck,
            }, cts.Token).ConfigureAwait(false);
            return new Conn(tcp, ssl, seen);
        }
        catch
        {
            tcp.Dispose();
            throw;
        }
    }
}
