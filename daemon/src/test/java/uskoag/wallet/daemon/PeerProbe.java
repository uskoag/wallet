package uskoag.wallet.daemon;

import leasegate.core.peer.TcpPeer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Does the kernel actually name the process on the other end? Two checks: a connection this process makes
 * to itself must come back as this pid, and one made by a child must come back as the child's — which is
 * the part that proves the lookup discriminates rather than merely answers.
 */
public final class PeerProbe {

    public static void main(String[] args) throws Exception {
        System.out.println("available: " + TcpPeer.available()
                + (TcpPeer.available() ? "" : "  (" + TcpPeer.unavailableReason() + ")"));
        var failures = 0;

        try (var server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
            var port = server.getLocalPort();

            try (var client = new Socket(InetAddress.getLoopbackAddress(), port);
                 var accepted = server.accept()) {
                var found = TcpPeer.ownerOf(client.getLocalPort(), port);
                var mine = ProcessHandle.current().pid();
                var ok = found.isPresent() && found.getAsLong() == mine;
                System.out.println((ok ? "  ok   " : "  FAIL ") + "self: expected pid " + mine
                        + ", got " + (found.isPresent() ? found.getAsLong() : "(none)"));
                if (!ok) failures++;
            }

            // A child that holds a connection open: PowerShell on Windows; elsewhere nc, which stays connected while
            // its stdin (a pipe from this process) is open, and whose own pid is the socket's owner.
            var windows = System.getProperty("os.name", "").toLowerCase().contains("win");
            var child = (windows
                    ? new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                        "$c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1'," + port + ");"
                        + " Start-Sleep -Seconds 6")
                    : new ProcessBuilder("nc", "127.0.0.1", String.valueOf(port))).start();
            try (var accepted = server.accept()) {
                var found = TcpPeer.ownerOf(accepted.getPort(), port);
                var ok = found.isPresent() && found.getAsLong() == child.pid();
                System.out.println((ok ? "  ok   " : "  FAIL ") + "child: expected pid " + child.pid()
                        + ", got " + (found.isPresent() ? found.getAsLong() : "(none)"));
                if (!ok) failures++;

                var wrong = TcpPeer.ownerOf(accepted.getPort(), port + 1);
                var ok2 = wrong.isEmpty();
                System.out.println((ok2 ? "  ok   " : "  FAIL ")
                        + "a connection to a different port is not matched");
                if (!ok2) failures++;
            } finally {
                child.destroyForcibly();
            }
        }
        System.out.println(failures == 0 ? "\nALL PASS" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private PeerProbe() {
    }
}
